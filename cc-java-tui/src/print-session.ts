import type {ProtocolEvent} from './protocol.js';
import type {StdioClient} from './stdio-client.js';

/**
 * 在非 TTY 下运行一次无 ANSI 的协议会话。
 *
 * 该路径只验证 Surface 降级，不等同于最终 Java `--print`；真实 Provider 接入后，
 * Java Headless Print 才是权威的一次性入口。
 */
export async function runNonInteractive(
  client: StdioClient,
  prompt: string,
  output: NodeJS.WritableStream,
  diagnosticOutput: NodeJS.WritableStream = output,
): Promise<number> {
  return await new Promise<number>((resolve, reject) => {
    let initialized = false;
    const offEvent = client.onEvent((event: ProtocolEvent) => {
      if (event.type === 'initialized' && !initialized) {
        initialized = true;
        client.startRun(prompt);
      } else if (event.type === 'model.text.delta') {
        output.write(String(event.payload.text));
      } else if (event.type === 'run.completed') {
        output.write('\n');
        void client.shutdown().then(() => resolve(0), reject);
      } else if (event.type === 'run.cancelled') {
        diagnosticOutput.write('cc-java: run cancelled\n');
        void client.shutdown().then(() => resolve(130), reject);
      } else if (event.type === 'run.failed') {
        diagnosticOutput.write(failureMessage(event));
        void client.shutdown().then(() => resolve(1), reject);
      }
    });
    const offFailure = client.onFailure(message => {
      reject(new Error(message));
    });
    client.onExit(() => {
      offEvent();
      offFailure();
    });
    client.initialize();
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
  return 'cc-java: run failed\n';
}
