export type PlanReviewDecision = 'approve_auto' | 'approve_user' | 'continue_planning' | 'reject';
export type PlanContextPolicy = 'keep' | 'clear';

export interface PlanReviewPickerItem {
  readonly decision: PlanReviewDecision;
  readonly label: string;
}

export interface PlanReviewPickerState {
  readonly planId: string;
  readonly revision: number;
  readonly contentDigest: string;
  readonly workspaceDigest: string;
  readonly contextPolicy: PlanContextPolicy;
  readonly durable: boolean;
  readonly selectedIndex: number;
  readonly submitted: boolean;
}

export const PLAN_REVIEW_PICKER_ITEMS: readonly PlanReviewPickerItem[] = [
  {decision: 'approve_auto', label: '批准并自动执行（后续 ASK 由受限复核处理）'},
  {decision: 'approve_user', label: '批准并执行（后续 Tool 正常逐项询问）'},
  {decision: 'continue_planning', label: '带反馈继续规划'},
  {decision: 'reject', label: '拒绝并退出'},
];

/** 为精确 durable review revision 创建单一封闭 picker，默认选中自动执行。 */
export function createPlanReviewPicker(
  planId: string,
  revisionOrDigest: number | string,
  contentDigestOrRevision?: string | number,
  workspaceDigest = '',
  suggestedContextPolicy: PlanContextPolicy = 'keep',
): PlanReviewPickerState {
  // 临时兼容旧 plan.proposed 测试；durable review 总是走 number + digest + workspaceDigest。
  const revision = typeof revisionOrDigest === 'number'
    ? revisionOrDigest : typeof contentDigestOrRevision === 'number' ? contentDigestOrRevision : 1;
  const contentDigest = typeof revisionOrDigest === 'string'
    ? revisionOrDigest : String(contentDigestOrRevision ?? '');
  const durable = typeof revisionOrDigest === 'number';
  return {
    planId, revision, contentDigest, workspaceDigest: durable ? workspaceDigest : contentDigest,
    contextPolicy: suggestedContextPolicy, durable,
    selectedIndex: 0, submitted: false,
  };
}

export function movePlanReviewPicker(
  state: PlanReviewPickerState,
  delta: -1 | 1,
): PlanReviewPickerState {
  const count = state.durable ? PLAN_REVIEW_PICKER_ITEMS.length : 3;
  return {...state, selectedIndex: (state.selectedIndex + delta + count) % count};
}

export function selectedPlanReviewDecision(state: PlanReviewPickerState): PlanReviewDecision {
  if (!state.durable) return (['approve_auto', 'continue_planning', 'reject'] as const)[state.selectedIndex] ?? 'approve_auto';
  return PLAN_REVIEW_PICKER_ITEMS[state.selectedIndex]?.decision ?? 'approve_auto';
}

/** Tab 可显式覆盖 keep/clear，不改变当前决定。 */
export function togglePlanContextPolicy(state: PlanReviewPickerState): PlanReviewPickerState {
  return {...state, contextPolicy: state.contextPolicy === 'keep' ? 'clear' : 'keep'};
}
