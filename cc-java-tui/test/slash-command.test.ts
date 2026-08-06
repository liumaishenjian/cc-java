import {describe, expect, it} from 'vitest';
import {parseSlashCommand, renderSlashResult} from '../src/slash-command.js';

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
  });

  it('keeps ordinary prompts out of the command path and rejects invalid inputs', () => {
    expect(parseSlashCommand('explain this repository')).toEqual({kind: 'not-command'});
    expect(parseSlashCommand('/unknown')).toEqual({kind: 'invalid', message: '未知 Slash 命令'});
    expect(parseSlashCommand('/doctor extra').kind).toBe('invalid');
    expect(parseSlashCommand(`/model ${'x'.repeat(257)}`).kind).toBe('invalid');
    expect(parseSlashCommand('/permissions change').kind).toBe('invalid');
    expect(parseSlashCommand('/permissions mode plan').kind).toBe('invalid');
    expect(parseSlashCommand('/permissions mode PLAN extra').kind).toBe('invalid');
  });

  it('renders fixed local status without server-provided text', () => {
    expect(renderSlashResult('compact', 'rejected', 'not_available'))
      .toBe('/compact 未执行：当前版本尚未提供');
    expect(renderSlashResult('doctor', 'succeeded', 'ok')).toBe('/doctor 已完成');
  });
});
