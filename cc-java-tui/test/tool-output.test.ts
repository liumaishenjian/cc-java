import {describe, expect, it} from 'vitest';
import {
  appendToolOutput,
  EMPTY_TOOL_OUTPUT,
  finalizeToolOutput,
  toolOutputStats,
} from '../src/tool-output.js';

describe('Tool output projection', () => {
  it('跨 chunk 组装并只压缩相邻同流完整重复行', () => {
    let output = appendToolOutput(EMPTY_TOOL_OUTPUT, 'stderr', 'warn');
    output = appendToolOutput(output, 'stderr', 'ing\nwarning\nother\nwarning\n');
    output = finalizeToolOutput(output);

    expect(output.lines).toEqual([
      {stream: 'stderr', text: 'warning', complete: true, repetitions: 2},
      {stream: 'stderr', text: 'other', complete: true, repetitions: 1},
      {stream: 'stderr', text: 'warning', complete: true, repetitions: 1},
    ]);
    expect(toolOutputStats(output)).toEqual({stdoutLines: 0, stderrLines: 4, repeatedLines: 1});
  });

  it('不跨 stdout/stderr 合并相同文本并保持通道顺序', () => {
    let output = appendToolOutput(EMPTY_TOOL_OUTPUT, 'stdout', 'same\n');
    output = appendToolOutput(output, 'stderr', 'same\n');
    output = appendToolOutput(output, 'stdout', 'same\n');

    expect(output.lines.map(line => [line.stream, line.text, line.repetitions])).toEqual([
      ['stdout', 'same', 1],
      ['stderr', 'same', 1],
      ['stdout', 'same', 1],
    ]);
  });

  it('对 CRLF 做通用行终止归一而不识别诊断文案', () => {
    const output = appendToolOutput(EMPTY_TOOL_OUTPUT, 'stderr', 'diagnostic\r\ndiagnostic\r\n');
    expect(output.lines).toEqual([
      {stream: 'stderr', text: 'diagnostic', complete: true, repetitions: 2},
    ]);
  });

  it('按 code point 预算截断且不破坏 Unicode', () => {
    const output = appendToolOutput(EMPTY_TOOL_OUTPUT, 'stdout', '界'.repeat(64 * 1_024 + 1));
    expect(output.characters).toBe(64 * 1_024);
    expect(output.truncated).toBe(true);
    expect(Array.from(output.lines[0]?.text ?? '')).toHaveLength(64 * 1_024);
  });
});
