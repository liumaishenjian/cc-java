import {useEffect, useReducer, useRef, useState} from 'react';
import {Box, Text, useApp, useInput, usePaste, useWindowSize} from 'ink';
import {initialTuiState, reduceTuiState} from './state.js';
import type {ProtocolEvent} from './protocol.js';
import type {ApprovalView, RunView} from './state.js';
import {AssistantMarkdown} from './assistant-markdown.js';
import {ToolActivityGroup} from './tool-activity.js';

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
  resolveApproval(approvalId: string, decision: 'allow_once' | 'deny'): string;
  shutdown(): Promise<void>;
  terminate(): void;
}

export const MAX_INPUT_CHARS = 8_192;

/**
 * S03 最小 React/Ink 终端 Surface。
 *
 * 组件只把键盘动作转换成命令并渲染 Reducer 投影；Java Headless 始终拥有 Session、
 * Run、Tool 与终态。当前只展示脱敏 Tool 摘要，不执行 Tool；审批仍属于 S04。
 */
export function AgentTui({client}: AgentTuiProps) {
  const [state, dispatch] = useReducer(reduceTuiState, initialTuiState);
  const [input, setInput] = useState('');
  const inputRef = useRef('');
  const cancelPending = useRef(false);
  const pendingApproval = state.runs.findLast(
    run => run.status === 'running',
  )?.pendingApproval;
  const {exit} = useApp();
  const {columns} = useWindowSize();

  useEffect(() => {
    const offEvent = client.onEvent(event => {
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
      dispatch({type: 'transport.failed', message});
    });
    const offExit = client.onExit(() => {
      cancelPending.current = false;
      dispatch({type: 'closed'});
      exit();
    });
    client.initialize();
    return () => {
      offEvent();
      offFailure();
      offExit();
      client.terminate();
    };
  }, [client, exit]);

  usePaste(pasted => {
    if (canEditInput(state.phase)) {
      const next = appendInput(inputRef.current, pasted);
      inputRef.current = next;
      setInput(next);
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
    if (!canEditInput(state.phase)) {
      return;
    }
    if (key.return) {
      if (state.phase !== 'ready') {
        return;
      }
      const prompt = inputRef.current.trim();
      if (prompt.length > 0) {
        const requestId = client.startRun(prompt);
        dispatch({type: 'run.submitted', requestId, prompt});
        inputRef.current = '';
        setInput('');
      }
      return;
    }
    if (key.backspace) {
      const next = editInput(inputRef.current, text, key);
      inputRef.current = next;
      setInput(next);
      return;
    }
    if (!key.ctrl && !key.meta && text.length > 0) {
      const next = editInput(inputRef.current, text, key);
      inputRef.current = next;
      setInput(next);
    }
  }, {
    isActive: state.phase === 'connecting'
      || state.phase === 'ready'
      || state.phase === 'running',
  });

  return <AgentView state={state} input={input} columns={columns} />;
}

export interface AgentViewProps {
  readonly state: ReturnType<typeof reduceTuiState>;
  readonly input: string;
  readonly columns: number;
}

/**
 * 纯展示组件，使宽字符、窄窗口和各 Run 终态无需真实终端即可验证。
 */
export function AgentView({state, input, columns}: AgentViewProps) {
  const width = Math.max(20, columns);
  return (
    <Box flexDirection="column" width={width}>
      <Box>
        <Text bold color="cyan">cc-java</Text>
        <Text color="blue">  S04</Text>
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
        </Box>
      ))}
      {state.notice === undefined ? null : (
        <Box marginTop={1}>
          <Text color="red">✗ {state.notice}</Text>
        </Box>
      )}
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
        <Text>{canEditInput(state.phase) ? input : ''}</Text>
        {state.phase === 'running'
          ? <Text dimColor>正在处理…  Ctrl+C 取消</Text>
          : input.length === 0
            ? <Text dimColor>{inputHint(state.phase)}</Text>
            : null}
      </Box>
    </Box>
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
          : 'Y 允许本次　N 拒绝　Ctrl+C 取消 Run'}
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
  return phase === 'connecting' ? '连接中，可以先输入任务' : '输入任务，Enter 发送';
}

export function approvalDecision(
  text: string,
): 'allow_once' | 'deny' | undefined {
  const normalized = text.toLowerCase();
  if (normalized === 'y') {
    return 'allow_once';
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
 * 连接建立期间允许预先编辑输入，但只有 ready 状态才能提交给 Java。
 */
export function canEditInput(
  phase: ReturnType<typeof reduceTuiState>['phase'],
): boolean {
  return phase === 'connecting' || phase === 'ready';
}

export function editInput(
  current: string,
  text: string,
  key: {readonly backspace: boolean; readonly ctrl: boolean; readonly meta: boolean},
): string {
  if (key.backspace) {
    return Array.from(current).slice(0, -1).join('');
  }
  return !key.ctrl && !key.meta && text.length > 0
    ? appendInput(current, text)
    : current;
}

/**
 * 按 Unicode Code Point 限制输入长度，避免一次大段 Paste 无界占用 TUI 内存。
 */
export function appendInput(current: string, text: string): string {
  const remaining = MAX_INPUT_CHARS - Array.from(current).length;
  if (remaining <= 0 || text.length === 0) {
    return current;
  }
  return current + Array.from(text).slice(0, remaining).join('');
}
