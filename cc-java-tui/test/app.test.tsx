import {render} from 'ink-testing-library';
import {describe, expect, it} from 'vitest';
import {
  AgentTui,
  AgentView,
  approvalDecision,
  adjacentCheckpointId,
  appendInput,
  canEditInput,
  checkpointAction,
  decideInterrupt,
  editInput,
  MAX_INPUT_CHARS,
  undoConfirmation,
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
      checkpoints: [],
      checkpointPanelOpen: false,
      selectedCheckpointId: undefined,
      checkpointDiff: undefined,
      pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      runs: [{
        requestId: 'req-1',
        prompt: '解释中文宽字符',
        runId: 'run-1',
        text: '你好，coding agent。',
        tools: [],
        status: 'completed',
        stopReason: 'completed',
        modelTurns: 1,
        toolCalls: 0,
      }],
    };
    const view = render(<AgentView state={state} input="下一步" columns={20} />);

    expect(view.lastFrame()).toContain('解释中文宽字符');
    expect(view.lastFrame()).toContain('coding');
    expect(view.lastFrame()).toContain('已完成');
    expect(view.lastFrame()).toContain('1 回合');
    expect(view.lastFrame()).toContain('下一步');
  });

  it('运行中只显示 Java 投影出的状态', () => {
    const state: TuiState = {
      phase: 'running',
      sessionId: 'session-1',
      activeRunId: 'run-2',
      notice: undefined,
      checkpoints: [],
      checkpointPanelOpen: false,
      selectedCheckpointId: undefined,
      checkpointDiff: undefined,
      pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      runs: [{
        requestId: 'req-2',
        prompt: '继续',
        runId: 'run-2',
        text: '流式',
        tools: [{
          ordinal: 1,
          name: 'read_file',
          mode: undefined,
          status: 'started',
          returnedCharacters: undefined,
          returnedItems: undefined,
          filteredItems: undefined,
          truncated: false,
          truncationReason: undefined,
          errorCode: undefined,
          output: '',
        }],
        status: 'running',
        stopReason: undefined,
        modelTurns: undefined,
        toolCalls: undefined,
      }],
    };
    const view = render(<AgentView state={state} input="" columns={80} />);

    expect(view.lastFrame()).toContain('正在处理');
    expect(view.lastFrame()).toContain('阅读文件（进行中）');
    expect(view.lastFrame()).toContain('运行中');
  });

  it('审批面板展示受控相对路径和变更行数', () => {
    const state: TuiState = {
      phase: 'running',
      sessionId: 'session-1',
      activeRunId: 'run-write',
      notice: undefined,
      checkpoints: [],
      checkpointPanelOpen: false,
      selectedCheckpointId: undefined,
      checkpointDiff: undefined,
      pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      runs: [{
        requestId: 'req-write',
        prompt: '修改文件',
        runId: 'run-write',
        text: '',
        tools: [],
        pendingApproval: {
          approvalId: 'approval-1',
          ordinal: 1,
          toolName: 'apply_patch',
          effect: 'write_workspace',
          target: 'src/main/App.java',
          operation: 'modify',
          removedLines: 2,
          addedLines: 3,
          command: undefined,
          shell: undefined,
          workingDirectory: undefined,
          submitted: false,
        },
        status: 'running',
        stopReason: undefined,
        modelTurns: undefined,
        toolCalls: undefined,
      }],
    };

    const view = render(<AgentView state={state} input="" columns={80} />);

    expect(view.lastFrame()).toContain('修改：src/main/App.java');
    expect(view.lastFrame()).toContain('+3 行');
    expect(view.lastFrame()).toContain('-2 行');
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
      checkpoints: [],
      checkpointPanelOpen: false,
      selectedCheckpointId: undefined,
      checkpointDiff: undefined,
      pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      runs: [{
        requestId: 'req-resize',
        prompt: '保留上下文',
        runId: 'run-resize',
        text: '已有回答',
        tools: [],
        status: 'completed',
        stopReason: 'completed',
        modelTurns: 1,
        toolCalls: 0,
      }],
    };
    const view = render(<AgentView state={state} input="未提交输入" columns={100} />);

    view.rerender(<AgentView state={state} input="未提交输入" columns={20} />);

    expect(view.lastFrame()).toContain('保留上下文');
    expect(view.lastFrame()).toContain('已有回答');
    expect(view.lastFrame()).toContain('未提交输入');
  });

  it('失败终态展示 Java 权威原因和消耗计数', () => {
    const state: TuiState = {
      phase: 'ready',
      sessionId: 'session-1',
      activeRunId: undefined,
      notice: undefined,
      checkpoints: [],
      checkpointPanelOpen: false,
      selectedCheckpointId: undefined,
      checkpointDiff: undefined,
      pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      runs: [{
        requestId: 'req-failed',
        prompt: '分析 Agent Loop',
        runId: 'run-failed',
        text: '',
        tools: [],
        status: 'failed',
        stopReason: 'model_retry_exhausted',
        modelFailure: {
          category: 'provider_unavailable',
          statusClass: '5xx',
          attempts: 3,
          receivedOutput: false,
        },
        modelTurns: 1,
        toolCalls: 0,
      }],
    };
    const view = render(<AgentView state={state} input="" columns={80} />);

    expect(view.lastFrame()).toContain(
      '运行失败 · model_retry_exhausted · 1 回合 · 0 次工具',
    );
    expect(view.lastFrame()).toContain(
      '模型服务暂时不可用（5xx），已尝试 3 次；请稍后重试',
    );
  });

  it('Checkpoint 面板展示具体 phase、Diff 和二次确认目标', () => {
    const state: TuiState = {
      ...initialCheckpointState(),
      checkpoints: [{
        checkpointId: 'checkpoint-run-1-1',
        callId: 'call-1',
        toolName: 'apply_patch',
        target: 'src/App.java',
        existedBefore: true,
        phase: 'post_journal_uncertain',
        undoable: false,
      }, {
        checkpointId: 'checkpoint-run-1-2',
        callId: 'call-2',
        toolName: 'apply_patch',
        target: 'src/Ready.java',
        existedBefore: true,
        phase: 'completed_present',
        undoable: true,
      }],
      selectedCheckpointId: 'checkpoint-run-1-2',
      checkpointDiff: {
        checkpointId: 'checkpoint-run-1-2',
        target: 'src/Ready.java',
        status: 'changed',
        text: '-old\n+new\n',
        truncated: false,
      },
      pendingUndoCheckpointId: 'checkpoint-run-1-2',
    };
    const view = render(<AgentView state={state} input="" columns={100} />);

    expect(view.lastFrame()).toContain('结果记录不确定');
    expect(view.lastFrame()).toContain('Diff · src/Ready.java · changed');
    expect(view.lastFrame()).toContain('确认 Undo 当前 Checkpoint');
    expect(view.lastFrame()).toContain('checkpoint-run-1-2');
    expect(view.lastFrame()).toContain('仅按 Shift+Y 执行');
  });

  it('Checkpoint 键位只把大写 Y 视作针对当前项的二次确认', () => {
    const checkpoints = [{
      checkpointId: 'checkpoint-run-1-1',
      callId: 'call-1',
      toolName: 'apply_patch',
      target: 'src/App.java',
      existedBefore: true,
      phase: 'completed_present' as const,
      undoable: true,
    }, {
      checkpointId: 'checkpoint-run-1-2',
      callId: 'call-2',
      toolName: 'write_file',
      target: 'src/New.java',
      existedBefore: false,
      phase: 'completed_absent' as const,
      undoable: true,
    }];

    expect(checkpointAction('c', {}, false)).toBeUndefined();
    expect(checkpointAction('C', {}, false)).toBe('list');
    expect(checkpointAction('D', {}, false)).toBeUndefined();
    expect(checkpointAction('U', {}, false)).toBeUndefined();
    expect(checkpointAction('', {downArrow: true}, false)).toBeUndefined();
    expect(checkpointAction('D', {}, true)).toBe('diff');
    expect(checkpointAction('U', {}, true)).toBe('undo');
    expect(checkpointAction('', {downArrow: true}, true)).toBe('next');
    expect(adjacentCheckpointId(checkpoints, 'checkpoint-run-1-1', 1))
      .toBe('checkpoint-run-1-2');
    expect(undoConfirmation('y')).toBeUndefined();
    expect(undoConfirmation('Y')).toBe('confirm');
    expect(undoConfirmation('N')).toBe('cancel');
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
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('任务');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);

    expect(client.prompts).toEqual(['预输入任务']);
    view.unmount();
  });

  it('Slash 命令仅经 session command 通道发送，并显示安全终态提示', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'tui-1', sessionId: 'session-1', sequence: 1, payload: {protocolVersion: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('/doctor');
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    expect(client.sessionCommands).toEqual(['tui-command-1:doctor:{}']);
    expect(client.prompts).toEqual([]);
    client.emit({
      version: 0, type: 'session.command.result', requestId: 'command-result', sessionId: 'session-1', sequence: 2,
      payload: {commandId: 'tui-command-1', intent: 'doctor', status: 'rejected', code: 'deferred', result: {}},
    });
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(view.lastFrame()).toContain('/doctor 未执行');
    view.unmount();
  });

  it('没有 session command 通道时 Slash 命令本地拒绝而不作为模型提示词提交', async () => {
    const client = new FakeAgentClient();
    Object.defineProperty(client, 'sessionCommand', {value: undefined});
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'tui-1', sessionId: 'session-1', sequence: 1, payload: {protocolVersion: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('/doctor');
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('当前连接不支持 Slash 命令') === true);
    expect(client.prompts).toEqual([]);
    view.stdin.write('/unknown');
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('未知 Slash 命令') === true);
    expect(client.prompts).toEqual([]);
    view.unmount();
  });

  it('真实 useInput 链路完整提交含小写 c/d/u 的普通输入且不触发 Checkpoint', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({
      version: 0,
      type: 'initialized',
      requestId: 'tui-1',
      sessionId: 'session-1',
      sequence: 1,
      payload: {protocolVersion: 0},
    });
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('coding');
    await waitForFrame(() => view.lastFrame()?.includes('coding') === true);
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);

    expect(client.prompts).toEqual(['coding']);
    expect(client.checkpointCommands).toEqual([]);
    view.unmount();
  });

  it('真实 useInput 链路可达 list/diff/undo 且仅二次确认后发送 Undo', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({
      version: 0,
      type: 'initialized',
      requestId: 'tui-1',
      sessionId: 'session-1',
      sequence: 1,
      payload: {protocolVersion: 0},
    });
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('C');
    await waitForFrame(() => client.checkpointCommands.length === 1);
    expect(client.checkpointCommands).toEqual(['list']);
    client.emit({
      version: 0,
      type: 'checkpoint.listed',
      requestId: 'tui-checkpoint-list',
      sessionId: 'session-1',
      sequence: 2,
      payload: {
        checkpoints: [{
          checkpointId: 'checkpoint-run-1-1',
          callId: 'call-1',
          toolName: 'apply_patch',
          target: 'src/App.java',
          existedBefore: true,
          phase: 'completed_present',
          undoable: true,
        }],
      },
    });
    await waitForFrame(() => view.lastFrame()?.includes('checkpoint-run-1-1') === true);

    view.stdin.write('D');
    await waitForFrame(() => client.checkpointCommands.length === 2);
    expect(client.checkpointCommands[1]).toBe('diff:checkpoint-run-1-1');
    view.stdin.write('U');
    await waitForFrame(() => view.lastFrame()?.includes('确认 Undo 当前 Checkpoint') === true);
    view.stdin.write('y');
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(client.checkpointCommands).toHaveLength(2);
    view.stdin.write('Y');
    await waitForFrame(() => client.checkpointCommands.length === 3);
    expect(client.checkpointCommands[2]).toBe('undo:checkpoint-run-1-1:true');
    view.unmount();
  });
});

function initialCheckpointState(): TuiState {
  return {
    phase: 'ready',
    sessionId: 'session-1',
    activeRunId: undefined,
    runs: [],
    checkpoints: [],
    checkpointPanelOpen: true,
    selectedCheckpointId: undefined,
    checkpointDiff: undefined,
    pendingUndoCheckpointId: undefined,
    checkpointUndo: undefined,
    notice: undefined,
  };
}

describe('approvalDecision', () => {
  it('把 Y/A/N 映射为单次允许、会话允许或拒绝', () => {
    expect(approvalDecision('Y')).toBe('allow_once');
    expect(approvalDecision('a')).toBe('allow_session');
    expect(approvalDecision('n')).toBe('deny');
    expect(approvalDecision('x')).toBeUndefined();
  });
});

class FakeAgentClient implements AgentClient {
  readonly prompts: string[] = [];
  readonly checkpointCommands: string[] = [];
  readonly sessionCommands: string[] = [];
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

  public resolveApproval(): string {
    return 'tui-4';
  }

  public listCheckpoints(): string {
    this.checkpointCommands.push('list');
    return 'tui-checkpoint-list';
  }

  public checkpointDiff(checkpointId: string): string {
    this.checkpointCommands.push(`diff:${checkpointId}`);
    return 'tui-checkpoint-diff';
  }

  public undoCheckpoint(checkpointId: string, confirmed: boolean): string {
    this.checkpointCommands.push(`undo:${checkpointId}:${confirmed}`);
    return 'tui-checkpoint-undo';
  }

  public sessionCommand(commandId: string, intent: 'help' | 'clear' | 'compact' | 'context' | 'doctor' | 'model' | 'permissions' | 'resume', arguments_: Readonly<Record<string, unknown>>): string {
    this.sessionCommands.push(`${commandId}:${intent}:${JSON.stringify(arguments_)}`);
    return 'tui-session-command';
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
