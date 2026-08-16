const INDEPENDENT_CONTROL_PREFIX = 'tui-provider';

/** 为独立高级 Provider 命令生成与连接向导不相交、且绑定 intent 的 control ID。 */
export function independentProviderControlId(sequence: number, intent: string): string {
  return `${INDEPENDENT_CONTROL_PREFIX}:${sequence}:${intent}`;
}

/**
 * 校验独立 Provider 结果的 namespace、单调序号与 intent 绑定。
 *
 * 该分类不保存历史 pending 集合；请求/结果精确对应仍由 stdio client 校验。
 */
export function isIndependentProviderControlResult(controlId: string, intent: string): boolean {
  const match = /^tui-provider:([1-9]\d*):([a-z]+\.[a-z]+)$/u.exec(controlId);
  return match !== null && match[2] === intent;
}
