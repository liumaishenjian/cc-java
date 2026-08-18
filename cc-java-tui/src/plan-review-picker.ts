export type PlanReviewDecision = 'approve' | 'revise' | 'reject';

export interface PlanReviewPickerItem {
  readonly decision: PlanReviewDecision;
  readonly label: string;
}

export interface PlanReviewPickerState {
  readonly planId: string;
  readonly workspaceDigest: string;
  readonly selectedIndex: number;
  readonly submitted: boolean;
}

export const PLAN_REVIEW_PICKER_ITEMS: readonly PlanReviewPickerItem[] = [
  {decision: 'approve', label: '批准并执行'},
  {decision: 'revise', label: '继续修改计划'},
  {decision: 'reject', label: '拒绝并退出'},
];

/** 为服务端提案创建只绑定其 Plan 身份与事件摘要的本地选择状态。 */
export function createPlanReviewPicker(
  planId: string,
  workspaceDigest: string,
): PlanReviewPickerState {
  return {planId, workspaceDigest, selectedIndex: 0, submitted: false};
}

/** 在三个封闭决定间循环移动，方向键不会改变任何 Java 权威状态。 */
export function movePlanReviewPicker(
  state: PlanReviewPickerState,
  delta: -1 | 1,
): PlanReviewPickerState {
  const count = PLAN_REVIEW_PICKER_ITEMS.length;
  return {...state, selectedIndex: (state.selectedIndex + delta + count) % count};
}

/** 返回当前选中的封闭决定。 */
export function selectedPlanReviewDecision(state: PlanReviewPickerState): PlanReviewDecision {
  return PLAN_REVIEW_PICKER_ITEMS[state.selectedIndex]?.decision ?? 'approve';
}
