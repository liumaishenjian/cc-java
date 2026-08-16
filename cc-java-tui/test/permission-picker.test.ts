import {describe, expect, it} from 'vitest';
import {
  initialPermissionPickerState,
  movePermissionPicker,
  PERMISSION_PICKER_ITEMS,
  selectedPermissionSelection,
} from '../src/permission-picker.js';

describe('permission picker', () => {
  it('固定展示三项精确标签及 wire selection', () => {
    expect(PERMISSION_PICKER_ITEMS).toEqual([
      {label: 'Plan', selection: 'PLAN'},
      {label: 'Ask for approval', selection: 'ASK'},
      {label: 'Approve for me', selection: 'AUTO'},
    ]);
  });

  it('初始选择 ASK，上下移动循环且选择始终映射到受限 wire value', () => {
    expect(selectedPermissionSelection(initialPermissionPickerState)).toBe('ASK');
    const previous = movePermissionPicker(initialPermissionPickerState, -1);
    expect(selectedPermissionSelection(previous)).toBe('PLAN');
    const next = movePermissionPicker(previous, 1);
    expect(selectedPermissionSelection(next)).toBe('ASK');
    expect(selectedPermissionSelection(movePermissionPicker(next, 1))).toBe('AUTO');
  });
});
