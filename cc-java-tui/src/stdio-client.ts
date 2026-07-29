import {spawn, type ChildProcessWithoutNullStreams} from 'node:child_process';
import {EventEmitter} from 'node:events';
import {TextDecoder} from 'node:util';
import {
  MAX_LINE_BYTES,
  PROTOCOL_VERSION,
  ProtocolViolation,
  decodeEvent,
  encodeCommand,
  type ProtocolCommand,
  type ProtocolEvent,
} from './protocol.js';

export interface ChildProcessSpec {
  readonly executable: string;
  readonly args: readonly string[];
  readonly cwd: string;
  readonly env?: NodeJS.ProcessEnv;
}

export interface StdioClientOptions {
  readonly maxLineBytes?: number;
  readonly shutdownTimeoutMs?: number;
  readonly cancelTimeoutMs?: number;
}

/**
 * 维护 TUI 到 Java Headless 的结构化子进程连接。
 *
 * 该类只发送协议命令、验证事件并管理 Java 进程生命周期。它不执行 Tool、不解释
 * Agent 终态，也不把 stderr 内容展示给用户，从而避免诊断管道成为 Secret 泄漏路径。
 */
export class StdioClient {
  readonly #child: ChildProcessWithoutNullStreams;
  readonly #events = new EventEmitter();
  readonly #decoder = new TextDecoder('utf-8', {fatal: true});
  readonly #maxLineBytes: number;
  readonly #shutdownTimeoutMs: number;
  readonly #cancelTimeoutMs: number;
  #pending = Buffer.alloc(0);
  #nextCommandSequence = 1;
  #nextEventSequence = 1;
  #nextRequestNumber = 1;
  #sessionId: string | undefined;
  #activeRunId: string | undefined;
  #closed = false;
  #shutdownRequested = false;
  #failureEmitted = false;
  #cancelTimer: NodeJS.Timeout | undefined;
  #stderrBytes = 0;

  public constructor(spec: ChildProcessSpec, options: StdioClientOptions = {}) {
    this.#maxLineBytes = options.maxLineBytes ?? MAX_LINE_BYTES;
    this.#shutdownTimeoutMs = options.shutdownTimeoutMs ?? 2_000;
    this.#cancelTimeoutMs = options.cancelTimeoutMs ?? 2_000;
    this.#child = spawn(spec.executable, [...spec.args], {
      cwd: spec.cwd,
      env: spec.env ?? process.env,
      shell: false,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
    this.#child.stdout.on('data', (chunk: Buffer) => this.#acceptStdout(chunk));
    this.#child.stderr.on('data', (chunk: Buffer) => {
      this.#stderrBytes += chunk.length;
    });
    this.#child.once('error', error => {
      const code = 'code' in error && typeof error.code === 'string'
        ? error.code
        : 'UNKNOWN';
      this.#fail(`Java 子进程启动失败：${code}`);
    });
    this.#child.once('exit', (code, signal) => {
      this.#clearCancelTimer();
      if (this.#pending.length > 0) {
        this.#emitFailure('Java stdout 以不完整协议行结束');
      } else if (!this.#shutdownRequested && !this.#failureEmitted) {
        this.#emitFailure('Java 子进程意外退出');
      }
      this.#closed = true;
      this.#events.emit('exit', {code, signal, stderrBytes: this.#stderrBytes});
    });
  }

  public onEvent(listener: (event: ProtocolEvent) => void): () => void {
    this.#events.on('event', listener);
    return () => this.#events.off('event', listener);
  }

  public onFailure(listener: (message: string) => void): () => void {
    this.#events.on('failure', listener);
    return () => this.#events.off('failure', listener);
  }

  public onExit(
    listener: (result: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number}) => void,
  ): () => void {
    this.#events.on('exit', listener);
    return () => this.#events.off('exit', listener);
  }

  public initialize(): string {
    return this.#send('initialize', {});
  }

  public startRun(prompt: string): string {
    if (this.#sessionId === undefined) {
      throw new Error('Session 尚未初始化');
    }
    return this.#send('run.start', {prompt}, this.#sessionId);
  }

  public cancelRun(): string {
    if (this.#sessionId === undefined || this.#activeRunId === undefined) {
      throw new Error('当前没有可以取消的 Run');
    }
    const requestId = this.#send('run.cancel', {}, this.#sessionId, this.#activeRunId);
    this.#clearCancelTimer();
    this.#cancelTimer = setTimeout(() => {
      if (this.#activeRunId !== undefined && !this.#closed) {
        this.#fail('Java 子进程未在取消期限内结束当前 Run');
      }
    }, this.#cancelTimeoutMs);
    this.#cancelTimer.unref();
    return requestId;
  }

  public async shutdown(): Promise<void> {
    if (this.#closed) {
      return;
    }
    this.#clearCancelTimer();
    this.#send('shutdown', {}, this.#sessionId);
    this.#shutdownRequested = true;
    this.#child.stdin.end();
    if (await this.#waitForExit(this.#shutdownTimeoutMs)) {
      return;
    }
    this.terminate();
    if (!await this.#waitForExit(this.#shutdownTimeoutMs)) {
      throw new Error('Java 子进程在强制终止后仍未退出');
    }
  }

  public terminate(): void {
    if (!this.#closed) {
      this.#shutdownRequested = true;
      this.#child.kill();
    }
  }

  /**
   * 返回当前 Java 子进程 PID，仅用于生命周期观测与验证。
   */
  public processId(): number | undefined {
    return this.#child.pid;
  }

  /**
   * 判断底层 Java 子进程是否已经触发 exit。
   */
  public isClosed(): boolean {
    return this.#closed;
  }

  #send(
    type: ProtocolCommand['type'],
    payload: Readonly<Record<string, unknown>>,
    sessionId?: string,
    runId?: string,
  ): string {
    if (this.#closed || !this.#child.stdin.writable) {
      throw new Error('Java 子进程连接已关闭');
    }
    const requestId = `tui-${this.#nextRequestNumber++}`;
    const command: ProtocolCommand = {
      version: PROTOCOL_VERSION,
      type,
      requestId,
      ...(sessionId === undefined ? {} : {sessionId}),
      ...(runId === undefined ? {} : {runId}),
      sequence: this.#nextCommandSequence++,
      payload,
    };
    this.#child.stdin.write(encodeCommand(command), 'utf8');
    return requestId;
  }

  #acceptStdout(chunk: Buffer): void {
    if (this.#closed) {
      return;
    }
    this.#pending = Buffer.concat([this.#pending, chunk]);
    let newline = this.#pending.indexOf(0x0a);
    while (newline >= 0) {
      const rawLine = this.#pending.subarray(0, newline);
      this.#pending = this.#pending.subarray(newline + 1);
      if (rawLine.length > this.#maxLineBytes) {
        this.#fail('Java stdout 协议行超过大小限制');
        return;
      }
      const withoutCarriageReturn =
        rawLine.at(-1) === 0x0d ? rawLine.subarray(0, -1) : rawLine;
      try {
        const line = this.#decoder.decode(withoutCarriageReturn);
        const event = decodeEvent(line, this.#nextEventSequence);
        this.#nextEventSequence++;
        this.#observeAuthority(event);
        this.#events.emit('event', event);
      } catch (error) {
        const message = error instanceof ProtocolViolation
          ? error.message
          : 'Java stdout 包含无效 UTF-8';
        this.#fail(message);
        return;
      }
      newline = this.#pending.indexOf(0x0a);
    }
    if (this.#pending.length > this.#maxLineBytes) {
      this.#fail('Java stdout 协议行超过大小限制');
    }
  }

  #observeAuthority(event: ProtocolEvent): void {
    if (event.type === 'initialized') {
      this.#sessionId = event.sessionId;
    } else if (event.type === 'run.started') {
      this.#activeRunId = event.runId;
    } else if (
      event.type === 'run.completed'
      || event.type === 'run.failed'
      || event.type === 'run.cancelled'
    ) {
      this.#activeRunId = undefined;
      this.#clearCancelTimer();
    }
  }

  #fail(message: string): void {
    if (this.#closed || this.#failureEmitted) {
      return;
    }
    this.#emitFailure(message);
    this.terminate();
  }

  #emitFailure(message: string): void {
    if (this.#failureEmitted) {
      return;
    }
    this.#failureEmitted = true;
    this.#events.emit('failure', message);
  }

  #clearCancelTimer(): void {
    if (this.#cancelTimer !== undefined) {
      clearTimeout(this.#cancelTimer);
      this.#cancelTimer = undefined;
    }
  }

  async #waitForExit(timeoutMs: number): Promise<boolean> {
    if (this.#closed) {
      return true;
    }
    return await new Promise<boolean>(resolve => {
      const onExit = () => {
        clearTimeout(timer);
        resolve(true);
      };
      const timer = setTimeout(() => {
        this.#child.off('exit', onExit);
        resolve(this.#closed);
      }, timeoutMs);
      timer.unref();
      this.#child.once('exit', onExit);
    });
  }
}

/**
 * 在 Node 主进程退出时同步触发子进程终止，避免 TUI 异常退出后遗留 Java 进程。
 *
 * Node 的 exit 事件不能等待异步清理，因此这里只执行可同步发起的 kill；正常关闭仍由
 * {@link StdioClient.shutdown} 负责等待子进程真正退出。
 */
export function installProcessExitGuard(
  client: Pick<StdioClient, 'terminate'>,
): () => void {
  const terminateChild = () => client.terminate();
  process.once('exit', terminateChild);
  return () => process.off('exit', terminateChild);
}
