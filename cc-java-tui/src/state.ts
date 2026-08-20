import type {ProtocolEvent} from './protocol.js';

export type RunStatus = 'running' | 'completed' | 'cancelled' | 'failed';
export type ClientPhase = 'connecting' | 'ready' | 'running' | 'closing' | 'closed' | 'failed';
export type SearchMode = 'content' | 'files' | 'count';
export type ModelFailureCategory =
  | 'provider_unavailable'
  | 'rate_limited'
  | 'request_timeout'
  | 'request_conflict'
  | 'authentication_failed'
  | 'invalid_request'
  | 'network_error'
  | 'incomplete_stream'
  | 'invalid_response'
  | 'provider_error'
  | 'configuration_required';

export interface ModelFailureView {
  readonly category: ModelFailureCategory;
  readonly statusClass: '4xx' | '5xx' | undefined;
  readonly attempts: number;
  readonly receivedOutput: boolean;
}

export interface ApprovalView {
  readonly approvalId: string;
  readonly ordinal: number;
  readonly toolName: string;
  readonly effect: 'write_workspace' | 'execute_process' | 'network_or_remote';
  readonly target: string | undefined;
  readonly operation: 'modify' | 'create' | undefined;
  readonly removedLines: number | undefined;
  readonly addedLines: number | undefined;
  readonly command: string | undefined;
  readonly shell: 'powershell' | 'sh' | undefined;
  readonly workingDirectory: string | undefined;
  readonly destination: 'configured_web_search_provider' | undefined;
  readonly query: string | undefined;
  readonly submitted: boolean;
}

export interface ToolView {
  readonly ordinal: number;
  readonly name: string;
  readonly mode: SearchMode | undefined;
  readonly status: 'started' | 'success' | 'failed' | 'denied';
  readonly returnedCharacters: number | undefined;
  readonly returnedItems: number | undefined;
  readonly filteredItems: number | undefined;
  readonly truncated: boolean;
  readonly truncationReason: string | undefined;
  readonly errorCode: string | undefined;
  readonly failureCategory: string | undefined;
  readonly retryable: boolean | undefined;
  readonly output: string;
}

export interface PlanProposalView {
  readonly planId: string;
  readonly status: 'awaiting_approval';
  readonly objective: string;
  readonly workspaceDigest: string;
  readonly steps: readonly {
    readonly ordinal: number;
    readonly title: string;
    readonly detail: string;
  }[];
}

export interface PlanReviewView {
  readonly planId: string;
  readonly status: 'awaiting_approval';
  readonly revision: number;
  readonly contentDigest: string;
  readonly markdown: string;
  readonly workspaceDigest: string;
  readonly originalPermissionMode: 'default' | 'accept_edits';
  readonly suggestedContextPolicy: 'keep' | 'clear';
}

export interface UserQuestionView {
  readonly callId: string;
  readonly question: string;
  readonly options: readonly {
    readonly optionId: string;
    readonly label: string;
    readonly description: string;
  }[];
  readonly submitted: boolean;
}

export interface RunView {
  readonly requestId: string;
  readonly prompt: string;
  readonly runId: string | undefined;
  readonly text: string;
  readonly tools: readonly ToolView[];
  readonly pendingApproval?: ApprovalView | undefined;
  readonly planProposal?: PlanProposalView | undefined;
  readonly planReview?: PlanReviewView | undefined;
  readonly pendingQuestion?: UserQuestionView | undefined;
  readonly status: RunStatus;
  readonly stopReason: string | undefined;
  readonly modelFailure?: ModelFailureView | undefined;
  readonly modelTurns: number | undefined;
  readonly toolCalls: number | undefined;
}

export type CheckpointPhase =
  | 'create_prepared'
  | 'create_journal_uncertain'
  | 'created'
  | 'post_prepared'
  | 'post_journal_uncertain'
  | 'completed_present'
  | 'completed_absent'
  | 'undo_prepared'
  | 'undo_applied'
  | 'undo_journal_uncertain'
  | 'undone';

export interface CheckpointView {
  readonly checkpointId: string;
  readonly callId: string;
  readonly toolName: string;
  readonly target: string;
  readonly existedBefore: boolean;
  readonly phase: CheckpointPhase;
  readonly undoable: boolean;
}

export interface CheckpointDiffView {
  readonly checkpointId: string;
  readonly target: string;
  readonly status: 'unchanged' | 'changed' | 'absent' | 'conflict';
  readonly text: string;
  readonly truncated: boolean;
}

export interface CheckpointUndoView {
  readonly checkpointId: string;
  readonly target: string;
  readonly status: 'restored' | 'already_restored' | 'conflict';
  readonly message: string;
}

export interface ChildTaskView {
  readonly taskId: string;
  readonly definitionId: string;
  readonly status: 'queued' | 'starting' | 'running' | 'succeeded' | 'failed' | 'cancelled' | 'interrupted_unknown';
  readonly failure: string;
  readonly modelTurns: number;
  readonly toolCalls: number;
  readonly estimatedTokens: number;
  readonly elapsedMillis: number;
  readonly summary: string;
  readonly verified: boolean;
  readonly worktreeDisposition: string | undefined;
}

export interface TuiState {
  readonly phase: ClientPhase;
  readonly sessionId: string | undefined;
  readonly activeRunId: string | undefined;
  readonly runs: readonly RunView[];
  readonly checkpoints: readonly CheckpointView[];
  readonly childTasks?: readonly ChildTaskView[];
  readonly checkpointPanelOpen: boolean;
  readonly selectedCheckpointId: string | undefined;
  readonly checkpointDiff: CheckpointDiffView | undefined;
  readonly pendingUndoCheckpointId: string | undefined;
  readonly checkpointUndo: CheckpointUndoView | undefined;
  readonly steeringQueueDepth?: number | undefined;
  readonly notice: string | undefined;
}

export type TuiAction =
  | {
    readonly type: 'run.submitted';
    readonly requestId: string;
    readonly prompt: string;
    readonly steering?: boolean;
  }
  | {readonly type: 'approval.submitted'; readonly approvalId: string}
  | {readonly type: 'plan.status.received'; readonly requestId: string; readonly proposal: PlanProposalView}
  | {readonly type: 'checkpoint.selected'; readonly checkpointId: string}
  | {readonly type: 'checkpoint.undo.requested'; readonly checkpointId: string}
  | {readonly type: 'checkpoint.undo.cancelled'}
  | {readonly type: 'event.received'; readonly event: ProtocolEvent}
  | {readonly type: 'transport.failed'; readonly message: string}
  | {readonly type: 'slash.notice'; readonly message: string}
  | {readonly type: 'closing'}
  | {readonly type: 'closed'};

export const initialTuiState: TuiState = {
  phase: 'connecting',
  sessionId: undefined,
  activeRunId: undefined,
  runs: [],
  checkpoints: [],
  childTasks: [],
  checkpointPanelOpen: false,
  selectedCheckpointId: undefined,
  checkpointDiff: undefined,
  pendingUndoCheckpointId: undefined,
  checkpointUndo: undefined,
  steeringQueueDepth: 0,
  notice: undefined,
};

/**
 * 把 Java 事件投影为只读终端状态。
 *
 * Reducer 不启动进程、不发送命令，也不自行推断 Run 完成；只有 Java 的互斥终态事件
 * 能把活动 Run 变回 Ready。
 */
export function reduceTuiState(state: TuiState, action: TuiAction): TuiState {
  switch (action.type) {
    case 'run.submitted':
      if (state.phase !== 'ready') {
        return {...state, notice: '当前状态不能开始新的 Run'};
      }
      return {
        ...state,
        phase: 'running',
        steeringQueueDepth: action.steering === true
          ? Math.max(0, (state.steeringQueueDepth ?? 0) - 1)
          : state.steeringQueueDepth,
        notice: undefined,
        runs: [
          ...state.runs,
          {
            requestId: action.requestId,
            prompt: action.prompt,
            runId: undefined,
            text: '',
            tools: [],
            pendingApproval: undefined,
            planProposal: undefined,
            status: 'running',
            stopReason: undefined,
            modelFailure: undefined,
            modelTurns: undefined,
            toolCalls: undefined,
          },
        ],
      };
    case 'approval.submitted':
      return {
        ...state,
        runs: state.runs.map(run => run.pendingApproval?.approvalId === action.approvalId
          ? {
              ...run,
              pendingApproval: {...run.pendingApproval, submitted: true},
            }
          : run),
      };
    case 'plan.status.received': {
      const existingIndex = state.runs.findLastIndex(run =>
        run.planProposal?.planId === action.proposal.planId);
      if (existingIndex >= 0) {
        return {
          ...state,
          runs: state.runs.map((run, index) => index === existingIndex
            ? {...run, planProposal: action.proposal} : run),
        };
      }
      return {
        ...state,
        runs: [...state.runs, {
          requestId: action.requestId,
          prompt: '/plan',
          runId: undefined,
          text: '',
          tools: [],
          pendingApproval: undefined,
          planProposal: action.proposal,
          status: 'completed',
          stopReason: 'completed',
          modelFailure: undefined,
          modelTurns: undefined,
          toolCalls: undefined,
        }],
      };
    }
    case 'checkpoint.selected':
      return state.checkpoints.some(item => item.checkpointId === action.checkpointId)
        ? {
            ...state,
            selectedCheckpointId: action.checkpointId,
            checkpointDiff: undefined,
            checkpointUndo: undefined,
            notice: undefined,
          }
        : state;
    case 'checkpoint.undo.requested':
      return state.checkpoints.some(item => item.checkpointId === action.checkpointId
        && item.undoable)
        ? {...state, pendingUndoCheckpointId: action.checkpointId, notice: undefined}
        : {...state, notice: '当前 Checkpoint 不可 Undo'};
    case 'checkpoint.undo.cancelled':
      return {...state, pendingUndoCheckpointId: undefined};
    case 'event.received':
      return applyEvent(state, action.event);
    case 'transport.failed':
      return {
        ...state,
        phase: 'failed',
        notice: action.message,
        activeRunId: undefined,
        steeringQueueDepth: 0,
      };
    case 'slash.notice':
      return {...state, notice: action.message};
    case 'closing':
      return {...state, phase: 'closing'};
    case 'closed':
      return {...state, phase: 'closed', activeRunId: undefined, steeringQueueDepth: 0};
  }
}

function applyEvent(state: TuiState, event: ProtocolEvent): TuiState {
  switch (event.type) {
    case 'initialized':
      return {
        ...state,
        phase: 'ready',
        sessionId: event.sessionId,
        steeringQueueDepth: 0,
        notice: undefined,
      };
    case 'skill.invoked':
      return {...state, notice: `Skill /${String(event.payload.skillId)} 已提交`};
    case 'skill.completed':
      return {...state, notice: event.payload.status === 'succeeded'
        ? `Skill /${String(event.payload.skillId)} 已完成`
        : `Skill /${String(event.payload.skillId)} 未完成`};
    case 'task.status':
    case 'task.terminal': {
      const task: ChildTaskView = {
        taskId: String(event.payload.taskId),
        definitionId: String(event.payload.definitionId),
        status: event.payload.status as ChildTaskView['status'],
        failure: String(event.payload.failure),
        modelTurns: Number(event.payload.modelTurns),
        toolCalls: Number(event.payload.toolCalls),
        estimatedTokens: Number(event.payload.estimatedTokens),
        elapsedMillis: Number(event.payload.elapsedMillis),
        summary: String(event.payload.summary),
        verified: event.payload.verified === true,
        worktreeDisposition: optionalText(event.payload.worktreeDisposition),
      };
      const childTasks = state.childTasks ?? [];
      const existing = childTasks.findIndex(item => item.taskId === task.taskId);
      return {
        ...state,
        childTasks: existing < 0
          ? [...childTasks, task]
          : childTasks.map(item => item.taskId === task.taskId ? task : item),
        notice: event.type === 'task.terminal'
          ? `子任务 ${task.taskId}：${task.status}` : state.notice,
      };
    }
    case 'task.worktree': {
      const taskId = String(event.payload.taskId);
      const disposition = String(event.payload.disposition);
      return {
        ...state,
        childTasks: (state.childTasks ?? []).map(task => task.taskId === taskId
          ? {...task, worktreeDisposition: disposition} : task),
        notice: `子任务 ${taskId} worktree：${disposition}`,
      };
    }
    case 'run.budget.governed':
      return {...state, notice: `交互预算：${String(event.payload.reason)}`};
    case 'run.started':
      return updateCurrentRun(state, event, run => ({
        ...run,
        runId: event.runId,
      }), event.runId);
    case 'model.text.delta':
      return updateCurrentRun(state, event, run => ({
        ...run,
        text: run.text + String(event.payload.text),
      }));
    case 'plan.proposed':
      return updateCurrentRun(state, event, run => ({
        ...run,
        planProposal: {
          planId: String(event.payload.planId),
          status: 'awaiting_approval',
          objective: String(event.payload.objective),
          workspaceDigest: String(event.payload.workspaceDigest),
          steps: (event.payload.steps as readonly Readonly<Record<string, unknown>>[]).map(step => ({
            ordinal: Number(step.ordinal),
            title: String(step.title),
            detail: String(step.detail),
          })),
        },
      }));
    case 'plan.review.requested':
      return updateCurrentRun(state, event, run => ({
        ...run,
        planReview: {
          planId: String(event.payload.planId),
          status: 'awaiting_approval',
          revision: Number(event.payload.revision),
          contentDigest: String(event.payload.contentDigest),
          markdown: String(event.payload.markdown),
          workspaceDigest: String(event.payload.workspaceDigest),
          originalPermissionMode: String(event.payload.originalPermissionMode) as 'default' | 'accept_edits',
          suggestedContextPolicy: String(event.payload.suggestedContextPolicy) as 'keep' | 'clear',
        },
      }));
    case 'question.requested':
      return updateCurrentRun(state, event, run => ({
        ...run,
        pendingQuestion: {
          callId: String(event.payload.callId),
          question: String(event.payload.question),
          options: (event.payload.options as readonly Readonly<Record<string, unknown>>[]).map(option => ({
            optionId: String(option.optionId), label: String(option.label),
            description: String(option.description),
          })),
          submitted: false,
        },
      }));
    case 'approval.requested':
      return updateCurrentRun(state, event, run => ({
        ...run,
        pendingApproval: {
          approvalId: String(event.payload.approvalId),
          ordinal: Number(event.payload.ordinal),
          toolName: String(event.payload.toolName),
          effect: event.payload.effect as ApprovalView['effect'],
          target: optionalText(event.payload.target),
          operation: approvalOperation(event.payload.operation),
          removedLines: optionalNonNegativeInteger(event.payload.removedLines),
          addedLines: optionalNonNegativeInteger(event.payload.addedLines),
          command: optionalText(event.payload.command),
          shell: approvalShell(event.payload.shell),
          workingDirectory: optionalText(event.payload.workingDirectory),
          destination: approvalDestination(event.payload.destination),
          query: optionalText(event.payload.query),
          submitted: false,
        },
      }));
    case 'tool.started':
      return updateCurrentRun(state, event, run => ({
        ...run,
        tools: upsertStartedTool(run.tools, event),
      }));
    case 'tool.completed':
    case 'tool.failed':
      return updateCurrentRun(state, event, run => ({
        ...run,
        tools: upsertFinishedTool(run.tools, event),
        pendingApproval: run.pendingApproval?.ordinal === Number(event.payload.ordinal)
          ? undefined : run.pendingApproval,
        pendingQuestion: event.payload.toolName === 'ask_plan_question'
          ? undefined : run.pendingQuestion,
      }));
    case 'tool.output':
      return updateCurrentRun(state, event, run => ({
        ...run,
        tools: appendToolOutput(run.tools, event),
      }));
    case 'run.completed':
      return finishRun(state, event, 'completed');
    case 'run.cancelled':
      return finishRun(state, event, 'cancelled');
    case 'run.failed':
      return finishRun(state, event, 'failed');
    case 'checkpoint.listed': {
      const checkpoints = checkpointList(event.payload);
      const selection = checkpoints.some(item => item.checkpointId === state.selectedCheckpointId)
        ? state.selectedCheckpointId
        : checkpoints[0]?.checkpointId;
      return {
        ...state,
        checkpoints,
        checkpointPanelOpen: true,
        selectedCheckpointId: selection,
        checkpointDiff: selection === state.selectedCheckpointId
          ? state.checkpointDiff : undefined,
        pendingUndoCheckpointId: undefined,
        notice: checkpointListNotice(checkpoints),
      };
    }
    case 'checkpoint.diffed':
      return {
        ...state,
        selectedCheckpointId: String(event.payload.checkpointId),
        checkpointDiff: checkpointDiffView(event.payload),
        checkpointUndo: undefined,
        notice: undefined,
      };
    case 'checkpoint.undone':
      return {
        ...state,
        checkpoints: state.checkpoints.map(item =>
          item.checkpointId === event.payload.checkpointId
            ? {...item, phase: 'undone', undoable: false}
            : item),
        checkpointUndo: checkpointUndoView(event.payload),
        pendingUndoCheckpointId: undefined,
        notice: undefined,
      };
    case 'session.command.result':
      return applySessionCommandResult(state, event);
    case 'provider.control.result':
    case 'plan.feedback.accepted':
    case 'plan.execution.accepted':
    case 'plan.review.rejected':
    case 'plan.verification.required':
    case 'plan.verification.completed':
      return state;
    case 'file.suggestions':
      return state;
    case 'steering.queued':
      return {
        ...state,
        steeringQueueDepth: Number(event.payload.queueDepth),
        notice: `补充消息已排队（${event.payload.queueDepth}/100）`,
      };
    case 'steering.discarded':
      return {
        ...state,
        steeringQueueDepth: Math.max(0, (state.steeringQueueDepth ?? 0) - 1),
        notice: steeringDiscardedNotice(event.payload.reason),
      };
    case 'protocol.error':
      return {
        ...state,
        notice: safeProtocolMessage(event.payload),
      };
  }
}

function applySessionCommandResult(state: TuiState, event: ProtocolEvent): TuiState {
  if (
    event.payload.intent === 'resume'
    && event.payload.status === 'succeeded'
    && typeof event.payload.result === 'object'
    && event.payload.result !== null
    && !Array.isArray(event.payload.result)
  ) {
    const result = event.payload.result as Readonly<Record<string, unknown>>;
    if (
      typeof result.previousSessionId === 'string'
      && result.previousSessionId === state.sessionId
      && typeof result.resumedSessionId === 'string'
      && event.sessionId === result.resumedSessionId
    ) {
      return {...state, sessionId: result.resumedSessionId, steeringQueueDepth: 0};
    }
  }
  return state;
}

function steeringDiscardedNotice(reason: unknown): string {
  switch (reason) {
    case 'clear': return '已清除一条未发送补充消息';
    case 'cancelled': return '当前 Run 取消，已丢弃一条未发送补充消息';
    case 'session_switch': return '会话已切换，已丢弃一条未发送补充消息';
    case 'shutdown': return '连接关闭，已丢弃一条未发送补充消息';
    default: return '未发送补充消息已丢弃';
  }
}

function checkpointList(
  payload: Readonly<Record<string, unknown>>,
): readonly CheckpointView[] {
  return (payload.checkpoints as readonly Readonly<Record<string, unknown>>[]).map(item => ({
    checkpointId: String(item.checkpointId),
    callId: String(item.callId),
    toolName: String(item.toolName),
    target: String(item.target),
    existedBefore: item.existedBefore === true,
    phase: item.phase as CheckpointPhase,
    undoable: item.undoable === true,
  }));
}

function checkpointDiffView(
  payload: Readonly<Record<string, unknown>>,
): CheckpointDiffView {
  return {
    checkpointId: String(payload.checkpointId),
    target: String(payload.target),
    status: payload.status as CheckpointDiffView['status'],
    text: String(payload.text),
    truncated: payload.truncated === true,
  };
}

function checkpointUndoView(
  payload: Readonly<Record<string, unknown>>,
): CheckpointUndoView {
  return {
    checkpointId: String(payload.checkpointId),
    target: String(payload.target),
    status: payload.status as CheckpointUndoView['status'],
    message: String(payload.message),
  };
}

function checkpointListNotice(checkpoints: readonly CheckpointView[]): string {
  const uncertain = checkpoints.filter(item => !item.undoable
    && item.phase !== 'undone').length;
  return checkpoints.length === 0
    ? '当前 Session 没有 Checkpoint'
    : `Checkpoint：${checkpoints.length} 个，${uncertain} 个不可 Undo/需检查`;
}

function optionalText(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function approvalOperation(
  value: unknown,
): ApprovalView['operation'] {
  return value === 'modify' || value === 'create' ? value : undefined;
}

function approvalShell(value: unknown): ApprovalView['shell'] {
  return value === 'powershell' || value === 'sh' ? value : undefined;
}

function approvalDestination(value: unknown): ApprovalView['destination'] {
  return value === 'configured_web_search_provider' ? value : undefined;
}

function optionalNonNegativeInteger(value: unknown): number | undefined {
  return Number.isSafeInteger(value) && (value as number) >= 0
    ? value as number
    : undefined;
}

function upsertStartedTool(
  tools: readonly ToolView[],
  event: ProtocolEvent,
): readonly ToolView[] {
  const ordinal = Number(event.payload.ordinal);
  const item: ToolView = {
    ordinal,
    name: String(event.payload.toolName),
    mode: searchMode(event.payload.mode),
    status: 'started',
    returnedCharacters: undefined,
    returnedItems: undefined,
    filteredItems: undefined,
    truncated: false,
    truncationReason: undefined,
    errorCode: undefined,
    failureCategory: undefined,
    retryable: undefined,
    output: '',
  };
  return [...tools.filter(tool => tool.ordinal !== ordinal), item]
    .sort((left, right) => left.ordinal - right.ordinal);
}

function upsertFinishedTool(
  tools: readonly ToolView[],
  event: ProtocolEvent,
): readonly ToolView[] {
  const ordinal = Number(event.payload.ordinal);
  const rawStatus = String(event.payload.status);
  const status: ToolView['status'] = rawStatus === 'success'
    ? 'success'
    : rawStatus === 'denied' ? 'denied' : 'failed';
  const item: ToolView = {
    ordinal,
    name: String(event.payload.toolName),
    mode: searchMode(event.payload.mode),
    status,
    returnedCharacters: safeCount(event.payload.returnedCharacters),
    returnedItems: safeCount(event.payload.returnedItems),
    filteredItems: safeCount(event.payload.filteredItems),
    truncated: event.payload.truncated === true,
    truncationReason: typeof event.payload.truncationReason === 'string'
      ? event.payload.truncationReason : undefined,
    errorCode: typeof event.payload.errorCode === 'string'
      ? event.payload.errorCode : undefined,
    failureCategory: typeof event.payload.failureCategory === 'string'
      ? event.payload.failureCategory : undefined,
    retryable: typeof event.payload.retryable === 'boolean'
      ? event.payload.retryable : undefined,
    output: tools.find(tool => tool.ordinal === ordinal)?.output ?? '',
  };
  return [...tools.filter(tool => tool.ordinal !== ordinal), item]
    .sort((left, right) => left.ordinal - right.ordinal);
}

function appendToolOutput(
  tools: readonly ToolView[],
  event: ProtocolEvent,
): readonly ToolView[] {
  const ordinal = Number(event.payload.ordinal);
  const current = tools.find(tool => tool.ordinal === ordinal);
  if (current === undefined) {
    return tools;
  }
  const prefix = event.payload.stream === 'stderr' ? '[stderr] ' : '';
  const next = Array.from(current.output + prefix + String(event.payload.text))
    .slice(0, 64 * 1024)
    .join('');
  return tools.map(tool => tool.ordinal === ordinal ? {...tool, output: next} : tool);
}

function finishRun(
  state: TuiState,
  event: ProtocolEvent,
  status: Exclude<RunStatus, 'running'>,
): TuiState {
  const updated = updateCurrentRun(state, event, run => ({
    ...run,
    status,
    pendingApproval: undefined,
    stopReason: terminalText(event.payload.stopReason),
    modelFailure: modelFailureView(event.payload.modelFailure),
    modelTurns: terminalCount(event.payload.modelTurns),
    toolCalls: terminalCount(event.payload.toolCalls),
  }));
  return {
    ...updated,
    phase: 'ready',
    activeRunId: undefined,
  };
}

function modelFailureView(value: unknown): ModelFailureView | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return undefined;
  }
  const failure = value as Record<string, unknown>;
  return {
    category: failure.category as ModelFailureCategory,
    statusClass: failure.statusClass === '4xx' || failure.statusClass === '5xx'
      ? failure.statusClass : undefined,
    attempts: Number(failure.attempts),
    receivedOutput: failure.receivedOutput === true,
  };
}

function safeCount(value: unknown): number | undefined {
  return Number.isSafeInteger(value) && (value as number) >= 0
    ? value as number : undefined;
}

function searchMode(value: unknown): SearchMode | undefined {
  return value === 'content' || value === 'files' || value === 'count'
    ? value : undefined;
}

function terminalText(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function terminalCount(value: unknown): number | undefined {
  return Number.isSafeInteger(value) && (value as number) >= 0
    ? value as number : undefined;
}

function updateCurrentRun(
  state: TuiState,
  event: ProtocolEvent,
  transform: (run: RunView) => RunView,
  activeRunId: string | undefined = state.activeRunId,
): TuiState {
  const index = state.runs.findLastIndex(run => run.requestId === event.requestId);
  if (index < 0) {
    return {...state, phase: 'failed', notice: '收到无法关联到请求的 Run 事件'};
  }
  const run = state.runs[index];
  if (run === undefined) {
    return {...state, phase: 'failed', notice: 'Run 投影索引无效'};
  }
  if (run.runId !== undefined && event.runId !== run.runId) {
    return {...state, phase: 'failed', notice: 'Run ID 与当前投影不匹配'};
  }
  const runs = [...state.runs];
  runs[index] = transform(run);
  return {...state, runs, activeRunId};
}

function safeProtocolMessage(payload: Readonly<Record<string, unknown>>): string {
  const code = typeof payload.code === 'string' ? payload.code : 'PROTOCOL_ERROR';
  return `Java 协议错误：${code}`;
}
