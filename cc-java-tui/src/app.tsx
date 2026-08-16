import {useEffect, useReducer, useRef, useState} from 'react';
import {Box, Text, useApp, useInput, usePaste, useWindowSize} from 'ink';
import {initialTuiState, reduceTuiState} from './state.js';
import type {ProtocolEvent} from './protocol.js';
import type {ProviderLoginRequest, ProviderLoginResult} from './stdio-client.js';
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
  activeFileMention,
  boundedFileSuggestions,
  fileMentionEnabled,
  type ActiveFileMention,
} from './file-mention.js';
import {
  parseSlashCommand,
  renderSlashResult,
  slashCommandUsage,
} from './slash-command.js';
import {
  independentProviderControlId,
  isIndependentProviderControlResult,
} from './provider-control-id.js';
import {
  applyConnectWizardResult,
  beginConnectWizard,
  completeConnectWizardLogin,
  connectWizardInitialControls,
  connectWizardProviderOptions,
  connectedProfile,
  editConnectWizardText,
  enterConnectWizard,
  escapeConnectWizard,
  modelsForProvider,
  moveConnectWizard,
  providerName,
  type ConnectWizardState,
} from './connect-wizard.js';
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
  invokeSkill?(name: string, arguments_: string): string;
  cancelRun(): string;
  resolveApproval(
    approvalId: string,
    decision: 'allow_once' | 'allow_session' | 'deny',
  ): string;
  listCheckpoints?(): string;
  checkpointDiff?(checkpointId: string): string;
  undoCheckpoint?(checkpointId: string, confirmed: boolean): string;
  inspectTask?(taskId: string): string;
  waitTask?(taskId: string, timeoutMillis: number): string;
  cancelTask?(taskId: string): string;
  keepTaskWorktree?(taskId: string): string;
  removeTaskWorktree?(taskId: string): string;
  sessionCommand?(commandId: string, intent: 'help' | 'clear' | 'compact' | 'context' | 'doctor' | 'model' | 'permissions' | 'resume', arguments_: Readonly<Record<string, unknown>>): string;
  providerControl?(controlId: string, intent: 'providers.add' | 'auth.list' | 'auth.probe' | 'auth.logout' | 'models.list' | 'models.use' | 'models.add' | 'models.remove', arguments_: Readonly<Record<string, unknown>>): string;
  providerLogin?(request: ProviderLoginRequest): Promise<ProviderLoginResult>;
  cancelProviderLogin?(): void;
  suggestFiles?(query: string): string;
  shutdown(): Promise<void>;
  terminate(): void;
}

export function renderProviderControlResult(
  intent: string, status: string, code: string, result: Readonly<Record<string, unknown>>,
): string {
  if (status !== 'succeeded') return `Provider 控制未执行：${providerControlError(code)}（${code}）`;
  if (intent === 'auth.list' && Array.isArray(result.profiles)) {
    const lines = result.profiles.flatMap(item => {
      if (typeof item !== 'object' || item === null || Array.isArray(item)) return [];
      const profile = item as Record<string, unknown>;
      const flags = [profile.providerDefault === true ? '默认' : '',
        typeof profile.lastProbeCode === 'string' ? `探测 ${profile.lastProbeCode}` : '']
        .filter(Boolean).join(' · ');
      return [`${String(profile.providerId)}/${String(profile.profileId)} · ${String(profile.authMethod)}/${String(profile.refKind)} · ${String(profile.localStatus)}${flags.length === 0 ? '' : ` · ${flags}`}`];
    });
    return ['Credential profiles', ...(lines.length === 0 ? ['（无）'] : lines)].join('\n');
  }
  if (intent === 'models.list' && Array.isArray(result.models)) {
    const lines = result.models.flatMap(item => typeof item === 'object' && item !== null && !Array.isArray(item)
      ? [`${String((item as Record<string, unknown>).providerId)}/${String((item as Record<string, unknown>).modelId)}${(item as Record<string, unknown>).providerDefault === true ? ' · 默认' : ''}`]
      : []);
    return ['Models', ...(lines.length === 0 ? ['（无）'] : lines)].join('\n');
  }
  if (intent === 'models.add') {
    return `本地模型已添加：${String(result.providerId)}/${String(result.modelId)}${result.setDefault === true ? ' · 已设为持久默认' : ''}`;
  }
  if (intent === 'models.remove') {
    return `本地模型已移除：${String(result.providerId)}/${String(result.modelId)}`;
  }
  if (intent === 'models.use') {
    return `下一 Run 模型：${String(result.providerId)}/${String(result.modelId)} · profile ${String(result.profileId)}${result.setDefault === true ? ' · 已设为持久默认' : ''}`;
  }
  if (intent === 'auth.probe') {
    return `认证探测：${String(result.providerId)}/${String(result.profileId)} · ${String(result.modelId)} · ${String(result.outcome)} · ${String(result.probedAt)}`;
  }
  if (intent === 'auth.logout') {
    return `本机 credential 已删除：${String(result.providerId)}/${String(result.profileId)}；Provider 侧 credential 未撤销`;
  }
  return 'Provider 控制已完成';
}

function providerControlError(code: string): string {
  const labels: Readonly<Record<string, string>> = {
    AUTH_PROFILE_REQUIRED: '需要先选择 credential profile',
    AUTH_PROFILE_UNKNOWN: 'credential profile 不存在',
    AUTH_SECRET_UNAVAILABLE: '本机 secret 不可用',
    AUTH_STORE_INSECURE: '本机 credential store 未通过安全检查',
    AUTH_STORE_LOCKED: '本机 credential store 正在被占用',
    AUTH_STORE_CORRUPT: '本机 credential store 已损坏',
    AUTH_PROBE_REJECTED: 'Provider 拒绝该 credential',
    AUTH_PROBE_RATE_LIMITED: 'Provider 对探测限流',
    AUTH_PROBE_UNSUPPORTED: '该 Provider 不支持安全探测',
    AUTH_PROBE_UNREACHABLE: '探测目标不可达或响应无效',
    AUTH_PROBE_TIMED_OUT: '认证探测超时',
    AUTH_CANCELLED: '操作已取消',
    AUTH_LOGOUT_DRAIN_FAILED: '活动 Run 未能在期限内停止，credential 未删除',
    AUTH_STORE_DELETE_FAILED: '运行资源已停止，但本机 credential 删除失败',
    MODEL_UNKNOWN: '模型不在本地 Provider 目录中',
    AUTH_TRANSACTION_CONFLICT: '当前有活动 Run 或本机状态已变化',
    INVALID_ARGUMENT: '参数无效',
  };
  return labels[code] ?? '请求被安全拒绝';
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
  const [providerLoginActive, setProviderLoginActive] = useState(false);
  const [connectWizard, setConnectWizard] = useState<ConnectWizardState | undefined>(undefined);
  const composerRef = useRef(composer);
  const historySessionIdRef = useRef<string | undefined>(undefined);
  const pendingSteeringPromptsRef = useRef(new Map<string, string>());
  const pendingSubmissionsRef = useRef(new Map<string, {
    readonly composer: ComposerState;
    readonly label: string;
  }>());
  const cancelPending = useRef(false);
  const transportFailureRef = useRef(false);
  const nextCommandNumber = useRef(1);
  const nextConnectGeneration = useRef(1);
  const connectWizardRef = useRef<ConnectWizardState | undefined>(undefined);
  const fileSuggestionRef = useRef<{
    readonly requestId: string;
    readonly query: string;
    readonly mention: ActiveFileMention;
  } | undefined>(undefined);
  const fileSuggestionTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
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
  const replaceConnectWizard = (next: ConnectWizardState | undefined) => {
    connectWizardRef.current = next;
    setConnectWizard(next);
  };
  const applyComposer = (action: ComposerAction) => {
    const transition = reduceComposer(composerRef.current, action, composerLayout);
    replaceComposer(transition.state);
    return transition;
  };
  const sendIndependentProviderControl = (
    intent: 'providers.add' | 'auth.list' | 'auth.probe' | 'auth.logout' | 'models.list' | 'models.use' | 'models.add' | 'models.remove',
    arguments_: Readonly<Record<string, unknown>>,
  ) => {
    if (client.providerControl === undefined) return undefined;
    const controlId = independentProviderControlId(nextCommandNumber.current++, intent);
    client.providerControl(controlId, intent, arguments_);
    return controlId;
  };
  const acceptCurrentCompletion = () => {
    const current = composerRef.current;
    const selected = current.completionCandidates[current.completionIndex ?? 0];
    if (selected?.startsWith('@')) {
      const mention = activeFileMention(current);
      if (mention === undefined) {
        applyComposer({type: 'CloseCompletion'});
        return;
      }
      const replacement = reduceComposer(current, {
        type: 'ReplaceRange',
        startGrapheme: mention.startGrapheme,
        endGrapheme: mention.endGrapheme,
        text: selected,
      }, composerLayout);
      replaceComposer({...replacement.state, completionCandidates: [], completionIndex: undefined});
      fileSuggestionRef.current = undefined;
      return;
    }
    applyComposer({type: 'AcceptCompletion'});
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
      if (event.type === 'file.suggestions') {
        const pending = fileSuggestionRef.current;
        const mention = activeFileMention(composerRef.current);
        if (pending !== undefined
          && event.requestId === pending.requestId
          && event.payload.query === pending.query
          && mention !== undefined
          && mention.query === pending.query
          && mention.startGrapheme === pending.mention.startGrapheme) {
          const candidates = boundedFileSuggestions(event.payload.candidates as readonly string[]);
          const completion = reduceComposer(
            composerRef.current, {type: 'SetCompletions', candidates}, composerLayout,
          );
          replaceComposer(completion.state);
        }
      }
      if (event.type === 'provider.control.result') {
        const payload = event.payload;
        const currentWizard = connectWizardRef.current;
        const nextWizard = currentWizard === undefined ? undefined : applyConnectWizardResult(currentWizard, {
          controlId: String(payload.controlId), intent: String(payload.intent), status: String(payload.status),
          code: String(payload.code), result: payload.result as Readonly<Record<string, unknown>>,
        });
        if (currentWizard !== undefined && nextWizard !== currentWizard) {
          replaceConnectWizard(nextWizard);
        } else if (isIndependentProviderControlResult(String(payload.controlId), String(payload.intent))) {
          dispatch({type: 'slash.notice', message: renderProviderControlResult(
            String(payload.intent), String(payload.status), String(payload.code),
            payload.result as Readonly<Record<string, unknown>>,
          )});
          if (payload.intent === 'models.add' && payload.status === 'succeeded') {
            sendIndependentProviderControl('models.list', {});
          }
        }
      }
      if (event.type === 'session.command.result') {
        if (event.payload.intent === 'resume' && event.payload.status === 'succeeded') {
          historySessionIdRef.current = event.sessionId;
          fileSuggestionRef.current = undefined;
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
        if (fileSuggestionRef.current?.requestId === event.requestId) {
          fileSuggestionRef.current = undefined;
          if (composerRef.current.completionCandidates.some(candidate => candidate.startsWith('@'))) {
            applyComposer({type: 'CloseCompletion'});
          }
        }
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
      if (!transportFailureRef.current) {
        transportFailureRef.current = true;
        dispatch({type: 'transport.failed', message});
      }
    });
    const offExit = client.onExit(() => {
      cancelPending.current = false;
      pendingSteeringPromptsRef.current.clear();
      pendingSubmissionsRef.current.clear();
      if (transportFailureRef.current) {
        return;
      }
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

  useEffect(() => {
    if (fileSuggestionTimerRef.current !== undefined) {
      clearTimeout(fileSuggestionTimerRef.current);
      fileSuggestionTimerRef.current = undefined;
    }
    const mention = fileMentionEnabled(composerRef.current)
      ? activeFileMention(composerRef.current) : undefined;
    if (mention === undefined || client.suggestFiles === undefined || state.phase === 'connecting') {
      fileSuggestionRef.current = undefined;
      if (composerRef.current.completionCandidates.some(candidate => candidate.startsWith('@'))) {
        applyComposer({type: 'CloseCompletion'});
      }
      return;
    }
    fileSuggestionTimerRef.current = setTimeout(() => {
      try {
        const requestId = client.suggestFiles!(mention.query);
        fileSuggestionRef.current = {requestId, query: mention.query, mention};
      } catch {
        fileSuggestionRef.current = undefined;
      }
    }, 75);
    return () => {
      if (fileSuggestionTimerRef.current !== undefined) {
        clearTimeout(fileSuggestionTimerRef.current);
        fileSuggestionTimerRef.current = undefined;
      }
    };
  }, [composer.text, composer.cursorGrapheme, client, state.phase]);

  usePaste(pasted => {
    if (canEditInput(state.phase)) {
      applyComposer({type: 'Paste', text: pasted});
    }
  });

  useInput((text, key) => {
    if (key.ctrl && text.toLowerCase() === 'c') {
      if (providerLoginActive) {
        client.cancelProviderLogin?.();
        return;
      }
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
    const currentWizard = connectWizardRef.current;
    if (currentWizard !== undefined) {
      if (key.escape) {
        const next = escapeConnectWizard(currentWizard);
        replaceConnectWizard(next);
        return;
      }
      if (key.upArrow || key.downArrow) {
        replaceConnectWizard(moveConnectWizard(currentWizard, key.upArrow ? -1 : 1));
        return;
      }
      const textPage = currentWizard.phase === 'env-input'
        || currentWizard.phase === 'custom-name' || currentWizard.phase === 'custom-id'
        || currentWizard.phase === 'custom-base-url' || currentWizard.phase === 'custom-model';
      if (textPage) {
        if (key.backspace || key.delete) {
          replaceConnectWizard(editConnectWizardText(currentWizard, {kind: 'backspace'}));
          return;
        }
        if (!key.ctrl && !key.meta && text.length > 0 && !key.return) {
          replaceConnectWizard(editConnectWizardText(currentWizard, {kind: 'append', text}));
          return;
        }
      }
      if (key.return) {
        const action = enterConnectWizard(currentWizard);
        replaceConnectWizard(action.state);
        if (action.kind === 'control') {
          try {
            client.providerControl?.(action.controlId, action.intent, action.arguments);
          } catch {
            replaceConnectWizard({...action.state, phase: 'error', message: '当前连接未接受该操作',
              returnTo: action.intent === 'providers.add' ? 'custom-confirm'
                : action.intent === 'models.use' ? 'models' : 'connected'});
          }
        } else if (action.kind === 'login') {
          if (client.providerLogin === undefined || providerLoginActive) {
            replaceConnectWizard({...action.state, phase: 'error', message: '当前启动器不支持安全凭证输入', returnTo: 'auth'});
          } else {
            setProviderLoginActive(true);
            void client.providerLogin({providerId: action.providerId, profileId: action.profileId,
              secretSource: action.secretSource, setDefault: true,
              ...(action.environmentName === undefined ? {} : {environmentName: action.environmentName})})
              .then(result => {
                const latest = connectWizardRef.current;
                if (latest === undefined || latest.generation !== action.state.generation) return;
                const completed = completeConnectWizardLogin(latest, result.status);
                replaceConnectWizard(completed.state);
                if (completed.refreshControlId !== undefined) {
                  try {
                    client.providerControl?.(completed.refreshControlId, 'auth.list', {});
                  } catch {
                    replaceConnectWizard({...completed.state, phase: 'error', message: '凭证已保存，但状态刷新失败', returnTo: 'auth'});
                  }
                }
              }).catch(() => {
                const latest = connectWizardRef.current;
                if (latest !== undefined && latest.generation === action.state.generation) {
                  replaceConnectWizard({...latest, phase: 'error', message: '安全登录桥未能启动', returnTo: 'auth'});
                }
              }).finally(() => setProviderLoginActive(false));
          }
        }
        return;
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
        acceptCurrentCompletion();
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
      if (slash.kind === 'task') {
        const {action, taskId, timeoutMillis} = slash.command;
        try {
          if (action === 'wait' && client.waitTask !== undefined) client.waitTask(taskId, timeoutMillis ?? 30_000);
          else if (action === 'cancel' && client.cancelTask !== undefined) client.cancelTask(taskId);
          else if (action === 'keep' && client.keepTaskWorktree !== undefined) client.keepTaskWorktree(taskId);
          else if (action === 'remove' && client.removeTaskWorktree !== undefined) client.removeTaskWorktree(taskId);
          else throw new Error('unsupported');
        } catch {
          dispatch({type: 'slash.notice', message: '当前连接或状态不支持子任务动作'});
          return;
        }
      } else if (slash.kind === 'provider-control') {
        const {intent, arguments: arguments_} = slash.command;
        if (intent === 'connect') {
          const action = String(arguments_.action);
          if (action === 'providers') {
            if (client.providerControl === undefined) {
              dispatch({type: 'slash.notice', message: '当前连接不支持 Provider 控制命令'});
              return;
            }
            const wizard = beginConnectWizard(nextConnectGeneration.current++);
            replaceConnectWizard(wizard);
            for (const [controlId, controlIntent] of connectWizardInitialControls(wizard)) {
              client.providerControl(controlId, controlIntent as 'models.list' | 'auth.list', {});
            }
          } else if (action === 'login') {
            if (state.phase === 'running') {
              dispatch({type: 'slash.notice', message: 'Agent Run 运行中，结束或取消后再连接 Provider'});
              return;
            }
            if (client.providerLogin === undefined || providerLoginActive) {
              dispatch({type: 'slash.notice', message: providerLoginActive
                ? '已有 Provider 登录正在执行；Ctrl+C 可取消'
                : '当前启动器不支持安全 Provider 登录桥'});
              return;
            }
            const request: ProviderLoginRequest = {
              providerId: String(arguments_.providerId),
              profileId: String(arguments_.profileId),
              secretSource: arguments_.secretSource === 'env' ? 'env' : 'store',
              ...(typeof arguments_.environmentName === 'string'
                ? {environmentName: arguments_.environmentName} : {}),
            };
            setProviderLoginActive(true);
            dispatch({type: 'slash.notice', message: request.secretSource === 'store'
              ? '已暂停 TUI 输入；请在 Java 提示中输入 API key（输入将被遮蔽，Ctrl+C 取消）'
              : `正在保存 ENV 引用 ${request.environmentName ?? ''}；TUI 不读取环境值`});
            void client.providerLogin(request).then(result => {
              if (result.status === 'succeeded') {
                dispatch({type: 'slash.notice', message: 'Provider profile 已保存，正在刷新 credential 列表'});
                sendIndependentProviderControl('auth.list', {});
              } else {
                const label = result.status === 'cancelled' ? '已取消'
                  : result.status === 'timed_out' ? '已超时并终止子进程'
                    : `失败（exit ${result.exitCode ?? 'unknown'}）`;
                dispatch({type: 'slash.notice', message: `Provider 登录${label}；未通过 TUI 传输 secret`});
              }
            }).catch(() => {
              dispatch({type: 'slash.notice', message: 'Provider 登录桥启动失败；未通过 TUI 传输 secret'});
            }).finally(() => setProviderLoginActive(false));
          }
        } else if (client.providerControl === undefined) {
          dispatch({type: 'slash.notice', message: '当前连接不支持 Provider 控制命令'});
          return;
        } else {
          const action = String(arguments_.action);
          const wireIntent = intent === 'auth' ? `auth.${action}` : `models.${action}`;
          const {action: _ignored, ...wireArguments} = arguments_;
          sendIndependentProviderControl(
            wireIntent as 'auth.list' | 'auth.probe' | 'auth.logout' | 'models.list' | 'models.use' | 'models.add' | 'models.remove',
            wireArguments,
          );
        }
      } else if (slash.kind === 'command') {
        if (client.sessionCommand === undefined) {
          dispatch({type: 'slash.notice', message: '当前连接不支持 Slash 命令'});
          return;
        }
        client.sessionCommand(`tui-command-${nextCommandNumber.current++}`, slash.command.intent, slash.command.arguments);
      } else if (slash.kind === 'skill') {
        if (client.invokeSkill === undefined || state.phase !== 'ready') {
          dispatch({type: 'slash.notice', message: '当前连接或状态不支持 Skill 调用'});
          return;
        }
        try {
          const requestId = client.invokeSkill(slash.name, slash.arguments);
          const label = submittedComposerLabel(submission.state);
          pendingSubmissionsRef.current.set(requestId, {composer: submission.state, label});
          replaceComposer(beginPendingComposer(submission.state));
          dispatch({type: 'run.submitted', requestId, prompt: label});
        } catch {
          dispatch({type: 'slash.notice', message: 'Skill 调用未被 Java 接受'});
          return;
        }
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
      if (slash.kind === 'command' || slash.kind === 'provider-control' || slash.kind === 'task') replaceComposer(acceptSubmittedComposer(submission.state));
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
      if (current.completionCandidates.length > 0) acceptCurrentCompletion();
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
    isActive: !providerLoginActive && state.phase !== 'closing',
  });

  return <AgentView
    state={state}
    composer={composer}
    columns={columns}
    rows={rows}
    composerLayout={composerLayout}
    {...(connectWizard === undefined ? {} : {connectWizard})}
  />;
}

export interface AgentViewProps {
  readonly state: ReturnType<typeof reduceTuiState>;
  readonly composer?: ComposerState;
  /** 兼容纯展示测试；生产路径使用 composer。 */
  readonly input?: string;
  readonly columns: number;
  /** 终端可用行数；省略时保持既有无界纯展示测试兼容。 */
  readonly rows?: number;
  readonly composerLayout?: ComposerLayout;
  readonly connectWizard?: ConnectWizardState;
}

/**
 * 纯展示组件，使宽字符、窄窗口和各 Run 终态无需真实终端即可验证。
 */
export function AgentView({state, composer, input = '', columns, rows, composerLayout, connectWizard}: AgentViewProps) {
  const width = Math.max(20, columns);
  const viewportRows = rows === undefined
    ? undefined
    : Math.max(5, Math.floor(rows));
  const effectiveComposer = composer ?? reduceComposer(
    createComposerState(4), {type: 'InsertText', text: input}, {width: Math.max(1, width - 6), height: 4},
  ).state;
  const layout = composerLayout ?? {width: Math.max(1, width - 6), height: 4};
  const projection = projectComposer(effectiveComposer, layout);
  const renderedLines = renderComposerViewport(effectiveComposer, layout);
  const candidates = canEditInput(state.phase) ? effectiveComposer.completionCandidates : [];
  const selectedCompletion = effectiveComposer.completionIndex ?? 0;
  const composerFixedRows = renderedLines.length
    + 4
    + (effectiveComposer.validationCode === undefined ? 0 : 1);
  const candidateRegionRows = viewportRows === undefined
    ? undefined
    : Math.max(0, viewportRows - 1 - composerFixedRows);
  const visibleCandidates = completionWindow(
    candidates,
    selectedCompletion,
    candidateRegionRows === undefined ? candidates.length : Math.max(0, candidateRegionRows - 1),
  );
  return (
    <Box
      flexDirection="column"
      width={width}
      height={viewportRows}
      overflow={viewportRows === undefined ? 'visible' : 'hidden'}
    >
      <Box flexShrink={0}>
        <Text bold color="cyan">cc-java</Text>
        <Text color="blue">  S15</Text>
        <Text dimColor>  {phaseLabel(state.phase)}</Text>
      </Box>
      <Box
        flexDirection="column"
        flexGrow={1}
        flexShrink={1}
        overflow="hidden"
        justifyContent={state.runs.length === 0 ? 'flex-start' : 'flex-end'}
      >
        <Box flexDirection="column" flexShrink={0}>
      {state.notice === undefined ? null : (
        <Box marginTop={1}>
          <Text color="yellow">• {state.notice}</Text>
        </Box>
      )}
      {state.phase === 'failed' ? (
        <Box marginTop={1}>
          <Text color="red">连接已关闭，Ctrl+C退出</Text>
        </Box>
      ) : null}
      {connectWizard === undefined ? null : <ConnectWizardPanel state={connectWizard} />}
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
          {run.status === 'running' && run.tools.length === 0 && run.text.length === 0 ? (
            <Box marginTop={1} marginLeft={2}>
              <Text color="yellow">◌ 等待模型响应…</Text>
            </Box>
          ) : null}
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
      <ChildTaskPanel state={state} />
      <CheckpointPanel state={state} />
      {state.phase === 'ready' ? (
        <Box marginTop={1} flexDirection="column">
          {state.runs.length === 0 ? (
            <>
              <Text color="cyan">输入 /help 查看命令；输入 /connect 查看并配置 Provider。</Text>
              <Text dimColor>若 Provider/profile/model 未就绪，先运行 /connect；普通任务会快速安全失败并恢复输入。</Text>
            </>
          ) : null}
          <Text dimColor>
            C 列表　↑/↓ 选择　D Diff　U 请求 Undo
          </Text>
          <Text dimColor>
            Undo 必须针对当前 Checkpoint 二次确认，绝不自动重放。
          </Text>
        </Box>
      ) : null}
        </Box>
      </Box>
      <Box flexDirection="column" flexShrink={0}>
      <Box
        borderStyle="round"
        borderColor={state.runs.findLast(run => run.status === 'running')
          ?.pendingApproval === undefined
          ? state.phase === 'ready' ? 'cyan' : 'gray'
          : 'yellow'}
        paddingX={1}
      >
        <Text color="cyan">❯ </Text>
        {canEditInput(state.phase) && connectWizard === undefined ? (
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
        {connectWizard !== undefined
          ? <Text dimColor>正在配置连接，请按上方提示操作</Text>
          : state.phase === 'running'
          ? <Text dimColor>
              正在处理… Enter 排队补充{(state.steeringQueueDepth ?? 0) > 0
                ? `（${state.steeringQueueDepth}/100）` : ''}　Ctrl+C 取消
            </Text>
          : effectiveComposer.text.length === 0
            ? <Text dimColor>{inputHint(state.phase)}</Text>
            : null}
      </Box>
      {connectWizard !== undefined || effectiveComposer.validationCode === undefined ? null : (
        <Text color="red">输入未接受：{validationMessage(effectiveComposer.validationCode)}</Text>
      )}
      {connectWizard === undefined ? <Text dimColor>
        光标 {projection.cursorRow - projection.viewportTop + 1}:{projection.cursorColumn + 1}
      </Text> : null}
      {connectWizard !== undefined || candidates.length === 0 || candidateRegionRows === 0 ? null : (
        <Box
          flexDirection="column"
          marginLeft={2}
          height={candidateRegionRows}
          overflow="hidden"
          flexShrink={1}
        >
          <Text dimColor>{candidates[0]?.startsWith('@')
            ? '文件建议 · ↑/↓ 选择 · Tab/Enter 补全 · Esc 关闭'
            : 'Slash 命令 · ↑/↓ 选择 · Tab/Enter 补全'}</Text>
          {visibleCandidates.map(({candidate, index}) => (
            <Text key={candidate} color={index === selectedCompletion ? 'cyan' : 'white'}>
              {index === selectedCompletion ? '❯ ' : '  '}{candidate.startsWith('@')
                ? candidate : slashCommandUsage(candidate)}
            </Text>
          ))}
        </Box>
      )}
      </Box>
    </Box>
  );
}

export function completionWindow(
  candidates: readonly string[],
  selectedIndex: number,
  maximumItems: number,
): readonly {readonly candidate: string; readonly index: number}[] {
  if (maximumItems <= 0 || candidates.length === 0) return [];
  const size = Math.min(candidates.length, Math.floor(maximumItems));
  const selected = Math.max(0, Math.min(candidates.length - 1, selectedIndex));
  const start = Math.max(0, Math.min(selected - Math.floor(size / 2), candidates.length - size));
  return candidates.slice(start, start + size).map((candidate, offset) => ({
    candidate,
    index: start + offset,
  }));
}

function ConnectWizardPanel({state}: {readonly state: ConnectWizardState}) {
  const loadStatus = [
    state.auth.status === 'pending' ? '连接状态加载中' : state.auth.status === 'failed' ? '连接状态暂不可用' : undefined,
    state.models.status === 'pending' ? '模型目录加载中' : state.models.status === 'failed' ? '模型目录暂不可用' : undefined,
  ].filter((value): value is string => value !== undefined);
  const options = (items: readonly string[], selected: number) => items.map((item, index) => (
    <Text key={item} color={index === selected ? 'cyan' : 'white'}>
      {index === selected ? '❯ ' : '  '}{item}
    </Text>
  ));
  let body;
  switch (state.phase) {
    case 'select-provider':
      body = <>{options(connectWizardProviderOptions(state).map(option => option.kind === 'provider'
        ? `${option.label}${providerStatus(state, option.providerId)}` : option.label), state.providerIndex)}</>;
      break;
    case 'select-auth':
      body = <>
        <Text bold>{providerName(state.providerId)}</Text>
        {options(['粘贴 API Key（推荐）', '使用环境变量（高级）', '返回'], state.optionIndex)}
      </>;
      break;
    case 'custom-name':
      body = <><Text bold>1/5 服务名称</Text><Text>当前：<Text color="cyan">{state.custom.displayName || '（空）'}</Text></Text>
        <Text dimColor>示例：团队模型网关</Text>{customValidation(state)}</>;
      break;
    case 'custom-id':
      body = <><Text bold>2/5 稳定 ID</Text><Text>当前：<Text color="cyan">{state.custom.providerId || '（空）'}</Text></Text>
        <Text dimColor>示例：team-gateway；可编辑建议值，只接受小写字母、数字和连字符。</Text>{customValidation(state)}</>;
      break;
    case 'custom-base-url':
      body = <><Text bold>3/5 HTTPS Base URL</Text><Text>当前：<Text color="cyan">{state.custom.baseUrl || '（空）'}</Text></Text>
        <Text dimColor>示例：https://gateway.example/v1</Text>{customValidation(state)}</>;
      break;
    case 'custom-model':
      body = <><Text bold>4/5 模型名</Text><Text>当前：<Text color="cyan">{state.custom.modelId || '（空）'}</Text></Text>
        <Text dimColor>示例：my-chat-model</Text>{customValidation(state)}</>;
      break;
    case 'custom-confirm':
      body = <><Text bold>5/5 确认服务配置</Text><Text>名称：{state.custom.displayName}</Text>
        <Text>ID：{state.custom.providerId}</Text><Text>Base URL：{state.custom.baseUrl}</Text>
        <Text>模型：{state.custom.modelId}</Text><Text dimColor>Enter 保存；Esc 返回修改模型。</Text></>;
      break;
    case 'saving-provider':
      body = <><Text color="yellow">正在保存，请稍候。</Text>
        <Text dimColor>保存结果返回前 Enter/Esc 不会离开此页或重复提交。</Text></>;
      break;
    case 'env-input':
      body = <>
        <Text>环境变量名称</Text><Text color="cyan">{state.value.length === 0 ? '例如 OPENAI_API_KEY' : state.value}</Text>
        {state.validation === undefined ? null : <Text color="red">{state.validation}</Text>}
        <Text dimColor>这里只保存名称，TUI 不读取变量值。</Text>
      </>;
      break;
    case 'logging-in':
      body = <><Text color="yellow">正在打开安全凭证输入…</Text>
        {state.secretSource === 'env' ? <Text dimColor>TUI 只传环境变量名称，不读取变量值。</Text> : null}</>;
      break;
    case 'refreshing-credential':
      body = <Text color="yellow">凭证已保存，正在刷新连接状态…</Text>;
      break;
    case 'select-model': {
      const models = modelsForProvider(state, state.providerId);
      body = models.length === 0
        ? <><Text color="yellow">当前本地模型目录为空。</Text><Text>请运行 codej models add --help 添加模型，然后重新打开 /connect。</Text></>
        : <><Text bold>选择 {providerName(state.providerId)} 模型</Text>
            {options(models.map(model => `${model.modelId}${model.providerDefault ? ' · 当前默认' : ''}`), state.modelIndex)}</>;
      break;
    }
    case 'select-connected-action':
      body = <><Text color="green">已连接 {providerName(state.providerId)}</Text>
        {options(['选择模型', '更新凭证', '退出登录（高级）'], state.optionIndex)}</>;
      break;
    case 'confirm-logout':
      body = <><Text color="red">确认退出 {providerName(state.providerId)}？</Text>
        <Text dimColor>只删除本机凭证，不会撤销 Provider 侧 Key；活动任务会先安全停止。</Text>
        {options(['确认退出登录', '取消'], state.optionIndex)}</>;
      break;
    case 'waiting-control':
      body = <Text color="yellow">{state.action === 'models.use' ? '正在选择模型…' : '正在安全退出登录…'}</Text>;
      break;
    case 'complete':
      body = state.providerName === undefined
        ? <><Text color="green">{state.message}</Text><Text>可以开始对话</Text></>
        : <><Text color="green">已连接 {state.providerName}</Text>
            <Text color="green">已选择 {state.modelId}</Text><Text>可以开始对话</Text></>;
      break;
    case 'error':
      body = <><Text color="red">{state.message}</Text><Text>Enter 返回上一步，Esc 也可返回。</Text></>;
      break;
    case 'cancelled':
      body = <Text>连接已取消</Text>;
      break;
  }
  return <Box flexDirection="column" borderStyle="round" borderColor="cyan" paddingX={1} flexShrink={0}>
    <Text bold color="cyan">连接模型服务</Text>
    {loadStatus.length === 0 ? null : <Text dimColor>{loadStatus.join(' · ')}</Text>}
    {body}
    <Text dimColor>↑/↓ 选择　Enter 确认　Esc 返回或关闭</Text>
  </Box>;
}

function customValidation(state: ConnectWizardState) {
  return state.custom.validation === undefined ? null : <Text color="red">{state.custom.validation}</Text>;
}

function providerStatus(state: ConnectWizardState, providerId: string): string {
  const profile = connectedProfile(state, providerId);
  const model = modelsForProvider(state, providerId).find(item => item.providerDefault);
  if (profile === undefined) return state.auth.status === 'pending' ? ' · 加载中' : ' · 未连接';
  return ` · 已连接${model === undefined ? '' : ` · 当前使用 ${model.modelId}`}`;
}

function ChildTaskPanel({state}: {readonly state: AgentViewProps['state']}) {
  const tasks = state.childTasks ?? [];
  if (tasks.length === 0) return null;
  return (
    <Box marginTop={1} flexDirection="column" borderStyle="round" borderColor="magenta" paddingX={1}>
      <Text bold color="magenta">Sub-Agent Tasks</Text>
      {tasks.map(task => (
        <Text key={task.taskId} color={task.status === 'succeeded' ? 'green'
          : task.status === 'failed' || task.status === 'cancelled' ? 'red' : 'yellow'}>
          {task.taskId} · {task.definitionId} · {task.status}
          {' · '}{task.modelTurns} turns / {task.toolCalls} tools / {task.estimatedTokens} tokens
          {' · '}{task.elapsedMillis}ms
          {task.worktreeDisposition === undefined ? '' : ` · worktree ${task.worktreeDisposition}`}
        </Text>
      ))}
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
      case 'configuration_required': return '尚未配置 Provider profile 或模型选择';
    }
  })();
  const status = summary.statusClass === undefined ? '' : `（${summary.statusClass}）`;
  const attempts = summary.attempts > 1 ? `，已尝试 ${summary.attempts} 次` : '';
  const action = summary.category === 'authentication_failed'
    ? '；请检查 Provider 凭证或权限'
    : summary.category === 'invalid_request'
      ? '；请检查模型与请求配置'
      : summary.category === 'configuration_required'
        ? '；请运行 /connect 或 codej auth login'
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
  if (phase === 'failed' || phase === 'closed') {
    return 'terminate';
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
