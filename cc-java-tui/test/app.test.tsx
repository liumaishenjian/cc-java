import {readFile, readdir} from 'node:fs/promises';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
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
  renderProviderControlResult,
  undoConfirmation,
} from '../src/app.js';
import type {AgentClient} from '../src/app.js';
import type {ProtocolEvent} from '../src/protocol.js';
import type {ProviderLoginRequest, ProviderLoginResult} from '../src/stdio-client.js';
import type {TuiState} from '../src/state.js';
import {createComposerState, reduceComposer} from '../src/input-editor.js';
import {
  independentProviderControlId,
  isIndependentProviderControlResult,
} from '../src/provider-control-id.js';
import {applyConnectWizardResult, beginConnectWizard} from '../src/connect-wizard.js';

const SHIFT_ENTER = String.fromCharCode(27) + '[13;2u';

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

    expect(view.lastFrame()).toContain('cc-java  S15');
    expect(view.lastFrame()).not.toContain('S06');
    expect(view.lastFrame()).toContain('解释中文宽字符');
    expect(view.lastFrame()).toContain('coding');
    expect(view.lastFrame()).toContain('已完成');
    expect(view.lastFrame()).toContain('1 回合');
    expect(view.lastFrame()).toContain('下一步');
  });

  it('ready 引导明确且极短窗口中 Credential notice 不能挤掉 Composer', () => {
    const ready: TuiState = {
      phase: 'ready', sessionId: 'session-1', activeRunId: undefined, runs: [],
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: undefined,
    };
    const readyView = render(<AgentView state={ready} input="" columns={100} />);
    expect(readyView.lastFrame()).toContain('输入 /help 查看命令');
    expect(readyView.lastFrame()).toContain('输入 /connect 查看并配置 Provider');
    expect(readyView.lastFrame()).toContain('普通任务会快速安全失败并恢复输入');
    readyView.unmount();

    const state: TuiState = {
      phase: 'ready', sessionId: 'session-1', activeRunId: undefined, runs: [],
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined,
      notice: 'Credential profiles\n（无）\nModels\nanthropic/claude-sonnet-4-6',
    };
    const view = render(<AgentView state={state} input="" columns={80} rows={8} />);
    const frame = view.lastFrame() ?? '';

    expect(frame.split('\n').length).toBeLessThanOrEqual(8);
    expect(frame).toContain('╭');
    expect(frame).toContain('❯');
    expect(frame).toContain('Enter 发送，Shift+Enter 换行');
  });

  it('极短窗口中大量 Slash 候选窗口化且不能挤掉已输入 Composer', () => {
    const state: TuiState = {
      phase: 'ready', sessionId: 'session-1', activeRunId: undefined, runs: [],
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: 'Credential profiles\n（无）\nModels\n（无）',
    };
    const layout = {width: 74, height: 1};
    const inserted = reduceComposer(createComposerState(1), {
      type: 'InsertText', text: '/connect',
    }, layout).state;
    const composer = reduceComposer(inserted, {
      type: 'SetCompletions',
      candidates: Array.from({length: 24}, (_, index) => `/candidate-${index}`),
    }, layout).state;
    let selected = composer;
    for (let index = 0; index < 19; index++) {
      selected = reduceComposer(selected, {type: 'CompletionNext'}, layout).state;
    }
    const view = render(<AgentView
      state={state} composer={selected} columns={80} rows={8} composerLayout={layout}
    />);
    const frame = view.lastFrame() ?? '';

    expect(frame.split('\n').length).toBeLessThanOrEqual(8);
    expect(frame).toContain('╭');
    expect(frame).toContain('❯ /connect');
    expect(frame).toContain('光标 1:9');
    expect(frame).toContain('❯ /candidate-19');
    expect(frame).not.toContain('/candidate-0');
  });

  it('极短 running 窗口和长历史仍保留最新状态与 Composer', () => {
    const completedRuns = Array.from({length: 12}, (_, index) => ({
      requestId: `req-old-${index}`, prompt: `历史任务 ${index}`, runId: `run-old-${index}`,
      text: `历史回答 ${index}\n`.repeat(4), tools: [], status: 'completed' as const,
      stopReason: 'completed', modelTurns: 1, toolCalls: 0,
    }));
    const state: TuiState = {
      phase: 'running', sessionId: 'session-1', activeRunId: 'run-current',
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: '次要 notice\n'.repeat(8),
      runs: [...completedRuns, {
        requestId: 'req-current', prompt: '当前任务', runId: 'run-current', text: '', tools: [],
        status: 'running', stopReason: undefined, modelTurns: undefined, toolCalls: undefined,
      }],
    };
    const view = render(<AgentView state={state} input="可排队补充" columns={80} rows={9} />);
    const frame = view.lastFrame() ?? '';

    expect(frame.split('\n').length).toBeLessThanOrEqual(9);
    expect(frame).toContain('等待模型响应');
    expect(frame).toContain('╭');
    expect(frame).toContain('❯');
    expect(frame).toContain('可排队补充');
    expect(frame).toContain('Enter 排队补充');
  });

  it('短窗口审批状态优先于旧历史且 Composer 边界仍可见', () => {
    const state: TuiState = {
      phase: 'running', sessionId: 'session-1', activeRunId: 'run-approval',
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: '旧 notice\n'.repeat(12),
      runs: [{
        requestId: 'req-old', prompt: '旧任务', runId: 'run-old', text: '旧回答\n'.repeat(20),
        tools: [], status: 'completed', stopReason: 'completed', modelTurns: 1, toolCalls: 0,
      }, {
        requestId: 'req-approval', prompt: '当前修改', runId: 'run-approval', text: '', tools: [],
        pendingApproval: {approvalId: 'approval-short', ordinal: 1, toolName: 'apply_patch',
          effect: 'write_workspace', target: 'src/App.java', operation: 'modify',
          removedLines: 1, addedLines: 2, command: undefined, shell: undefined,
          workingDirectory: undefined, submitted: false},
        status: 'running', stopReason: undefined, modelTurns: undefined, toolCalls: undefined,
      }],
    };
    const view = render(<AgentView state={state} input="" columns={80} rows={12} />);
    const frame = view.lastFrame() ?? '';

    expect(frame.split('\n').length).toBeLessThanOrEqual(12);
    expect(frame).toContain('Y 允许本次');
    expect(frame).toContain('╭');
    expect(frame).toContain('❯');
  });

  it('模型首个输出前显示等待阶段', () => {
    const state: TuiState = {
      phase: 'running', sessionId: 'session-1', activeRunId: 'run-wait',
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: undefined,
      runs: [{requestId: 'req-wait', prompt: '等待', runId: 'run-wait', text: '', tools: [],
        status: 'running', stopReason: undefined, modelTurns: undefined, toolCalls: undefined}],
    };
    const view = render(<AgentView state={state} input="下一条" columns={80} />);
    expect(view.lastFrame()).toContain('等待模型响应');
    expect(view.lastFrame()).toContain('下一条');
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
    expect(decideInterrupt('failed', undefined)).toBe('terminate');
    expect(decideInterrupt('closed', undefined)).toBe('terminate');
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

  it('timeout 失败摘要后恢复 ready 输入', () => {
    const state: TuiState = {
      phase: 'ready', sessionId: 'session-1', activeRunId: undefined,
      checkpoints: [], checkpointPanelOpen: false, selectedCheckpointId: undefined,
      checkpointDiff: undefined, pendingUndoCheckpointId: undefined,
      checkpointUndo: undefined, notice: undefined,
      runs: [{requestId: 'req-timeout', prompt: '慢任务', runId: 'run-timeout', text: '', tools: [],
        status: 'failed', stopReason: 'time_limit_reached', modelTurns: 1, toolCalls: 0}],
    };
    const view = render(<AgentView state={state} input="可以继续输入" columns={80} />);
    expect(view.lastFrame()).toContain('运行失败 · time_limit_reached');
    expect(view.lastFrame()).toContain('可以继续输入');
    expect(view.lastFrame()).toContain('就绪');
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

  it('可见结构超过上限时显式拒绝，绝不静默截断', () => {
    expect(() => appendInput('前缀', '你'.repeat(MAX_INPUT_CHARS)))
      .toThrowError(new RangeError('VISIBLE_STRUCTURE_LIMIT'));
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

  it('transport failure 后保留安全摘要并等待 Ctrl+C 显式退出', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);

    const safeSummary = '传输通道异常，诊断详情已隐藏';
    client.emitFailure(safeSummary);
    client.emitExit();
    await waitForFrame(() => {
      const frame = view.lastFrame();
      return frame?.includes('连接失败') === true
        && frame.includes(safeSummary)
        && frame.includes('连接已关闭，Ctrl+C退出');
    });

    const failedFrame = view.lastFrame();
    expect(failedFrame).toContain('连接失败');
    expect(failedFrame).toContain(safeSummary);
    expect(failedFrame).toContain('连接已关闭，Ctrl+C退出');
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(view.lastFrame()).toContain('连接已关闭，Ctrl+C退出');
    expect(client.terminateCalls).toBe(0);
    expect(client.shutdownCalls).toBe(0);

    view.stdin.write('');
    view.unmount();
    expect(client.terminateCalls).toBeGreaterThanOrEqual(1);
    expect(client.shutdownCalls).toBe(0);
  });

  it('通过真实输入链路发送 wait/cancel/keep/remove 子任务动作', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    for (const command of [
      '/task wait task-a 1500', '/task cancel task-a',
      '/task keep task-a', '/task remove task-a',
    ]) {
      view.stdin.write(command); view.stdin.write('\r');
      await waitForFrame(() => client.taskCommands.length === ['/task wait task-a 1500', '/task cancel task-a', '/task keep task-a', '/task remove task-a'].indexOf(command) + 1);
    }
    expect(client.taskCommands).toEqual([
      'wait:task-a:1500', 'cancel:task-a', 'keep:task-a', 'remove:task-a',
    ]);
    view.unmount();
  });

  it('延迟 run.started 期间的后续编辑不会被确认快照覆盖', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('first');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    view.stdin.write('after');
    await waitForFrame(() => view.lastFrame()?.includes('after') === true);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1', runId: 'run-1', sequence: 2, payload: {}});
    await new Promise(resolve => setTimeout(resolve, 20));

    expect(view.lastFrame()).toContain('after');
    expect(view.lastFrame()).not.toContain('firstafter');
    view.unmount();
  });

  it('上一条未确认时阻止第二笔提交但保留全部键入草稿', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('one'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    view.stdin.write('two'); view.stdin.write('\r'); view.stdin.write('draft');
    await waitForFrame(() => view.lastFrame()?.includes('twodraft') === true);
    expect(client.prompts).toEqual(['one']);
    expect(view.lastFrame()).toContain('上一条输入仍在等待 Java 接受');

    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1', runId: 'run-1', sequence: 2, payload: {}});
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(view.lastFrame()).toContain('twodraft');
    view.unmount();
  });

  it('协议拒绝把已发送内容恢复到后续编辑之前', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('rejected'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    view.stdin.write('after');
    client.emit({version: 0, type: 'protocol.error', requestId: 'tui-2', sessionId: 'session-1', sequence: 2, payload: {code: 'INPUT_COMMIT_MISMATCH'}});
    await waitForFrame(() => view.lastFrame()?.includes('rejectedafter') === true);

    expect(view.lastFrame()).toContain('rejectedafter');
    view.unmount();
  });

  it('Shift+Enter 写入多行缓冲，Enter 显式提交完整内容', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'tui-1', sessionId: 'session-1', sequence: 1, payload: {protocolVersion: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    expect(view.lastFrame()).toContain('Enter 发送，Shift+Enter 换行');

    view.stdin.write('first');
    view.stdin.write(SHIFT_ENTER);
    view.stdin.write('second');
    expect(client.prompts).toEqual([]);
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);

    expect(client.prompts).toEqual(['first\nsecond']);
    view.unmount();
  });

  it('运行中仍可编辑并以 Enter 排队普通多行补充，不改变当前 Run 投影', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('initial');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    await waitForFrame(() => view.lastFrame()?.includes('运行中') === true);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1', runId: 'run-1', sequence: 2, payload: {}});
    view.stdin.write('follow');
    view.stdin.write(SHIFT_ENTER);
    view.stdin.write('up');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 2);

    expect(client.prompts).toEqual(['initial', 'follow\nup']);
    expect(view.lastFrame()).toContain('正在处理');
    expect(view.lastFrame()).toContain('Enter 排队补充');
    view.unmount();
  });

  it('steering 队列满拒绝会恢复本地草稿，且后续 started 不会物化它', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('initial');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    await waitForFrame(() => view.lastFrame()?.includes('运行中') === true);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1', runId: 'run-1', sequence: 2, payload: {}});
    view.stdin.write('REJECTED_STEERING_SECRET');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 2);
    client.emit({
      version: 0, type: 'protocol.error', requestId: 'tui-3', sessionId: 'session-1', sequence: 3,
      payload: {code: 'STEERING_QUEUE_FULL'},
    });
    await new Promise(resolve => setTimeout(resolve, 20));

    expect(view.lastFrame()).toContain('Java 协议错误：STEERING_QUEUE_FULL');
    expect(view.lastFrame()).toContain('REJECTED_STEERING_SECRET');
    expect(view.lastFrame()).not.toContain('（1/100）');
    view.unmount();
  });

  it('运行中 Slash 始终走命令通道，绝不进入 steering 或模型提示词', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('initial');
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    await waitForFrame(() => view.lastFrame()?.includes('运行中') === true);
    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1', runId: 'run-1', sequence: 2, payload: {}});
    view.stdin.write('/doctor');
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);

    expect(client.prompts).toEqual(['initial']);
    expect(client.sessionCommands).toEqual(['tui-command-1:doctor:{}']);
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

  it('输入斜杠显示命令面板，方向键选择并以 Enter 补全后提交', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'tui-1', sessionId: 'session-1', sequence: 1, payload: {protocolVersion: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('/');
    await waitForFrame(() => view.lastFrame()?.includes('Slash 命令 · ↑/↓ 选择') === true);
    expect(view.lastFrame()).toContain('/help — 查看命令与可用状态');
    view.stdin.write('\u001b[B');
    await waitForFrame(() => view.lastFrame()?.includes('❯ /compact') === true);
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('/compact') === true);
    expect(client.sessionCommands).toEqual([]);
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);

    expect(client.sessionCommands).toEqual(['tui-command-1:compact:{"anchors":[]}']);
    view.unmount();
  });

  it('文件建议优先补全而不提交，下一次 Enter 才发送并支持 steering', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('看 @spa');
    await waitForFrame(() => client.fileSuggestions.includes('spa'));
    client.emit({version: 0, type: 'file.suggestions', requestId: 'file-1', sessionId: 'session-1', sequence: 2,
      payload: {query: 'spa', candidates: ['dir/file name.md']}});
    await waitForFrame(() => view.lastFrame()?.includes('文件建议') === true);
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('@"dir/file name.md"') === true);
    expect(client.prompts).toEqual([]);
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    expect(client.prompts).toEqual(['看 @"dir/file name.md"']);

    client.emit({version: 0, type: 'run.started', requestId: 'tui-2', sessionId: 'session-1', runId: 'run-1', sequence: 3, payload: {}});
    view.stdin.write('补充 @src');
    await waitForFrame(() => client.fileSuggestions.includes('src'));
    const request = `file-${client.fileSuggestions.length}`;
    client.emit({version: 0, type: 'file.suggestions', requestId: request, sessionId: 'session-1', sequence: 4,
      payload: {query: 'src', candidates: ['src/App.java']}});
    await waitForFrame(() => view.lastFrame()?.includes('@src/App.java') === true);
    view.stdin.write('\t'); view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 2);
    expect(client.prompts[1]).toBe('补充 @src/App.java');
    view.unmount();
  });

  it('Escape 关闭文件建议，↑/↓ 在候选间选择', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('@src');
    await waitForFrame(() => client.fileSuggestions.includes('src'));
    client.emit({
      version: 0, type: 'file.suggestions', requestId: 'file-1', sessionId: 'session-1', sequence: 2,
      payload: {query: 'src', candidates: ['src/A.java', 'src/B.java']},
    });
    await waitForFrame(() => view.lastFrame()?.includes('@src/A.java') === true);
    expect(view.lastFrame()).toContain('❯ @src/A.java');

    view.stdin.write('\u001b[B');
    await waitForFrame(() => view.lastFrame()?.includes('❯ @src/B.java') === true);
    view.stdin.write('\u001b[A');
    await waitForFrame(() => view.lastFrame()?.includes('❯ @src/A.java') === true);

    view.stdin.write('\u001b');
    await waitForFrame(() => view.lastFrame()?.includes('@src/B.java') !== true);
    expect(client.prompts).toEqual([]);
    view.stdin.write('\r');
    await waitForFrame(() => client.prompts.length === 1);
    expect(client.prompts).toEqual(['@src']);
    view.unmount();
  });

  it('mention 交互期间 TUI 不读取本地文件系统', async () => {
    const sourceDirectory = fileURLToPath(new URL('../src/', import.meta.url));
    const sources = (await readdir(sourceDirectory))
      .filter(name => name.endsWith('.ts') || name.endsWith('.tsx'));
    expect(sources.length).toBeGreaterThan(0);
    const filesystemImports: string[] = [];
    for (const name of sources) {
      const text = await readFile(join(sourceDirectory, name), 'utf8');
      if (/from\s+'node:fs(\/promises)?'|require\('node:fs/u.test(text)) {
        filesystemImports.push(name);
      }
    }
    expect(filesystemImports).toEqual([]);

    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('看 @src/');
    await waitForFrame(() => client.fileSuggestions.includes('src/'));
    client.emit({
      version: 0, type: 'file.suggestions', requestId: 'file-1', sessionId: 'session-1', sequence: 2,
      payload: {query: 'src/', candidates: ['src/App.java']},
    });
    await waitForFrame(() => view.lastFrame()?.includes('@src/App.java') === true);
    view.stdin.write('\t');
    await waitForFrame(() => view.lastFrame()?.includes('看 @src/App.java') === true);

    expect(client.fileSuggestions).toEqual(['src/']);
    view.unmount();
  });

  it('迟到文件建议不会覆盖较新的 token 查询', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    view.stdin.write('@a'); await waitForFrame(() => client.fileSuggestions.includes('a'));
    view.stdin.write('b'); await waitForFrame(() => client.fileSuggestions.includes('ab'));
    client.emit({version: 0, type: 'file.suggestions', requestId: 'file-1', sessionId: 'session-1', sequence: 2,
      payload: {query: 'a', candidates: ['stale.java']}});
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(view.lastFrame()).not.toContain('@stale.java');
    view.unmount();
  });

  it('/help 将 Java 安全投影渲染为可读命令清单', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'tui-1', sessionId: 'session-1', sequence: 1, payload: {protocolVersion: 0}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('/help');
    view.stdin.write('\r');
    await waitForFrame(() => client.sessionCommands.length === 1);
    client.emit({
      version: 0, type: 'session.command.result', requestId: 'command-result', sessionId: 'session-1', sequence: 2,
      payload: {commandId: 'tui-command-1', intent: 'help', status: 'succeeded', code: 'ok', result: {commands: [
        {intent: 'help', support: 'available'}, {intent: 'clear', support: 'available'},
        {intent: 'compact', support: 'available'}, {intent: 'context', support: 'available'},
        {intent: 'doctor', support: 'available'}, {intent: 'model', support: 'available'},
        {intent: 'permissions', support: 'available'}, {intent: 'resume', support: 'available'},
      ]}},
    });
    await waitForFrame(() => view.lastFrame()?.includes('Slash 命令') === true);

    expect(view.lastFrame()).toContain('/context — 查看上下文用量　[可用]');
    expect(view.lastFrame()).toContain('/resume <session-id> — 安全恢复会话　[可用]');
    expect(view.lastFrame()).toContain('/connect [provider profile [env ENV_NAME]]');
    expect(view.lastFrame()).toContain('/auth list | probe');
    expect(view.lastFrame()).toContain('/models [provider] | use');
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

  it('Provider 控制完整展示 list、probe、selection、logout 和结构化错误', () => {
    expect(renderProviderControlResult('auth.list', 'succeeded', 'OK', {profiles: [{
      providerId: 'anthropic', profileId: 'personal', authMethod: 'API_KEY', refKind: 'ENV',
      localStatus: 'AVAILABLE_LOCAL', providerDefault: true, lastProbeCode: 'SUCCESS',
    }]})).toContain('API_KEY/ENV · AVAILABLE_LOCAL · 默认 · 探测 SUCCESS');
    expect(renderProviderControlResult('models.list', 'succeeded', 'OK', {models: [{
      providerId: 'anthropic', modelId: 'claude-sonnet', providerDefault: true,
    }]})).toContain('anthropic/claude-sonnet · 默认');
    const selection = renderProviderControlResult('models.use', 'succeeded', 'OK', {
      providerId: 'anthropic', modelId: 'claude-sonnet', profileId: 'personal', setDefault: true,
    });
    expect(selection).toContain('下一 Run 模型'); expect(selection).toContain('profile personal');
    expect(selection).toContain('持久默认');
    expect(renderProviderControlResult('models.add', 'succeeded', 'OK', {
      providerId: 'anthropic', modelId: 'claude-opus', setDefault: false,
    })).toContain('本地模型已添加');
    expect(renderProviderControlResult('models.remove', 'succeeded', 'OK', {
      providerId: 'anthropic', modelId: 'claude-opus',
    })).toContain('本地模型已移除');
    const probe = renderProviderControlResult('auth.probe', 'succeeded', 'OK', {
      providerId: 'anthropic', profileId: 'personal', modelId: 'claude-sonnet',
      outcome: 'SUCCESS', probedAt: '2026-08-14T12:00:00Z',
    });
    expect(probe).toContain('认证探测'); expect(probe).toContain('SUCCESS');
    expect(probe).toContain('2026-08-14T12:00:00Z');
    const logout = renderProviderControlResult('auth.logout', 'succeeded', 'OK', {
      providerId: 'anthropic', profileId: 'personal', remoteRevoked: false,
    });
    expect(logout).toContain('anthropic/personal'); expect(logout).toContain('Provider 侧 credential 未撤销');
    const conflict = renderProviderControlResult('models.use', 'rejected', 'AUTH_TRANSACTION_CONFLICT', {});
    expect(conflict).toContain('当前有活动 Run'); expect(conflict).toContain('AUTH_TRANSACTION_CONFLICT');
    const rejected = renderProviderControlResult('auth.probe', 'rejected', 'AUTH_PROBE_REJECTED', {});
    expect(rejected).toContain('Provider 拒绝该 credential'); expect(rejected).toContain('AUTH_PROBE_REJECTED');
  });

  it('远离内置命令的合法 Slash 仍经显式 Skill 通道提交', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('/deploy-check safe args'); view.stdin.write('\r');
    await waitForFrame(() => client.skillInvocations.length === 1);

    expect(client.skillInvocations).toEqual(['deploy-check:safe args']);
    expect(client.prompts).toEqual([]);
    expect(client.sessionCommands).toEqual([]);
    expect(client.providerControls).toEqual([]);
    view.unmount();
  });

  it('/connet 在本地建议 /connect 且不进入 Skill、Run、命令或 Hook 可达路径', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('/connet ignored-arguments'); view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('你是否想输入 /connect？') === true);

    expect(view.lastFrame()).toContain('/connet ignored-arguments');
    expect(client.skillInvocations).toEqual([]);
    expect(client.prompts).toEqual([]);
    expect(client.sessionCommands).toEqual([]);
    expect(client.providerControls).toEqual([]);
    expect(client.providerLogins).toEqual([]);
    expect(client.taskCommands).toEqual([]);

    for (let index = 0; index < '/connet ignored-arguments'.length; index++) view.stdin.write('\x7f');
    view.stdin.write('/connect'); view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 2);

    expect(client.providerControls).toEqual([
      'tui-connect:1:models:models.list:{}',
      'tui-connect:1:auth:auth.list:{}',
    ]);
    expect(client.skillInvocations).toEqual([]);
    expect(client.prompts).toEqual([]);
    expect(client.sessionCommands).toEqual([]);
    view.unmount();
  });

  it.each([
    ['models→auth', ['models.list', 'auth.list']],
    ['auth→models', ['auth.list', 'models.list']],
  ] as const)('/connect 聚合两个任意顺序的结果：%s', async (_label, order) => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 2);

    let sequence = 2;
    for (const intent of order) {
      client.emit(connectResult(intent === 'models.list' ? 'tui-connect:1:models' : 'tui-connect:1:auth', intent, sequence++,
        intent === 'models.list'
          ? {models: [{providerId: 'anthropic', modelId: 'claude-sonnet-4-6', providerDefault: true}]}
          : {profiles: [{providerId: 'anthropic', profileId: 'personal', authMethod: 'API_KEY',
              refKind: 'ENV', localStatus: 'AVAILABLE_LOCAL', providerDefault: true}]}));
    }
    await waitForFrame(() => view.lastFrame()?.includes('已连接 · 当前使用 claude-sonnet-4-6') === true);
    const frame = view.lastFrame() ?? '';

    expect(frame).toContain('连接模型服务');
    expect(frame).toContain('Anthropic · 已连接 · 当前使用 claude-sonnet-4-6');
    expect(frame).toContain('OpenRouter · 未连接');
    expect(frame).toContain('添加自定义服务（高级）');
    expect(frame).not.toContain('Credential profiles');
    expect(frame).not.toContain('providers add');
    expect(frame).not.toContain('profileId');
    expect(frame).not.toContain('（刷新中）');
    expect(client.prompts).toEqual([]);
    view.unmount();
  });

  it('/connect 空列表最终仍同时显示完整配置面板', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 2);
    client.emit(connectResult('tui-connect:1:models', 'models.list', 2, {models: []}));
    client.emit(connectResult('tui-connect:1:auth', 'auth.list', 3, {profiles: []}));
    await waitForFrame(() => view.lastFrame()?.includes('Anthropic · 未连接') === true
      && view.lastFrame()?.includes('加载中') === false);
    const frame = view.lastFrame() ?? '';

    expect(frame).toContain('连接模型服务');
    expect(frame).toContain('Anthropic · 未连接');
    expect(frame).toContain('OpenRouter · 未连接');
    expect(frame).toContain('添加自定义服务（高级）');
    expect(frame).not.toContain('Credential profiles');
    expect(frame).not.toContain('providers add');
    view.unmount();
  });

  it.each([
    ['models 失败', 'models.list', 'AUTH_STORE_CORRUPT'],
    ['auth 失败', 'auth.list', 'AUTH_STORE_INSECURE'],
  ] as const)('/connect 单边失败仍收敛并保留另一边：%s', async (_label, failedIntent, code) => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 2);
    const otherIntent = failedIntent === 'models.list' ? 'auth.list' : 'models.list';
    client.emit(connectResult(failedIntent === 'models.list' ? 'tui-connect:1:models' : 'tui-connect:1:auth',
      failedIntent, 2, {}, 'rejected', code));
    client.emit(connectResult(otherIntent === 'models.list' ? 'tui-connect:1:models' : 'tui-connect:1:auth',
      otherIntent, 3, otherIntent === 'models.list' ? {models: []} : {profiles: []}));
    await waitForFrame(() => view.lastFrame()?.includes('连接模型服务') === true
      && view.lastFrame()?.includes('加载中') === false);
    const frame = view.lastFrame() ?? '';

    expect(frame).toContain('连接模型服务');
    expect(frame).toContain(failedIntent === 'models.list' ? '模型目录暂不可用' : '连接状态暂不可用');
    expect(frame).toContain('添加自定义服务（高级）');
    expect(frame).not.toContain(code);
    expect(frame).not.toContain('Credential profiles');
    view.unmount();
  });

  it('连接向导只保留当前 generation 的两条加载腿，旧代与错误 intent 不改变状态', () => {
    const wizard = beginConnectWizard(10_000);
    expect(wizard.generation).toBe(10_000);
    expect(wizard.auth.controlId).toBe('tui-connect:10000:auth');
    expect(wizard.models.controlId).toBe('tui-connect:10000:models');
    expect(wizard.profiles).toEqual([]);
    expect(wizard.modelCatalog).toEqual([]);

    const stale = applyConnectWizardResult(wizard, {
      controlId: 'tui-connect:1:models', intent: 'models.list', status: 'succeeded', code: 'OK', result: {models: []},
    });
    const wrongIntent = applyConnectWizardResult(wizard, {
      controlId: wizard.models.controlId, intent: 'auth.list', status: 'succeeded', code: 'OK', result: {profiles: []},
    });
    expect(stale).toBe(wizard);
    expect(wrongIntent).toBe(wizard);
  });

  it('独立 Provider namespace 绑定 sequence 与 intent，无需 pending registry', () => {
    const controlId = independentProviderControlId(42, 'models.list');
    expect(controlId).toBe('tui-provider:42:models.list');
    expect(isIndependentProviderControlResult(controlId, 'models.list')).toBe(true);
    expect(isIndependentProviderControlResult(controlId, 'auth.list')).toBe(false);
    expect(isIndependentProviderControlResult('tui-connect:42:models', 'models.list')).toBe(false);
    expect(isIndependentProviderControlResult('unrelated-auth', 'auth.list')).toBe(false);
  });

  it('/connect 关闭后第二代 fence 第一代，并忽略重复、迟到与无关结果', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 2);
    view.stdin.write('\x1b');
    await waitForFrame(() => view.lastFrame()?.includes('连接模型服务') === false);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 4);

    client.emit(connectResult('tui-connect:1:models', 'models.list', 2, {
      models: [{providerId: 'stale', modelId: 'old-model', providerDefault: false}],
    }));
    client.emit(connectResult('unrelated-auth', 'auth.list', 3, {
      profiles: [{providerId: 'unrelated', profileId: 'foreign', authMethod: 'API_KEY', refKind: 'ENV',
        localStatus: 'AVAILABLE_LOCAL', providerDefault: false}],
    }));
    client.emit(connectResult('tui-connect:2:auth', 'auth.list', 4, {profiles: []}));
    client.emit(connectResult('tui-connect:2:models', 'models.list', 5, {models: []}));
    client.emit(connectResult('tui-connect:2:models', 'models.list', 6, {
      models: [{providerId: 'duplicate', modelId: 'late-model', providerDefault: false}],
    }));
    client.emit(connectResult('tui-connect:1:auth', 'auth.list', 7, {
      profiles: [{providerId: 'stale', profileId: 'old-profile', authMethod: 'API_KEY', refKind: 'ENV',
        localStatus: 'AVAILABLE_LOCAL', providerDefault: false}],
    }));
    await waitForFrame(() => view.lastFrame()?.includes('Anthropic · 未连接') === true
      && view.lastFrame()?.includes('加载中') === false);
    const frame = view.lastFrame() ?? '';

    expect(client.providerControls).toEqual([
      'tui-connect:1:models:models.list:{}', 'tui-connect:1:auth:auth.list:{}',
      'tui-connect:2:models:models.list:{}', 'tui-connect:2:auth:auth.list:{}',
    ]);
    expect(frame).toContain('Anthropic · 未连接');
    expect(frame).toContain('OpenRouter · 未连接');
    expect(frame).not.toContain('old-model');
    expect(frame).not.toContain('foreign');
    expect(frame).not.toContain('late-model');
    expect(frame).not.toContain('old-profile');
    expect(frame).not.toContain('（刷新中）');
    view.unmount();
  });

  it('无参数 /connect 从 Provider 到 masked login、刷新、模型选择和完成页完整闭环', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 2);
    client.emit(connectResult('tui-connect:1:models', 'models.list', 2, {models: [
      {providerId: 'anthropic', modelId: 'claude-sonnet-4-6', providerDefault: true},
    ]}));
    client.emit(connectResult('tui-connect:1:auth', 'auth.list', 3, {profiles: []}));
    await waitForFrame(() => view.lastFrame()?.includes('Anthropic · 未连接') === true);

    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('粘贴 API Key（推荐）') === true);
    view.stdin.write('\r');
    await waitForFrame(() => client.providerLogins.length === 1 && client.providerControls.length === 3);
    expect(client.providerLogins).toEqual([{providerId: 'anthropic', profileId: 'default', secretSource: 'store', setDefault: true}]);
    expect(client.providerControls[2]).toBe('tui-connect:1:refresh:auth:auth.list:{}');
    client.emit(connectResult('tui-connect:1:refresh:auth', 'auth.list', 4, {profiles: [{
      providerId: 'anthropic', profileId: 'default', authMethod: 'API_KEY', refKind: 'STORE',
      localStatus: 'AVAILABLE_LOCAL', providerDefault: true,
    }]}));
    await waitForFrame(() => view.lastFrame()?.includes('选择 Anthropic 模型') === true);
    view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 4);
    expect(client.providerControls[3]).toBe(
      'tui-connect:1:action:model:models.use:{"providerId":"anthropic","profileId":"default","modelId":"claude-sonnet-4-6","setDefault":true}',
    );
    client.emit({version: 0, type: 'provider.control.result', requestId: 'provider-result-5', sessionId: 'session-1',
      sequence: 5, payload: {controlId: 'tui-connect:1:action:model', intent: 'models.use', status: 'succeeded',
        code: 'OK', result: {providerId: 'anthropic', profileId: 'default', modelId: 'claude-sonnet-4-6', setDefault: true}}});
    await waitForFrame(() => view.lastFrame()?.includes('可以开始对话') === true);
    const frame = view.lastFrame() ?? '';
    expect(frame).toContain('已连接 Anthropic');
    expect(frame).toContain('已选择 claude-sonnet-4-6');
    expect(frame).not.toContain('refKind');
    expect(frame).not.toContain('localStatus');
    expect(frame).not.toContain('controlId');
    view.unmount();
  });

  it('已连接但模型目录为空时给出可执行提示，短窗口仍保留向导与 Composer', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 2);
    client.emit(connectResult('tui-connect:1:models', 'models.list', 2, {models: []}));
    client.emit(connectResult('tui-connect:1:auth', 'auth.list', 3, {profiles: [{
      providerId: 'anthropic', profileId: 'default', authMethod: 'API_KEY', refKind: 'ENV',
      localStatus: 'AVAILABLE_LOCAL', providerDefault: true,
    }]}));
    await waitForFrame(() => view.lastFrame()?.includes('Anthropic · 已连接') === true);
    view.stdin.write('\r'); view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('codej models add --help') === true);
    expect(view.lastFrame()).toContain('当前本地模型目录为空');

    const state: TuiState = {...initialCheckpointState(), checkpointPanelOpen: false};
    const wizard = applyConnectWizardResult(applyConnectWizardResult(beginConnectWizard(7), {
      controlId: 'tui-connect:7:models', intent: 'models.list', status: 'succeeded', code: 'OK', result: {models: []},
    }), {controlId: 'tui-connect:7:auth', intent: 'auth.list', status: 'succeeded', code: 'OK', result: {profiles: []}});
    const narrow = render(<AgentView state={state} input="" columns={70} rows={9} connectWizard={wizard} />);
    const frame = narrow.lastFrame() ?? '';
    expect(frame.split('\n').length).toBeLessThanOrEqual(9);
    expect(frame).toContain('连接模型服务');
    expect(frame).toContain('正在配置连接，请按上方提示操作');
    expect(frame).not.toContain('连接向导打开中');
    expect(frame).toContain('╭');
    narrow.unmount(); view.unmount();
  });

  it('无参数 /connect 的 ENV 路径只把名称与稳定 default profile 交给登录桥', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 2);
    client.emit(connectResult('tui-connect:1:models', 'models.list', 2, {models: []}));
    client.emit(connectResult('tui-connect:1:auth', 'auth.list', 3, {profiles: []}));
    await waitForFrame(() => view.lastFrame()?.includes('Anthropic · 未连接') === true);

    view.stdin.write('\r');
    view.stdin.write('\x1b[B');
    await waitForFrame(() => view.lastFrame()?.includes('❯ 使用环境变量（高级）') === true);
    view.stdin.write('\r');
    view.stdin.write('ANTHROPIC_API_KEY');
    await waitForFrame(() => view.lastFrame()?.includes('ANTHROPIC_API_KEY') === true);
    view.stdin.write('\r');
    await waitForFrame(() => client.providerLogins.length === 1);

    expect(client.providerLogins).toEqual([{
      providerId: 'anthropic', profileId: 'default', secretSource: 'env', environmentName: 'ANTHROPIC_API_KEY',
      setDefault: true,
    }]);
    expect(view.lastFrame()).toContain('TUI 只传环境变量名称，不读取变量值');
    view.unmount();
  });

  it('已连接 Provider 提供模型、更新凭证与二次确认 logout，并支持返回取消', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 2);
    client.emit(connectResult('tui-connect:1:models', 'models.list', 2, {models: [{
      providerId: 'anthropic', modelId: 'claude-sonnet-4-6', providerDefault: true,
    }]}));
    client.emit(connectResult('tui-connect:1:auth', 'auth.list', 3, {profiles: [{
      providerId: 'anthropic', profileId: 'default', authMethod: 'API_KEY', refKind: 'ENV',
      localStatus: 'AVAILABLE_LOCAL', providerDefault: true,
    }]}));
    await waitForFrame(() => view.lastFrame()?.includes('Anthropic · 已连接') === true);
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('退出登录（高级）') === true);
    view.stdin.write('\x1b[B'); view.stdin.write('\x1b[B'); view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('确认退出 Anthropic') === true);
    expect(view.lastFrame()).toContain('只删除本机凭证');
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('已连接 Anthropic') === true);
    view.stdin.write('\x1b[B'); view.stdin.write('\x1b[B'); view.stdin.write('\r');
    view.stdin.write('\x1b[A'); view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 3);
    expect(client.providerControls[2]).toBe(
      'tui-connect:1:action:logout:auth.logout:{"providerId":"anthropic","profileId":"default","confirmed":true}',
    );
    view.unmount();
  });

  it('真实键盘从自定义服务逐字段编辑、退格和 Esc，提交精确 providers.add payload', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 2);
    client.emit(connectResult('tui-connect:1:models', 'models.list', 2, {models: []}));
    client.emit(connectResult('tui-connect:1:auth', 'auth.list', 3, {profiles: []}));
    await waitForFrame(() => view.lastFrame()?.includes('连接状态加载中') === false);
    view.stdin.write('\x1b[B'); view.stdin.write('\x1b[B'); view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('1/5 服务名称') === true);
    view.stdin.write('Team GatewaX'); view.stdin.write('\x7f'); view.stdin.write('y'); view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('2/5 稳定 ID') === true);
    expect(view.lastFrame()).toContain('team-gateway');
    view.stdin.write('\x7f'); view.stdin.write('y');
    view.stdin.write('\x1b');
    await waitForFrame(() => view.lastFrame()?.includes('1/5 服务名称') === true);
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('team-gateway') === true);
    view.stdin.write('\r');
    view.stdin.write('https://gateway.example/v1'); view.stdin.write('\r');
    view.stdin.write('model-X'); view.stdin.write('\x7f'); view.stdin.write('x'); view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('5/5 确认服务配置') === true);
    view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 3);

    expect(client.providerControls[2]).toBe(
      'tui-connect:1:action:provider:providers.add:{"providerId":"team-gateway","displayName":"Team Gateway","baseUrl":"https://gateway.example/v1","modelId":"model-x"}',
    );
    expect(view.lastFrame()).toContain('正在保存，请稍候');
    expect(view.lastFrame()).not.toContain('codej providers add --help');
    client.emit(connectResult('tui-connect:1:action:provider', 'providers.add', 4, {
      providerId: 'team-gateway', displayName: 'Team Gateway', modelId: 'model-x',
    }));
    await waitForFrame(() => view.lastFrame()?.includes('粘贴 API Key（推荐）') === true);
    view.stdin.write('\r');
    await waitForFrame(() => client.providerLogins.length === 1 && client.providerControls.length === 4);
    expect(client.providerLogins).toEqual([{providerId: 'team-gateway', profileId: 'default', secretSource: 'store', setDefault: true}]);
    expect(client.providerControls[3]).toBe('tui-connect:1:refresh:auth:auth.list:{}');
    client.emit(connectResult('tui-connect:1:refresh:auth', 'auth.list', 5, {profiles: [{
      providerId: 'team-gateway', profileId: 'default', authMethod: 'API_KEY', refKind: 'STORE',
      localStatus: 'AVAILABLE_LOCAL', providerDefault: true,
    }]}));
    await waitForFrame(() => view.lastFrame()?.includes('选择 team-gateway 模型') === true);
    view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 5);
    expect(client.providerControls[4]).toBe(
      'tui-connect:1:action:model:models.use:{"providerId":"team-gateway","profileId":"default","modelId":"model-x","setDefault":true}',
    );
    client.emit(connectResult('tui-connect:1:action:model', 'models.use', 6, {
      providerId: 'team-gateway', profileId: 'default', modelId: 'model-x', setDefault: true,
    }));
    await waitForFrame(() => view.lastFrame()?.includes('可以开始对话') === true);
    expect(view.lastFrame()).toContain('已连接 team-gateway');
    expect(client.prompts).toEqual([]);
    view.unmount();
  });

  it('保存中重复 Enter/Esc 只提交一次，迟到成功进入认证且返回 picker 不会重放 add', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 2);
    client.emit(connectResult('tui-connect:1:models', 'models.list', 2, {models: []}));
    client.emit(connectResult('tui-connect:1:auth', 'auth.list', 3, {profiles: []}));
    view.stdin.write('\x1b[B'); view.stdin.write('\x1b[B'); view.stdin.write('\r');
    view.stdin.write('Team'); view.stdin.write('\r'); view.stdin.write('\r');
    view.stdin.write('https://gateway.example/v1'); view.stdin.write('\r');
    view.stdin.write('model-x'); view.stdin.write('\r'); view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 3);

    view.stdin.write('\r'); view.stdin.write('\x1b'); view.stdin.write('\r'); view.stdin.write('\x1b');
    await new Promise(resolve => setTimeout(resolve, 25));
    expect(client.providerControls).toHaveLength(3);
    expect(view.lastFrame()).toContain('正在保存，请稍候');

    client.emit(connectResult('tui-connect:1:action:provider', 'providers.add', 4, {
      providerId: 'team', displayName: 'Team', modelId: 'model-x',
    }));
    await waitForFrame(() => view.lastFrame()?.includes('粘贴 API Key（推荐）') === true);
    view.stdin.write('\x1b');
    await waitForFrame(() => view.lastFrame()?.includes('自定义 · team') === true);
    expect(client.providerControls).toHaveLength(3);
    expect(view.lastFrame()).not.toContain('5/5 确认服务配置');
    view.unmount();
  });

  it('重新打开 /connect 可见稳定排序的已有 custom，Enter 直接进入 management/auth 而不 add', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 2);
    client.emit(connectResult('tui-connect:1:models', 'models.list', 2, {models: [
      {providerId: 'z-gateway', modelId: 'z-model', providerDefault: true},
      {providerId: 'team-gateway', modelId: 'team-model', providerDefault: true},
    ]}));
    client.emit(connectResult('tui-connect:1:auth', 'auth.list', 3, {profiles: [{
      providerId: 'team-gateway', profileId: 'default', authMethod: 'API_KEY', refKind: 'ENV',
      localStatus: 'AVAILABLE_LOCAL', providerDefault: true,
    }]}));
    await waitForFrame(() => view.lastFrame()?.includes('自定义 · team-gateway · 已连接') === true);
    const frame = view.lastFrame() ?? '';
    expect(frame.indexOf('自定义 · team-gateway')).toBeLessThan(frame.indexOf('自定义 · z-gateway'));
    expect(frame.indexOf('自定义 · z-gateway')).toBeLessThan(frame.indexOf('添加自定义服务（高级）'));

    view.stdin.write('\x1b[B'); view.stdin.write('\x1b[B'); view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('已连接 team-gateway') === true);
    expect(view.lastFrame()).toContain('选择模型');
    expect(client.providerControls).toHaveLength(2);
    view.stdin.write('\x1b');
    view.stdin.write('\x1b[B'); view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('粘贴 API Key（推荐）') === true);
    expect(client.providerControls).toHaveLength(2);
    view.unmount();
  });

  it('providers.add 失败允许返回确认页修改，旧代与重复保存结果不污染新向导', async () => {
    const client = new FakeAgentClient();
    const view = await initializedTui(client);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 2);
    client.emit(connectResult('tui-connect:1:models', 'models.list', 2, {models: []}));
    client.emit(connectResult('tui-connect:1:auth', 'auth.list', 3, {profiles: []}));
    view.stdin.write('\x1b[B'); view.stdin.write('\x1b[B'); view.stdin.write('\r');
    view.stdin.write('Team'); view.stdin.write('\r'); view.stdin.write('\r');
    view.stdin.write('https://gateway.example/v1'); view.stdin.write('\r');
    view.stdin.write('model-x'); view.stdin.write('\r'); view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 3);
    client.emit(connectResult('tui-connect:1:action:provider', 'providers.add', 4, {}, 'rejected', 'PROVIDER_DEFINITION_INVALID'));
    await waitForFrame(() => view.lastFrame()?.includes('该 ID 已存在') === true);
    view.stdin.write('\r');
    await waitForFrame(() => view.lastFrame()?.includes('5/5 确认服务配置') === true);
    view.stdin.write('\x1b');
    await waitForFrame(() => view.lastFrame()?.includes('4/5 模型名') === true);
    view.stdin.write('\x1b'); await waitForFrame(() => view.lastFrame()?.includes('3/5 HTTPS Base URL') === true);
    view.stdin.write('\x1b'); await waitForFrame(() => view.lastFrame()?.includes('2/5 稳定 ID') === true);
    view.stdin.write('\x1b'); await waitForFrame(() => view.lastFrame()?.includes('1/5 服务名称') === true);
    view.stdin.write('\x1b'); await waitForFrame(() => view.lastFrame()?.includes('❯ 添加自定义服务（高级）') === true);
    view.stdin.write('\x1b'); await waitForFrame(() => view.lastFrame()?.includes('连接模型服务') === false);
    submitConnect(view);
    await waitForFrame(() => client.providerControls.length === 5);
    client.emit(connectResult('tui-connect:1:action:provider', 'providers.add', 5, {
      providerId: 'team', displayName: 'Team', modelId: 'model-x',
    }));
    client.emit(connectResult('tui-connect:2:auth', 'auth.list', 6, {profiles: []}));
    client.emit(connectResult('tui-connect:2:models', 'models.list', 7, {models: []}));
    await waitForFrame(() => view.lastFrame()?.includes('Anthropic · 未连接') === true);
    expect(view.lastFrame()).not.toContain('粘贴 API Key（推荐）');
    view.unmount();
  });

  it('/connect profile 直接调用一次性 Java 登录并在成功后刷新 auth.list', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('/connect anthropic personal'); view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 1);

    expect(client.providerLogins).toEqual([{
      providerId: 'anthropic', profileId: 'personal', secretSource: 'store',
    }]);
    expect(client.providerControls).toEqual(['tui-provider:1:auth.list:auth.list:{}']);
    expect(client.prompts).toEqual([]);
    expect(view.lastFrame()).toContain('正在刷新 credential 列表');
    view.unmount();
  });

  it('/connect ENV 只传合法环境变量名称而不读取值', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('/connect openrouter ci env OPENROUTER_API_KEY'); view.stdin.write('\r');
    await waitForFrame(() => client.providerLogins.length === 1);

    expect(client.providerLogins[0]).toEqual({
      providerId: 'openrouter', profileId: 'ci', secretSource: 'env', environmentName: 'OPENROUTER_API_KEY',
    });
    expect(view.lastFrame()).toContain('TUI 不读取环境值');
    view.unmount();
  });

  it('发送 models.add/remove，并在 add 成功后刷新 models.list', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);

    view.stdin.write('/models add anthropic claude-opus default'); view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 1);
    expect(client.providerControls).toEqual([
      'tui-provider:1:models.add:models.add:{"providerId":"anthropic","modelId":"claude-opus","setDefault":true}',
    ]);
    client.emit({version: 0, type: 'provider.control.result', requestId: 'provider-result',
      sessionId: 'session-1', sequence: 2, payload: {controlId: 'tui-provider:1:models.add', intent: 'models.add',
        status: 'succeeded', code: 'OK', result: {providerId: 'anthropic', modelId: 'claude-opus'}}});
    await waitForFrame(() => client.providerControls.length === 2);
    expect(client.providerControls[1]).toBe('tui-provider:2:models.list:models.list:{}');

    view.stdin.write('/models remove anthropic claude-sonnet'); view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 3);
    expect(client.providerControls[2]).toBe(
      'tui-provider:3:models.remove:models.remove:{"providerId":"anthropic","modelId":"claude-sonnet"}',
    );
    expect(client.prompts).toEqual([]);
    view.unmount();
  });

  it('Provider Slash 通过真实 stdio 控制通道并渲染安全结果', async () => {
    const client = new FakeAgentClient();
    const view = render(<AgentTui client={client} />);
    await waitForFrame(() => client.initializeCalls === 1);
    client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
    await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
    view.stdin.write('/auth list'); view.stdin.write('\r');
    await waitForFrame(() => client.providerControls.length === 1);
    expect(client.providerControls).toEqual(['tui-provider:1:auth.list:auth.list:{}']);
    expect(client.prompts).toEqual([]);
    client.emit({version: 0, type: 'provider.control.result', requestId: 'provider-result',
      sessionId: 'session-1', sequence: 2, payload: {controlId: 'tui-provider:1:auth.list', intent: 'auth.list',
        status: 'succeeded', code: 'OK', result: {profiles: [{providerId: 'anthropic', profileId: 'personal',
          authMethod: 'API_KEY', refKind: 'ENV', localStatus: 'AVAILABLE_LOCAL', providerDefault: true}]}}});
    await waitForFrame(() => view.lastFrame()?.includes('anthropic/personal') === true);
    view.unmount();
  });
async function initializedTui(client: FakeAgentClient) {
  const view = render(<AgentTui client={client} />);
  await waitForFrame(() => client.initializeCalls === 1);
  client.emit({version: 0, type: 'initialized', requestId: 'init', sessionId: 'session-1', sequence: 1, payload: {}});
  await waitForFrame(() => view.lastFrame()?.includes('就绪') === true);
  return view;
}

function submitConnect(view: ReturnType<typeof render>): void {
  view.stdin.write('/connect');
  view.stdin.write('\r');
}

function connectResult(
  controlId: string,
  intent: 'providers.add' | 'models.list' | 'models.use' | 'auth.list',
  sequence: number,
  result: Readonly<Record<string, unknown>>,
  status: 'succeeded' | 'rejected' = 'succeeded',
  code = 'OK',
): ProtocolEvent {
  return {
    version: 0, type: 'provider.control.result', requestId: `provider-result-${sequence}`,
    sessionId: 'session-1', sequence,
    payload: {controlId, intent, status, code, result},
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
  readonly providerControls: string[] = [];
  readonly providerLogins: ProviderLoginRequest[] = [];
  providerLoginResult: ProviderLoginResult = {status: 'succeeded', exitCode: 0};
  readonly fileSuggestions: string[] = [];
  readonly taskCommands: string[] = [];
  readonly skillInvocations: string[] = [];
  initializeCalls = 0;
  terminateCalls = 0;
  shutdownCalls = 0;
  readonly #eventListeners = new Set<(event: ProtocolEvent) => void>();
  readonly #failureListeners = new Set<(message: string) => void>();
  readonly #exitListeners = new Set<() => void>();

  public onEvent(listener: (event: ProtocolEvent) => void): () => void {
    this.#eventListeners.add(listener);
    return () => this.#eventListeners.delete(listener);
  }

  public onFailure(listener: (message: string) => void): () => void {
    this.#failureListeners.add(listener);
    return () => this.#failureListeners.delete(listener);
  }

  public onExit(listener: () => void): () => void {
    this.#exitListeners.add(listener);
    return () => this.#exitListeners.delete(listener);
  }

  public initialize(): string {
    this.initializeCalls++;
    return 'tui-1';
  }

  public startRun(prompt: string): string {
    this.prompts.push(prompt);
    return `tui-${this.prompts.length + 1}`;
  }

  public invokeSkill(name: string, arguments_: string): string {
    this.skillInvocations.push(`${name}:${arguments_}`);
    return `tui-skill-${this.skillInvocations.length}`;
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

  public waitTask(taskId: string, timeoutMillis: number): string {
    this.taskCommands.push(`wait:${taskId}:${timeoutMillis}`); return 'tui-task-wait';
  }
  public cancelTask(taskId: string): string {
    this.taskCommands.push(`cancel:${taskId}`); return 'tui-task-cancel';
  }
  public keepTaskWorktree(taskId: string): string {
    this.taskCommands.push(`keep:${taskId}`); return 'tui-task-keep';
  }
  public removeTaskWorktree(taskId: string): string {
    this.taskCommands.push(`remove:${taskId}`); return 'tui-task-remove';
  }

  public sessionCommand(commandId: string, intent: 'help' | 'clear' | 'compact' | 'context' | 'doctor' | 'model' | 'permissions' | 'resume', arguments_: Readonly<Record<string, unknown>>): string {
    this.sessionCommands.push(`${commandId}:${intent}:${JSON.stringify(arguments_)}`);
    return 'tui-session-command';
  }

  public providerControl(controlId: string, intent: 'auth.list' | 'auth.probe' | 'auth.logout' | 'models.list' | 'models.use' | 'models.add' | 'models.remove', arguments_: Readonly<Record<string, unknown>>): string {
    this.providerControls.push(`${controlId}:${intent}:${JSON.stringify(arguments_)}`);
    return 'tui-provider-control';
  }
  public async providerLogin(request: ProviderLoginRequest): Promise<ProviderLoginResult> {
    this.providerLogins.push(request);
    return await Promise.resolve(this.providerLoginResult);
  }
  public cancelProviderLogin(): void {
  }
  public suggestFiles(query: string): string {
    this.fileSuggestions.push(query);
    return `file-${this.fileSuggestions.length}`;
  }

  public async shutdown(): Promise<void> {
    this.shutdownCalls++;
    return await Promise.resolve();
  }

  public terminate(): void {
    this.terminateCalls++;
  }

  public emit(event: ProtocolEvent): void {
    for (const listener of this.#eventListeners) {
      listener(event);
    }
  }

  public emitFailure(message: string): void {
    for (const listener of this.#failureListeners) {
      listener(message);
    }
  }

  public emitExit(): void {
    for (const listener of this.#exitListeners) {
      listener();
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
