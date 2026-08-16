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

  it('接受 privacy-safe Skill lifecycle 并拒绝参数或正文泄漏', () => {
    const invoked = {
      version: 0, type: 'skill.invoked', requestId: 'req-skill', sessionId: 'session-1', sequence: 1,
      payload: {skillId: 'code-review', invocationKind: 'explicit'},
    };
    expect(decodeEvent(JSON.stringify(invoked), 1).payload.skillId).toBe('code-review');
    const completed = {
      ...invoked, type: 'skill.completed', runId: 'run-1', sequence: 2,
      payload: {skillId: 'code-review', invocationKind: 'explicit', status: 'succeeded', stopReason: 'completed'},
    };
    expect(decodeEvent(JSON.stringify(completed), 2).payload.status).toBe('succeeded');
    expect(() => decodeEvent(JSON.stringify({
      ...invoked, payload: {...invoked.payload, arguments: 'SECRET'},
    }), 1)).toThrowError(/Skill/);
    expect(() => decodeEvent(JSON.stringify({...completed, runId: undefined}), 2)).toThrowError(/runId/);
  });

  it('严格接受 providers.add 非秘密投影并拒绝 endpoint 或控制字', () => {
    const base = {
      version: 0, type: 'provider.control.result', requestId: 'provider-add', sessionId: 'session-1', sequence: 1,
      payload: {controlId: 'tui-connect:1:action:provider', intent: 'providers.add', status: 'succeeded', code: 'OK',
        result: {providerId: 'team', displayName: 'Team Gateway', modelId: 'model-x'}},
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.result).toEqual(base.payload.result);
    expect(() => decodeEvent(JSON.stringify({...base, payload: {...base.payload,
      result: {...base.payload.result, baseUrl: 'https://private.example'}}}), 1)).toThrowError(/provider/);
    expect(() => decodeEvent(JSON.stringify({...base, payload: {...base.payload,
      result: {...base.payload.result, displayName: 'bad\nname'}}}), 1)).toThrowError(/provider/);
  });

  it.each([
    ['models.add', {providerId: 'anthropic', modelId: 'model-x', setDefault: true}],
    ['models.remove', {providerId: 'anthropic', modelId: 'model-x'}],
    ['models.use', {providerId: 'anthropic', profileId: 'default', modelId: 'model-x', setDefault: true}],
  ])('严格接受 %s 正式结果投影并拒绝多余字段', (intent, result) => {
    const event = {
      version: 0, type: 'provider.control.result', requestId: `request-${intent}`,
      sessionId: 'session-1', sequence: 1,
      payload: {controlId: `control-${intent}`, intent, status: 'succeeded', code: 'OK', result},
    };
    expect(decodeEvent(JSON.stringify(event), 1).payload.result).toEqual(result);
    expect(() => decodeEvent(JSON.stringify({...event, payload: {...event.payload,
      result: {...result, unexpected: true}}}), 1)).toThrowError(/provider\.control/);
  });

  it('拒绝 models.add/use 非 boolean setDefault', () => {
    for (const intent of ['models.add', 'models.use']) {
      const result = intent === 'models.use'
        ? {providerId: 'anthropic', profileId: 'default', modelId: 'm', setDefault: 'true'}
        : {providerId: 'anthropic', modelId: 'm', setDefault: 'true'};
      expect(() => decodeEvent(JSON.stringify({
        version: 0, type: 'provider.control.result', requestId: 'request', sessionId: 'session-1', sequence: 1,
        payload: {controlId: 'control', intent, status: 'succeeded', code: 'OK', result},
      }), 1)).toThrowError(/provider\.control/);
    }
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

  it('接受无 Run 的 Checkpoint 投影并拒绝伪造 Run 关联', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0,
      type: 'checkpoint.listed',
      requestId: 'req-checkpoint',
      sessionId: 'session-1',
      sequence: 1,
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
    }), 1);

    expect(event.payload.checkpoints).toBeDefined();
    expect(() => decodeEvent(JSON.stringify({
      ...event,
      runId: 'run-forged',
    }), 1)).toThrowError(/不能携带 runId/);
  });

  it('严格校验 Checkpoint 列表的 phase、相对路径、字段和数量', () => {
    const base = {
      version: 0,
      type: 'checkpoint.listed',
      requestId: 'req-checkpoint',
      sessionId: 'session-1',
      sequence: 1,
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
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.checkpoints).toHaveLength(1);

    for (const payload of [
      {...base.payload, checkpoints: [{...base.payload.checkpoints[0], phase: 'unknown'}]},
      {...base.payload, checkpoints: [{...base.payload.checkpoints[0], target: 'C:\\secret.txt'}]},
      {...base.payload, checkpoints: [{...base.payload.checkpoints[0], undoable: false}]},
      {...base.payload, checkpoints: [{...base.payload.checkpoints[0], secret: 'leak'}]},
      {checkpoints: Array.from({length: 1_001}, () => base.payload.checkpoints[0])},
    ]) {
      expect(() => decodeEvent(JSON.stringify({...base, payload}), 1))
        .toThrowError(/checkpoint\.listed/);
    }
  });

  it('严格校验 Checkpoint Diff 和 Undo 的有界安全投影', () => {
    const diff = {
      version: 0,
      type: 'checkpoint.diffed',
      requestId: 'req-diff',
      sessionId: 'session-1',
      sequence: 1,
      payload: {
        checkpointId: 'checkpoint-run-1-1',
        target: 'src/App.java',
        status: 'changed',
        text: '-old\n+new\n',
        truncated: false,
      },
    };
    expect(decodeEvent(JSON.stringify(diff), 1).payload.status).toBe('changed');
    expect(() => decodeEvent(JSON.stringify({
      ...diff,
      payload: {...diff.payload, text: 'x'.repeat(16 * 1_024 + 1)},
    }), 1)).toThrowError(/checkpoint\.diffed/);
    expect(() => decodeEvent(JSON.stringify({
      ...diff,
      payload: {...diff.payload, status: 'restored'},
    }), 1)).toThrowError(/checkpoint\.diffed/);

    const undo = {
      ...diff,
      type: 'checkpoint.undone',
      requestId: 'req-undo',
      payload: {
        checkpointId: 'checkpoint-run-1-1',
        target: 'src/App.java',
        status: 'restored',
        message: 'Checkpoint 已恢复',
      },
    };
    expect(decodeEvent(JSON.stringify(undo), 1).payload.status).toBe('restored');
    expect(() => decodeEvent(JSON.stringify({
      ...undo,
      payload: {...undo.payload, providerText: 'secret'},
    }), 1)).toThrowError(/checkpoint\.undone/);
  });

  it('只接受白名单 steering 安全投影', () => {
    const queued = {
      version: 0, type: 'steering.queued', requestId: 'steering-1', sessionId: 'session-1', sequence: 1,
      payload: {queueDepth: 1},
    };
    expect(decodeEvent(JSON.stringify(queued), 1).payload.queueDepth).toBe(1);
    const discarded = {
      ...queued, type: 'steering.discarded', requestId: 'steering-2',
      payload: {reason: 'cancelled'},
    };
    expect(decodeEvent(JSON.stringify(discarded), 1).payload.reason).toBe('cancelled');
    for (const invalid of [
      {...queued, payload: {queueDepth: 0}},
      {...queued, payload: {queueDepth: 101}},
      {...queued, runId: 'run-1'},
      {...discarded, payload: {reason: 'secret'}},
      {...discarded, payload: {reason: 'clear', prompt: 'SECRET_PROMPT'}},
    ]) {
      expect(() => decodeEvent(JSON.stringify(invalid), 1)).toThrowError(/steering/);
    }
  });

  it('接受严格且无 Run 关联的 session command terminal result', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0, type: 'session.command.result', requestId: 'req-command',
      sessionId: 'session-1', sequence: 1,
      payload: {commandId: 'command-1', intent: 'doctor', status: 'rejected', code: 'deferred', result: {}},
    }), 1);
    expect(event.type).toBe('session.command.result');
    expect(() => decodeEvent(JSON.stringify({
      ...event, runId: 'run-1', payload: {...event.payload, secret: 'leak'},
    }), 1)).toThrowError(/session\.command\.result/);
  });

  it('按 intent 严格校验 session command 投影，拒绝泄漏、未知字段和超限数组', () => {
    const base = {
      version: 0, type: 'session.command.result', requestId: 'req-command',
      sessionId: 'session-1', sequence: 1,
      payload: {commandId: 'command-1', intent: 'help', status: 'succeeded', code: 'ok', result: {
        commands: [
          {intent: 'help', support: 'available'}, {intent: 'clear', support: 'deferred'},
          {intent: 'compact', support: 'not_available'}, {intent: 'context', support: 'available'},
          {intent: 'doctor', support: 'available'}, {intent: 'model', support: 'not_available'},
          {intent: 'permissions', support: 'deferred'}, {intent: 'resume', support: 'deferred'},
        ],
      }},
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.intent).toBe('help');
    expect(() => decodeEvent(JSON.stringify({
      ...base, payload: {...base.payload, result: {commands: base.payload.result.commands, providerText: 'secret'}},
    }), 1)).toThrowError(/session\.command\.result/);
    expect(() => decodeEvent(JSON.stringify({
      ...base, payload: {...base.payload, result: {commands: [...base.payload.result.commands, ...base.payload.result.commands]}},
    }), 1)).toThrowError(/session\.command\.result/);
    expect(() => decodeEvent(JSON.stringify({
      ...base, payload: {...base.payload, status: 'succeeded', code: 'active_run', result: {}},
    }), 1)).toThrowError(/session\.command\.result/);
    expect(() => decodeEvent(JSON.stringify({
      ...base, payload: {...base.payload, commandId: 'bad\ncommand'},
    }), 1)).toThrowError(/session\.command\.result/);
  });

  it('严格校验 permissions 安全投影且不接受 selector 泄漏', () => {
    const result = {
      effectiveMode: 'PLAN', modeSourceKind: 'PROJECT_SHARED', modeSafeSourceId: 'project-shared',
      modeValidationStatus: 'VALID', startupRuleCount: 1,
      rules: [{ruleId: 'project-read', sourceKind: 'PROJECT_SHARED', safeSourceId: 'project-shared',
        operation: 'REPLACE', validationStatus: 'VALID'}],
    };
    expect(decodeEvent(JSON.stringify({
      version: 0, type: 'session.command.result', requestId: 'req-permissions', sessionId: 'session-1', sequence: 1,
      payload: {commandId: 'permissions-1', intent: 'permissions', status: 'succeeded', code: 'ok', result},
    }), 1).payload.result).toEqual(result);
    expect(() => decodeEvent(JSON.stringify({
      version: 0, type: 'session.command.result', requestId: 'req-permissions', sessionId: 'session-1', sequence: 1,
      payload: {commandId: 'permissions-1', intent: 'permissions', status: 'succeeded', code: 'ok',
        result: {...result, rules: [{...result.rules[0], selector: 'secret'}]}},
    }), 1)).toThrowError(/permissions/);
  });

  it('接受 overflow context 的负 freeTokens，但仍拒绝不安全数值', () => {
    const result = {
      systemTokens: 10, transcriptTokens: 20, toolTokens: 0, memoryTokens: 0,
      totalTokens: 30, availableInputTokens: 25, freeTokens: -5, overflowTokens: 5,
      sourceRevision: 1, estimateKind: 'HEURISTIC', contextStatus: 'OVERFLOW',
      modelRequestAttempts: 0, reductionStrategies: [], reasonCodes: ['OVERFLOW'],
    };
    expect(decodeEvent(JSON.stringify({
      version: 0, type: 'session.command.result', requestId: 'req-context', sessionId: 'session-1', sequence: 1,
      payload: {commandId: 'context-1', intent: 'context', status: 'succeeded', code: 'ok', result},
    }), 1).payload.result).toEqual(result);
    expect(() => decodeEvent(JSON.stringify({
      version: 0, type: 'session.command.result', requestId: 'req-context', sessionId: 'session-1', sequence: 1,
      payload: {commandId: 'context-1', intent: 'context', status: 'succeeded', code: 'ok', result: {...result, freeTokens: Number.MAX_SAFE_INTEGER + 1}},
    }), 1)).toThrowError(/context/);
  });

  it('接受 resume 的最小会话切换投影并拒绝路径或额外字段', () => {
    const base = {
      version: 0, type: 'session.command.result', requestId: 'req-resume', sessionId: 'session-target', sequence: 1,
      payload: {commandId: 'resume-1', intent: 'resume', status: 'succeeded', code: 'ok', result: {
        previousSessionId: 'session-source', resumedSessionId: 'session-target',
      }},
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.result).toEqual(base.payload.result);
    expect(() => decodeEvent(JSON.stringify({
      ...base, payload: {...base.payload, result: {...base.payload.result, storagePath: 'C:\\secret'}},
    }), 1)).toThrowError(/resume/);
    expect(() => decodeEvent(JSON.stringify({
      ...base, payload: {...base.payload, result: {...base.payload.result, resumedSessionId: 'session-source'}},
    }), 1)).toThrowError(/resume/);
  });

  it('严格校验有界 file suggestions 安全投影', () => {
    const base = {
      version: 0, type: 'file.suggestions', requestId: 'req-file', sessionId: 'session-1', sequence: 1,
      payload: {query: 'src', candidates: ['src/App.java', 'dir/file name.md']},
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.candidates).toHaveLength(2);
    for (const invalid of [
      {...base, runId: 'run-1'},
      {...base, payload: {...base.payload, secret: 'leak'}},
      {...base, payload: {query: 'src', candidates: ['../escape']}},
      {...base, payload: {query: 'src', candidates: Array.from({length: 33}, (_, i) => `${i}`)}},
      {...base, payload: {query: 'src', candidates: ['same', 'same']}},
    ]) expect(() => decodeEvent(JSON.stringify(invalid), 1)).toThrow(/file\.suggestions/);
  });

  it('只接受 Java 权威且有界的 task.worktree 投影', () => {
    const base = {
      version: 0,
      type: 'task.worktree',
      requestId: 'req-worktree',
      sessionId: 'session-1',
      sequence: 1,
      payload: {taskId: 'task-a', disposition: 'removed'},
    };
    expect(decodeEvent(JSON.stringify(base), 1).payload.disposition).toBe('removed');
    for (const invalid of [
      {...base, runId: 'run-1'},
      {...base, payload: {...base.payload, taskId: 'invalid'}},
      {...base, payload: {...base.payload, disposition: 'x'.repeat(65)}},
      {...base, payload: {...base.payload, secret: 'leak'}},
    ]) {
      expect(() => decodeEvent(JSON.stringify(invalid), 1)).toThrowError(/task\.worktree/);
    }
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

  it('只接受白名单模型失败摘要', () => {
    const event = decodeEvent(JSON.stringify({
      version: 0,
      type: 'run.failed',
      requestId: 'req-1',
      sessionId: 'session-1',
      runId: 'run-1',
      sequence: 1,
      payload: {
        stopReason: 'model_retry_exhausted',
        modelTurns: 1,
        toolCalls: 0,
        modelFailure: {
          category: 'provider_unavailable',
          statusClass: '5xx',
          attempts: 3,
          receivedOutput: false,
        },
      },
    }), 1);

    expect(event.payload.modelFailure).toEqual(expect.objectContaining({attempts: 3}));
    expect(() => decodeEvent(JSON.stringify({
      ...event,
      payload: {
        ...event.payload,
        modelFailure: {
          category: 'provider_unavailable',
          statusClass: '5xx',
          attempts: 3,
          receivedOutput: false,
          message: 'SECRET_PROVIDER_TEXT',
        },
      },
    }), 1)).toThrowError(/模型失败摘要/);
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
