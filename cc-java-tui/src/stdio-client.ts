import {
  spawn,
  type ChildProcess,
  type ChildProcessWithoutNullStreams,
  type SpawnOptions,
} from 'node:child_process';
import {EventEmitter} from 'node:events';
import {createHash} from 'node:crypto';
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
  readonly providerLoginTimeoutMs?: number;
}

export interface ProviderLoginRequest {
  readonly providerId: string;
  readonly profileId: string;
  readonly secretSource: 'store' | 'env';
  readonly environmentName?: string;
}

export interface ProviderLoginResult {
  readonly status: 'succeeded' | 'failed' | 'cancelled' | 'timed_out';
  readonly exitCode: number | null;
}

interface LoginTerminal {
  readonly isTTY?: boolean;
  readonly isRaw?: boolean;
  pause(): void;
  resume(): void;
  setRawMode?(mode: boolean): unknown;
}

type LoginSpawn = (
  executable: string,
  args: readonly string[],
  options: SpawnOptions,
) => ChildProcess;

export interface ProviderLoginBridgeOptions {
  readonly timeoutMs?: number;
  readonly spawnProcess?: LoginSpawn;
  readonly terminal?: LoginTerminal;
}

const JAVA_MAIN_CLASS = 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain';
const PROVIDER_ID = /^[a-z0-9][a-z0-9-]{0,62}$/u;
const ENVIRONMENT_NAME = /^[A-Z][A-Z0-9_]{0,127}$/u;

/**
 * 从启动时已验证的 Java ChildProcessSpec 派生一次性认证进程。
 *
 * 该桥只固定替换主类后的参数，使用 shell=false 且直接继承终端。STORE 模式下 API key
 * 由 Java Console.readPassword 遮蔽读取，Node/Ink 不接收、不编码也不保存 secret。
 */
export class ProviderLoginBridge {
  readonly #spec: ChildProcessSpec;
  readonly #timeoutMs: number;
  readonly #spawn: LoginSpawn;
  readonly #terminal: LoginTerminal;
  #active: ChildProcess | undefined;
  #cancelled = false;
  #loginClaimed = false;

  public constructor(spec: ChildProcessSpec, options: ProviderLoginBridgeOptions | number = {}) {
    this.#spec = validateJavaChildSpec(spec);
    const normalized = typeof options === 'number' ? {timeoutMs: options} : options;
    const timeoutMs = normalized.timeoutMs ?? 300_000;
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1_000 || timeoutMs > 900_000) {
      throw new Error('Provider login timeout 必须在 1000..900000ms');
    }
    this.#timeoutMs = timeoutMs;
    this.#spawn = normalized.spawnProcess ?? spawn;
    this.#terminal = normalized.terminal ?? process.stdin;
  }

  public active(): boolean {
    return this.#loginClaimed;
  }

  public cancel(): void {
    if (!this.#loginClaimed) return;
    this.#cancelled = true;
    this.#active?.kill();
  }

  public async login(request: ProviderLoginRequest): Promise<ProviderLoginResult> {
    if (this.#loginClaimed) throw new Error('已有 Provider login 正在执行');
    const args = providerLoginArguments(this.#spec.args, request);
    const terminal = this.#terminal;
    if (request.secretSource === 'store' && terminal.isTTY !== true) {
      throw new Error('STORE 登录需要可交互 TTY；可改用 /connect provider profile ENV_NAME');
    }
    this.#loginClaimed = true;
    this.#cancelled = false;
    const wasRaw = terminal.isRaw === true;
    let paused = false;
    let rawChanged = false;
    try {
      terminal.pause();
      paused = true;
      if (terminal.isTTY === true && typeof terminal.setRawMode === 'function') {
        terminal.setRawMode(false);
        rawChanged = true;
      }
      const child = this.#spawn(this.#spec.executable, args, {
        cwd: this.#spec.cwd,
        env: this.#spec.env ?? process.env,
        shell: false,
        stdio: 'inherit',
        windowsHide: false,
      });
      this.#active = child;
      return await new Promise<ProviderLoginResult>(resolve => {
        let settled = false;
        let timedOut = false;
        const finish = (result: ProviderLoginResult) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          child.removeListener('error', onError);
          child.removeListener('exit', onExit);
          resolve(result);
        };
        const terminalStatus = () => timedOut ? 'timed_out' as const
          : this.#cancelled ? 'cancelled' as const : 'failed' as const;
        const onError = () => finish({status: terminalStatus(), exitCode: null});
        const onExit = (code: number | null) => finish({
          status: timedOut ? 'timed_out' : this.#cancelled ? 'cancelled'
            : code === 0 ? 'succeeded' : 'failed',
          exitCode: timedOut ? null : code,
        });
        const timer = setTimeout(() => {
          timedOut = true;
          child.kill();
        }, this.#timeoutMs);
        timer.unref();
        child.once('error', onError);
        child.once('exit', onExit);
      });
    } catch {
      return {status: this.#cancelled ? 'cancelled' : 'failed', exitCode: null};
    } finally {
      this.#active = undefined;
      this.#loginClaimed = false;
      if (rawChanged && typeof terminal.setRawMode === 'function') terminal.setRawMode(wasRaw);
      if (paused) terminal.resume();
    }
  }
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
  readonly #loginSpec: ChildProcessSpec;
  readonly #providerLoginTimeoutMs: number;
  #loginBridge: ProviderLoginBridge | undefined;
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
  #issuedSessionCommandIds = new Set<string>();
  #pendingSessionCommands = new Map<string, string>();
  #pendingProviderControls = new Map<string, string>();
  #pendingRunStartRequestId: string | undefined;
  #pendingSteeringRequests = new Map<string, 'awaiting_queued' | 'queued'>();
  #pendingFileSuggestions = new Map<string, string>();
  #completedFileSuggestionIds = new Set<string>();
  static readonly #MAX_ISSUED_SESSION_COMMAND_IDS = 256;
  static readonly #MAX_PENDING_FILE_SUGGESTIONS = 256;
  static readonly #MAX_COMPLETED_FILE_SUGGESTIONS = 256;

  public constructor(spec: ChildProcessSpec, options: StdioClientOptions = {}) {
    this.#maxLineBytes = options.maxLineBytes ?? MAX_LINE_BYTES;
    this.#shutdownTimeoutMs = options.shutdownTimeoutMs ?? 2_000;
    this.#cancelTimeoutMs = options.cancelTimeoutMs ?? 2_000;
    this.#loginSpec = spec;
    this.#providerLoginTimeoutMs = options.providerLoginTimeoutMs ?? 300_000;
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
        this.#emitFailure(unexpectedExitMessage(code, signal, this.#stderrBytes));
      }
      this.#closed = true;
      this.#pendingSessionCommands.clear();
      this.#pendingProviderControls.clear();
      this.#pendingRunStartRequestId = undefined;
      this.#pendingSteeringRequests.clear();
      this.#pendingFileSuggestions.clear();
      this.#completedFileSuggestionIds.clear();
      this.#issuedSessionCommandIds.clear();
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
    const encoded = Buffer.from(prompt, 'utf8');
    const requestId = `tui-${this.#nextRequestNumber++}`;
    const direct = this.#command(
      'run.start', {prompt}, requestId, this.#nextCommandSequence, this.#sessionId,
    );
    if (commandBytes(direct) < this.#maxLineBytes) {
      this.#write(direct);
    } else {
      const inputId = `input-${requestId}`;
      const chunks = protocolTextChunks(
        prompt,
        this.#maxLineBytes,
        (text, ordinal, sequence) => this.#command(
          'input.chunk', {inputId, ordinal, text}, requestId, sequence, this.#sessionId,
        ),
        this.#nextCommandSequence + 1,
      );
      if (chunks.length > 64) throw new Error('输入编码后需要超过 64 个协议分块');
      this.#write(this.#command('input.begin', {
        inputId,
        byteCount: encoded.byteLength,
        chunkCount: chunks.length,
        sha256: createHash('sha256').update(encoded).digest('hex'),
      }, requestId, this.#nextCommandSequence, this.#sessionId));
      chunks.forEach((text, ordinal) => {
        this.#write(this.#command(
          'input.chunk', {inputId, ordinal, text}, requestId,
          this.#nextCommandSequence, this.#sessionId,
        ));
      });
      this.#write(this.#command(
        'input.commit', {inputId}, requestId, this.#nextCommandSequence, this.#sessionId,
      ));
    }
    if (this.#activeRunId !== undefined || this.#pendingRunStartRequestId !== undefined) {
      this.#pendingSteeringRequests.set(requestId, 'awaiting_queued');
    } else {
      this.#pendingRunStartRequestId = requestId;
    }
    return requestId;
  }

  /** 启动 Java 权威的显式 Skill Run。 */
  public invokeSkill(name: string, arguments_: string): string {
    if (this.#sessionId === undefined || this.#activeRunId !== undefined
      || this.#pendingRunStartRequestId !== undefined) {
      throw new Error('只有就绪 Session 可以显式调用 Skill');
    }
    const requestId = this.#send('skill.invoke', {name, arguments: arguments_}, this.#sessionId);
    this.#pendingRunStartRequestId = requestId;
    return requestId;
  }

  public sessionCommand(
    commandId: string,
    intent: 'help' | 'clear' | 'compact' | 'context' | 'doctor' | 'model' | 'permissions' | 'resume',
    arguments_: Readonly<Record<string, unknown>>,
  ): string {
    if (this.#sessionId === undefined) {
      throw new Error('Session 尚未初始化');
    }
    if (this.#issuedSessionCommandIds.has(commandId)) {
      throw new Error('session.command commandId 已在当前连接签发');
    }
    if (this.#issuedSessionCommandIds.size >= StdioClient.#MAX_ISSUED_SESSION_COMMAND_IDS) {
      throw new Error('session.command commandId 签发数量超过上限');
    }
    const requestId = this.#send('session.command', {
      protocolVersion: PROTOCOL_VERSION, commandId, intent, arguments: arguments_,
    }, this.#sessionId);
    this.#issuedSessionCommandIds.add(commandId);
    this.#pendingSessionCommands.set(commandId, requestId);
    return requestId;
  }

  /** 发送不含 secret 的 Provider/Auth 本地控制命令。 */
  public providerControl(
    controlId: string,
    intent: 'auth.list' | 'auth.probe' | 'auth.logout' | 'models.list' | 'models.use' | 'models.add' | 'models.remove',
    arguments_: Readonly<Record<string, unknown>>,
  ): string {
    if (this.#sessionId === undefined) throw new Error('Session 尚未初始化');
    if (this.#pendingProviderControls.has(controlId)) throw new Error('provider.control controlId 重复');
    const requestId = this.#send('provider.control', {controlId, intent, arguments: arguments_}, this.#sessionId);
    this.#pendingProviderControls.set(controlId, requestId);
    return requestId;
  }
  /** 通过继承终端的一次性 Java 进程执行登录；Agent stdio 连接不承载 secret。 */
  public providerLogin(request: ProviderLoginRequest): Promise<ProviderLoginResult> {
    const bridge = this.#loginBridge ??= new ProviderLoginBridge(this.#loginSpec, {
      timeoutMs: this.#providerLoginTimeoutMs,
    });
    return bridge.login(request);
  }

  /** 取消当前一次性登录进程。 */
  public cancelProviderLogin(): void {
    this.#loginBridge?.cancel();
  }

  /** 请求 Java 权威 Workspace 返回显式文件 mention 候选。 */
  public suggestFiles(query: string): string {
    if (this.#sessionId === undefined) {
      throw new Error('Session 尚未初始化');
    }
    if (this.#pendingFileSuggestions.size >= StdioClient.#MAX_PENDING_FILE_SUGGESTIONS) {
      throw new Error('file.suggest 待处理请求超过上限');
    }
    const requestId = this.#send('file.suggest', {query}, this.#sessionId);
    this.#pendingFileSuggestions.set(requestId, query);
    return requestId;
  }

  public listCheckpoints(): string {
    if (this.#sessionId === undefined || this.#activeRunId !== undefined) {
      throw new Error('只有就绪 Session 可以列出 Checkpoint');
    }
    return this.#send('checkpoint.list', {}, this.#sessionId);
  }

  public checkpointDiff(checkpointId: string): string {
    if (this.#sessionId === undefined || this.#activeRunId !== undefined) {
      throw new Error('只有就绪 Session 可以比较 Checkpoint');
    }
    return this.#send('checkpoint.diff', {checkpointId}, this.#sessionId);
  }

  public undoCheckpoint(checkpointId: string, confirmed: boolean): string {
    if (this.#sessionId === undefined || this.#activeRunId !== undefined) {
      throw new Error('只有就绪 Session 可以执行 Undo');
    }
    return this.#send('checkpoint.undo', {checkpointId, confirmed}, this.#sessionId);
  }

  /** 查询 Java 权威子任务状态。 */
  public inspectTask(taskId: string): string {
    return this.#taskCommand('task.inspect', taskId, {});
  }

  /** 有界等待 Java 权威子任务状态；超时不会推断终态。 */
  public waitTask(taskId: string, timeoutMillis: number): string {
    if (!Number.isSafeInteger(timeoutMillis) || timeoutMillis < 1 || timeoutMillis > 300_000) {
      throw new Error('子任务等待时间必须在 1..300000ms');
    }
    return this.#taskCommand('task.wait', taskId, {timeoutMillis});
  }

  /** 请求 Java 权威端取消子任务。 */
  public cancelTask(taskId: string): string {
    return this.#taskCommand('task.cancel', taskId, {});
  }

  /** 显式保留子任务绑定的 worktree。 */
  public keepTaskWorktree(taskId: string): string {
    return this.#taskCommand('task.keep', taskId, {});
  }

  /** 显式删除可证明 clean 的子任务 worktree。 */
  public removeTaskWorktree(taskId: string): string {
    return this.#taskCommand('task.remove', taskId, {});
  }

  #taskCommand(
    type: 'task.inspect' | 'task.wait' | 'task.cancel' | 'task.keep' | 'task.remove',
    taskId: string,
    payload: Readonly<Record<string, unknown>>,
  ): string {
    if (this.#sessionId === undefined || this.#activeRunId !== undefined) {
      throw new Error('只有就绪 Session 可以管理子任务');
    }
    if (!/^task-[A-Za-z0-9_-]{1,96}$/u.test(taskId)) {
      throw new Error('子任务 ID 无效');
    }
    return this.#send(type, {taskId, ...payload}, this.#sessionId);
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

  /**
   * 把用户对当前展示请求的单次决定发给 Java 权威端。
   */
  public resolveApproval(
    approvalId: string,
    decision: 'allow_once' | 'allow_session' | 'deny',
  ): string {
    if (this.#sessionId === undefined || this.#activeRunId === undefined) {
      throw new Error('当前没有可以审批的 Run');
    }
    return this.#send(
      'approval.resolve',
      {approvalId, decision},
      this.#sessionId,
      this.#activeRunId,
    );
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
    this.#loginBridge?.cancel();
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
    fixedRequestId?: string,
  ): string {
    const requestId = fixedRequestId ?? `tui-${this.#nextRequestNumber++}`;
    this.#write(this.#command(
      type, payload, requestId, this.#nextCommandSequence, sessionId, runId,
    ));
    return requestId;
  }

  #command(
    type: ProtocolCommand['type'],
    payload: Readonly<Record<string, unknown>>,
    requestId: string,
    sequence: number,
    sessionId?: string,
    runId?: string,
  ): ProtocolCommand {
    return {
      version: PROTOCOL_VERSION,
      type,
      requestId,
      ...(sessionId === undefined ? {} : {sessionId}),
      ...(runId === undefined ? {} : {runId}),
      sequence,
      payload,
    };
  }

  #write(command: ProtocolCommand): void {
    if (this.#closed || !this.#child.stdin.writable) {
      throw new Error('Java 子进程连接已关闭');
    }
    const encoded = encodeCommand(command);
    if (Buffer.byteLength(encoded, 'utf8') >= this.#maxLineBytes) {
      throw new Error('Client 协议行超过 Java reader 限制');
    }
    this.#child.stdin.write(encoded, 'utf8');
    this.#nextCommandSequence++;
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
    if (event.type === 'protocol.error') {
      this.#pendingFileSuggestions.delete(event.requestId);
      if (event.requestId === this.#pendingRunStartRequestId) {
        this.#pendingRunStartRequestId = undefined;
      } else {
        this.#pendingSteeringRequests.delete(event.requestId);
      }
    } else if (event.type === 'file.suggestions') {
      const expectedQuery = this.#pendingFileSuggestions.get(event.requestId);
      if (this.#completedFileSuggestionIds.has(event.requestId)
        || expectedQuery === undefined
        || event.sessionId !== this.#sessionId
        || event.payload.query !== expectedQuery) {
        throw new ProtocolViolation('file.suggestions 与待处理请求或当前 Session 不匹配');
      }
      this.#pendingFileSuggestions.delete(event.requestId);
      this.#completedFileSuggestionIds.add(event.requestId);
      if (this.#completedFileSuggestionIds.size > StdioClient.#MAX_COMPLETED_FILE_SUGGESTIONS) {
        const oldest = this.#completedFileSuggestionIds.values().next().value;
        if (oldest !== undefined) this.#completedFileSuggestionIds.delete(oldest);
      }
    } else if (event.type === 'steering.queued') {
      if (
        event.sessionId !== this.#sessionId
        || this.#pendingSteeringRequests.get(event.requestId) !== 'awaiting_queued'
      ) {
        throw new ProtocolViolation('steering.queued 与待处理请求或当前 Session 不匹配');
      }
      this.#pendingSteeringRequests.set(event.requestId, 'queued');
    } else if (event.type === 'steering.discarded') {
      if (
        event.sessionId !== this.#sessionId
        || this.#pendingSteeringRequests.get(event.requestId) !== 'queued'
      ) {
        throw new ProtocolViolation('steering.discarded 与已排队请求或当前 Session 不匹配');
      }
      this.#pendingSteeringRequests.delete(event.requestId);
    } else if (event.type === 'provider.control.result') {
      const controlId = event.payload.controlId;
      if (typeof controlId !== 'string' || this.#pendingProviderControls.get(controlId) !== event.requestId
        || event.sessionId !== this.#sessionId) {
        throw new ProtocolViolation('provider.control.result 与待处理请求不匹配');
      }
      this.#pendingProviderControls.delete(controlId);
    } else if (event.type === 'session.command.result') {
      const commandId = event.payload.commandId;
      if (typeof commandId !== 'string') {
        throw new ProtocolViolation('session.command.result 缺少 commandId');
      }
      const requestId = this.#pendingSessionCommands.get(commandId);
      if (requestId === undefined || event.requestId !== requestId) {
        throw new ProtocolViolation('session.command.result 与待处理请求不匹配');
      }
      this.#pendingSessionCommands.delete(commandId);
      if (event.payload.intent === 'resume' && event.payload.status === 'succeeded') {
        const result = event.payload.result;
        if (typeof result === 'object' && result !== null && !Array.isArray(result)
          && typeof (result as Record<string, unknown>).previousSessionId === 'string'
          && (result as Record<string, unknown>).previousSessionId === this.#sessionId
          && typeof (result as Record<string, unknown>).resumedSessionId === 'string'
          && event.sessionId === (result as Record<string, unknown>).resumedSessionId) {
          this.#sessionId = event.sessionId;
          this.#pendingRunStartRequestId = undefined;
          this.#pendingSteeringRequests.clear();
          this.#pendingFileSuggestions.clear();
        } else {
          throw new ProtocolViolation('session.command.result resume 与当前 Session 不匹配');
        }
      }
    }
    if (event.type === 'initialized') {
      if (this.#sessionId !== undefined && this.#sessionId !== event.sessionId) {
        this.#pendingFileSuggestions.clear();
      }
      this.#sessionId = event.sessionId;
    } else if (event.type === 'run.started') {
      if (event.sessionId !== this.#sessionId) {
        throw new ProtocolViolation('run.started 与当前 Session 不匹配');
      }
      if (event.requestId === this.#pendingRunStartRequestId) {
        this.#pendingRunStartRequestId = undefined;
      } else if (this.#pendingSteeringRequests.get(event.requestId) === 'queued') {
        this.#pendingSteeringRequests.delete(event.requestId);
      } else {
        throw new ProtocolViolation('run.started 与已签发 run.start 请求不匹配');
      }
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
    this.#closed = true;
    this.#shutdownRequested = true;
    this.#clearCancelTimer();
    this.#pendingSessionCommands.clear();
    this.#pendingRunStartRequestId = undefined;
    this.#pendingSteeringRequests.clear();
    this.#pendingFileSuggestions.clear();
    this.#completedFileSuggestionIds.clear();
    this.#issuedSessionCommandIds.clear();
    this.#emitFailure(message);
    this.#child.kill();
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

function protocolTextChunks(
  text: string,
  maximumLineBytes: number,
  command: (text: string, ordinal: number, sequence: number) => ProtocolCommand,
  firstSequence: number,
): readonly string[] {
  const chunks: string[] = [];
  let points: string[] = [];
  let encodedTextBytes = 0;
  for (const point of text) {
    const pointBytes = jsonStringContentBytes(point);
    const ordinal = chunks.length;
    const emptyLineBytes = commandBytes(command('', ordinal, firstSequence + ordinal));
    if (points.length > 0 && emptyLineBytes + encodedTextBytes + pointBytes >= maximumLineBytes) {
      chunks.push(points.join(''));
      points = [];
      encodedTextBytes = 0;
    }
    const nextOrdinal = chunks.length;
    const nextEmptyLineBytes = commandBytes(command('', nextOrdinal, firstSequence + nextOrdinal));
    if (nextEmptyLineBytes + pointBytes >= maximumLineBytes) {
      throw new Error('单个 Unicode 字符无法放入协议分块');
    }
    points.push(point);
    encodedTextBytes += pointBytes;
  }
  if (points.length > 0) chunks.push(points.join(''));
  for (let ordinal = 0; ordinal < chunks.length; ordinal++) {
    if (commandBytes(command(chunks[ordinal]!, ordinal, firstSequence + ordinal)) >= maximumLineBytes) {
      throw new Error('Client 无法生成受限协议分块');
    }
  }
  return chunks;
}

function jsonStringContentBytes(text: string): number {
  return Buffer.byteLength(JSON.stringify(text), 'utf8') - 2;
}

function commandBytes(command: ProtocolCommand): number {
  return Buffer.byteLength(encodeCommand(command), 'utf8');
}

function validateJavaChildSpec(spec: ChildProcessSpec): ChildProcessSpec {
  if (spec.executable.length === 0 || /[\x00\r\n]/u.test(spec.executable)
    || spec.cwd.length === 0 || /[\x00\r\n]/u.test(spec.cwd)
    || spec.args.length < 1 || spec.args.length > 64
    || spec.args.some(value => value.length === 0 || /[\x00\r\n]/u.test(value))) {
    throw new Error('Java ChildProcessSpec 无效');
  }
  const mainIndex = spec.args.indexOf(JAVA_MAIN_CLASS);
  if (mainIndex < 0 || spec.args.indexOf(JAVA_MAIN_CLASS, mainIndex + 1) >= 0
    || spec.args.at(-1) !== '--stdio'
    || spec.args.indexOf('--stdio') !== spec.args.length - 1) {
    throw new Error('Java ChildProcessSpec 必须固定为唯一主类且以 --stdio 结尾');
  }
  return {executable: spec.executable, args: [...spec.args], cwd: spec.cwd, ...(spec.env === undefined ? {} : {env: spec.env})};
}

function providerLoginArguments(base: readonly string[], request: ProviderLoginRequest): string[] {
  if (!PROVIDER_ID.test(request.providerId) || !PROVIDER_ID.test(request.profileId)) {
    throw new Error('Provider/profile ID 无效');
  }
  const mainIndex = base.indexOf(JAVA_MAIN_CLASS);
  const fixed = base.slice(0, mainIndex + 1);
  const control = ['auth', 'login', '--provider', request.providerId, '--profile', request.profileId];
  if (request.secretSource === 'store') {
    if (request.environmentName !== undefined) throw new Error('STORE 不接受 ENV name');
  } else {
    if (request.environmentName === undefined || !ENVIRONMENT_NAME.test(request.environmentName)) {
      throw new Error('ENV name 无效');
    }
    control.push('--from-env', request.environmentName);
  }
  return [...fixed, ...control];
}

function unexpectedExitMessage(
  code: number | null,
  signal: NodeJS.Signals | null,
  stderrBytes: number,
): string {
  const reason = code === null ? `signal=${signal ?? 'UNKNOWN'}` : `exit=${code}`;
  return `Java 子进程意外退出（${reason}，stderr=${stderrBytes} bytes）`;
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
