import {describe, expect, it} from 'vitest';
import {initialTuiState, reduceTuiState} from '../src/state.js';
import type {ProtocolEvent} from '../src/protocol.js';

describe('reduceTuiState', () => {
  it('只根据 Java 终态完成一次流式 Run', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'req-init', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'run.submitted',
      requestId: 'req-run',
      prompt: '解释项目',
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.started', 2, {}, 'req-run', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('model.text.delta', 3, {text: '你好'}, 'req-run', 'session-1', 'run-1'),
    });

    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('tool.started', 4, {ordinal: 1, toolName: 'read_file'}, 'req-run', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('tool.completed', 5, {
        ordinal: 1,
        toolName: 'read_file',
        status: 'success',
        returnedCharacters: 42,
        returnedItems: 2,
        filteredItems: 3,
        truncated: true,
        truncationReason: 'item_limit',
      }, 'req-run', 'session-1', 'run-1'),
    });

    expect(state.phase).toBe('running');
    expect(state.runs[0]?.text).toBe('你好');
    expect(state.runs[0]?.tools).toEqual([
      expect.objectContaining({
        name: 'read_file',
        status: 'success',
        returnedItems: 2,
        filteredItems: 3,
        truncated: true,
        truncationReason: 'item_limit',
      }),
    ]);

    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.completed', 6, {
        stopReason: 'completed',
        modelTurns: 2,
        toolCalls: 1,
      }, 'req-run', 'session-1', 'run-1'),
    });
    expect(state.phase).toBe('ready');
    expect(state.runs[0]).toEqual(expect.objectContaining({
      status: 'completed',
      stopReason: 'completed',
      modelTurns: 2,
      toolCalls: 1,
    }));
  });

  it('不把协议错误文本原样显示', () => {
    const state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event(
        'protocol.error',
        1,
        {code: 'INVALID_INPUT', message: '可能包含秘密的原文'},
        'req-1',
      ),
    });

    expect(state.notice).toBe('Java 协议错误：INVALID_INPUT');
    expect(state.notice).not.toContain('秘密');
  });

  it('同一 Session 连续保留两个已完成 Run', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'init', 'session-1'),
    });
    for (const [index, prompt] of ['first', 'second'].entries()) {
      const requestId = `req-${index + 1}`;
      const runId = `run-${index + 1}`;
      state = reduceTuiState(state, {
        type: 'run.submitted',
        requestId,
        prompt,
      });
      state = reduceTuiState(state, {
        type: 'event.received',
        event: event(
          'run.started',
          2 + index * 3,
          {},
          requestId,
          'session-1',
          runId,
        ),
      });
      state = reduceTuiState(state, {
        type: 'event.received',
        event: event(
          'model.text.delta',
          3 + index * 3,
          {text: `answer-${index + 1}`},
          requestId,
          'session-1',
          runId,
        ),
      });
      state = reduceTuiState(state, {
        type: 'event.received',
        event: event(
          'run.completed',
          4 + index * 3,
          {stopReason: 'completed', modelTurns: 1, toolCalls: 0},
          requestId,
          'session-1',
          runId,
        ),
      });
    }

    expect(state.sessionId).toBe('session-1');
    expect(state.phase).toBe('ready');
    expect(state.runs).toEqual([
      expect.objectContaining({prompt: 'first', text: 'answer-1', status: 'completed'}),
      expect.objectContaining({prompt: 'second', text: 'answer-2', status: 'completed'}),
    ]);
  });

  it('保留 Java 返回的失败原因和预算计数', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'run.submitted',
      requestId: 'req-failed',
      prompt: '检查失败原因',
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.started', 2, {}, 'req-failed', 'session-1', 'run-failed'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.failed', 3, {
        stopReason: 'turn_limit_reached',
        modelTurns: 16,
        toolCalls: 12,
      }, 'req-failed', 'session-1', 'run-failed'),
    });

    expect(state.runs[0]).toEqual(expect.objectContaining({
      status: 'failed',
      stopReason: 'turn_limit_reached',
      modelTurns: 16,
      toolCalls: 12,
    }));
  });

  it('投影单次审批并在用户提交决定后清理等待面板', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'run.submitted',
      requestId: 'req-run',
      prompt: '修改文件',
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.started', 2, {}, 'req-run', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('approval.requested', 3, {
        approvalId: 'approval-1',
        ordinal: 1,
        toolName: 'apply_patch',
        effect: 'write_workspace',
        target: 'src/main/App.java',
        operation: 'modify',
        removedLines: 2,
        addedLines: 3,
      }, 'req-run', 'session-1', 'run-1'),
    });

    expect(state.runs[0]?.pendingApproval).toEqual({
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
    });
    state = reduceTuiState(state, {
      type: 'approval.submitted',
      approvalId: 'approval-1',
    });
    expect(state.runs[0]?.pendingApproval?.submitted).toBe(true);
    expect(state.phase).toBe('running');
  });

  it('把命令输出追加到对应 Tool 且保持通道标记', () => {
    let state = reduceTuiState(initialTuiState, {
      type: 'event.received',
      event: event('initialized', 1, {protocolVersion: 0}, 'init', 'session-1'),
    });
    state = reduceTuiState(state, {
      type: 'run.submitted',
      requestId: 'req-command',
      prompt: '运行测试',
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.started', 2, {}, 'req-command', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('tool.started', 3, {
        ordinal: 1,
        toolName: 'run_command',
        status: 'started',
      }, 'req-command', 'session-1', 'run-1'),
    });
    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('tool.output', 4, {
        ordinal: 1,
        toolName: 'run_command',
        stream: 'stderr',
        text: 'test failed\n',
      }, 'req-command', 'session-1', 'run-1'),
    });

    expect(state.runs[0]?.tools[0]?.output).toBe('[stderr] test failed\n');
  });
});

function event(
  type: ProtocolEvent['type'],
  sequence: number,
  payload: Record<string, unknown>,
  requestId: string,
  sessionId?: string,
  runId?: string,
): ProtocolEvent {
  return {
    version: 0,
    type,
    requestId,
    ...(sessionId === undefined ? {} : {sessionId}),
    ...(runId === undefined ? {} : {runId}),
    sequence,
    payload,
  };
}
