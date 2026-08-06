export type SlashIntent =
  | 'help'
  | 'clear'
  | 'compact'
  | 'context'
  | 'doctor'
  | 'model'
  | 'permissions'
  | 'resume';

export interface ParsedSlashCommand {
  readonly intent: SlashIntent;
  readonly arguments: Readonly<Record<string, unknown>>;
}

export type SlashParseResult =
  | {readonly kind: 'not-command'}
  | {readonly kind: 'command'; readonly command: ParsedSlashCommand}
  | {readonly kind: 'invalid'; readonly message: string};

const MAX_ARGUMENT_CHARS = 256;
const MAX_COMPACT_ANCHORS = 16;
const MAX_COMPACT_ANCHOR_CODE_POINTS = 512;
const CONTROL_CHARACTER_PATTERN = /[\u0000-\u001f\u007f]/u;
const COMMANDS = new Set<SlashIntent>([
  'help', 'clear', 'compact', 'context', 'doctor', 'model', 'permissions', 'resume',
]);

/**
 * 将 TUI 输入转换为封闭的 S08 命令意图，不猜测路径、配置或权限 selector。
 */
export function parseSlashCommand(input: string): SlashParseResult {
  if (!input.startsWith('/')) return {kind: 'not-command'};
  const [rawName, ...values] = input.slice(1).trim().split(/\s+/u);
  if (rawName === undefined || rawName.length === 0 || !COMMANDS.has(rawName as SlashIntent)) {
    return {kind: 'invalid', message: '未知 Slash 命令'};
  }
  const intent = rawName as SlashIntent;
  if (['help', 'clear', 'context', 'doctor'].includes(intent)) {
    return values.length === 0
      ? {kind: 'command', command: {intent, arguments: {}}}
      : {kind: 'invalid', message: `/${intent} 不接受参数`};
  }
  if (intent === 'compact') {
    if (values.length > MAX_COMPACT_ANCHORS || values.some(invalidCompactAnchor)) {
      return {kind: 'invalid', message: '/compact 参数非法或超过上限'};
    }
    return {kind: 'command', command: {intent, arguments: {anchors: values}}};
  }
  if (intent === 'permissions') {
    if (values.length === 0 || (values.length === 1 && values[0] === 'query')) {
      return {kind: 'command', command: {intent, arguments: {}}};
    }
    const [operation, mode] = values;
    return values.length === 2 && operation === 'mode' && mode !== undefined
      && (mode === 'DEFAULT' || mode === 'PLAN' || mode === 'ACCEPT_EDITS')
      ? {kind: 'command', command: {intent, arguments: {mode}}}
      : {kind: 'invalid', message: '/permissions 只接受 query 或 mode DEFAULT|PLAN|ACCEPT_EDITS'};
  }
  const value = values[0];
  const key = intent === 'model' ? 'name' : 'sessionId';
  return values.length === 1 && value !== undefined && !invalidArgument(value)
    ? {kind: 'command', command: {intent, arguments: {[key]: value}}}
    : {kind: 'invalid', message: `/${intent} 需要一个有界参数`};
}

/** 将固定结果代码渲染为不含服务端原文的本地提示。 */
export function renderSlashResult(intent: string, status: string, code: string): string {
  if (status === 'succeeded') return `/${intent} 已完成`;
  const labels: Record<string, string> = {
    active_run: '当前 Run 仍在执行', unavailable: '当前没有可用视图',
    not_available: '当前版本尚未提供', deferred: '已延期至后续安全切片',
    invalid_argument: '参数无效', request_budget_exhausted: '命令请求额度已用尽',
    cancelled: '请求已取消', compaction_rejected: '压缩候选未通过安全校验',
    internal_failure: '内部处理未完成', current_session: '目标已经是当前 Session',
    session_active: '目标 Session 正由其他 Writer 使用', recovery_required: '目标未通过恢复安全检查',
  };
  return `/${intent} 未执行：${labels[code] ?? '请求被安全拒绝'}`;
}

function invalidArgument(value: string): boolean {
  return value.length === 0 || Array.from(value).length > MAX_ARGUMENT_CHARS
    || CONTROL_CHARACTER_PATTERN.test(value);
}

function invalidCompactAnchor(value: string): boolean {
  return value.length === 0 || Array.from(value).length > MAX_COMPACT_ANCHOR_CODE_POINTS
    || CONTROL_CHARACTER_PATTERN.test(value);
}
