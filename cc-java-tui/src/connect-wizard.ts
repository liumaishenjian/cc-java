export type ConnectWizardProviderId = string;

export interface ConnectWizardProfile {
  readonly providerId: string;
  readonly profileId: string;
  readonly localStatus: string;
  readonly providerDefault: boolean;
}

export interface ConnectWizardModel {
  readonly providerId: string;
  readonly modelId: string;
  readonly providerDefault: boolean;
}

interface ConnectWizardLoad {
  readonly controlId: string;
  readonly status: 'pending' | 'succeeded' | 'failed';
  readonly code?: string;
}

interface CustomProviderDraft {
  readonly displayName: string;
  readonly providerId: string;
  readonly baseUrl: string;
  readonly modelId: string;
  readonly validation?: string;
}

interface ConnectWizardBase {
  readonly generation: number;
  readonly providerIndex: number;
  readonly auth: ConnectWizardLoad;
  readonly models: ConnectWizardLoad;
  readonly profiles: readonly ConnectWizardProfile[];
  readonly modelCatalog: readonly ConnectWizardModel[];
  readonly custom: CustomProviderDraft;
}

export type ConnectWizardState = ConnectWizardBase & (
  | {readonly phase: 'select-provider'}
  | {readonly phase: 'custom-name' | 'custom-id' | 'custom-base-url' | 'custom-model' | 'custom-confirm'}
  | {readonly phase: 'saving-provider'; readonly controlId: string}
  | {readonly phase: 'select-auth'; readonly providerId: ConnectWizardProviderId; readonly optionIndex: number}
  | {readonly phase: 'env-input'; readonly providerId: ConnectWizardProviderId; readonly value: string; readonly validation?: string}
  | {readonly phase: 'logging-in'; readonly providerId: ConnectWizardProviderId; readonly secretSource: 'store' | 'env'}
  | {readonly phase: 'refreshing-credential'; readonly providerId: ConnectWizardProviderId; readonly profileId: string; readonly refreshControlId: string}
  | {readonly phase: 'select-model'; readonly providerId: ConnectWizardProviderId; readonly profileId: string; readonly modelIndex: number}
  | {readonly phase: 'select-connected-action'; readonly providerId: ConnectWizardProviderId; readonly profileId: string; readonly optionIndex: number}
  | {readonly phase: 'confirm-logout'; readonly providerId: ConnectWizardProviderId; readonly profileId: string; readonly optionIndex: number}
  | {readonly phase: 'waiting-control'; readonly providerId: ConnectWizardProviderId; readonly profileId: string; readonly action: 'models.use' | 'auth.logout'; readonly controlId: string; readonly modelId?: string}
  | {readonly phase: 'complete'; readonly providerName?: string; readonly modelId?: string; readonly message?: string}
  | {readonly phase: 'error'; readonly message: string; readonly returnTo: 'providers' | 'auth' | 'models' | 'connected' | 'custom-confirm'}
  | {readonly phase: 'cancelled'}
);

export interface ConnectWizardControlResult {
  readonly controlId: string;
  readonly intent: string;
  readonly status: string;
  readonly code: string;
  readonly result: Readonly<Record<string, unknown>>;
}

export type ConnectWizardEnterAction =
  | {readonly kind: 'state'; readonly state: ConnectWizardState}
  | {readonly kind: 'login'; readonly state: ConnectWizardState; readonly providerId: ConnectWizardProviderId; readonly profileId: string; readonly secretSource: 'store' | 'env'; readonly environmentName?: string}
  | {readonly kind: 'control'; readonly state: ConnectWizardState; readonly controlId: string; readonly intent: 'providers.add' | 'models.use' | 'auth.logout'; readonly arguments: Readonly<Record<string, unknown>>};

const BUILTIN_PROVIDERS = ['anthropic', 'openrouter'] as const;
const MAX_CUSTOM_PROVIDER_OPTIONS = 32;
const PROVIDER_ID = /^[a-z0-9][a-z0-9-]{0,62}$/u;

export type ConnectWizardProviderOption =
  | {readonly kind: 'provider'; readonly providerId: string; readonly label: string}
  | {readonly kind: 'add-custom'; readonly label: string};
const ENVIRONMENT_NAME = /^[A-Z][A-Z0-9_]{0,127}$/u;
const CONTROL_CHARACTER = /[\u0000-\u001f\u007f]/u;
const AVAILABLE_STATUS = 'AVAILABLE_LOCAL';
const TEXT_LIMITS = {
  'custom-name': 80,
  'custom-id': 63,
  'custom-base-url': 2_048,
  'custom-model': 256,
} as const;

/** 创建一代有界连接向导，并精确绑定认证与模型目录两条加载腿。 */
export function beginConnectWizard(generation: number): ConnectWizardState {
  if (!Number.isSafeInteger(generation) || generation < 1) throw new Error('connect generation 无效');
  return {
    generation,
    phase: 'select-provider',
    providerIndex: 0,
    auth: pendingLoad(connectControlId(generation, 'load', 'auth')),
    models: pendingLoad(connectControlId(generation, 'load', 'models')),
    profiles: [],
    modelCatalog: [],
    custom: {displayName: '', providerId: '', baseUrl: '', modelId: ''},
  };
}

export function connectWizardInitialControls(state: ConnectWizardState): readonly [string, string][] {
  return [[state.models.controlId, 'models.list'], [state.auth.controlId, 'auth.list']];
}

/** 只接受当前 phase、generation 与 controlId 精确匹配且尚未结算的结果。 */
export function applyConnectWizardResult(state: ConnectWizardState, event: ConnectWizardControlResult): ConnectWizardState {
  if (event.controlId === state.auth.controlId && event.intent === 'auth.list'
    && state.auth.status === 'pending') return applyAuthResult(state, event, false);
  if (event.controlId === state.models.controlId && event.intent === 'models.list'
    && state.models.status === 'pending') return applyModelsResult(state, event);
  if (state.phase === 'saving-provider' && event.controlId === state.controlId && event.intent === 'providers.add') {
    if (event.status !== 'succeeded') {
      return {...state, phase: 'error', message: connectError(event.code), returnTo: 'custom-confirm'};
    }
    const providerId = stringResult(event.result, 'providerId');
    const displayName = stringResult(event.result, 'displayName');
    const modelId = stringResult(event.result, 'modelId');
    if (providerId !== state.custom.providerId || displayName !== state.custom.displayName
      || modelId !== state.custom.modelId) return state;
    const modelCatalog = mergeProviderModel(state.modelCatalog, {providerId, modelId, providerDefault: true});
    return {...state, modelCatalog, phase: 'select-auth', providerId, optionIndex: 0};
  }
  if (state.phase === 'refreshing-credential'
    && event.controlId === state.refreshControlId && event.intent === 'auth.list') {
    return applyAuthResult(state, event, true);
  }
  if (state.phase !== 'waiting-control' || event.controlId !== state.controlId
    || event.intent !== state.action) return state;
  if (event.status !== 'succeeded') {
    return {...state, phase: 'error', message: connectError(event.code),
      returnTo: state.action === 'models.use' ? 'models' : 'connected'};
  }
  if (state.action === 'models.use') {
    return {...state, phase: 'complete', providerName: providerName(state.providerId),
      ...(state.modelId === undefined ? {} : {modelId: state.modelId})};
  }
  return {...state, phase: 'complete', message: `已退出登录 ${providerName(state.providerId)}`};
}

/** 处理方向键；文本页不吞掉上下键之外的输入。 */
export function moveConnectWizard(state: ConnectWizardState, delta: -1 | 1): ConnectWizardState {
  switch (state.phase) {
    case 'select-provider': return {...state, providerIndex: wrap(
      normalizeProviderIndex(state), connectWizardProviderOptions(state).length, delta)};
    case 'select-auth': return {...state, optionIndex: wrap(state.optionIndex, 3, delta)};
    case 'select-connected-action': return {...state, optionIndex: wrap(state.optionIndex, 3, delta)};
    case 'confirm-logout': return {...state, optionIndex: wrap(state.optionIndex, 2, delta)};
    case 'select-model': {
      const count = modelsForProvider(state, state.providerId).length;
      return count === 0 ? state : {...state, modelIndex: wrap(state.modelIndex, count, delta)};
    }
    default: return state;
  }
}

/** 将每一页 Enter 转成明确状态、一次性 masked login 或精确 Java control intent。 */
export function enterConnectWizard(state: ConnectWizardState): ConnectWizardEnterAction {
  switch (state.phase) {
    case 'select-provider': {
      const option = connectWizardProviderOptions(state)[normalizeProviderIndex(state)];
      if (option?.kind === 'add-custom') return stateAction({...state, phase: 'custom-name'});
      const providerId = option?.providerId;
      if (providerId === undefined) return stateAction({...state, phase: 'error', message: 'Provider 选择无效', returnTo: 'providers'});
      const profile = connectedProfile(state, providerId);
      if (profile !== undefined) return stateAction({...state, phase: 'select-connected-action', providerId, profileId: profile.profileId, optionIndex: 0});
      if (state.auth.status === 'pending') return stateAction({...state, phase: 'error', message: '连接状态仍在加载，请稍候再试', returnTo: 'providers'});
      return stateAction({...state, phase: 'select-auth', providerId, optionIndex: 0});
    }
    case 'custom-name': {
      const displayName = state.custom.displayName;
      if (!validBoundedText(displayName, 80, 256)) return customValidation(state, '请输入 1～80 个字符的服务名称');
      const suggested = state.custom.providerId.length === 0 ? suggestProviderId(displayName) : state.custom.providerId;
      return stateAction({...state, custom: withoutCustomValidation({...state.custom, providerId: suggested}), phase: 'custom-id'});
    }
    case 'custom-id':
      return PROVIDER_ID.test(state.custom.providerId)
        ? stateAction({...state, custom: withoutCustomValidation(state.custom), phase: 'custom-base-url'})
        : customValidation(state, 'ID 需为小写字母/数字/连字符，最长 63 字符');
    case 'custom-base-url':
      return validHttpsUrl(state.custom.baseUrl)
        ? stateAction({...state, custom: withoutCustomValidation(state.custom), phase: 'custom-model'})
        : customValidation(state, '请输入不含账号、查询或片段的 absolute HTTPS URL');
    case 'custom-model':
      return validBoundedText(state.custom.modelId, 256, 1_024)
        ? stateAction({...state, custom: withoutCustomValidation(state.custom), phase: 'custom-confirm'})
        : customValidation(state, '请输入 1～256 个字符的模型名');
    case 'custom-confirm': {
      const controlId = connectControlId(state.generation, 'action', 'provider');
      return {kind: 'control', state: {...state, phase: 'saving-provider', controlId}, controlId,
        intent: 'providers.add', arguments: {
          providerId: state.custom.providerId, displayName: state.custom.displayName,
          baseUrl: state.custom.baseUrl, modelId: state.custom.modelId,
        }};
    }
    case 'select-auth':
      if (state.optionIndex === 2) return stateAction({...state, phase: 'select-provider'});
      if (state.optionIndex === 1) return stateAction({...state, phase: 'env-input', value: ''});
      return loginAction(state, state.providerId, 'store');
    case 'env-input':
      if (!ENVIRONMENT_NAME.test(state.value)) return stateAction({...state, validation: '请输入大写字母开头的环境变量名称'});
      return loginAction(state, state.providerId, 'env', state.value);
    case 'select-connected-action':
      if (state.optionIndex === 0) return stateAction(toModelSelection(state, state.providerId, state.profileId));
      if (state.optionIndex === 1) return stateAction({...state, phase: 'select-auth', optionIndex: 0});
      return stateAction({...state, phase: 'confirm-logout', optionIndex: 1});
    case 'confirm-logout': {
      if (state.optionIndex === 1) return stateAction({...state, phase: 'select-connected-action', optionIndex: 0});
      const controlId = connectControlId(state.generation, 'action', 'logout');
      return {kind: 'control', state: {...state, phase: 'waiting-control', action: 'auth.logout', controlId},
        controlId, intent: 'auth.logout', arguments: {providerId: state.providerId, profileId: state.profileId, confirmed: true}};
    }
    case 'select-model': {
      const model = modelsForProvider(state, state.providerId)[state.modelIndex];
      if (model === undefined) return stateAction({...state, phase: 'error', message: '当前没有可选模型', returnTo: 'models'});
      const controlId = connectControlId(state.generation, 'action', 'model');
      return {kind: 'control', state: {...state, phase: 'waiting-control', action: 'models.use', controlId, modelId: model.modelId},
        controlId, intent: 'models.use', arguments: {providerId: state.providerId, profileId: state.profileId,
          modelId: model.modelId, setDefault: true}};
    }
    case 'error': return stateAction(returnFromError(state));
    case 'complete':
    case 'cancelled': return stateAction({...state, phase: 'select-provider'});
    case 'saving-provider': return stateAction(state);
    case 'logging-in':
    case 'refreshing-credential':
    case 'waiting-control': return stateAction(state);
  }
}

/** 登录成功后刷新 credential metadata；Secret 始终留在 Java masked Console/ENV resolver。 */
export function completeConnectWizardLogin(state: ConnectWizardState, status: 'succeeded' | 'failed' | 'cancelled' | 'timed_out'):
{readonly state: ConnectWizardState; readonly refreshControlId?: string} {
  if (state.phase !== 'logging-in') return {state};
  if (status !== 'succeeded') {
    const message = status === 'cancelled' ? '连接已取消' : status === 'timed_out' ? '连接超时，请重试' : '凭证未保存，请检查输入后重试';
    return {state: {...state, phase: 'error', message, returnTo: 'auth'}};
  }
  const refreshControlId = connectControlId(state.generation, 'refresh', 'auth');
  return {state: {...state, phase: 'refreshing-credential', profileId: 'default', refreshControlId}, refreshControlId};
}

/** 编辑当前文本页；支持 Backspace/Delete、控制字符过滤和逐页 code point 上限。 */
export function editConnectWizardText(state: ConnectWizardState,
  action: {readonly kind: 'append'; readonly text: string} | {readonly kind: 'backspace'}): ConnectWizardState {
  if (state.phase === 'env-input') {
    const value = editValue(state.value, action, 128);
    const {validation: _validation, ...withoutValidation} = state;
    return {...withoutValidation, value};
  }
  if (!(state.phase in TEXT_LIMITS)) return state;
  const phase = state.phase as keyof typeof TEXT_LIMITS;
  const field = customField(phase);
  const value = editValue(state.custom[field], action, TEXT_LIMITS[phase]);
  return {...state, custom: withoutCustomValidation({...state.custom, [field]: value})};
}

/** 兼容既有 ENV reducer 调用。 */
export function editConnectWizardEnvironment(state: ConnectWizardState,
  action: {readonly kind: 'append'; readonly text: string} | {readonly kind: 'backspace'}): ConnectWizardState {
  return editConnectWizardText(state, action);
}

export function escapeConnectWizard(state: ConnectWizardState): ConnectWizardState | undefined {
  switch (state.phase) {
    case 'select-provider': return undefined;
    case 'custom-name': return {...state, phase: 'select-provider'};
    case 'custom-id': return {...state, phase: 'custom-name'};
    case 'custom-base-url': return {...state, phase: 'custom-id'};
    case 'custom-model': return {...state, phase: 'custom-base-url'};
    case 'custom-confirm': return {...state, phase: 'custom-model'};
    case 'saving-provider': return state;
    case 'select-auth': return {...state, phase: 'select-provider'};
    case 'env-input': return {...state, phase: 'select-auth', optionIndex: 1};
    case 'select-model': return {...state, phase: 'select-auth', optionIndex: 0};
    case 'confirm-logout': return {...state, phase: 'select-connected-action', optionIndex: 0};
    case 'select-connected-action': return {...state, phase: 'select-provider'};
    case 'error': return returnFromError(state);
    case 'complete':
    case 'cancelled': return undefined;
    default: return state;
  }
}

export function modelsForProvider(state: ConnectWizardState, providerId: string): readonly ConnectWizardModel[] {
  return state.modelCatalog.filter(model => model.providerId === providerId);
}

/**
 * 从安全的模型/profile 投影导出 Provider picker 的唯一选项源。
 *
 * 内置项固定在前，自定义 ID 去重后按 code point 稳定排序并受本地 store ceiling 限制；
 * “添加自定义服务”始终是最后一项。方向键与 Enter 必须共同调用该 helper。
 */
export function connectWizardProviderOptions(state: ConnectWizardState): readonly ConnectWizardProviderOption[] {
  const builtins: ConnectWizardProviderOption[] = BUILTIN_PROVIDERS.map(providerId => ({
    kind: 'provider', providerId, label: providerName(providerId),
  }));
  const customIds = [...new Set([
    ...state.modelCatalog.map(model => model.providerId),
    ...state.profiles.map(profile => profile.providerId),
  ].filter(providerId => PROVIDER_ID.test(providerId) && !BUILTIN_PROVIDERS.includes(
    providerId as typeof BUILTIN_PROVIDERS[number])))]
    .sort((left, right) => left < right ? -1 : left > right ? 1 : 0)
    .slice(0, MAX_CUSTOM_PROVIDER_OPTIONS);
  return [
    ...builtins,
    ...customIds.map(providerId => ({kind: 'provider' as const, providerId,
      label: `自定义 · ${providerId}`})),
    {kind: 'add-custom', label: '添加自定义服务（高级）'},
  ];
}

export function connectedProfile(state: ConnectWizardState, providerId: string): ConnectWizardProfile | undefined {
  const profiles = state.profiles.filter(profile => profile.providerId === providerId && profile.localStatus === AVAILABLE_STATUS);
  return profiles.find(profile => profile.providerDefault) ?? profiles.find(profile => profile.profileId === 'default') ?? profiles[0];
}

export function providerName(providerId: string): string {
  return providerId === 'anthropic' ? 'Anthropic' : providerId === 'openrouter' ? 'OpenRouter' : providerId;
}

export function connectControlId(generation: number, stage: 'load' | 'refresh' | 'action',
  leg: 'models' | 'auth' | 'model' | 'logout' | 'provider'): string {
  return stage === 'load' ? `tui-connect:${generation}:${leg}` : `tui-connect:${generation}:${stage}:${leg}`;
}

function applyAuthResult(state: ConnectWizardState, event: ConnectWizardControlResult, refresh: boolean): ConnectWizardState {
  if (event.status !== 'succeeded' || !Array.isArray(event.result.profiles)) {
    return refresh ? {...state, phase: 'error', message: connectError(event.code), returnTo: 'auth'}
      : {...state, auth: {controlId: state.auth.controlId, status: 'failed', code: event.code}};
  }
  const profiles = event.result.profiles.flatMap(parseProfile);
  const next = {...state, profiles, auth: {controlId: state.auth.controlId, status: 'succeeded'} as ConnectWizardLoad};
  if (!refresh || state.phase !== 'refreshing-credential') return next;
  const profile = connectedProfile(next, state.providerId);
  return profile === undefined ? {...next, phase: 'error', message: '凭证已保存，但刷新后未找到可用连接', returnTo: 'auth'}
    : toModelSelection(next, state.providerId, profile.profileId);
}

function applyModelsResult(state: ConnectWizardState, event: ConnectWizardControlResult): ConnectWizardState {
  if (event.status !== 'succeeded' || !Array.isArray(event.result.models)) {
    return {...state, models: {controlId: state.models.controlId, status: 'failed', code: event.code}};
  }
  return {...state, models: {controlId: state.models.controlId, status: 'succeeded'}, modelCatalog: event.result.models.flatMap(parseModel)};
}

function parseProfile(value: unknown): readonly ConnectWizardProfile[] {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return [];
  const item = value as Record<string, unknown>;
  return typeof item.providerId === 'string' && typeof item.profileId === 'string'
    && typeof item.localStatus === 'string' && typeof item.providerDefault === 'boolean'
    ? [{providerId: item.providerId, profileId: item.profileId, localStatus: item.localStatus, providerDefault: item.providerDefault}] : [];
}

function parseModel(value: unknown): readonly ConnectWizardModel[] {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return [];
  const item = value as Record<string, unknown>;
  return typeof item.providerId === 'string' && typeof item.modelId === 'string' && typeof item.providerDefault === 'boolean'
    ? [{providerId: item.providerId, modelId: item.modelId, providerDefault: item.providerDefault}] : [];
}

function loginAction(state: ConnectWizardState, providerId: ConnectWizardProviderId,
  secretSource: 'store' | 'env', environmentName?: string): ConnectWizardEnterAction {
  return {kind: 'login', state: {...state, phase: 'logging-in', providerId, secretSource}, providerId,
    profileId: 'default', secretSource, ...(environmentName === undefined ? {} : {environmentName})};
}

function toModelSelection(state: ConnectWizardState, providerId: ConnectWizardProviderId, profileId: string): ConnectWizardState {
  const models = modelsForProvider(state, providerId);
  const index = Math.max(0, models.findIndex(model => model.providerDefault));
  return {...state, phase: 'select-model', providerId, profileId, modelIndex: index};
}

function returnFromError(state: Extract<ConnectWizardState, {readonly phase: 'error'}>): ConnectWizardState {
  if (state.returnTo === 'custom-confirm') return {...state, phase: 'custom-confirm'};
  if (state.returnTo === 'auth' && 'providerId' in state) return {...state, phase: 'select-auth', providerId: String(state.providerId), optionIndex: 0};
  if (state.returnTo === 'models' && 'providerId' in state && 'profileId' in state) return toModelSelection(state, String(state.providerId), String(state.profileId));
  if (state.returnTo === 'connected' && 'providerId' in state && 'profileId' in state) return {...state, phase: 'select-connected-action', providerId: String(state.providerId), profileId: String(state.profileId), optionIndex: 0};
  return {...state, phase: 'select-provider'};
}

function stateAction(state: ConnectWizardState): ConnectWizardEnterAction { return {kind: 'state', state}; }
function pendingLoad(controlId: string): ConnectWizardLoad { return {controlId, status: 'pending'}; }
function normalizeProviderIndex(state: ConnectWizardState): number {
  return Math.max(0, Math.min(connectWizardProviderOptions(state).length - 1, state.providerIndex));
}
function wrap(index: number, count: number, delta: -1 | 1): number { return (index + delta + count) % count; }
function stringResult(result: Readonly<Record<string, unknown>>, field: string): string { return typeof result[field] === 'string' ? result[field] : ''; }
function mergeProviderModel(models: readonly ConnectWizardModel[], model: ConnectWizardModel): readonly ConnectWizardModel[] {
  return [...models.filter(item => item.providerId !== model.providerId), model];
}
function withoutCustomValidation(custom: CustomProviderDraft): CustomProviderDraft {
  const {validation: _validation, ...withoutValidation} = custom;
  return withoutValidation;
}
function customValidation(state: ConnectWizardState, validation: string): ConnectWizardEnterAction {
  return stateAction({...state, custom: {...state.custom, validation}});
}
function customField(phase: keyof typeof TEXT_LIMITS): 'displayName' | 'providerId' | 'baseUrl' | 'modelId' {
  return phase === 'custom-name' ? 'displayName' : phase === 'custom-id' ? 'providerId'
    : phase === 'custom-base-url' ? 'baseUrl' : 'modelId';
}
function editValue(current: string, action: {readonly kind: 'append'; readonly text: string} | {readonly kind: 'backspace'}, limit: number): string {
  const next = action.kind === 'backspace' ? Array.from(current).slice(0, -1).join('')
    : current + Array.from(action.text).filter(point => !CONTROL_CHARACTER.test(point)).join('');
  return Array.from(next).length <= limit ? next : current;
}
function validBoundedText(value: string, codePoints: number, bytes: number): boolean {
  return value.trim() === value && value.length > 0 && Array.from(value).length <= codePoints
    && Buffer.byteLength(value, 'utf8') <= bytes && !CONTROL_CHARACTER.test(value);
}
function validHttpsUrl(value: string): boolean {
  if (value.trim() !== value || value.length === 0 || Array.from(value).length > 2_048 || CONTROL_CHARACTER.test(value)) return false;
  try {
    const parsed = new URL(value);
    return parsed.protocol === 'https:' && parsed.hostname.length > 0 && parsed.username.length === 0
      && parsed.password.length === 0 && parsed.search.length === 0 && parsed.hash.length === 0;
  } catch { return false; }
}
function suggestProviderId(name: string): string {
  const suggested = name.normalize('NFKD').toLowerCase().replace(/[^a-z0-9]+/gu, '-').replace(/^-+|-+$/gu, '').slice(0, 63);
  return PROVIDER_ID.test(suggested) ? suggested : 'custom-provider';
}
function connectError(code: string): string {
  const labels: Readonly<Record<string, string>> = {
    PROVIDER_DEFINITION_INVALID: '服务配置无效或该 ID 已存在，请返回修改',
    AUTH_STORE_INSECURE: '本机凭证存储未通过安全检查', AUTH_STORE_LOCKED: '本机凭证存储正在被占用',
    AUTH_STORE_CORRUPT: '本机凭证存储不可用', AUTH_LOGOUT_DRAIN_FAILED: '活动任务未能安全停止，尚未退出登录',
    AUTH_STORE_DELETE_FAILED: '本机凭证删除失败', AUTH_TRANSACTION_CONFLICT: '本机连接状态已变化，请刷新后重试',
    MODEL_UNKNOWN: '所选模型已不在本地目录中',
  };
  return labels[code] ?? '操作未完成，请返回修改或重试';
}
