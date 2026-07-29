import type {ProtocolEvent} from './protocol.js';

export type RunStatus = 'running' | 'completed' | 'cancelled' | 'failed';
export type ClientPhase = 'connecting' | 'ready' | 'running' | 'closing' | 'closed' | 'failed';

export interface RunView {
  readonly requestId: string;
  readonly prompt: string;
  readonly runId: string | undefined;
  readonly text: string;
  readonly status: RunStatus;
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
            status: 'running',
          },
        ],
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

function finishRun(
  state: TuiState,
  event: ProtocolEvent,
  status: Exclude<RunStatus, 'running'>,
): TuiState {
  const updated = updateCurrentRun(state, event, run => ({...run, status}));
  return {
    ...updated,
    phase: 'ready',
    activeRunId: undefined,
  };
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
