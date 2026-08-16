export interface ModelSetupBase {
  readonly generation: number;
  readonly required: boolean;
  readonly baseUrl: string;
  readonly modelId: string;
  readonly validation?: string;
}

export type ModelSetupState = ModelSetupBase & (
  | {readonly phase: 'form'; readonly field: 'baseUrl' | 'modelId'}
  | {readonly phase: 'saving'; readonly controlId: string}
  | {readonly phase: 'credential'; readonly providerId: string; readonly credentialPreview: string;
      readonly credentialLength: number}
  | {readonly phase: 'logging-in'; readonly providerId: string; readonly credentialPreview: string}
  | {readonly phase: 'complete'; readonly modelId: string; readonly credentialPreview?: string}
  | {readonly phase: 'error'; readonly message: string}
);

export type ModelSetupEnterAction =
  | {readonly kind: 'state'; readonly state: ModelSetupState}
  | {readonly kind: 'control'; readonly state: ModelSetupState; readonly controlId: string;
      readonly intent: 'providers.configure'; readonly arguments: Readonly<Record<string, unknown>>};

export interface ModelSetupControlResult {
  readonly controlId: string;
  readonly intent: string;
  readonly status: string;
  readonly code: string;
  readonly result: Readonly<Record<string, unknown>>;
}

const CONTROL_CHARACTER = /[\u0000-\u001f\u007f]/u;

/** 创建首次启动或手动 /connect 共用的最小模型配置表单。 */
export function beginModelSetup(generation: number, required = false): ModelSetupState {
  if (!Number.isSafeInteger(generation) || generation < 1) throw new Error('setup generation 无效');
  return {generation, required, phase: 'form', field: 'baseUrl', baseUrl: '', modelId: ''};
}

/** 编辑当前字段；API Key 不进入 Ink/Node 状态。 */
export function editModelSetup(state: ModelSetupState,
  action: {readonly kind: 'append'; readonly text: string} | {readonly kind: 'backspace'}): ModelSetupState {
  if (state.phase !== 'form') return state;
  const limit = state.field === 'baseUrl' ? 2_048 : 256;
  const current = state[state.field];
  const next = action.kind === 'backspace' ? Array.from(current).slice(0, -1).join('')
    : current + Array.from(action.text).filter(point => !CONTROL_CHARACTER.test(point)).join('');
  if (Array.from(next).length > limit) return state;
  const {validation: _validation, ...clean} = state;
  return {...clean, [state.field]: next};
}

/** 在两个可见字段之间移动焦点。 */
export function moveModelSetup(state: ModelSetupState, field: 'baseUrl' | 'modelId'): ModelSetupState {
  return state.phase === 'form' ? {...state, field} : state;
}

/** 校验表单并生成唯一一次幂等配置命令。 */
export function enterModelSetup(state: ModelSetupState): ModelSetupEnterAction {
  if (state.phase === 'error') return stateAction({...state, phase: 'form', field: 'baseUrl'});
  if (state.phase === 'complete') return stateAction(state);
  if (state.phase !== 'form') return stateAction(state);
  if (state.field === 'baseUrl') {
    return validHttpsUrl(state.baseUrl)
      ? stateAction({...state, field: 'modelId'})
      : stateAction({...state, validation: '请输入有效的 HTTPS API Base URL'});
  }
  if (!validModel(state.modelId)) {
    return stateAction({...state, validation: '请输入模型名称'});
  }
  const controlId = `tui-setup:${state.generation}:configure`;
  return {kind: 'control', state: {...state, phase: 'saving', controlId}, controlId,
    intent: 'providers.configure', arguments: {baseUrl: state.baseUrl, modelId: state.modelId}};
}

/** 接受当前 generation 的配置结果；成功后只投影稳定 Provider ID，不暴露 endpoint。 */
export function applyModelSetupResult(state: ModelSetupState, event: ModelSetupControlResult): ModelSetupState {
  if (state.phase !== 'saving' || event.controlId !== state.controlId || event.intent !== 'providers.configure') return state;
  if (event.status !== 'succeeded') {
    return {...state, phase: 'error', message: setupError(event.code)};
  }
  const providerId = typeof event.result.providerId === 'string' ? event.result.providerId : '';
  const modelId = typeof event.result.modelId === 'string' ? event.result.modelId : '';
  if (providerId !== 'codej-custom' || modelId !== state.modelId) return state;
  return {...state, phase: 'credential', providerId, credentialPreview: '', credentialLength: 0};
}

/** 只把实时脱敏摘要和长度投影进 React 状态，原始字节由调用方的短生命周期缓冲持有。 */
export function projectModelSetupCredential(
  state: ModelSetupState, credentialPreview: string, credentialLength: number,
): ModelSetupState {
  return state.phase === 'credential' ? {...state, credentialPreview, credentialLength} : state;
}

/** Key 已交给一次性登录子进程后清除长度，只保留完成页需要的脱敏摘要。 */
export function beginModelSetupLogin(state: ModelSetupState): ModelSetupState {
  return state.phase === 'credential'
    ? {...state, phase: 'logging-in', credentialPreview: state.credentialPreview}
    : state;
}

/** masked Java 登录完成后直接结束配置；Provider 与模型默认值已在 configure 中原子保存。 */
export function completeModelSetupLogin(
  state: ModelSetupState, status: 'succeeded' | 'failed' | 'cancelled' | 'timed_out',
  credentialPreview?: string,
): ModelSetupState {
  if (state.phase !== 'logging-in') return state;
  if (status === 'succeeded') return {...state, phase: 'complete', modelId: state.modelId,
    credentialPreview: credentialPreview ?? state.credentialPreview};
  const message = status === 'cancelled' ? 'API Key 输入已取消'
    : status === 'timed_out' ? 'API Key 输入超时，请重试' : 'API Key 未保存，请检查后重试';
  return {...state, phase: 'error', message};
}

/** 首次必填表单不能被 Esc 绕过；手动 /connect 可以关闭。 */
export function escapeModelSetup(state: ModelSetupState): ModelSetupState | undefined {
  if (state.phase === 'credential') return {...state, phase: 'form', field: 'modelId'};
  if (state.phase === 'form' && state.field === 'modelId') return {...state, field: 'baseUrl'};
  if (state.phase === 'form') return state.required ? state : undefined;
  if (state.phase === 'error') return {...state, phase: 'form', field: 'baseUrl'};
  if (state.phase === 'complete') return undefined;
  return state;
}

function stateAction(state: ModelSetupState): ModelSetupEnterAction { return {kind: 'state', state}; }

function validHttpsUrl(value: string): boolean {
  if (value.trim() !== value || value.length === 0 || CONTROL_CHARACTER.test(value)) return false;
  try {
    const parsed = new URL(value);
    return parsed.protocol === 'https:' && parsed.hostname.length > 0 && parsed.username.length === 0
      && parsed.password.length === 0 && parsed.search.length === 0 && parsed.hash.length === 0;
  } catch { return false; }
}

function validModel(value: string): boolean {
  return value.trim() === value && value.length > 0 && Array.from(value).length <= 256
    && Buffer.byteLength(value, 'utf8') <= 1_024 && !CONTROL_CHARACTER.test(value);
}

function setupError(code: string): string {
  const labels: Readonly<Record<string, string>> = {
    AUTH_STORE_INSECURE: '本机配置存储未通过安全检查',
    AUTH_STORE_LOCKED: '本机配置正在被占用，请稍后重试',
    AUTH_STORE_CORRUPT: '本机配置存储不可用',
    AUTH_TRANSACTION_CONFLICT: '当前有任务正在运行，请结束后重试',
    PROVIDER_DEFINITION_INVALID: 'API 地址或模型配置无效',
    INVALID_ARGUMENT: 'API 地址或模型配置无效',
  };
  return labels[code] ?? '模型配置未保存，请检查后重试';
}
