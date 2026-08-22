export type ToolOutputStream = 'stdout' | 'stderr';

export interface ToolOutputLine {
  readonly stream: ToolOutputStream;
  readonly text: string;
  readonly complete: boolean;
  readonly repetitions: number;
}

export interface ToolOutputBuffer {
  readonly lines: readonly ToolOutputLine[];
  readonly characters: number;
  readonly truncated: boolean;
}

export const EMPTY_TOOL_OUTPUT: ToolOutputBuffer = {
  lines: [],
  characters: 0,
  truncated: false,
};

const MAX_TOOL_OUTPUT_CHARACTERS = 64 * 1_024;

/**
 * 增量追加 Tool 输出，只压缩相邻、同通道且内容完全相同的完整行。
 *
 * 不识别任何命令或诊断文案；stdout/stderr 切换会封口当前残片，避免跨通道重排。
 */
export function appendToolOutput(
  current: ToolOutputBuffer,
  stream: ToolOutputStream,
  chunk: string,
): ToolOutputBuffer {
  if (current.truncated || chunk.length === 0) {
    return current;
  }
  const remaining = MAX_TOOL_OUTPUT_CHARACTERS - current.characters;
  if (remaining <= 0) {
    return {...current, truncated: true};
  }
  const codePoints = Array.from(chunk);
  const accepted = codePoints.slice(0, remaining).join('');
  const truncated = codePoints.length > remaining;
  let lines = [...current.lines];
  if (lines.length > 0) {
    const last = lines.at(-1)!;
    if (!last.complete && last.stream !== stream) {
      lines[lines.length - 1] = {...last, complete: true};
    }
  }

  const parts = accepted.split('\n');
  for (let index = 0; index < parts.length; index += 1) {
    const complete = index < parts.length - 1;
    let text = parts[index] ?? '';
    if (complete && text.endsWith('\r')) {
      text = text.slice(0, -1);
    }
    const last = lines[lines.length - 1];
    if (index === 0 && last !== undefined && !last.complete && last.stream === stream) {
      lines[lines.length - 1] = {...last, text: last.text + text, complete};
    } else if (text.length > 0 || complete) {
      lines.push({stream, text, complete, repetitions: 1});
    }
    if (complete) {
      lines = collapseLastCompleteLine(lines);
    }
  }

  return {
    lines,
    characters: current.characters + Array.from(accepted).length,
    truncated,
  };
}

/** Tool 终态时将最后一个残片固定为可渲染行。 */
export function finalizeToolOutput(current: ToolOutputBuffer): ToolOutputBuffer {
  const lines = [...current.lines];
  const last = lines[lines.length - 1];
  if (last !== undefined && !last.complete) {
    lines[lines.length - 1] = {...last, complete: true};
    return {...current, lines: collapseLastCompleteLine(lines)};
  }
  return current;
}

export function toolOutputStats(output: ToolOutputBuffer): {
  readonly stdoutLines: number;
  readonly stderrLines: number;
  readonly repeatedLines: number;
} {
  let stdoutLines = 0;
  let stderrLines = 0;
  let repeatedLines = 0;
  for (const line of output.lines) {
    if (line.stream === 'stderr') stderrLines += line.repetitions;
    else stdoutLines += line.repetitions;
    repeatedLines += line.repetitions - 1;
  }
  return {stdoutLines, stderrLines, repeatedLines};
}

function collapseLastCompleteLine(lines: ToolOutputLine[]): ToolOutputLine[] {
  const current = lines[lines.length - 1];
  const previous = lines[lines.length - 2];
  if (current === undefined || previous === undefined
    || !current.complete || !previous.complete
    || current.stream !== previous.stream || current.text !== previous.text) {
    return lines;
  }
  lines.splice(lines.length - 2, 2, {
    ...previous,
    repetitions: previous.repetitions + current.repetitions,
  });
  return lines;
}
