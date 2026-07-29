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

    expect(state.phase).toBe('running');
    expect(state.runs[0]?.text).toBe('你好');

    state = reduceTuiState(state, {
      type: 'event.received',
      event: event('run.completed', 4, {}, 'req-run', 'session-1', 'run-1'),
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
