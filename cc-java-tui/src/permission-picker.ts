/**
 * 本地 permissions picker 的纯状态契约。
 *
 * <p>该模块只维护终端中的选中项，不决定 Runtime Permission、不会发送命令，
 * 也不将 AUTO 解释为绕过 Java 的审批或拒绝规则。</p>
 */
export type PermissionSelection = 'PLAN' | 'ASK' | 'AUTO';

export interface PermissionPickerItem {
  readonly label: 'Plan' | 'Ask for approval' | 'Approve for me';
  readonly selection: PermissionSelection;
}

export interface PermissionPickerState {
  readonly selectedIndex: number;
}

export const PERMISSION_PICKER_ITEMS: readonly PermissionPickerItem[] = [
  {label: 'Plan', selection: 'PLAN'},
  {label: 'Ask for approval', selection: 'ASK'},
  {label: 'Approve for me', selection: 'AUTO'},
];

export const initialPermissionPickerState: PermissionPickerState = {selectedIndex: 1};

/** 在固定三项中循环移动，确保键盘输入的选择结果可预测。 */
export function movePermissionPicker(
  state: PermissionPickerState,
  direction: -1 | 1,
): PermissionPickerState {
  const length = PERMISSION_PICKER_ITEMS.length;
  return {selectedIndex: (state.selectedIndex + direction + length) % length};
}

/** 返回当前选择对应的受限 wire value。 */
export function selectedPermissionSelection(state: PermissionPickerState): PermissionSelection {
  return PERMISSION_PICKER_ITEMS[state.selectedIndex]!.selection;
}
