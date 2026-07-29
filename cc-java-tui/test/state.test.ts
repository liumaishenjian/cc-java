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
        truncated: true,
      }, 'req-run', 'session-1', 'run-1'),
    });

    expect(state.phase).toBe('running');
    expect(state.runs[0]?.text).toBe('你好');
    expect(state.runs[0]?.tools).toEqual([
      expect.objectContaining({name: 'read_file', status: 'success', truncated: true}),
    ]);

    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.completed', 6, {}, 'req-run', 'session-1', 'run-1'),
    });
    expect(state.phase).toBe('ready');
    expect(state.runs[0]?.status).toBe('completed');
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
          {},
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
