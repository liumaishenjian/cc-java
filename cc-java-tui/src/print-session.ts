import type {ProtocolEvent} from './protocol.js';
import type {StdioClient} from './stdio-client.js';

type NonInteractiveClient = Pick<
  StdioClient,
  'onEvent' | 'onFailure' | 'onExit' | 'initialize' | 'startRun' | 'closePrintTransport'
>;

export interface NonInteractiveDeadline {
  /** Java `--timeout` 解析后的权威 Run 墙钟预算。 */
  readonly runTimeoutMs: number;
  /** initialize、JVM 启动和首个协议往返的额外余量。 */
  readonly startupGraceMs?: number;
}

const DEFAULT_STARTUP_GRACE_MS = 5_000;
const MIN_RUN_TIMEOUT_MS = 10;
const MAX_RUN_TIMEOUT_MS = 30 * 60_000;

/**
 * 从已经结构化验证的 Java child argv 中取得唯一的 {@code --timeout}。
 *
 * 该解析器不读取环境变量、不连接 shell，也不接受组合参数或带符号数值。支持与 Java
 * CliDuration 相交的整数 {@code ms/s/m} 和无符号 {@code PT...H...M...S} 安全子集；最终值
 * 仍受 10ms..30m 边界约束。
 */
export function parseJavaRunTimeoutMillis(args: readonly string[]): number {
  const timeoutIndexes = args.flatMap((value, index) => value === '--timeout' ? [index] : []);
  if (timeoutIndexes.length !== 1) throw new Error('Java child argv 必须包含唯一 --timeout');
  const index = timeoutIndexes[0]!;
  const value = args[index + 1];
  if (value === undefined || value.startsWith('--')) throw new Error('Java child argv 缺少 --timeout 值');
  const milliseconds = parseDurationMillis(value);
  if (!Number.isSafeInteger(milliseconds)
    || milliseconds < MIN_RUN_TIMEOUT_MS || milliseconds > MAX_RUN_TIMEOUT_MS) {
    throw new Error('Java child --timeout 必须在 10ms..30m');
  }
  return milliseconds;
}

/**
 * 在非 TTY 下运行一次无 ANSI 的协议会话。
 *
 * Java Runtime 仍是正常 Run 终态的唯一权威；Surface deadline 只在协议或 Core 未能收敛时
 * fail closed。watchdog 到期后输出唯一固定诊断，经同一可靠关闭路径等待真实子进程 exit，
 * 不把 TUI 变成第二套 Agent 状态机。
 */
export async function runNonInteractive(
  client: NonInteractiveClient,
  prompt: string,
  output: NodeJS.WritableStream,
  diagnosticOutput: NodeJS.WritableStream = output,
  deadline?: NonInteractiveDeadline,
): Promise<number> {
  return await new Promise<number>(resolve => {
    let initialized = false;
    let settled = false;
    let finishing = false;
    let diagnosticWritten = false;
    let offExit = () => {};
    const watchdog = deadline === undefined ? undefined : setTimeout(() => {
      finishAfterTransportClose(1, 'cc-java: run timed out\n');
    }, checkedDeadlineMillis(deadline));
    watchdog?.unref();
    const finish = (code: number) => {
      if (settled) return;
      settled = true;
      if (watchdog !== undefined) clearTimeout(watchdog);
      offEvent();
      offFailure();
      offExit();
      resolve(code);
    };
    const writeDiagnostic = (message: string) => {
      if (diagnosticWritten) return;
      diagnosticWritten = true;
      diagnosticOutput.write(message);
    };
    const finishAfterTransportClose = (code: number, diagnostic?: string) => {
      if (finishing) return;
      finishing = true;
      if (watchdog !== undefined) clearTimeout(watchdog);
      if (diagnostic !== undefined) writeDiagnostic(diagnostic);
      void client.closePrintTransport().then(
        () => finish(code),
        () => {
          writeDiagnostic('cc-java: Java 子进程关闭失败\n');
          finish(code === 0 ? 1 : code);
        },
      );
    };
    const offEvent = client.onEvent((event: ProtocolEvent) => {
      if (finishing) return;
      if (event.type === 'initialized' && !initialized) {
        initialized = true;
        try {
          client.startRun(prompt);
        } catch {
          finishAfterTransportClose(1, 'cc-java: 无法启动 Java run\n');
        }
      } else if (event.type === 'model.text.delta') {
        output.write(String(event.payload.text));
      } else if (event.type === 'run.completed') {
        output.write('\n');
        finishAfterTransportClose(0);
      } else if (event.type === 'run.cancelled') {
        finishAfterTransportClose(130, 'cc-java: run cancelled\n');
      } else if (event.type === 'run.failed') {
        finishAfterTransportClose(1, failureMessage(event));
      } else if (event.type === 'protocol.error') {
        finishAfterTransportClose(1, 'cc-java: Java 返回协议错误\n');
      }
    });
    const offFailure = client.onFailure(message => {
      finishAfterTransportClose(1, `cc-java: ${message}\n`);
    });
    offExit = client.onExit(() => {
      if (!settled && !finishing) {
        finishAfterTransportClose(1, 'cc-java: Java 子进程连接提前关闭\n');
      }
    });
    try {
      client.initialize();
    } catch {
      finishAfterTransportClose(1, 'cc-java: 无法初始化 Java 子进程连接\n');
    }
  });
}

/**
 * 将 Java 的结构化失败原因映射为固定诊断，不透传 Provider 异常或其他不可信文本。
 */
function failureMessage(event: ProtocolEvent): string {
  if (event.payload.stopReason === 'time_limit_reached') {
    return 'cc-java: run timed out\n';
  }
  if (event.payload.stopReason === 'output_limit_reached') {
    return 'cc-java: output limit reached\n';
  }
  const modelFailure = event.payload.modelFailure;
  if (isRecord(modelFailure) && modelFailure.category === 'configuration_required') {
    return 'cc-java: Provider 配置不可用；请运行 /connect 或 codej auth login\n';
  }
  if (isRecord(modelFailure) && modelFailure.category === 'provider_error') {
    return 'cc-java: 模型服务调用失败；请检查 Provider 状态\n';
  }
  return 'cc-java: run failed\n';
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function checkedDeadlineMillis(deadline: NonInteractiveDeadline): number {
  const startupGraceMs = deadline.startupGraceMs ?? DEFAULT_STARTUP_GRACE_MS;
  if (!Number.isSafeInteger(deadline.runTimeoutMs)
    || deadline.runTimeoutMs < MIN_RUN_TIMEOUT_MS
    || deadline.runTimeoutMs > MAX_RUN_TIMEOUT_MS
    || !Number.isSafeInteger(startupGraceMs)
    || startupGraceMs < 0 || startupGraceMs > 60_000) {
    throw new Error('非交互 deadline 参数无效');
  }
  return deadline.runTimeoutMs + startupGraceMs;
}

function parseDurationMillis(value: string): number {
  const short = /^(\d+)(ms|s|m)$/u.exec(value.toLowerCase());
  if (short !== null) {
    const amount = Number(short[1]);
    const multiplier = short[2] === 'ms' ? 1 : short[2] === 's' ? 1_000 : 60_000;
    return amount * multiplier;
  }
  const iso = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d{1,9}))?S)?$/iu.exec(value);
  if (iso === null || iso.slice(1).every(part => part === undefined)) {
    throw new Error('Java child --timeout 语法无效');
  }
  const hours = Number(iso[1] ?? 0);
  const minutes = Number(iso[2] ?? 0);
  const seconds = Number(iso[3] ?? 0);
  const fractionMillis = Number(`0.${iso[4] ?? '0'}`) * 1_000;
  const milliseconds = hours * 3_600_000 + minutes * 60_000 + seconds * 1_000 + fractionMillis;
  if (!Number.isInteger(milliseconds)) throw new Error('Java child --timeout 必须精确到毫秒');
  return milliseconds;
}
