import {describe, expect, it} from 'vitest';
import {
  activeFileMention,
  boundedFileSuggestions,
  fileMentionEnabled,
} from '../src/file-mention.js';
import {createComposerState, reduceComposer} from '../src/input-editor.js';

const layout = {width: 80, height: 4};

function state(text: string, left = 0) {
  let current = reduceComposer(createComposerState(), {type: 'InsertText', text}, layout).state;
  for (let index = 0; index < left; index++) {
    current = reduceComposer(current, {type: 'MoveLeft'}, layout).state;
  }
  return current;
}

describe('explicit file mention', () => {
  it('识别开头/空白后的未引号与开放引号形式', () => {
    expect(activeFileMention(state('@src/A'))?.query).toBe('src/A');
    expect(activeFileMention(state('看 @"dir/file na'))?.query).toBe('dir/file na');
  });

  it('不触发邮箱、转义字面量、闭合引号或 Slash-only 选择', () => {
    expect(activeFileMention(state('mail@example.com'))).toBeUndefined();
    expect(activeFileMention(state('\\@literal'))).toBeUndefined();
    expect(activeFileMention(state('@"done.md"'))).toBeUndefined();
    expect(fileMentionEnabled(state('/compact @src/A'))).toBe(false);
  });

  it('只替换当前 grapheme 光标拥有的 token，保留前后多行与 CJK/Emoji', () => {
    const initial = state('前😀\n查 @src/A 后文', 3);
    const mention = activeFileMention(initial)!;
    const replaced = reduceComposer(initial, {
      type: 'ReplaceRange', startGrapheme: mention.startGrapheme,
      endGrapheme: mention.endGrapheme, text: '@"dir/file name.md"',
    }, layout).state;
    expect(replaced.text).toBe('前😀\n查 @"dir/file name.md" 后文');
    expect(replaced.cursorGrapheme).toBe(2 + 3 + Array.from('@"dir/file name.md"').length);
  });

  it('光标位于中间 token 时只替换该 token', () => {
    const initial = state('先 @one 再 @two 尾', 2);
    expect(activeFileMention(initial)?.query).toBe('two');
  });

  it('把 Java 原始协议路径转换为可提交 mention，并拒绝不安全路径', () => {
    expect(boundedFileSuggestions([
      'src/App.java', 'dir/file name.md', 'docs/name#Lguide.md', '../escape', 'bad"quote.md',
    ])).toEqual([
      '@src/App.java', '@"dir/file name.md"', '@"docs/name#Lguide.md"',
    ]);
  });

  it('查询限制与 Java 的 256 code point 契约一致', () => {
    expect(activeFileMention(state(`@${'a'.repeat(256)}`))).toBeDefined();
    expect(activeFileMention(state(`@${'a'.repeat(257)}`))).toBeUndefined();
  });
});
