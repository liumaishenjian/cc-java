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

  it('按实际 NDJSON 编码大小分块并保持 Unicode 无损', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    const text = `${'\\\"\n\t'.repeat(20_000)}${'中文😀'.repeat(5_000)}`;
    const requestId = client.startRun(text);
    await waitFor(() => events.some(event => event.type === 'run.started'));

    expect(events.find(event => event.type === 'run.started')?.requestId).toBe(requestId);
    expect(client.isClosed()).toBe(false);
    await client.shutdown();
  });

  it.each(['chunk-error-begin', 'chunk-error-chunk', 'chunk-error-commit'])(
    '%s 将整条分块 submission 关联为同一拒绝并清理',
    async mode => {
      const client = createClient(mode);
      const events: ProtocolEvent[] = [];
      const failures: string[] = [];
      client.onEvent(event => events.push(event));
      client.onFailure(message => failures.push(message));
      client.initialize();
      await waitFor(() => events.some(event => event.type === 'initialized'));
      const requestId = client.startRun('x'.repeat(100_000));
      await waitFor(() => events.some(event => event.type === 'protocol.error'));
      expect(events.find(event => event.type === 'protocol.error')?.requestId).toBe(requestId);
      expect(failures).toEqual([]);
      expect(client.isClosed()).toBe(false);
      await client.shutdown();
    },
  );

  it('初始化后请求并严格关联 Java 文件建议', async () => {
    const client = createClient();
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    const requestId = client.suggestFiles('space');
    await waitFor(() => events.some(event => event.type === 'file.suggestions'));
    const result = events.find(event => event.type === 'file.suggestions')!;
    expect(result.requestId).toBe(requestId);
    expect(result.payload).toEqual({query: 'space', candidates: ['dir/file name.md']});
    await client.shutdown();
  });

  it('file.suggest 协议错误会终结对应待处理请求而不耗尽上限', async () => {
    const client = createClient('suggest-error');
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    for (let index = 0; index < 300; index++) {
      const requestId = client.suggestFiles(`q-${index}`);
      await waitFor(() => events.some(event =>
        event.type === 'protocol.error' && event.requestId === requestId));
    }

    expect(client.isClosed()).toBe(false);
    await client.shutdown();
  });

  it.each([
    ['suggest-duplicate', '重复响应'],
    ['suggest-unknown-request', '未知 requestId'],
    ['suggest-wrong-session', '错配 Session'],
    ['suggest-wrong-query', '错配 query'],
  ])('file.suggestions %s 立即 fail closed', async mode => {
    const client = createClient(mode);
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    client.suggestFiles('src');
    await waitFor(() => failures.length === 1);

    expect(failures[0]).toContain('file.suggestions');
    expect(client.isClosed()).toBe(true);
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

  it('严格关联 steering 事件，并且畸形 payload、请求或 Session 立即关闭连接', async () => {
    const valid = createClient('steering-normal');
    const validEvents: ProtocolEvent[] = [];
    valid.onEvent(event => validEvents.push(event));
    valid.initialize();
    await waitFor(() => validEvents.some(event => event.type === 'initialized'));
    valid.startRun('first');
    await waitFor(() => validEvents.some(event => event.type === 'run.started'));
    valid.startRun('UNSENT_STEERING_SECRET');
    await waitFor(() => validEvents.some(event => event.type === 'steering.discarded'));
    expect(validEvents.map(event => event.type)).toEqual([
      'initialized', 'run.started', 'steering.queued', 'steering.discarded',
    ]);
    expect(JSON.stringify(validEvents)).not.toContain('UNSENT_STEERING_SECRET');
    await valid.shutdown();

    for (const mode of ['steering-invalid-payload', 'steering-wrong-request', 'steering-wrong-session']) {
      const client = createClient(mode);
      const events: ProtocolEvent[] = [];
      const failures: string[] = [];
      client.onEvent(event => events.push(event));
      client.onFailure(message => failures.push(message));
      client.initialize();
      await waitFor(() => events.some(event => event.type === 'initialized'));
      client.startRun('first');
      await waitFor(() => events.some(event => event.type === 'run.started'));
      client.startRun('secret');
      await waitFor(() => failures.length === 1);
      expect(client.isClosed()).toBe(true);
      expect(failures[0]).toMatch(/steering/);
    }
  });

  it('steering 队列满的协议拒绝只清理对应请求，连接与后续 steering 保持可用', async () => {
    const client = createClient('steering-queue-full');
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('first');
    await waitFor(() => events.some(event => event.type === 'run.started'));
    const rejectedRequestId = client.startRun('rejected');
    await waitFor(() => events.some(event => event.type === 'protocol.error'));

    expect(events.find(event => event.type === 'protocol.error')).toEqual(expect.objectContaining({
      requestId: rejectedRequestId,
      payload: {code: 'STEERING_QUEUE_FULL'},
    }));
    expect(failures).toEqual([]);
    expect(client.isClosed()).toBe(false);
    client.startRun('accepted');
    await waitFor(() => events.some(event => event.type === 'steering.discarded'));
    await client.shutdown();
  });

  it('queue-full 拒绝后的迟到 run.started 无法重新物化被拒绝请求', async () => {
    const client = createClient('steering-queue-full-late-start');
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));
    client.startRun('first');
    await waitFor(() => events.some(event => event.type === 'run.started'));
    client.startRun('rejected');
    await waitFor(() => failures.length === 1);

    expect(events.filter(event => event.type === 'run.started')).toHaveLength(1);
    expect(failures[0]).toContain('run.started');
    expect(client.isClosed()).toBe(true);
  });

  it('首个 run.started 延迟时仍将第二个 startRun 关联为 steering', async () => {
    const client = createClient('steering-race');
    const events: ProtocolEvent[] = [];
    client.onEvent(event => events.push(event));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    client.startRun('first');
    client.startRun('second');
    await waitFor(() => events.some(event => event.type === 'steering.discarded'));
    await waitFor(() => events.some(event => event.type === 'run.started'));

    expect(events.map(event => event.type)).toEqual([
      'initialized', 'steering.queued', 'steering.discarded', 'run.started',
    ]);
    await client.shutdown();
  });

  it('拒绝 steering 生命周期中的重复或乱序事件', async () => {
    for (const mode of [
      'steering-duplicate-queued',
      'steering-discarded-before-queued',
      'steering-duplicate-discarded',
      'steering-start-before-queued',
    ]) {
      const client = createClient(mode);
      const events: ProtocolEvent[] = [];
      const failures: string[] = [];
      client.onEvent(event => events.push(event));
      client.onFailure(message => failures.push(message));
      client.initialize();
      await waitFor(() => events.some(event => event.type === 'initialized'));
      client.startRun('first');
      await waitFor(() => events.some(event => event.type === 'run.started'));
      client.startRun('second');
      await waitFor(() => failures.length === 1);

      expect(client.isClosed()).toBe(true);
      expect(failures[0]).toMatch(/steering|run\.started/);
    }
  });

  it('resume 结果未关联当前 Session 时 fail closed', async () => {
    const client = createClient('resume-mismatched-previous');
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.initialize();
    await waitFor(() => events.some(event => event.type === 'initialized'));

    client.sessionCommand('resume-1', 'resume', {sessionId: 'session-2'});
    await waitFor(() => failures.length === 1);

    expect(failures[0]).toContain('resume');
    expect(client.isClosed()).toBe(true);
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
