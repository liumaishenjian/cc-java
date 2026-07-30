import {describe, expect, it} from 'vitest';
import {ProtocolViolation, decodeEvent} from '../src/protocol.js';

describe('decodeEvent', () => {
  it('接受带中文 Delta 的连续事件', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0,
      type: 'model.text.delta',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 2,
      payload: {text: '你好'},
    }), 2);

    expect(event.payload.text).toBe('你好');
  });

  it('拒绝乱序事件', () => {
    expect(() => decodeEvent(JSON.stringify({
      version: 0,
      type: 'initialized',
      requestId: 'req-1',
      sessionId: 'session-1',
      sequence: 3,
      payload: {},
    }), 1)).toThrowError(ProtocolViolation);
  });

  it('拒绝缺失 Run 关联的 Delta', () => {
    expect(() => decodeEvent(JSON.stringify({
      version: 0,
      type: 'model.text.delta',
      requestId: 'req-1',
      sessionId: 'session-1',
      sequence: 1,
      payload: {text: 'x'},
    }), 1)).toThrowError(/runId/);
  });

  it('拒绝包含控制字符的终止原因', () => {
    expect(() => decodeEvent(JSON.stringify({
      version: 0,
      type: 'run.failed',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 1,
      payload: {stopReason: 'model_error\n伪造终端输出'},
    }), 1)).toThrowError(/stopReason/);
  });

  it('接受安全 Tool 展示摘要并拒绝未知模式', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0,
      type: 'tool.completed',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 1,
      payload: {
        ordinal: 1,
        toolName: 'search_text',
        status: 'success',
        mode: 'content',
        returnedItems: 12,
        truncationReason: 'item_limit',
      },
    }), 1);

    expect(event.payload.returnedItems).toBe(12);
    expect(() => decodeEvent(JSON.stringify({
      ...event,
      payload: {...event.payload, mode: 'raw'},
    }), 1)).toThrowError(/模式/);
  });
});
