export const MAX_INPUT_CODE_POINTS = 8_192;
export const MAX_HISTORY_ENTRIES = 100;
export const MAX_COMPLETION_CANDIDATES = 32;

const COMMAND_COMPLETIONS = [
  '/clear',
  '/compact',
  '/context',
  '/doctor',
  '/help',
  '/model',
  '/permissions',
  '/resume',
] as const;

const ARGUMENT_COMPLETIONS: Readonly<Record<string, readonly string[]>> = {
  '/permissions': [
    '/permissions mode ACCEPT_EDITS',
    '/permissions mode DEFAULT',
    '/permissions mode PLAN',
    '/permissions query',
  ],
};

export interface InputHistoryState {
  readonly entries: readonly string[];
  readonly index: number | undefined;
  readonly draft: string | undefined;
}

export const initialInputHistoryState: InputHistoryState = {
  entries: [],
  index: undefined,
  draft: undefined,
};

/**
 * 受控 TUI 编辑器的纯瞬态操作。
 *
 * <p>历史和补全永不进入 stdio、Session JSONL 或 Canonical Transcript。字符串上限一律按
 * Unicode code point 计算，避免截断代理对。</p>
 */
export function appendInput(current: string, text: string): string {
  const remaining = MAX_INPUT_CODE_POINTS - Array.from(current).length;
  if (remaining <= 0 || text.length === 0) {
    return current;
  }
  return current + Array.from(text).slice(0, remaining).join('');
}

export function removeLastCodePoint(input: string): string {
  return Array.from(input).slice(0, -1).join('');
}

export function recordInputHistory(
  state: InputHistoryState,
  submitted: string,
): InputHistoryState {
  if (submitted.length === 0) {
    return {...state, index: undefined, draft: undefined};
  }
  return {
    entries: [...state.entries, submitted].slice(-MAX_HISTORY_ENTRIES),
    index: undefined,
    draft: undefined,
  };
}

export function navigateInputHistory(
  state: InputHistoryState,
  currentInput: string,
  direction: 'previous' | 'next',
): {readonly state: InputHistoryState; readonly input: string | undefined} {
  if (state.entries.length === 0) {
    return {state, input: undefined};
  }
  if (direction === 'previous') {
    const index = state.index === undefined
      ? state.entries.length - 1
      : Math.max(0, state.index - 1);
    return {
      state: {
        ...state,
        index,
        draft: state.index === undefined ? currentInput : state.draft,
      },
      input: state.entries[index],
    };
  }
  if (state.index === undefined) {
    return {state, input: undefined};
  }
  const index = state.index + 1;
  if (index >= state.entries.length) {
    return {
      state: {...state, index: undefined, draft: undefined},
      input: state.draft ?? '',
    };
  }
  return {state: {...state, index}, input: state.entries[index]};
}

export function completionCandidates(input: string): readonly string[] {
  if (!input.startsWith('/')) {
    return [];
  }
  const candidates = ARGUMENT_COMPLETIONS[input.split(/\s+/u)[0] ?? '']
    ?? COMMAND_COMPLETIONS;
  return candidates
    .filter(candidate => candidate.startsWith(input))
    .slice()
    .sort()
    .slice(0, MAX_COMPLETION_CANDIDATES);
}
