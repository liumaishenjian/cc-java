import type {ProtocolEvent} from './protocol.js';

export type RunStatus = 'running' | 'completed' | 'cancelled' | 'failed';
export type ClientPhase = 'connecting' | 'ready' | 'running' | 'closing' | 'closed' | 'failed';
export type SearchMode = 'content' | 'files' | 'count';

export interface ApprovalView {
  readonly approvalId: string;
  readonly ordinal: number;
  readonly toolName: string;
  readonly effect: 'write_workspace' | 'execute_process';
  readonly target: string | undefined;
  readonly operation: 'modify' | 'create' | undefined;
  readonly removedLines: number | undefined;
  readonly addedLines: number | undefined;
  readonly command: string | undefined;
  readonly shell: 'powershell' | 'sh' | undefined;
  readonly workingDirectory: string | undefined;
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
  readonly output: string;
}

export interface RunView {
  readonly requestId: string;
  readonly prompt: string;
  readonly runId: string | undefined;
  readonly text: string;
  readonly tools: readonly ToolView[];
  readonly pendingApproval?: ApprovalView | undefined;
  readonly status: RunStatus;
  readonly stopReason: string | undefined;
  readonly modelTurns: number | undefined;
  readonly toolCalls: number | undefined;
}

export interface TuiState {
  readonly phase: ClientPhase;
  readonly sessionId: string | undefined;
  readonly activeRunId: string | undefined;
  readonly runs: readonly RunView[];
  readonly notice: string | undefined;
}

export type TuiAction =
  | {readonly type: 'run.submitted'; readonly requestId: string; readonly prompt: string}
  | {readonly type: 'approval.submitted'; readonly approvalId: string}
  | {readonly type: 'event.received'; readonly event: ProtocolEvent}
  | {readonly type: 'transport.failed'; readonly message: string}
  | {readonly type: 'closing'}
  | {readonly type: 'closed'};

export const initialTuiState: TuiState = {
  phase: 'connecting',
  sessionId: undefined,
  activeRunId: undefined,
  runs: [],
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
            status: 'running',
            stopReason: undefined,
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
    case 'event.received':
      return applyEvent(state, action.event);
    case 'transport.failed':
      return {...state, phase: 'failed', notice: action.message, activeRunId: undefined};
    case 'closing':
      return {...state, phase: 'closing'};
    case 'closed':
      return {...state, phase: 'closed', activeRunId: undefined};
  }
}

function applyEvent(state: TuiState, event: ProtocolEvent): TuiState {
  switch (event.type) {
    case 'initialized':
      return {
        ...state,
        phase: 'ready',
        sessionId: event.sessionId,
        notice: undefined,
      };
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
    case 'protocol.error':
      return {
        ...state,
        notice: safeProtocolMessage(event.payload),
      };
  }
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
    modelTurns: terminalCount(event.payload.modelTurns),
    toolCalls: terminalCount(event.payload.toolCalls),
  }));
  return {
    ...updated,
    phase: 'ready',
    activeRunId: undefined,
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
