import {describe, expect, it} from 'vitest';
import {
  applyModelSetupResult,
  beginModelSetupLogin,
  beginModelSetup,
  completeModelSetupLogin,
  editModelSetup,
  enterModelSetup,
  escapeModelSetup,
  projectModelSetupCredential,
} from '../src/model-setup.js';

describe('model setup', () => {
  it('只收集 URL 与模型并生成不含 secret 的幂等配置命令', () => {
    let state = beginModelSetup(1, true);
    state = editModelSetup(state, {kind: 'append', text: 'https://gateway.example/v1'});
    const modelPage = enterModelSetup(state);
    expect(modelPage.kind).toBe('state');
    state = modelPage.state;
    state = editModelSetup(state, {kind: 'append', text: 'model-x'});
    const submit = enterModelSetup(state);
    expect(submit.kind).toBe('control');
    if (submit.kind !== 'control') throw new Error('expected control');
    expect(submit.intent).toBe('providers.configure');
    expect(submit.arguments).toEqual({baseUrl: 'https://gateway.example/v1', modelId: 'model-x'});
    expect(JSON.stringify(submit)).not.toContain('apiKey');

    const credential = applyModelSetupResult(submit.state, {
      controlId: submit.controlId, intent: 'providers.configure', status: 'succeeded', code: 'OK',
      result: {providerId: 'codej-custom', displayName: 'CodeJ Custom', modelId: 'model-x'},
    });
    expect(credential.phase).toBe('credential');
    const loggingIn = beginModelSetupLogin(projectModelSetupCredential(credential, 'sk-••••a9K2', 18));
    expect(completeModelSetupLogin(loggingIn, 'succeeded', 'sk-…a9K2')).toMatchObject({
      phase: 'complete', credentialPreview: 'sk-…a9K2',
    });
  });

  it('拒绝非 HTTPS URL，且首次必填表单不能被 Esc 关闭', () => {
    let state = beginModelSetup(2, true);
    state = editModelSetup(state, {kind: 'append', text: 'http://unsafe.example/v1'});
    const invalid = enterModelSetup(state).state;
    expect(invalid.phase).toBe('form');
    expect(invalid.validation).toContain('HTTPS');
    expect(escapeModelSetup(invalid)).toEqual(invalid);
    expect(escapeModelSetup(beginModelSetup(3))).toBeUndefined();
  });
});
