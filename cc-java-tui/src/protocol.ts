export const PROTOCOL_VERSION = 0;
export const MAX_LINE_BYTES = 64 * 1024;
export const MAX_IDENTIFIER_CHARS = 128;
export const MAX_CHECKPOINTS = 1_000;
export const MAX_CHECKPOINT_TARGET_CHARS = 1_024;
export const MAX_CHECKPOINT_DIFF_CHARS = 16 * 1_024;
export const MAX_CHECKPOINT_MESSAGE_CHARS = 1_024;

const CHECKPOINT_ID = /^checkpoint-[A-Za-z0-9-]+$/;
const CHECKPOINT_PHASES = new Set([
  'create_prepared',
  'create_journal_uncertain',
  'created',
  'post_prepared',
  'post_journal_uncertain',
  'completed_present',
  'completed_absent',
  'undo_prepared',
  'undo_applied',
  'undo_journal_uncertain',
  'undone',
]);
const CHECKPOINT_DIFF_STATUSES = new Set([
  'unchanged', 'changed', 'absent', 'conflict',
]);
const CHECKPOINT_UNDO_STATUSES = new Set([
  'restored', 'already_restored', 'conflict',
]);

const EVENT_TYPES = new Set([
  'initialized',
  'run.started',
  'model.text.delta',
  'approval.requested',
  'tool.started',
  'tool.output',
  'tool.completed',
  'tool.failed',
  'run.completed',
  'run.failed',
  'run.cancelled',
  'checkpoint.listed',
  'checkpoint.diffed',
  'checkpoint.undone',
  'protocol.error',
]);

export type EventType =
  | 'initialized'
  | 'run.started'
  | 'model.text.delta'
  | 'approval.requested'
  | 'tool.started'
  | 'tool.output'
  | 'tool.completed'
  | 'tool.failed'
  | 'run.completed'
  | 'run.failed'
  | 'run.cancelled'
  | 'checkpoint.listed'
  | 'checkpoint.diffed'
  | 'checkpoint.undone'
  | 'protocol.error';

export interface ProtocolEvent {
  readonly version: number;
  readonly type: EventType;
  readonly requestId: string;
  readonly sessionId?: string;
  readonly runId?: string;
  readonly sequence: number;
  readonly payload: Readonly<Record<string, unknown>>;
}

export interface ProtocolCommand {
  readonly version: number;
  readonly type:
    | 'initialize'
    | 'run.start'
    | 'run.cancel'
    | 'approval.resolve'
    | 'checkpoint.list'
    | 'checkpoint.diff'
    | 'checkpoint.undo'
    | 'shutdown';
  readonly requestId: string;
  readonly sessionId?: string;
  readonly runId?: string;
  readonly sequence: number;
  readonly payload: Readonly<Record<string, unknown>>;
}

/**
 * 表示 TUI 在信任事件前发现的协议错误。
 *
 * Java 仍是 Agent 状态权威；该错误只保护终端 Client 不消费畸形、乱序或未知事件。
 */
export class ProtocolViolation extends Error {
  public constructor(message: string) {
    super(message);
    this.name = 'ProtocolViolation';
  }
}

export function decodeEvent(line: string, expectedSequence: number): ProtocolEvent {
  let value: unknown;
  try {
    value = JSON.parse(line);
  } catch {
    throw new ProtocolViolation('Java stdout 包含无效 JSON');
  }
  if (!isRecord(value)) {
    throw new ProtocolViolation('协议事件必须是 JSON Object');
  }

  const version = requireInteger(value, 'version');
  if (version !== PROTOCOL_VERSION) {
    throw new ProtocolViolation(`不支持的协议版本：${version}`);
  }
  const type = requireText(value, 'type');
  if (!EVENT_TYPES.has(type)) {
    throw new ProtocolViolation(`未知协议事件：${type}`);
  }
  const requestId = requireText(value, 'requestId');
  const sequence = requireInteger(value, 'sequence');
  if (sequence !== expectedSequence) {
    throw new ProtocolViolation(
      `事件 sequence 不连续：期望 ${expectedSequence}，实际 ${sequence}`,
    );
  }
  const payload = value.payload;
  if (!isRecord(payload)) {
    throw new ProtocolViolation('payload 必须是 JSON Object');
  }

  const sessionId = optionalText(value, 'sessionId');
  const runId = optionalText(value, 'runId');
  validateEventShape(type as EventType, sessionId, runId, payload);

  return {
    version,
    type: type as EventType,
    requestId,
    ...(sessionId === undefined ? {} : {sessionId}),
    ...(runId === undefined ? {} : {runId}),
    sequence,
    payload,
  };
}

export function encodeCommand(command: ProtocolCommand): string {
  return `${JSON.stringify(command)}\n`;
}

function validateEventShape(
  type: EventType,
  sessionId: string | undefined,
  runId: string | undefined,
  payload: Readonly<Record<string, unknown>>,
): void {
  if (type === 'initialized' && sessionId === undefined) {
    throw new ProtocolViolation('initialized 缺少 sessionId');
  }
  if (
    (type === 'checkpoint.listed'
      || type === 'checkpoint.diffed'
      || type === 'checkpoint.undone')
    && (sessionId === undefined || runId !== undefined)
  ) {
    throw new ProtocolViolation(`${type} 必须携带 sessionId 且不能携带 runId`);
  }
  if (type === 'checkpoint.listed') {
    validateCheckpointList(payload);
  } else if (type === 'checkpoint.diffed') {
    validateCheckpointDiff(payload);
  } else if (type === 'checkpoint.undone') {
    validateCheckpointUndo(payload);
  }
  if (
    (type === 'run.started'
      || type === 'model.text.delta'
      || type === 'approval.requested'
      || type === 'tool.started'
      || type === 'tool.output'
      || type === 'tool.completed'
      || type === 'tool.failed'
      || type === 'run.completed'
      || type === 'run.failed'
      || type === 'run.cancelled')
    && (sessionId === undefined || runId === undefined)
  ) {
    throw new ProtocolViolation(`${type} 缺少 sessionId 或 runId`);
  }
  if (type === 'model.text.delta' && typeof payload.text !== 'string') {
    throw new ProtocolViolation('model.text.delta 缺少文本');
  }
  if (
    type === 'approval.requested'
    && (typeof payload.approvalId !== 'string'
      || payload.approvalId.trim().length === 0
      || payload.approvalId.length > MAX_IDENTIFIER_CHARS
      || !Number.isSafeInteger(payload.ordinal)
      || (payload.ordinal as number) < 1
      || typeof payload.toolName !== 'string'
      || payload.toolName.trim().length === 0
      || (payload.effect !== 'write_workspace'
        && payload.effect !== 'execute_process'))
  ) {
    throw new ProtocolViolation('approval.requested 缺少安全审批摘要');
  }
  if (
    type === 'tool.output'
    && (!Number.isSafeInteger(payload.ordinal)
      || (payload.ordinal as number) < 1
      || typeof payload.toolName !== 'string'
      || payload.toolName.trim().length === 0
      || (payload.stream !== 'stdout' && payload.stream !== 'stderr')
      || typeof payload.text !== 'string'
      || payload.text.length === 0
      || Array.from(payload.text).length > 4_096)
  ) {
    throw new ProtocolViolation('tool.output 缺少有界输出摘要');
  }
  if (type === 'approval.requested') {
    validateApprovalPreview(payload);
  }
  if (
    (type === 'tool.started' || type === 'tool.completed' || type === 'tool.failed')
    && (!Number.isSafeInteger(payload.ordinal)
      || (payload.ordinal as number) < 1
      || typeof payload.toolName !== 'string'
      || payload.toolName.trim().length === 0)
  ) {
    throw new ProtocolViolation(`${type} 缺少安全 Tool 摘要`);
  }
  if (type === 'tool.started' || type === 'tool.completed' || type === 'tool.failed') {
    validateOptionalToolPresentation(type, payload);
  }
  if (isTerminalRunEvent(type)) {
    const stopReason = payload.stopReason;
    if (
      typeof stopReason !== 'string'
      || !/^[a-z][a-z0-9_]{0,63}$/.test(stopReason)
    ) {
      throw new ProtocolViolation(`${type} 缺少安全 stopReason`);
    }
    validateOptionalTerminalCount(type, payload, 'modelTurns');
    validateOptionalTerminalCount(type, payload, 'toolCalls');
    validateOptionalModelFailure(type, payload);
  }
}

function validateCheckpointList(
  payload: Readonly<Record<string, unknown>>,
): void {
  if (
    Object.keys(payload).some(key => key !== 'checkpoints')
    || !Array.isArray(payload.checkpoints)
    || payload.checkpoints.length > MAX_CHECKPOINTS
  ) {
    throw new ProtocolViolation('checkpoint.listed 包含无效列表');
  }
  for (const checkpoint of payload.checkpoints) {
    if (!isRecord(checkpoint)) {
      throw new ProtocolViolation('checkpoint.listed 包含无效条目');
    }
    const allowedFields = new Set([
      'checkpointId', 'callId', 'toolName', 'target', 'existedBefore', 'phase', 'undoable',
    ]);
    if (
      Object.keys(checkpoint).some(key => !allowedFields.has(key))
      || Object.keys(checkpoint).length !== allowedFields.size
      || !isCheckpointId(checkpoint.checkpointId)
      || !isBoundedIdentifier(checkpoint.callId)
      || !isBoundedIdentifier(checkpoint.toolName)
      || !isSafeRelativeTarget(checkpoint.target)
      || typeof checkpoint.existedBefore !== 'boolean'
      || typeof checkpoint.phase !== 'string'
      || !CHECKPOINT_PHASES.has(checkpoint.phase)
      || typeof checkpoint.undoable !== 'boolean'
      || checkpoint.undoable !== (
        checkpoint.phase === 'completed_present'
        || checkpoint.phase === 'completed_absent'
      )
    ) {
      throw new ProtocolViolation('checkpoint.listed 包含无效条目');
    }
  }
}

function validateCheckpointDiff(
  payload: Readonly<Record<string, unknown>>,
): void {
  const allowedFields = new Set([
    'checkpointId', 'target', 'status', 'text', 'truncated',
  ]);
  if (
    Object.keys(payload).some(key => !allowedFields.has(key))
    || Object.keys(payload).length !== allowedFields.size
    || !isCheckpointId(payload.checkpointId)
    || !isSafeRelativeTarget(payload.target)
    || typeof payload.status !== 'string'
    || !CHECKPOINT_DIFF_STATUSES.has(payload.status)
    || !isSafeDisplayText(payload.text, MAX_CHECKPOINT_DIFF_CHARS, true)
    || typeof payload.truncated !== 'boolean'
  ) {
    throw new ProtocolViolation('checkpoint.diffed 包含无效有界结果');
  }
}

function validateCheckpointUndo(
  payload: Readonly<Record<string, unknown>>,
): void {
  const allowedFields = new Set([
    'checkpointId', 'target', 'status', 'message',
  ]);
  if (
    Object.keys(payload).some(key => !allowedFields.has(key))
    || Object.keys(payload).length !== allowedFields.size
    || !isCheckpointId(payload.checkpointId)
    || !isSafeRelativeTarget(payload.target)
    || typeof payload.status !== 'string'
    || !CHECKPOINT_UNDO_STATUSES.has(payload.status)
    || !isSafeDisplayText(payload.message, MAX_CHECKPOINT_MESSAGE_CHARS, false)
  ) {
    throw new ProtocolViolation('checkpoint.undone 包含无效结果');
  }
}

function isCheckpointId(value: unknown): value is string {
  return typeof value === 'string'
    && value.length <= MAX_IDENTIFIER_CHARS
    && CHECKPOINT_ID.test(value);
}

function isBoundedIdentifier(value: unknown): value is string {
  return typeof value === 'string'
    && value.trim().length > 0
    && value.length <= MAX_IDENTIFIER_CHARS
    && !/[\u0000-\u001f\u007f]/u.test(value);
}

function isSafeRelativeTarget(value: unknown): value is string {
  return typeof value === 'string'
    && value.length > 0
    && value.length <= MAX_CHECKPOINT_TARGET_CHARS
    && !/[\u0000-\u001f\u007f]/u.test(value)
    && !value.startsWith('/')
    && !value.startsWith('\\')
    && !/^[A-Za-z]:/.test(value)
    && !value.split(/[\\/]/).includes('..');
}

function isSafeDisplayText(
  value: unknown,
  maxCharacters: number,
  allowEmpty: boolean,
): value is string {
  return typeof value === 'string'
    && (allowEmpty || value.length > 0)
    && Array.from(value).length <= maxCharacters
    && !/[\u0000\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(value);
}

function validateApprovalPreview(
  payload: Readonly<Record<string, unknown>>,
): void {
  const fields = [
    payload.target,
    payload.operation,
    payload.removedLines,
    payload.addedLines,
  ];
  const present = fields.filter(value => value !== undefined).length;
  const commandFields = [
    payload.command,
    payload.shell,
    payload.workingDirectory,
  ];
  const commandPresent = commandFields.filter(value => value !== undefined).length;
  if (present === 0 && commandPresent === 0) {
    return;
  }
  if (commandPresent > 0) {
    if (
      present !== 1
      || payload.operation !== 'execute'
      || commandPresent !== commandFields.length
      || typeof payload.command !== 'string'
      || payload.command.trim().length === 0
      || Array.from(payload.command).length > 8_192
      || typeof payload.shell !== 'string'
      || (payload.shell !== 'powershell' && payload.shell !== 'sh')
      || payload.workingDirectory !== '.'
    ) {
      throw new ProtocolViolation('approval.requested 命令预览无效');
    }
    return;
  }
  if (
    present !== fields.length
    || typeof payload.target !== 'string'
    || payload.target.length === 0
    || payload.target.length > 512
    || payload.target.startsWith('/')
    || payload.target.startsWith('\\')
    || /^[A-Za-z]:/.test(payload.target)
    || payload.target.split(/[\\/]/).includes('..')
    || (payload.operation !== 'modify' && payload.operation !== 'create')
    || !Number.isSafeInteger(payload.removedLines)
    || (payload.removedLines as number) < 0
    || !Number.isSafeInteger(payload.addedLines)
    || (payload.addedLines as number) < 0
  ) {
    throw new ProtocolViolation('approval.requested 文件预览无效');
  }
}

function validateOptionalToolPresentation(
  type: EventType,
  payload: Readonly<Record<string, unknown>>,
): void {
  if (
    'mode' in payload
    && payload.mode !== 'content'
    && payload.mode !== 'files'
    && payload.mode !== 'count'
  ) {
    throw new ProtocolViolation(`${type} 包含未知搜索展示模式`);
  }
  if (
    'returnedItems' in payload
    && (!Number.isSafeInteger(payload.returnedItems)
      || (payload.returnedItems as number) < 0)
  ) {
    throw new ProtocolViolation(`${type} 的 returnedItems 必须是非负安全整数`);
  }
  if (
    'truncationReason' in payload
    && (typeof payload.truncationReason !== 'string'
      || !/^[a-z][a-z0-9_]{0,63}$/.test(payload.truncationReason))
  ) {
    throw new ProtocolViolation(`${type} 包含无效截断原因`);
  }
}

function isTerminalRunEvent(type: EventType): boolean {
  return type === 'run.completed'
    || type === 'run.failed'
    || type === 'run.cancelled';
}

function validateOptionalTerminalCount(
  type: EventType,
  payload: Readonly<Record<string, unknown>>,
  field: 'modelTurns' | 'toolCalls',
): void {
  if (
    field in payload
    && (!Number.isSafeInteger(payload[field]) || (payload[field] as number) < 0)
  ) {
    throw new ProtocolViolation(`${type} 的 ${field} 必须是非负安全整数`);
  }
}

const MODEL_FAILURE_CATEGORIES = new Set([
  'provider_unavailable',
  'rate_limited',
  'request_timeout',
  'request_conflict',
  'authentication_failed',
  'invalid_request',
  'network_error',
  'incomplete_stream',
  'invalid_response',
  'provider_error',
]);

function validateOptionalModelFailure(
  type: EventType,
  payload: Readonly<Record<string, unknown>>,
): void {
  if (!('modelFailure' in payload)) {
    return;
  }
  if (type !== 'run.failed' || !isRecord(payload.modelFailure)) {
    throw new ProtocolViolation(`${type} 包含无效模型失败摘要`);
  }
  const failure = payload.modelFailure;
  const allowedFields = new Set([
    'category', 'statusClass', 'attempts', 'receivedOutput',
  ]);
  if (
    Object.keys(failure).some(key => !allowedFields.has(key))
    || typeof failure.category !== 'string'
    || !MODEL_FAILURE_CATEGORIES.has(failure.category)
    || ('statusClass' in failure
      && failure.statusClass !== '4xx'
      && failure.statusClass !== '5xx')
    || !Number.isSafeInteger(failure.attempts)
    || (failure.attempts as number) < 1
    || (failure.attempts as number) > 100
    || typeof failure.receivedOutput !== 'boolean'
  ) {
    throw new ProtocolViolation(`${type} 包含无效模型失败摘要`);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function requireText(value: Record<string, unknown>, field: string): string {
  const text = value[field];
  if (
    typeof text !== 'string'
    || text.trim().length === 0
    || text.length > MAX_IDENTIFIER_CHARS
  ) {
    throw new ProtocolViolation(`${field} 为空或超过长度限制`);
  }
  return text;
}

function optionalText(
  value: Record<string, unknown>,
  field: string,
): string | undefined {
  if (!(field in value) || value[field] === null) {
    return undefined;
  }
  return requireText(value, field);
}

function requireInteger(value: Record<string, unknown>, field: string): number {
  const number = value[field];
  if (!Number.isSafeInteger(number) || (number as number) < 0) {
    throw new ProtocolViolation(`${field} 必须是非负安全整数`);
  }
  return number as number;
}
