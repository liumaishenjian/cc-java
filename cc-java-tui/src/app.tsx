import {useEffect, useReducer, useRef, useState} from 'react';
import {Box, Text, useApp, useInput, usePaste, useWindowSize} from 'ink';
import {initialTuiState, reduceTuiState} from './state.js';
import type {ProtocolEvent} from './protocol.js';

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
  shutdown(): Promise<void>;
  terminate(): void;
}

export const MAX_INPUT_CHARS = 8_192;

/**
 * S02 最小 React/Ink 终端 Surface。
 *
 * 组件只把键盘动作转换成命令并渲染 Reducer 投影；Java Headless 始终拥有 Session、
 * Run、取消与终态。S04 以前本组件不会执行 Tool 或展示审批。
 */
export function AgentTui({client}: AgentTuiProps) {
  const [state, dispatch] = useReducer(reduceTuiState, initialTuiState);
  const [input, setInput] = useState('');
  const cancelPending = useRef(false);
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
    if (state.phase === 'ready') {
      setInput(current => appendInput(current, pasted));
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
    if (state.phase !== 'ready') {
      return;
    }
    if (key.return) {
      const prompt = input.trim();
      if (prompt.length > 0) {
        const requestId = client.startRun(prompt);
        dispatch({type: 'run.submitted', requestId, prompt});
        setInput('');
      }
      return;
    }
    if (key.backspace) {
      setInput(current => editInput(current, text, key));
      return;
    }
    if (!key.ctrl && !key.meta && text.length > 0) {
      setInput(current => editInput(current, text, key));
    }
  }, {isActive: state.phase === 'ready' || state.phase === 'running'});

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
  return (
    <Box flexDirection="column" width={Math.max(20, columns)}>
      <Text bold color="cyan">cc-java S02 terminal spike</Text>
      <Text dimColor>状态：{state.phase}　Ctrl+C：取消 Run；再次按下强制退出</Text>
      {state.runs.map(run => (
        <Box key={run.requestId} flexDirection="column" marginTop={1}>
          <Text color="green">&gt; {run.prompt}</Text>
          <Text>{run.text}</Text>
          <Text dimColor>[{run.status}]</Text>
        </Box>
      ))}
      {state.notice === undefined ? null : <Text color="red">{state.notice}</Text>}
      <Box marginTop={1}>
        <Text color="yellow">&gt; </Text>
        <Text>{state.phase === 'ready' ? input : ''}</Text>
        {state.phase === 'running' ? <Text dimColor>模型输出中…</Text> : null}
      </Box>
    </Box>
  );
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
