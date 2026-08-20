import {render} from 'ink-testing-library';
import {describe, expect, it} from 'vitest';
import {ToolActivityGroup} from '../src/tool-activity.js';
import type {ToolView} from '../src/state.js';

describe('ToolActivityGroup', () => {
  it('聚合连续同类 Tool 并显示有界元数据', () => {
    const tools: ToolView[] = [
      {...tool(1, 'search_text', 'success', 900), mode: 'content', returnedItems: 7},
      {
        ...tool(2, 'search_text', 'success', 1_200),
        mode: 'content',
        returnedItems: 5,
        truncated: true,
        truncationReason: 'item_limit',
      },
      {...tool(3, 'read_file', 'success', 800), returnedItems: 20},
    ];
    const view = render(<ToolActivityGroup tools={tools} />);
    const frame = view.lastFrame();

    expect(frame).toContain('搜索内容 ×2 · 12 处匹配 · 结果已截断');
    expect(frame).toContain('阅读文件 · 20 行');
    expect(frame).not.toContain('[tool 1]');
  });

  it('活动、失败与拒绝使用清晰且不泄漏结果的摘要', () => {
    const tools: ToolView[] = [
      {...tool(1, 'search_text', 'started'), mode: 'content'},
      {...tool(2, 'read_file', 'failed'), errorCode: 'READ_FAILED'},
      tool(3, 'git_diff', 'denied'),
    ];
    const view = render(<ToolActivityGroup tools={tools} />);
    const frame = view.lastFrame();

    expect(frame).toContain('搜索内容（进行中）');
    expect(frame).toContain('阅读文件 · READ_FAILED');
    expect(frame).toContain('查看变更');
  });

  it('同类 Tool 失败后恢复时显示混合结果而不是伪装成全失败', () => {
    const tools: ToolView[] = [
      {
        ...tool(1, 'search_text', 'failed'),
        mode: 'content',
        errorCode: 'INVALID_ARGUMENTS',
        failureCategory: 'validation',
        retryable: false,
      },
      {
        ...tool(2, 'search_text', 'success', 2_000),
        mode: 'content',
        returnedItems: 4,
      },
    ];
    const view = render(<ToolActivityGroup tools={tools} />);
    const frame = view.lastFrame();

    expect(frame).toContain('! 搜索内容 ×2 · 4 处匹配');
    expect(frame).toContain('1 次失败');
    expect(frame).toContain('INVALID_ARGUMENTS');
  });
});

function tool(
  ordinal: number,
  name: string,
  status: ToolView['status'],
  returnedCharacters?: number,
): ToolView {
  return {
    ordinal,
    name,
    mode: undefined,
    status,
    returnedCharacters,
    returnedItems: undefined,
    filteredItems: undefined,
    truncated: false,
    truncationReason: undefined,
    errorCode: undefined,
          failureCategory: undefined,
          retryable: undefined,
    output: '',
  };
}
