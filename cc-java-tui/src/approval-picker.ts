/**
 * 普通 Ask 权限审批的键盘选择状态。
 *
 * <p>该状态只描述 TUI 当前焦点，不执行权限决定；最终决定仍通过
 * Java 提供的 resolveApproval 统一入口提交。</p>
 */
export type ApprovalDecision = 'allow_once' | 'allow_session' | 'deny';

export interface ApprovalPickerItem {
  readonly decision: ApprovalDecision;
  readonly label: 'Allow once' | 'Allow for session' | 'Deny';
}

export interface ApprovalPickerState {
  readonly approvalId: string | undefined;
  readonly selectedIndex: number;
}

export const APPROVAL_PICKER_ITEMS: readonly ApprovalPickerItem[] = [
  {decision: 'allow_once', label: 'Allow once'},
  {decision: 'allow_session', label: 'Allow for session'},
  {decision: 'deny', label: 'Deny'},
];

export function initialApprovalPickerState(approvalId?: string): ApprovalPickerState {
  return {approvalId, selectedIndex: 0};
}

/** approvalId 变化时回到安全且明确的 Allow once 默认项。 */
export function resetApprovalPicker(
  state: ApprovalPickerState,
  approvalId: string,
): ApprovalPickerState {
  return state.approvalId === approvalId ? state : initialApprovalPickerState(approvalId);
}

/** 在三项决定中循环移动，首尾相连。 */
export function moveApprovalPicker(
  state: ApprovalPickerState,
  direction: -1 | 1,
): ApprovalPickerState {
  const length = APPROVAL_PICKER_ITEMS.length;
  return {...state, selectedIndex: (state.selectedIndex + direction + length) % length};
}

export function selectedApprovalDecision(state: ApprovalPickerState): ApprovalDecision {
  return APPROVAL_PICKER_ITEMS[state.selectedIndex]!.decision;
}
