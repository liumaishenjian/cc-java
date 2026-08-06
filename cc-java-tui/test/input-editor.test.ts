import {describe, expect, it} from 'vitest';
import {
  MAX_COMPLETION_CANDIDATES,
  MAX_HISTORY_ENTRIES,
  MAX_INPUT_CODE_POINTS,
  appendInput,
  completionCandidates,
  initialInputHistoryState,
  navigateInputHistory,
  recordInputHistory,
} from '../src/input-editor.js';

describe('input editor transient state', () => {
  it('uses Unicode code points for the 8192 input cap', () => {
    const input = appendInput('😀'.repeat(MAX_INPUT_CODE_POINTS - 1), '😀😀');

    expect(Array.from(input)).toHaveLength(MAX_INPUT_CODE_POINTS);
    expect(input.endsWith('😀')).toBe(true);
  });

  it('retains only the last 100 submitted entries and navigates deterministically', () => {
    let history = initialInputHistoryState;
    for (let index = 0; index < MAX_HISTORY_ENTRIES + 2; index++) {
      history = recordInputHistory(history, `input-${index}`);
    }

    expect(history.entries).toHaveLength(MAX_HISTORY_ENTRIES);
    expect(history.entries[0]).toBe('input-2');
    let previous = navigateInputHistory(history, 'draft', 'previous');
    expect(previous.input).toBe('input-101');
    previous = navigateInputHistory(previous.state, 'input-101', 'previous');
    expect(previous.input).toBe('input-100');
    const next = navigateInputHistory(previous.state, 'input-100', 'next');
    expect(next.input).toBe('input-101');
    expect(navigateInputHistory(next.state, 'input-101', 'next').input).toBe('draft');
  });

  it('returns stable closed completion candidates within the fixed cap', () => {
    const candidates = completionCandidates('/');

    expect(candidates).toEqual([...candidates].sort((left, right) => left.localeCompare(right, 'en')));
    expect(candidates).toHaveLength(8);
    expect(candidates.length).toBeLessThanOrEqual(MAX_COMPLETION_CANDIDATES);
    expect(completionCandidates('/permissions m')).toEqual([
      '/permissions mode ACCEPT_EDITS',
      '/permissions mode DEFAULT',
      '/permissions mode PLAN',
    ]);
    expect(completionCandidates('normal input')).toEqual([]);
  });
});
