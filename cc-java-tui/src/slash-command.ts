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
  | {readonly kind: 'skill'; readonly name: string; readonly arguments: string}
  | {readonly kind: 'invalid'; readonly message: string};

const MAX_ARGUMENT_CHARS = 256;
const MAX_COMPACT_ANCHORS = 16;
const MAX_COMPACT_ANCHOR_CODE_POINTS = 512;
const CONTROL_CHARACTER_PATTERN = /[\u0000-\u001f\u007f]/u;
const COMMANDS = new Set<SlashIntent>([
  'help', 'clear', 'compact', 'context', 'doctor', 'model', 'permissions', 'resume',
]);

const COMMAND_USAGE: Readonly<Record<SlashIntent, string>> = {
  help: '/help — 查看命令与可用状态',
  clear: '/clear — 清除当前界面的瞬态内容',
  compact: '/compact [anchor...] — 请求压缩下一轮上下文',
  context: '/context — 查看上下文用量',
  doctor: '/doctor — 查看安全诊断',
  model: '/model <name> — 切换到已配置模型',
  permissions: '/permissions [query|mode MODE] — 查看或切换权限模式',
  resume: '/resume <session-id> — 安全恢复会话',
};

/**
 * 将 TUI 输入转换为封闭的 S08 命令意图，不猜测路径、配置或权限 selector。
 */
export function parseSlashCommand(input: string): SlashParseResult {
  if (!input.startsWith('/')) return {kind: 'not-command'};
  const [rawName, ...values] = input.slice(1).trim().split(/\s+/u);
  if (rawName === undefined || rawName.length === 0 || !COMMANDS.has(rawName as SlashIntent)) {
    if (/^[a-z0-9]+(?:-[a-z0-9]+)*$/u.test(rawName ?? '') && (rawName?.length ?? 0) <= 64) {
      const arguments_ = values.join(' ');
      return Array.from(arguments_).length <= 8_192
        ? {kind: 'skill', name: rawName ?? '', arguments: arguments_}
        : {kind: 'invalid', message: 'Skill 参数超过上限'};
    }
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

/** 返回命令面板使用的本地固定说明，不使用服务端自由文本。 */
export function slashCommandUsage(candidate: string): string {
  if (candidate.includes(' ')) {
    return candidate;
  }
  const intent = candidate.slice(1).split(/\s+/u)[0] as SlashIntent | undefined;
  return intent !== undefined && intent in COMMAND_USAGE
    ? COMMAND_USAGE[intent]
    : candidate;
}

/** 将严格协议结果渲染为不含 Prompt、Secret 或服务端自由文本的本地安全投影。 */
export function renderSlashResult(
  intent: string,
  status: string,
  code: string,
  result: Readonly<Record<string, unknown>> = {},
): string {
  if (status === 'succeeded') {
    return renderSuccessfulResult(intent, result);
  }
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

function renderSuccessfulResult(
  intent: string,
  result: Readonly<Record<string, unknown>>,
): string {
  if (intent === 'help' && Array.isArray(result.commands)) {
    const supportLabels: Readonly<Record<string, string>> = {
      available: '可用', deferred: '延期', not_available: '不可用',
    };
    const lines = result.commands.flatMap(item => {
      if (!isRecord(item) || typeof item.intent !== 'string' || typeof item.support !== 'string') {
        return [];
      }
      const usage = COMMAND_USAGE[item.intent as SlashIntent] ?? `/${item.intent}`;
      return [`${usage}　[${supportLabels[item.support] ?? '未知'}]`];
    });
    return ['Slash 命令', ...lines].join('\n');
  }
  if (intent === 'context') {
    return [
      'Context 用量',
      `总计 ${safeValue(result.totalTokens)} / 可输入 ${safeValue(result.availableInputTokens)} / 剩余 ${safeValue(result.freeTokens)}`,
      `系统 ${safeValue(result.systemTokens)} · 对话 ${safeValue(result.transcriptTokens)} · 工具 ${safeValue(result.toolTokens)} · 记忆 ${safeValue(result.memoryTokens)}`,
      `状态 ${safeValue(result.contextStatus)} · 估算 ${safeValue(result.estimateKind)} · 溢出 ${safeValue(result.overflowTokens)}`,
      `压缩 ${safeList(result.reductionStrategies)} · 原因 ${safeList(result.reasonCodes)}`,
    ].join('\n');
  }
  if (intent === 'permissions') {
    const rules = Array.isArray(result.rules) ? result.rules : [];
    const ruleLines = rules.flatMap(rule => isRecord(rule)
      ? [`- ${safeValue(rule.ruleId)} · ${safeValue(rule.sourceKind)}/${safeValue(rule.safeSourceId)} · ${safeValue(rule.operation)}`]
      : []);
    return [
      'Permissions',
      `模式 ${safeValue(result.effectiveMode)} · 来源 ${safeValue(result.modeSourceKind)}/${safeValue(result.modeSafeSourceId)} · ${safeValue(result.modeValidationStatus)}`,
      `启动规则 ${safeValue(result.startupRuleCount)}`,
      ...ruleLines,
    ].join('\n');
  }
  if (intent === 'doctor') {
    const entries = Array.isArray(result.entries) ? result.entries : [];
    const entryLines = entries.flatMap(entry => isRecord(entry)
      ? [`- ${safeValue(entry.component)} · ${safeValue(entry.sourceKind)}/${safeValue(entry.safeId)} · ${safeValue(entry.code)} · ${safeValue(entry.severity)}`]
      : []);
    return [
      'Doctor',
      `Settings ${result.settingsAvailable === true ? '可用' : '不可用'} (rev ${safeValue(result.settingsRevision)}) · Instructions ${safeValue(result.instructionCount)}`,
      `Context ${result.contextAvailable === true ? '可用' : '不可用'} · Run ${result.activeRun === true ? '活动' : '空闲'}`,
      ...entryLines,
    ].join('\n');
  }
  return `/${intent} 已完成`;
}

function safeValue(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '-';
}

function safeList(value: unknown): string {
  return Array.isArray(value) && value.every(item => typeof item === 'string') && value.length > 0
    ? value.join(', ')
    : '无';
}

function isRecord(value: unknown): value is Readonly<Record<string, unknown>> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function invalidArgument(value: string): boolean {
  return value.length === 0 || Array.from(value).length > MAX_ARGUMENT_CHARS
    || CONTROL_CHARACTER_PATTERN.test(value);
}

function invalidCompactAnchor(value: string): boolean {
  return value.length === 0 || Array.from(value).length > MAX_COMPACT_ANCHOR_CODE_POINTS
    || CONTROL_CHARACTER_PATTERN.test(value);
}
