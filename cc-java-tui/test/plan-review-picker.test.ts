import {describe, expect, it} from 'vitest';
import {
  createPlanReviewPicker,
  movePlanReviewPicker,
  PLAN_REVIEW_PICKER_ITEMS,
  selectedPlanReviewDecision,
  togglePlanContextPolicy,
} from '../src/plan-review-picker.js';

describe('durable plan review picker', () => {
  it('defaults to automatic execution and exposes exactly four user choices', () => {
    const state = createPlanReviewPicker('plan-a', 3, 'a'.repeat(64), 'b'.repeat(64), 'keep');
    expect(PLAN_REVIEW_PICKER_ITEMS.map(item => item.decision)).toEqual([
      'approve_auto', 'approve_user', 'continue_planning', 'reject',
    ]);
    expect(selectedPlanReviewDecision(state)).toBe('approve_auto');
    expect(state.contentDigest).toBe('a'.repeat(64));
    expect(state.workspaceDigest).toBe('b'.repeat(64));
    expect(state.workspaceDigest).not.toBe(state.contentDigest);
  });

  it('selects normal per-tool approval and explicitly toggles keep/clear', () => {
    const state = createPlanReviewPicker('plan-a', 3, 'a'.repeat(64), 'b'.repeat(64), 'clear');
    const normal = movePlanReviewPicker(state, 1);
    expect(selectedPlanReviewDecision(normal)).toBe('approve_user');
    expect(normal.contextPolicy).toBe('clear');
    expect(togglePlanContextPolicy(normal).contextPolicy).toBe('keep');
  });
});
