import {describe, expect, it} from 'vitest';
import {
  parseSlashCommand,
  renderSlashResult,
  slashCommandUsage,
} from '../src/slash-command.js';

describe('parseSlashCommand', () => {
  it('parses only declared commands into bounded transport arguments', () => {
    expect(parseSlashCommand('/help')).toEqual({
      kind: 'command', command: {intent: 'help', arguments: {}},
    });
    expect(parseSlashCommand('/compact focus release')).toEqual({
      kind: 'command', command: {intent: 'compact', arguments: {anchors: ['focus', 'release']}},
    });
    expect(parseSlashCommand('/permissions')).toEqual({
      kind: 'command', command: {intent: 'permissions', arguments: {}},
    });
    expect(parseSlashCommand('/permissions query')).toEqual({
      kind: 'command', command: {intent: 'permissions', arguments: {}},
    });
    expect(parseSlashCommand('/permissions mode ACCEPT_EDITS')).toEqual({
      kind: 'command', command: {intent: 'permissions', arguments: {mode: 'ACCEPT_EDITS'}},
    });
    expect(parseSlashCommand('/task wait task-a 1500')).toEqual({
      kind: 'task', command: {action: 'wait', taskId: 'task-a', timeoutMillis: 1500},
    });
    expect(parseSlashCommand('/task cancel task-a')).toEqual({
      kind: 'task', command: {action: 'cancel', taskId: 'task-a'},
    });
    expect(parseSlashCommand('/task keep task-a')).toEqual({
      kind: 'task', command: {action: 'keep', taskId: 'task-a'},
    });
    expect(parseSlashCommand('/task remove task-a')).toEqual({
      kind: 'task', command: {action: 'remove', taskId: 'task-a'},
    });
  });

  it('keeps ordinary prompts out of the command path and rejects invalid inputs', () => {
    expect(parseSlashCommand('explain this repository')).toEqual({kind: 'not-command'});
    expect(parseSlashCommand('/unknown')).toEqual({kind: 'skill', name: 'unknown', arguments: ''});
    expect(parseSlashCommand('/doctor extra').kind).toBe('invalid');
    expect(parseSlashCommand(`/compact ${Array.from({length: 17}, () => 'anchor').join(' ')}`).kind).toBe('invalid');
    expect(parseSlashCommand(`/compact ${'x'.repeat(513)}`).kind).toBe('invalid');
    expect(parseSlashCommand('/compact bad\0anchor').kind).toBe('invalid');
    expect(parseSlashCommand(`/model ${'x'.repeat(257)}`).kind).toBe('invalid');
    expect(parseSlashCommand('/permissions change').kind).toBe('invalid');
    expect(parseSlashCommand('/permissions mode plan').kind).toBe('invalid');
    expect(parseSlashCommand('/permissions mode PLAN extra').kind).toBe('invalid');
    expect(parseSlashCommand('/task wait task-a 0').kind).toBe('invalid');
    expect(parseSlashCommand('/task remove invalid').kind).toBe('invalid');
  });

  it('renders fixed local status without server-provided text', () => {
    expect(renderSlashResult('compact', 'rejected', 'not_available'))
      .toBe('/compact 未执行：当前版本尚未提供');
    expect(renderSlashResult('compact', 'succeeded', 'ok')).toBe('/compact 已完成');
  });

  it('renders discoverable usage and structured safe command projections', () => {
    expect(slashCommandUsage('/context')).toContain('查看上下文用量');
    expect(slashCommandUsage('/permissions mode PLAN')).toBe('/permissions mode PLAN');
    expect(renderSlashResult('help', 'succeeded', 'ok', {commands: [
      {intent: 'help', support: 'available'},
      {intent: 'clear', support: 'available'},
    ]})).toContain('/help — 查看命令与可用状态　[可用]');
    expect(renderSlashResult('context', 'succeeded', 'ok', {
      systemTokens: 10, transcriptTokens: 20, toolTokens: 30, memoryTokens: 40,
      totalTokens: 100, availableInputTokens: 256000, freeTokens: 255900,
      overflowTokens: 0, sourceRevision: 1, estimateKind: 'ESTIMATED',
      contextStatus: 'WITHIN_BUDGET', modelRequestAttempts: 1,
      reductionStrategies: ['C1'], reasonCodes: [],
    })).toContain('总计 100 / 可输入 256000 / 剩余 255900');
    expect(renderSlashResult('permissions', 'succeeded', 'ok', {
      effectiveMode: 'PLAN', modeSourceKind: 'SESSION', modeSafeSourceId: 'session',
      modeValidationStatus: 'VALID', startupRuleCount: 1,
      rules: [{ruleId: 'read-docs', sourceKind: 'PROJECT_SHARED', safeSourceId: 'project', operation: 'REPLACE', validationStatus: 'VALID'}],
    })).toContain('- read-docs · PROJECT_SHARED/project · REPLACE');
    expect(renderSlashResult('doctor', 'succeeded', 'ok', {
      settingsAvailable: true, settingsRevision: 3, instructionCount: 2,
      contextAvailable: true, activeRun: false,
      entries: [{component: 'SETTINGS', sourceKind: 'PROJECT_SHARED', safeId: 'project', code: 'PUBLISHED', severity: 'INFO'}],
    })).toContain('- SETTINGS · PROJECT_SHARED/project · PUBLISHED · INFO');
  });
});
