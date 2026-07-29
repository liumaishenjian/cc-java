import {render} from 'ink-testing-library';
import {describe, expect, it} from 'vitest';
import {
  AgentTui,
  AgentView,
  appendInput,
  canEditInput,
  decideInterrupt,
  editInput,
  MAX_INPUT_CHARS,
} from '../src/app.js';
import type {AgentClient} from '../src/app.js';
import type {ProtocolEvent} from '../src/protocol.js';
import type {TuiState} from '../src/state.js';

describe('AgentView', () => {
  it('窄窗口仍能渲染中文、输入和完成状态', () => {
    const state: TuiState = {
      phase: 'ready',
      sessionId: 'session-1',
      activeRunId: undefined,
      notice: undefined,
      runs: [{
        requestId: 'req-1',
        prompt: '解释中文宽字符',
        runId: 'run-1',
        text: '你好，coding agent。',
        status: 'completed',
      }],
    };
    const view = render(<AgentView state={state} input="下一步" columns={20} />);

    expect(view.lastFrame()).toContain('解释中文宽字符');
    expect(view.lastFrame()).toContain('coding agent');
    expect(view.lastFrame()).toContain('[completed]');
    expect(view.lastFrame()).toContain('下一步');
  });

  it('运行中只显示 Java 投影出的状态', () => {
    const state: TuiState = {
      phase: 'running',
      sessionId: 'session-1',
      activeRunId: 'run-2',
      notice: undefined,
      runs: [{
        requestId: 'req-2',
        prompt: '继续',
        runId: 'run-2',
        text: '流式',
        status: 'running',
      }],
    };
    const view = render(<AgentView state={state} input="" columns={80} />);

    expect(view.lastFrame()).toContain('模型输出中');
    expect(view.lastFrame()).toContain('[running]');
  });

  it('Backspace 按 Unicode Code Point 删除且中断动作取决于 Java Run 投影', () => {
    const afterText = editInput('', '你好', {
      backspace: false,
      ctrl: false,
      meta: false,
    });
    const afterBackspace = editInput(afterText, '', {
      backspace: true,
      ctrl: false,
      meta: false,
    });

    expect(afterBackspace).toBe('你');
    expect(decideInterrupt('running', 'run-1')).toBe('cancel');
    expect(decideInterrupt('running', 'run-1', true)).toBe('terminate');
    expect(decideInterrupt('ready', undefined)).toBe('shutdown');
  });

  it('Resize 只改变布局宽度，不丢失已有 Run 和输入状态', () => {
    const state: TuiState = {
      phase: 'ready',
      sessionId: 'session-1',
      activeRunId: undefined,
      notice: undefined,
      runs: [{
        requestId: 'req-resize',
        prompt: '保留上下文',
        runId: 'run-resize',
        text: '已有回答',
        status: 'completed',
      }],
    };
    const view = render(<AgentView state={state} input="未提交输入" columns={100} />);

    view.rerender(<AgentView state={state} input="未提交输入" columns={20} />);

    expect(view.lastFrame()).toContain('保留上下文');
    expect(view.lastFrame()).toContain('已有回答');
    expect(view.lastFrame()).toContain('未提交输入');
  });

  it('Paste 按 Unicode Code Point 截断到输入上限', () => {
    const result = appendInput('前缀', '你'.repeat(MAX_INPUT_CHARS));

    expect(Array.from(result)).toHaveLength(MAX_INPUT_CHARS);
    expect(result.startsWith('前缀')).toBe(true);
  });

  it('连接期间真实 useInput 链路立即回显，ready 后可以提交', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);

    await waitForFrame(() => client.initializeCalls === 1);
    view.stdin.write('预输入');
    await waitForFrame(() => view.lastFrame()?.includes('预输入') === true);
    expect(client.prompts).toEqual([]);
    expect(canEditInput('connecting')).toBe(true);

    client.emit({
      version: 0,
      type: 'initialized',
      requestId: 'tui-1',
      sessionId: 'session-1',
      sequence: 1,
      payload: {protocolVersion: 0},
    });
    await waitForFrame(() => view.lastFrame()?.includes('状态：ready') === true);
    view.stdin.write('任务');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);

    expect(client.prompts).toEqual(['预输入任务']);
    view.unmount();
  });
});

class FakeAgentClient implements AgentClient {
  readonly prompts: string[] = [];
  initializeCalls = 0;
  readonly #eventListeners = new Set<(event: ProtocolEvent) => void>();

  public onEvent(listener: (event: ProtocolEvent) => void): () => void {
    this.#eventListeners.add(listener);
    return () => this.#eventListeners.delete(listener);
  }

  public onFailure(): () => void {
    return () => {};
  }

  public onExit(): () => void {
    return () => {};
  }

  public initialize(): string {
    this.initializeCalls++;
    return 'tui-1';
  }

  public startRun(prompt: string): string {
    this.prompts.push(prompt);
    return 'tui-2';
  }

  public cancelRun(): string {
    return 'tui-3';
  }

  public async shutdown(): Promise<void> {
    return await Promise.resolve();
  }

  public terminate(): void {
  }

  public emit(event: ProtocolEvent): void {
    for (const listener of this.#eventListeners) {
      listener(event);
    }
  }
}

async function waitForFrame(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 2_000;
  while (!predicate()) {
    if (Date.now() >= deadline) {
      throw new Error('等待 Ink 输入投影超时');
    }
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}
