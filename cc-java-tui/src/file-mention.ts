import {MAX_COMPLETION_CANDIDATES, type ComposerState} from './input-editor.js';

export const FILE_SUGGESTION_QUERY_MAX_CHARS = 256;
export const FILE_SUGGESTION_CANDIDATE_MAX_CHARS = 1_024;

const segmenter = new Intl.Segmenter('en', {granularity: 'grapheme'});

/** 当前光标所在、可交给 Java 查询的显式文件 mention。 */
export interface ActiveFileMention {
  readonly startGrapheme: number;
  readonly endGrapheme: number;
  readonly query: string;
}

/**
 * 识别 prompt 中当前光标拥有的文件 mention，不把邮箱、转义字面量或已闭合引号当作查询。
 */
export function activeFileMention(state: ComposerState): ActiveFileMention | undefined {
  const units = graphemes(state.text);
  const cursor = Math.min(Math.max(0, state.cursorGrapheme), units.length);
  let start = -1;
  for (let index = cursor - 1; index >= 0; index--) {
    if (units[index] === '@' && (index === 0 || /^\s$/u.test(units[index - 1]!))) {
      start = index;
      break;
    }
    if (units[index] === '\n') break;
  }
  if (start < 0) return undefined;

  const quoted = units[start + 1] === '"';
  if (!quoted) {
    let end = start;
    while (end < units.length && !/^\s$/u.test(units[end]!)) end++;
    if (cursor < start + 1 || cursor > end) return undefined;
    if (units.slice(start + 1, cursor).some(unit => /^\s$/u.test(unit))) return undefined;
    const query = units.slice(start + 1, cursor).join('');
    return validQuery(query) ? {startGrapheme: start, endGrapheme: end, query} : undefined;
  }

  const closingQuote = units.indexOf('"', start + 2);
  if (closingQuote >= 0 && closingQuote < cursor) return undefined;
  if (cursor < start + 2) return undefined;
  const query = units.slice(start + 2, cursor).join('');
  return validQuery(query)
    ? {startGrapheme: start, endGrapheme: closingQuote >= 0 ? closingQuote + 1 : cursor, query}
    : undefined;
}

/** 文件建议只在普通 prompt 模式启用；Slash-only 选择保持封闭命令目录所有权。 */
export function fileMentionEnabled(state: ComposerState): boolean {
  const trimmed = state.text.trimStart();
  return !trimmed.startsWith('/');
}

/** 对来自 Java 的候选执行额外防御；协议层仍负责完整 fail-closed 校验。 */
export function boundedFileSuggestions(candidates: readonly string[]): readonly string[] {
  const mentions: string[] = [];
  const seen = new Set<string>();
  for (const candidate of candidates) {
    if (!isSafeProtocolPath(candidate)) continue;
    const mention = /\s|#L/u.test(candidate) ? `@"${candidate}"` : `@${candidate}`;
    if (!seen.has(mention)) {
      seen.add(mention);
      mentions.push(mention);
    }
    if (mentions.length === MAX_COMPLETION_CANDIDATES) break;
  }
  return mentions;
}

function validQuery(query: string): boolean {
  return Array.from(query).length <= FILE_SUGGESTION_QUERY_MAX_CHARS
    && !/[\u0000-\u001f\u007f]/u.test(query);
}

function isSafeProtocolPath(path: string): boolean {
  const segments = path.split('/');
  return path.length > 0
    && Array.from(path).length <= FILE_SUGGESTION_CANDIDATE_MAX_CHARS
    && !path.startsWith('/')
    && !path.startsWith('\\')
    && !/^[A-Za-z]:/u.test(path)
    && !path.includes('\\')
    && !path.includes('"')
    && !/[\u0000-\u001f\u007f]/u.test(path)
    && segments.every(segment => segment.length > 0 && segment !== '.' && segment !== '..');
}

function graphemes(text: string): readonly string[] {
  return [...segmenter.segment(text)].map(item => item.segment);
}
