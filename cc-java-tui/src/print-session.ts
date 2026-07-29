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
  return await new Promise<number>(resolve => {
    let initialized = false;
    let settled = false;
    let finishing = false;
    let offExit = () => {};
    const finish = (code: number) => {
      if (settled) {
        return;
      }
      settled = true;
      offEvent();
      offFailure();
      offExit();
      resolve(code);
    };
    const finishAfterShutdown = (code: number) => {
      finishing = true;
      void client.shutdown().then(
        () => finish(code),
        () => {
          diagnosticOutput.write('cc-java: Java 子进程关闭失败\n');
          client.terminate();
          finish(1);
        },
      );
    };
    const offEvent = client.onEvent((event: ProtocolEvent) => {
      if (event.type === 'initialized' && !initialized) {
        initialized = true;
        client.startRun(prompt);
      } else if (event.type === 'model.text.delta') {
        output.write(String(event.payload.text));
      } else if (event.type === 'run.completed') {
        output.write('\n');
        finishAfterShutdown(0);
      } else if (event.type === 'run.cancelled') {
        diagnosticOutput.write('cc-java: run cancelled\n');
        finishAfterShutdown(130);
      } else if (event.type === 'run.failed') {
        diagnosticOutput.write(failureMessage(event));
        finishAfterShutdown(1);
      } else if (event.type === 'protocol.error') {
        diagnosticOutput.write('cc-java: Java 返回协议错误\n');
        client.terminate();
        finish(1);
      }
    });
    const offFailure = client.onFailure(message => {
      diagnosticOutput.write(`cc-java: ${message}\n`);
      finish(1);
    });
    offExit = client.onExit(() => {
      if (!settled && !finishing) {
        diagnosticOutput.write('cc-java: Java 子进程连接提前关闭\n');
        finish(1);
      }
    });
    try {
      client.initialize();
    } catch {
      diagnosticOutput.write('cc-java: 无法初始化 Java 子进程连接\n');
      client.terminate();
      finish(1);
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
  return 'cc-java: run failed\n';
}
