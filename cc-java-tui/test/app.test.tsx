import {render} from 'ink-testing-library';
import {describe, expect, it} from 'vitest';
import {
  AgentView,
  appendInput,
  decideInterrupt,
  editInput,
  MAX_INPUT_CHARS,
} from '../src/app.js';
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
});
