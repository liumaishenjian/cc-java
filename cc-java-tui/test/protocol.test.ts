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

  it('只接受带固定副作用分类的审批摘要', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0,
      type: 'approval.requested',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 1,
      payload: {
        approvalId: 'approval-1',
        ordinal: 2,
        toolName: 'apply_patch',
        effect: 'write_workspace',
        target: 'src/main/App.java',
        operation: 'modify',
        removedLines: 2,
        addedLines: 3,
      },
    }), 1);

    expect(event.payload.approvalId).toBe('approval-1');
    expect(() => decodeEvent(JSON.stringify({
      ...event,
      payload: {...event.payload, effect: 'system_or_destructive'},
    }), 1)).toThrowError(/审批摘要/);
    expect(() => decodeEvent(JSON.stringify({
      ...event,
      payload: {...event.payload, target: 'C:\\secret.txt'},
    }), 1)).toThrowError(/文件预览/);
  });

  it('接受准确命令审批和有界 Tool 输出事件', () => {
    const approval = decodeEvent(JSON.stringify({
      version: 0,
      type: 'approval.requested',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 1,
      payload: {
        approvalId: 'approval-command',
        ordinal: 1,
        toolName: 'run_command',
        effect: 'execute_process',
        operation: 'execute',
        command: 'mvn test',
        shell: 'powershell',
        workingDirectory: '.',
      },
    }), 1);
    const output = decodeEvent(JSON.stringify({
      version: 0,
      type: 'tool.output',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 2,
      payload: {
        ordinal: 1,
        toolName: 'run_command',
        stream: 'stdout',
        text: 'BUILD SUCCESS\n',
      },
    }), 2);

    expect(approval.payload.command).toBe('mvn test');
    expect(output.payload.text).toContain('BUILD SUCCESS');
  });
});
