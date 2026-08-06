import {fileURLToPath} from 'node:url';
import {describe, expect, it} from 'vitest';
import {StdioClient} from '../src/stdio-client.js';
import type {ProtocolEvent} from '../src/protocol.js';

const fixture = fileURLToPath(new URL('./fixtures/fake-stdio-child.mjs', import.meta.url));

describe('StdioClient', () => {
  it('通过结构化子进程完成初始化、流式输出和退出', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();

    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('你好');
    await waitFor(() => events.some(event => event.type === 'run.completed'));
    await client.shutdown();

    expect(events.map(event => event.type)).toEqual([
      'initialized',
      'run.started',
      'model.text.delta',
      'model.text.delta',
      'run.completed',
    ]);
    expect(events.filter(event => event.type === 'model.text.delta')
      .map(event => event.payload.text).join('')).toBe('你好 agent');
  });

  it('活动 Run 可以通过命令取消', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();

    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('取消我');
    await waitFor(() => events.some(event => event.type === 'run.started'));
    client.cancelRun();
    await waitFor(() => events.some(event => event.type === 'run.cancelled'));
    await client.shutdown();

    expect(events.filter(event => event.type.startsWith('run.')).at(-1)?.type)
      .toBe('run.cancelled');
  });

  it('把匹配的单次审批决定发送给 Java 子进程', async () => {
    const client = createClient('approval');
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();

    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('修改文件');
    await waitFor(() => events.some(event => event.type === 'approval.requested'));
    const approval = events.find(event => event.type === 'approval.requested')!;
    client.resolveApproval(String(approval.payload.approvalId), 'allow_once');
    await waitFor(() => events.some(event => event.type === 'run.completed'));
    await client.shutdown();

    expect(events.map(event => event.type)).toContain('tool.completed');
    expect(approval.payload).toMatchObject({
      target: 'src/App.java',
      operation: 'modify',
      removedLines: 1,
      addedLines: 2,
    });
  });

  it('仅接受精确关联的 session command terminal result，并保留已签发 commandId', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    const requestId = client.sessionCommand('command-1', 'doctor', {});
    await waitFor(() => events.some(event => event.type === 'session.command.result'));
    expect(events.find(event => event.type === 'session.command.result')?.requestId).toBe(requestId);
    expect(() => client.sessionCommand('command-1', 'doctor', {})).toThrow(/当前连接签发/);
    await client.shutdown();
  });

  it('顺序完成的 commandId 也会消耗固定连接预算', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    for (let index = 1; index <= 256; index++) {
      client.sessionCommand(`command-${index}`, 'doctor', {});
      await waitFor(() => events.filter(event => event.type === 'session.command.result').length === index);
    }
    expect(() => client.sessionCommand('command-overflow', 'doctor', {})).toThrow(/签发数量超过上限/);
    await client.shutdown();
  });

  it('重复 commandId、错配和重复 result 均 fail closed', async () => {
    const delayed = createClient('command-delay');
    const delayedEvents: ProtocolEvent[] = [];
    delayed.onEvent(event => delayedEvents.push(event));
    delayed.initialize();
    await waitFor(() => delayedEvents.some(event => event.type === 'initialized'));
    delayed.sessionCommand('same-command', 'doctor', {});
    expect(() => delayed.sessionCommand('same-command', 'doctor', {})).toThrow(/当前连接签发/);
    await delayed.shutdown();

    for (const mode of ['command-wrong-request', 'command-duplicate-result']) {
      const client = createClient(mode);
      const failures: string[] = [];
      const events: ProtocolEvent[] = [];
      client.onFailure(message => failures.push(message));
      client.onEvent(event => events.push(event));
      client.initialize();
      await waitFor(() => events.some(event => event.type === 'initialized'));
      client.sessionCommand('command-1', 'doctor', {});
      await waitFor(() => failures.length === 1);
      expect(failures[0]).toContain('session.command.result');
      expect(client.isClosed()).toBe(true);
    }
  });

  it('乱序 stdout 触发失败并终止子进程', async () => {
    const client = createClient('bad-sequence');
    const failures: string[] = [];
    const events: ProtocolEvent[] = [];
    client.onFailure(message => failures.push(message));
    client.onEvent(event => events.push(event));
    client.initialize();

    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('触发错误');
    await waitFor(() => failures.length > 0);

    expect(failures[0]).toContain('sequence');
  });

  it('子进程意外崩溃转成传输失败并报告退出', async () => {
    const client = createClient('crash');
    const failures: string[] = [];
    let exited = false;
    client.onFailure(message => failures.push(message));
    client.onExit(() => {
      exited = true;
    });
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();

    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('触发崩溃');
    await waitFor(() => exited);

    expect(failures).toEqual([
      'Java 子进程意外退出（exit=17，stderr=0 bytes）',
    ]);
    expect(client.isClosed()).toBe(true);
  });

  it('shutdown 超时后强制终止并等待子进程实际退出', async () => {
    const client = createClient('ignore-shutdown');
    const pid = client.processId();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    await client.shutdown();

    expect(client.isClosed()).toBe(true);
    expect(pid).toBeDefined();
    expect(isProcessAlive(pid!)).toBe(false);
  });

  it('取消超时后终止无响应子进程且不遗留 PID', async () => {
    const client = createClient('ignore-cancel');
    const pid = client.processId();
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('不响应取消');
    await waitFor(() => events.some(event => event.type === 'run.started'));

    client.cancelRun();
    await waitFor(() => client.isClosed());

    expect(failures).toEqual(['Java 子进程未在取消期限内结束当前 Run']);
    expect(pid).toBeDefined();
    expect(isProcessAlive(pid!)).toBe(false);
  });
});

function createClient(mode = 'normal'): StdioClient {
  return new StdioClient({
    executable: process.execPath,
    args: [fixture, mode],
    cwd: process.cwd(),
  }, {shutdownTimeoutMs: 100, cancelTimeoutMs: 100});
}

async function waitFor(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 10_000;
  while (!predicate()) {
    if (Date.now() >= deadline) {
      throw new Error('等待 Fake stdio 事件超时');
    }
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}

function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}
