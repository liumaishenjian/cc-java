import {describe, expect, it} from 'vitest';
import {
  initialApprovalPickerState,
  moveApprovalPicker,
  resetApprovalPicker,
  selectedApprovalDecision,
} from '../src/approval-picker.js';

describe('approval picker', () => {
  it('默认选中 Allow once，并在 approvalId 变化时重置', () => {
    const initial = initialApprovalPickerState('approval-1');
    expect(initial.selectedIndex).toBe(0);
    expect(selectedApprovalDecision(initial)).toBe('allow_once');
    const moved = moveApprovalPicker(moveApprovalPicker(initial, 1), 1);
    expect(moved.selectedIndex).toBe(2);
    expect(resetApprovalPicker(moved, 'approval-2')).toEqual(initialApprovalPickerState('approval-2'));
  });

  it('上下方向首尾循环', () => {
    const initial = initialApprovalPickerState('approval-1');
    expect(moveApprovalPicker(initial, -1).selectedIndex).toBe(2);
    expect(moveApprovalPicker(moveApprovalPicker(initial, 1), 1).selectedIndex).toBe(2);
    expect(selectedApprovalDecision(moveApprovalPicker(initial, -1))).toBe('deny');
  });

  it('同一 approvalId 保留焦点', () => {
    const moved = moveApprovalPicker(initialApprovalPickerState('approval-1'), 1);
    expect(resetApprovalPicker(moved, 'approval-1')).toBe(moved);
  });
});
