import {useEffect, useReducer, useRef, useState} from 'react';
import {Box, Text, useApp, useInput, usePaste, useWindowSize} from 'ink';
import {initialTuiState, reduceTuiState} from './state.js';
import type {ProtocolEvent} from './protocol.js';
import type {
  ApprovalView,
  CheckpointPhase,
  CheckpointView,
  ModelFailureView,
  RunView,
} from './state.js';
import {AssistantMarkdown} from './assistant-markdown.js';
import {ToolActivityGroup} from './tool-activity.js';
import {
  parseSlashCommand,
  renderSlashResult,
  slashCommandUsage,
} from './slash-command.js';
import {
  acceptPendingComposer,
  acceptSubmittedComposer,
  appendInput,
  beginPendingComposer,
  completionCandidates,
  createComposerState,
  projectComposer,
  reduceComposer,
  removeLastCodePoint,
  renderComposerViewport,
  restoreRejectedComposer,
  submittedComposerLabel,
  type ComposerAction,
  type ComposerLayout,
  type ComposerState,
} from './input-editor.js';

export interface AgentTuiProps {
  readonly client: AgentClient;
}

export interface AgentClient {
  onEvent(listener: (event: ProtocolEvent) => void): () => void;
  onFailure(listener: (message: string) => void): () => void;
  onExit(listener: () => void): () => void;
  initialize(): string;
  startRun(prompt: string): string;
  cancelRun(): string;
  resolveApproval(
    approvalId: string,
    decision: 'allow_once' | 'allow_session' | 'deny',
  ): string;
  listCheckpoints?(): string;
  checkpointDiff?(checkpointId: string): string;
  undoCheckpoint?(checkpointId: string, confirmed: boolean): string;
  sessionCommand?(commandId: string, intent: 'help' | 'clear' | 'compact' | 'context' | 'doctor' | 'model' | 'permissions' | 'resume', arguments_: Readonly<Record<string, unknown>>): string;
  shutdown(): Promise<void>;
  terminate(): void;
}

export {
  appendInput,
  MAX_INPUT_CODE_POINTS as MAX_INPUT_CHARS,
} from './input-editor.js';

/**
 * S03 最小 React/Ink 终端 Surface。
 *
 * 组件只把键盘动作转换成命令并渲染 Reducer 投影；Java Headless 始终拥有 Session、
 * Run、Tool 与终态。当前只展示脱敏 Tool 摘要，不执行 Tool；审批仍属于 S04。
 */
export function AgentTui({client}: AgentTuiProps) {
  const [state, dispatch] = useReducer(reduceTuiState, initialTuiState);
  const [composer, setComposer] = useState<ComposerState>(() => createComposerState(4));
  const composerRef = useRef(composer);
  const historySessionIdRef = useRef<string | undefined>(undefined);
  const pendingSteeringPromptsRef = useRef(new Map<string, string>());
  const pendingSubmissionsRef = useRef(new Map<string, {
    readonly composer: ComposerState;
    readonly label: string;
  }>());
  const cancelPending = useRef(false);
  const nextCommandNumber = useRef(1);
  const pendingApproval = state.runs.findLast(
    run => run.status === 'running',
  )?.pendingApproval;
  const pendingUndo = state.checkpoints.find(
    item => item.checkpointId === state.pendingUndoCheckpointId,
  );
  const selectedCheckpoint = state.checkpoints.find(
    item => item.checkpointId === state.selectedCheckpointId,
  );
  const checkpointSupported = client.listCheckpoints !== undefined
    && client.checkpointDiff !== undefined
    && client.undoCheckpoint !== undefined;
  const {exit} = useApp();
  const {columns, rows} = useWindowSize();
  const composerLayout: ComposerLayout = {
    width: Math.max(1, columns - 6),
    height: Math.max(1, Math.min(8, rows - 6)),
  };
  const replaceComposer = (next: ComposerState) => {
    composerRef.current = next;
    setComposer(next);
  };
  const applyComposer = (action: ComposerAction) => {
    const transition = reduceComposer(composerRef.current, action, composerLayout);
    replaceComposer(transition.state);
    return transition;
  };

  useEffect(() => {
    const offEvent = client.onEvent(event => {
      if (event.type === 'initialized') {
        if (historySessionIdRef.current !== event.sessionId) {
          const switchingSession = historySessionIdRef.current !== undefined;
          historySessionIdRef.current = event.sessionId;
          if (switchingSession) replaceComposer(createComposerState(4));
          pendingSteeringPromptsRef.current.clear();
          pendingSubmissionsRef.current.clear();
        }
      }
      if (event.type === 'session.command.result') {
        if (event.payload.intent === 'resume' && event.payload.status === 'succeeded') {
          historySessionIdRef.current = event.sessionId;
          replaceComposer(createComposerState(4));
          pendingSteeringPromptsRef.current.clear();
          pendingSubmissionsRef.current.clear();
        }
        const payload = event.payload;
        dispatch({
          type: 'slash.notice',
          message: renderSlashResult(
            String(payload.intent), String(payload.status), String(payload.code),
            payload.result as Readonly<Record<string, unknown>>,
          ),
        });
      }
      if (event.type === 'steering.discarded' || event.type === 'protocol.error') {
        pendingSteeringPromptsRef.current.delete(event.requestId);
        const rejected = pendingSubmissionsRef.current.get(event.requestId);
        if (rejected !== undefined) {
          replaceComposer(restoreRejectedComposer(composerRef.current, rejected.composer));
          pendingSubmissionsRef.current.delete(event.requestId);
        }
      }
      if (event.type === 'steering.queued') {
        const pending = pendingSubmissionsRef.current.get(event.requestId);
        if (pending !== undefined) {
          replaceComposer(acceptPendingComposer(composerRef.current, pending.composer));
          pendingSubmissionsRef.current.delete(event.requestId);
        }
      }
      if (event.type === 'run.started') {
        const pending = pendingSubmissionsRef.current.get(event.requestId);
        if (pending !== undefined) {
          replaceComposer(acceptPendingComposer(composerRef.current, pending.composer));
          pendingSubmissionsRef.current.delete(event.requestId);
        }
        const prompt = pendingSteeringPromptsRef.current.get(event.requestId);
        if (prompt !== undefined) {
          pendingSteeringPromptsRef.current.delete(event.requestId);
          dispatch({type: 'run.submitted', requestId: event.requestId, prompt, steering: true});
        }
      }
      if (
        event.type === 'run.completed'
        || event.type === 'run.failed'
        || event.type === 'run.cancelled'
      ) {
        cancelPending.current = false;
      }
      dispatch({type: 'event.received', event});
    });
    const offFailure = client.onFailure(message => {
      cancelPending.current = false;
      pendingSteeringPromptsRef.current.clear();
          pendingSubmissionsRef.current.clear();
      dispatch({type: 'transport.failed', message});
    });
    const offExit = client.onExit(() => {
      cancelPending.current = false;
      pendingSteeringPromptsRef.current.clear();
          pendingSubmissionsRef.current.clear();
      dispatch({type: 'closed'});
      exit();
    });
    client.initialize();
    return () => {
      offEvent();
      offFailure();
      offExit();
      pendingSteeringPromptsRef.current.clear();
          pendingSubmissionsRef.current.clear();
      client.terminate();
    };
  }, [client, exit]);

  useEffect(() => {
    applyComposer({type: 'Resize', width: composerLayout.width, height: composerLayout.height});
  }, [columns, rows]);

  usePaste(pasted => {
    if (canEditInput(state.phase)) {
      applyComposer({type: 'Paste', text: pasted});
    }
  });

  useInput((text, key) => {
    if (key.ctrl && text.toLowerCase() === 'c') {
      const action = decideInterrupt(
        state.phase,
        state.activeRunId,
        cancelPending.current,
      );
      if (action === 'cancel') {
        cancelPending.current = true;
        client.cancelRun();
      } else if (action === 'terminate') {
        client.terminate();
        exit();
      } else {
        dispatch({type: 'closing'});
        void client.shutdown();
      }
      return;
    }
    if (pendingApproval !== undefined) {
      const decision = approvalDecision(text);
      if (decision !== undefined && !pendingApproval.submitted) {
        client.resolveApproval(pendingApproval.approvalId, decision);
        dispatch({
          type: 'approval.submitted',
          approvalId: pendingApproval.approvalId,
        });
      }
      return;
    }
    if (pendingUndo !== undefined) {
      const decision = undoConfirmation(text);
      if (decision === 'confirm') {
        client.undoCheckpoint?.(pendingUndo.checkpointId, true);
      } else if (decision === 'cancel') {
        dispatch({type: 'checkpoint.undo.cancelled'});
      }
      return;
    }
    if (
      state.phase === 'ready'
      && checkpointSupported
      && composerRef.current.text.length === 0
    ) {
      const action = checkpointAction(text, key, state.checkpointPanelOpen);
      if (action === 'list') {
        client.listCheckpoints?.();
        return;
      }
      if (action === 'previous' || action === 'next') {
        const checkpointId = adjacentCheckpointId(
          state.checkpoints,
          state.selectedCheckpointId,
          action === 'previous' ? -1 : 1,
        );
        if (checkpointId !== undefined) {
          dispatch({type: 'checkpoint.selected', checkpointId});
        }
        return;
      }
      if (action === 'diff' && selectedCheckpoint !== undefined) {
        client.checkpointDiff?.(selectedCheckpoint.checkpointId);
        return;
      }
      if (action === 'undo' && selectedCheckpoint?.undoable === true) {
        dispatch({
          type: 'checkpoint.undo.requested',
          checkpointId: selectedCheckpoint.checkpointId,
        });
        return;
      }
    }
    if (!canEditInput(state.phase)) {
      return;
    }
    const current = composerRef.current;
    const candidates = completionCandidates(current.text);
    if (key.shift && key.return) {
      applyComposer({type: 'InsertText', text: '\n'});
      return;
    }
    if (key.escape) {
      applyComposer({type: 'CloseCompletion'});
      return;
    }
    if (key.return) {
      if (current.completionCandidates.length > 0) {
        applyComposer({type: 'AcceptCompletion'});
        return;
      }
      if (pendingSubmissionsRef.current.size > 0) {
        dispatch({type: 'slash.notice', message: '上一条输入仍在等待 Java 接受，当前草稿已保留'});
        return;
      }
      const submission = applyComposer({type: 'Submit'});
      if (submission.kind !== 'submit-ready') return;
      const prompt = submission.expandedText;
      if (prompt.trim().length === 0) return;
      const slash = parseSlashCommand(prompt.trim());
      if (slash.kind === 'command') {
        if (client.sessionCommand === undefined) {
          dispatch({type: 'slash.notice', message: '当前连接不支持 Slash 命令'});
          return;
        }
        client.sessionCommand(`tui-command-${nextCommandNumber.current++}`, slash.command.intent, slash.command.arguments);
      } else if (slash.kind === 'invalid') {
        dispatch({type: 'slash.notice', message: slash.message});
        return;
      } else {
        try {
          const requestId = client.startRun(prompt);
          const label = submittedComposerLabel(submission.state);
          const asSteering = state.phase !== 'ready' || pendingSubmissionsRef.current.size > 0;
          pendingSubmissionsRef.current.set(requestId, {composer: submission.state, label});
          replaceComposer(beginPendingComposer(submission.state));
          if (asSteering) {
            pendingSteeringPromptsRef.current.set(requestId, label);
          } else {
            dispatch({type: 'run.submitted', requestId, prompt: label});
          }
        } catch {
          dispatch({type: 'slash.notice', message: '输入传输未被接受，草稿已保留'});
          return;
        }
      }
      if (slash.kind === 'command') replaceComposer(acceptSubmittedComposer(submission.state));
      return;
    }
    if (key.upArrow || key.downArrow) {
      applyComposer({type: key.upArrow ? 'MoveUp' : 'MoveDown'});
      return;
    }
    if (key.leftArrow || key.rightArrow) {
      applyComposer({type: key.leftArrow
        ? key.ctrl || key.meta ? 'MoveWordLeft' : 'MoveLeft'
        : key.ctrl || key.meta ? 'MoveWordRight' : 'MoveRight'});
      return;
    }
    if (key.home || key.end) {
      applyComposer({type: key.home ? 'MoveHome' : 'MoveEnd'});
      return;
    }
    if (key.tab) {
      if (current.completionCandidates.length > 0) applyComposer({type: 'AcceptCompletion'});
      return;
    }
    if (key.backspace || key.delete) {
      applyComposer({type: key.backspace ? 'Backspace' : 'DeleteForward'});
      return;
    }
    if (!key.ctrl && !key.meta && text.length > 0) {
      const transition = applyComposer({type: 'InsertText', text});
      if (transition.kind === 'updated') {
        const nextCandidates = completionCandidates(transition.state.text)
          .filter(candidate => candidate !== transition.state.text);
        const completion = reduceComposer(
          transition.state, {type: 'SetCompletions', candidates: nextCandidates}, composerLayout,
        );
        replaceComposer(completion.state);
      }
    }
  }, {
    isActive: state.phase === 'connecting'
      || state.phase === 'ready'
      || state.phase === 'running',
  });

  return <AgentView
    state={state}
    composer={composer}
    columns={columns}
    composerLayout={composerLayout}
  />;
}

export interface AgentViewProps {
  readonly state: ReturnType<typeof reduceTuiState>;
  readonly composer?: ComposerState;
  /** 兼容纯展示测试；生产路径使用 composer。 */
  readonly input?: string;
  readonly columns: number;
  readonly composerLayout?: ComposerLayout;
}

/**
 * 纯展示组件，使宽字符、窄窗口和各 Run 终态无需真实终端即可验证。
 */
export function AgentView({state, composer, input = '', columns, composerLayout}: AgentViewProps) {
  const width = Math.max(20, columns);
  const effectiveComposer = composer ?? reduceComposer(
    createComposerState(4), {type: 'InsertText', text: input}, {width: Math.max(1, width - 6), height: 4},
  ).state;
  const layout = composerLayout ?? {width: Math.max(1, width - 6), height: 4};
  const projection = projectComposer(effectiveComposer, layout);
  const renderedLines = renderComposerViewport(effectiveComposer, layout);
  const candidates = canEditInput(state.phase) ? effectiveComposer.completionCandidates : [];
  const selectedCompletion = effectiveComposer.completionIndex ?? 0;
  return (
    <Box flexDirection="column" width={width}>
      <Box>
        <Text bold color="cyan">cc-java</Text>
        <Text color="blue">  S06</Text>
        <Text dimColor>  {phaseLabel(state.phase)}</Text>
      </Box>
      {state.runs.map(run => (
        <Box key={run.requestId} flexDirection="column" marginTop={1}>
          <Box>
            <Text color="green" bold>❯ </Text>
            <Text bold>{run.prompt}</Text>
          </Box>
          <ToolActivityGroup tools={run.tools} />
          {run.tools.filter(tool => tool.output.length > 0).map(tool => (
            <Box
              key={`output-${tool.ordinal}`}
              marginLeft={4}
              flexDirection="column"
            >
              <Text dimColor>{tool.output}</Text>
            </Box>
          ))}
          {run.pendingApproval === undefined
            ? null
            : <ApprovalPrompt approval={run.pendingApproval} />}
          {run.text.length === 0 ? null : (
            <Box marginTop={1} flexDirection="row">
              <Text color="cyan">● </Text>
              <Box flexDirection="column" flexGrow={1}>
                <AssistantMarkdown text={run.text} />
              </Box>
            </Box>
          )}
          <RunTerminal run={run} />
          {run.modelFailure === undefined ? null : (
            <Box marginLeft={4}>
              <Text color="red">{formatModelFailure(run.modelFailure)}</Text>
            </Box>
          )}
        </Box>
      ))}
      <CheckpointPanel state={state} />
      {state.notice === undefined ? null : (
        <Box marginTop={1}>
          <Text color="yellow">• {state.notice}</Text>
        </Box>
      )}
      {state.phase === 'ready' ? (
        <Box marginTop={1} flexDirection="column">
          <Text dimColor>
            C 列表　↑/↓ 选择　D Diff　U 请求 Undo
          </Text>
          <Text dimColor>
            Undo 必须针对当前 Checkpoint 二次确认，绝不自动重放。
          </Text>
        </Box>
      ) : null}
      <Box
        marginTop={1}
        borderStyle="round"
        borderColor={state.runs.findLast(run => run.status === 'running')
          ?.pendingApproval === undefined
          ? state.phase === 'ready' ? 'cyan' : 'gray'
          : 'yellow'}
        paddingX={1}
      >
        <Text color="cyan">❯ </Text>
        {canEditInput(state.phase) ? (
          <Box flexDirection="column">
            {renderedLines.map((line, index) => (
              <Text key={`${projection.viewportTop + index}-${line.beforeCursor.length}`}>
                {line.beforeCursor}
                {line.cursorText === undefined ? null : <Text inverse>{line.cursorText}</Text>}
                {line.afterCursor}
              </Text>
            ))}
          </Box>
        ) : null}
        {state.phase === 'running'
          ? <Text dimColor>
              正在处理… Enter 排队补充{(state.steeringQueueDepth ?? 0) > 0
                ? `（${state.steeringQueueDepth}/100）` : ''}　Ctrl+C 取消
            </Text>
          : effectiveComposer.text.length === 0
            ? <Text dimColor>{inputHint(state.phase)}</Text>
            : null}
      </Box>
      {effectiveComposer.validationCode === undefined ? null : (
        <Text color="red">输入未接受：{validationMessage(effectiveComposer.validationCode)}</Text>
      )}
      <Text dimColor>
        光标 {projection.cursorRow - projection.viewportTop + 1}:{projection.cursorColumn + 1}
      </Text>
      {candidates.length === 0 ? null : (
        <Box flexDirection="column" marginLeft={2}>
          <Text dimColor>Slash 命令 · ↑/↓ 选择 · Tab/Enter 补全</Text>
          {candidates.map((candidate, index) => (
            <Text key={candidate} color={index === selectedCompletion ? 'cyan' : 'white'}>
              {index === selectedCompletion ? '❯ ' : '  '}{slashCommandUsage(candidate)}
            </Text>
          ))}
        </Box>
      )}
    </Box>
  );
}

function CheckpointPanel({state}: {readonly state: AgentViewProps['state']}) {
  if (
    !state.checkpointPanelOpen
    && state.checkpointDiff === undefined
    && state.checkpointUndo === undefined
  ) {
    return null;
  }
  const pendingUndo = state.checkpoints.find(
    item => item.checkpointId === state.pendingUndoCheckpointId,
  );
  return (
    <Box
      marginTop={1}
      flexDirection="column"
      borderStyle="round"
      borderColor={pendingUndo === undefined ? 'blue' : 'red'}
      paddingX={1}
    >
      <Text bold color="blue">Session Checkpoints</Text>
      {state.checkpoints.length === 0
        ? <Text dimColor>当前 Session 没有 Checkpoint</Text>
        : state.checkpoints.map(checkpoint => (
          <CheckpointRow
            key={checkpoint.checkpointId}
            checkpoint={checkpoint}
            selected={checkpoint.checkpointId === state.selectedCheckpointId}
          />
        ))}
      {state.checkpointDiff === undefined ? null : (
        <Box marginTop={1} flexDirection="column">
          <Text color="cyan">
            Diff · {state.checkpointDiff.target} · {state.checkpointDiff.status}
            {state.checkpointDiff.truncated ? ' · 已裁剪' : ''}
          </Text>
          {state.checkpointDiff.text.length === 0
            ? <Text dimColor>（无文本差异）</Text>
            : <Text>{state.checkpointDiff.text}</Text>}
        </Box>
      )}
      {state.checkpointUndo === undefined ? null : (
        <Text color={state.checkpointUndo.status === 'conflict' ? 'red' : 'green'}>
          Undo · {state.checkpointUndo.target} · {state.checkpointUndo.status}
        </Text>
      )}
      {pendingUndo === undefined ? null : (
        <Box marginTop={1} flexDirection="column">
          <Text color="red" bold>
            确认 Undo 当前 Checkpoint？
          </Text>
          <Text>{pendingUndo.checkpointId}</Text>
          <Text>{pendingUndo.target}</Text>
          <Text dimColor>
            仅按 Shift+Y 执行；N 或 Esc 取消。此操作只恢复普通文件 Checkpoint。
          </Text>
        </Box>
      )}
    </Box>
  );
}

function CheckpointRow({
  checkpoint,
  selected,
}: {
  readonly checkpoint: CheckpointView;
  readonly selected: boolean;
}) {
  return (
    <Text color={checkpoint.undoable ? 'green' : 'yellow'}>
      {selected ? '❯' : ' '} {checkpoint.checkpointId} · {checkpoint.target}
      {' · '}{checkpointPhaseLabel(checkpoint.phase)}
      {checkpoint.undoable ? ' · 可 Undo' : ''}
    </Text>
  );
}

function ApprovalPrompt({approval}: {readonly approval: ApprovalView}) {
  const action = approval.effect === 'write_workspace'
    ? '修改 Workspace'
    : '启动本地进程';
  return (
    <Box
      marginTop={1}
      marginLeft={2}
      flexDirection="column"
      borderStyle="round"
      borderColor="yellow"
      paddingX={1}
    >
      <Text color="yellow" bold>需要批准：{action}</Text>
      <Text>{approval.toolName} · 第 {approval.ordinal} 个工具调用</Text>
      {approval.target === undefined
        ? null
        : (
          <>
            <Text>
              {approval.operation === 'create' ? '创建' : '修改'}：{approval.target}
            </Text>
            <Text color="green">
              +{approval.addedLines ?? 0} 行
              <Text color="red">　-{approval.removedLines ?? 0} 行</Text>
            </Text>
          </>
        )}
      {approval.command === undefined
        ? null
        : (
          <>
            <Text>Shell：{approval.shell}</Text>
            <Text>工作目录：{approval.workingDirectory}</Text>
            <Text color="cyan">{approval.command}</Text>
          </>
        )}
      <Text dimColor>
        {approval.submitted
          ? '决定已发送，等待 Java 确认'
          : 'Y 允许本次　A 当前会话允许　N 拒绝　Ctrl+C 取消 Run'}
      </Text>
    </Box>
  );
}

/**
 * 把 Java 权威终态投影为不包含 Provider 原文的稳定诊断摘要。
 */
export function formatRunTerminal(run: RunView): string {
  if (run.status === 'running') {
    return '正在运行';
  }
  const counts = [
    run.modelTurns === undefined ? undefined : `${run.modelTurns} 回合`,
    run.toolCalls === undefined ? undefined : `${run.toolCalls} 次工具`,
  ].filter((value): value is string => value !== undefined);
  if (run.status === 'completed') {
    return counts.length === 0 ? '已完成' : `已完成 · ${counts.join(' · ')}`;
  }
  const reason = run.stopReason === undefined ? '' : ` · ${run.stopReason}`;
  return `${runStatusLabel(run.status)}${reason}`
    + (counts.length === 0 ? '' : ` · ${counts.join(' · ')}`);
}

export function formatModelFailure(summary: ModelFailureView): string {
  const base = (() => {
    switch (summary.category) {
      case 'provider_unavailable': return '模型服务暂时不可用';
      case 'rate_limited': return '模型服务请求过于频繁';
      case 'request_timeout': return '模型请求超时';
      case 'request_conflict': return '模型服务暂时无法处理该请求';
      case 'authentication_failed': return '模型服务鉴权失败';
      case 'invalid_request': return '模型服务拒绝了请求';
      case 'network_error': return '无法连接模型服务';
      case 'incomplete_stream': return '模型输出流未完整结束';
      case 'invalid_response': return '模型服务返回了无效响应';
      case 'provider_error': return '模型服务调用失败';
    }
  })();
  const status = summary.statusClass === undefined ? '' : `（${summary.statusClass}）`;
  const attempts = summary.attempts > 1 ? `，已尝试 ${summary.attempts} 次` : '';
  const action = summary.category === 'authentication_failed'
    ? '；请检查 Provider 凭证或权限'
    : summary.category === 'invalid_request'
      ? '；请检查模型与请求配置'
      : summary.category === 'invalid_response' || summary.category === 'provider_error'
        ? '；请检查 Provider 状态'
        : '；请稍后重试';
  return base + status + attempts + action;
}

function RunTerminal({run}: {readonly run: RunView}) {
  if (run.status === 'running') {
    return null;
  }
  const failed = run.status === 'failed';
  return (
    <Box marginTop={1} marginLeft={2}>
      <Text color={failed ? 'red' : run.status === 'cancelled' ? 'yellow' : 'green'}
        dimColor={!failed}>
        {failed ? '✗' : run.status === 'cancelled' ? '■' : '✓'} {formatRunTerminal(run)}
      </Text>
    </Box>
  );
}

function phaseLabel(phase: ReturnType<typeof reduceTuiState>['phase']): string {
  switch (phase) {
    case 'connecting':
      return '正在连接';
    case 'ready':
      return '就绪';
    case 'running':
      return '运行中';
    case 'closing':
      return '正在关闭';
    case 'closed':
      return '已关闭';
    case 'failed':
      return '连接失败';
  }
}

function runStatusLabel(status: Exclude<RunView['status'], 'running' | 'completed'>): string {
  return status === 'cancelled' ? '已取消' : '运行失败';
}

function inputHint(phase: ReturnType<typeof reduceTuiState>['phase']): string {
  return phase === 'connecting'
    ? '连接中，可以先输入任务'
    : 'Enter 发送，Shift+Enter 换行';
}

function validationMessage(code: ComposerState['validationCode']): string {
  switch (code) {
    case 'VISIBLE_STRUCTURE_LIMIT': return '可见输入结构超过 8192 单元';
    case 'PASTE_COUNT_LIMIT': return '折叠粘贴数量超过上限';
    case 'PASTE_ITEM_LIMIT': return '单次粘贴超过 1 MiB';
    case 'PASTE_TOTAL_LIMIT': return '粘贴总量超过 1 MiB';
    case 'PASTE_REFERENCE_FORGED': return '粘贴引用格式无效';
    case 'PASTE_REFERENCE_STALE': return '粘贴内容已失效';
    case 'PASTE_REFERENCE_DUPLICATE': return '粘贴引用重复';
    case 'PASTE_REFERENCE_ORPHAN': return '粘贴内容缺少引用';
    case 'SUBMISSION_CODE_POINT_LIMIT': return '展开内容的 Unicode 字符数超过上限';
    case 'SUBMISSION_UTF16_LIMIT': return '展开内容的 Java 字符数超过上限';
    case 'SUBMISSION_UTF8_LIMIT': return '展开内容的 UTF-8 字节数超过 1 MiB';
    case undefined: return '';
  }
}

export function checkpointAction(
  text: string,
  key: {readonly upArrow?: boolean; readonly downArrow?: boolean},
  panelOpen: boolean,
): 'list' | 'previous' | 'next' | 'diff' | 'undo' | undefined {
  if (panelOpen && key.upArrow === true) {
    return 'previous';
  }
  if (panelOpen && key.downArrow === true) {
    return 'next';
  }
  switch (text) {
    case 'C': return 'list';
    case 'D': return panelOpen ? 'diff' : undefined;
    case 'U': return panelOpen ? 'undo' : undefined;
    default: return undefined;
  }
}

export function adjacentCheckpointId(
  checkpoints: readonly CheckpointView[],
  selectedCheckpointId: string | undefined,
  delta: -1 | 1,
): string | undefined {
  if (checkpoints.length === 0) {
    return undefined;
  }
  const selected = checkpoints.findIndex(
    item => item.checkpointId === selectedCheckpointId,
  );
  const origin = selected < 0 ? (delta > 0 ? -1 : 0) : selected;
  const index = (origin + delta + checkpoints.length) % checkpoints.length;
  return checkpoints[index]?.checkpointId;
}

export function undoConfirmation(
  text: string,
): 'confirm' | 'cancel' | undefined {
  if (text === 'Y') {
    return 'confirm';
  }
  if (text.toLowerCase() === 'n' || text === '\u001b') {
    return 'cancel';
  }
  return undefined;
}

function checkpointPhaseLabel(phase: CheckpointPhase): string {
  switch (phase) {
    case 'create_prepared': return '创建准备中';
    case 'create_journal_uncertain': return '创建记录不确定';
    case 'created': return '等待 Tool 结果';
    case 'post_prepared': return '结果准备中';
    case 'post_journal_uncertain': return '结果记录不确定';
    case 'completed_present': return '已完成（文件存在）';
    case 'completed_absent': return '已完成（文件不存在）';
    case 'undo_prepared': return 'Undo 状态不确定';
    case 'undo_applied': return 'Undo 已应用待确认';
    case 'undo_journal_uncertain': return 'Undo 记录不确定';
    case 'undone': return '已 Undo';
  }
}

export function approvalDecision(
  text: string,
): 'allow_once' | 'allow_session' | 'deny' | undefined {
  const normalized = text.toLowerCase();
  if (normalized === 'y') {
    return 'allow_once';
  }
  if (normalized === 'a') {
    return 'allow_session';
  }
  if (normalized === 'n') {
    return 'deny';
  }
  return undefined;
}

export function decideInterrupt(
  phase: ReturnType<typeof reduceTuiState>['phase'],
  activeRunId: string | undefined,
  cancelPending = false,
): 'cancel' | 'terminate' | 'shutdown' {
  if (phase === 'running' && activeRunId !== undefined) {
    return cancelPending ? 'terminate' : 'cancel';
  }
  return 'shutdown';
}

/**
 * 连接建立期间允许预先编辑；运行期间也保留本地输入，以便提交普通 steering。
 */
export function canEditInput(
  phase: ReturnType<typeof reduceTuiState>['phase'],
): boolean {
  return phase === 'connecting' || phase === 'ready' || phase === 'running';
}

export function editInput(
  current: string,
  text: string,
  key: {readonly backspace: boolean; readonly ctrl: boolean; readonly meta: boolean},
): string {
  if (key.backspace) {
    return removeLastCodePoint(current);
  }
  return !key.ctrl && !key.meta && text.length > 0
    ? appendInput(current, text)
    : current;
}
