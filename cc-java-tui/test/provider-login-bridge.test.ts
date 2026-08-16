import {EventEmitter} from 'node:events';
import type {ChildProcess, SpawnOptions} from 'node:child_process';
import {PassThrough} from 'node:stream';
import {afterEach, describe, expect, it, vi} from 'vitest';
import {
  ProviderLoginBridge,
  type ChildProcessSpec,
  type ProviderLoginRequest,
} from '../src/stdio-client.js';

const MAIN = 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain';
const SPEC: ChildProcessSpec = {
  executable: 'java',
  args: ['-cp', 'fixed-classpath', MAIN, '--stdio'],
  cwd: 'fixed-workspace',
  env: {PATH: 'fixed-path'},
};
const STORE: ProviderLoginRequest = {
  providerId: 'anthropic',
  profileId: 'default',
  secretSource: 'store',
};
const DEFAULT_STORE: ProviderLoginRequest = {...STORE, setDefault: true};

class FakeChild extends EventEmitter {
  readonly kill = vi.fn(() => true);
  readonly stdin = new PassThrough();
  readonly stderr = new PassThrough();
}

function terminal(isTTY = true) {
  return {
    isTTY,
    isRaw: true,
    pause: vi.fn(),
    resume: vi.fn(),
    setRawMode: vi.fn(),
  };
}

function spawnFixture(child: FakeChild, failSynchronously = false) {
  return vi.fn((executable: string, args: readonly string[], options: SpawnOptions) => {
    if (failSynchronously) throw new Error('spawn failed');
    return child as unknown as ChildProcess;
  });
}

afterEach(() => vi.useRealTimers());

describe('ProviderLoginBridge', () => {
  it('只从固定 Java 主类派生参数且使用继承终端与 shell=false', async () => {
    const child = new FakeChild();
    const spawnProcess = spawnFixture(child);
    const tty = terminal();
    const bridge = new ProviderLoginBridge(SPEC, {spawnProcess, terminal: tty});

    const result = bridge.login(STORE);
    child.emit('exit', 0, null);

    await expect(result).resolves.toEqual({status: 'succeeded', exitCode: 0});
    expect(spawnProcess).toHaveBeenCalledWith('java', [
      '-cp', 'fixed-classpath', MAIN, 'auth', 'login',
      '--provider', 'anthropic', '--profile', 'default', '--tui-preview',
    ], expect.objectContaining({
      cwd: 'fixed-workspace', env: SPEC.env, shell: false,
      stdio: ['inherit', 'inherit', 'pipe'], windowsHide: false,
    }));
    expect(tty.pause).toHaveBeenCalledTimes(1);
    expect(tty.resume).toHaveBeenCalledTimes(1);
    expect(tty.setRawMode.mock.calls).toEqual([[false], [true]]);
    expect(bridge.active()).toBe(false);
  });

  it('仅在显式 setDefault=true 时追加持久默认参数，旧请求保持兼容', async () => {
    const defaultChild = new FakeChild();
    const defaultSpawn = spawnFixture(defaultChild);
    const bridge = new ProviderLoginBridge(SPEC, {spawnProcess: defaultSpawn, terminal: terminal()});
    const result = bridge.login(DEFAULT_STORE);
    defaultChild.emit('exit', 0, null);
    await result;
    expect(defaultSpawn.mock.calls[0]?.[1]).toEqual([
      '-cp', 'fixed-classpath', MAIN, 'auth', 'login',
      '--provider', 'anthropic', '--profile', 'default', '--tui-preview', '--set-default',
    ]);

    const legacyChild = new FakeChild();
    const legacySpawn = spawnFixture(legacyChild);
    const legacy = new ProviderLoginBridge(SPEC, {spawnProcess: legacySpawn, terminal: terminal()});
    const legacyResult = legacy.login(STORE);
    legacyChild.emit('exit', 0, null);
    await legacyResult;
    expect(legacySpawn.mock.calls[0]?.[1]).not.toContain('--set-default');
  });

  it('只接受 Java 生成的固定格式脱敏摘要', async () => {
    const child = new FakeChild();
    const bridge = new ProviderLoginBridge(SPEC, {
      spawnProcess: spawnFixture(child), terminal: terminal(),
    });

    const result = bridge.login(STORE);
    child.stderr.write('CODEJ_CREDENTIAL_PREVIEW=sk-...a9K2\n');
    child.emit('exit', 0, null);

    await expect(result).resolves.toEqual({
      status: 'succeeded', exitCode: 0, credentialPreview: 'sk-…a9K2',
    });
  });

  it('首次配置通过一次性 stdin 写入且立即清零调用方缓冲', async () => {
    const child = new FakeChild();
    const chunks: Buffer[] = [];
    child.stdin.on('data', chunk => chunks.push(Buffer.from(chunk)));
    const secretBytes = Buffer.from('sk-protected-a9K2');
    const bridge = new ProviderLoginBridge(SPEC, {
      spawnProcess: spawnFixture(child), terminal: terminal(),
    });

    const result = bridge.login({...STORE, secretSource: 'stdin', secretBytes});
    child.emit('exit', 0, null);

    await expect(result).resolves.toEqual({status: 'succeeded', exitCode: 0});
    expect(Buffer.concat(chunks).toString('utf8')).toBe('sk-protected-a9K2\n');
    expect(secretBytes.every(byte => byte === 0)).toBe(true);
  });

  it('spawn 同步失败也只恢复一次终端并返回 typed failed', async () => {
    const tty = terminal();
    const bridge = new ProviderLoginBridge(SPEC, {
      spawnProcess: spawnFixture(new FakeChild(), true), terminal: tty,
    });

    await expect(bridge.login(STORE)).resolves.toEqual({status: 'failed', exitCode: null});
    expect(tty.pause).toHaveBeenCalledTimes(1);
    expect(tty.resume).toHaveBeenCalledTimes(1);
    expect(tty.setRawMode.mock.calls).toEqual([[false], [true]]);
  });

  it('spawn error 与随后 exit 竞争时只完成并恢复一次', async () => {
    const child = new FakeChild();
    const tty = terminal();
    const bridge = new ProviderLoginBridge(SPEC, {spawnProcess: spawnFixture(child), terminal: tty});

    const result = bridge.login(STORE);
    child.emit('error', new Error('late spawn error'));
    child.emit('exit', 1, null);

    await expect(result).resolves.toEqual({status: 'failed', exitCode: null});
    expect(tty.resume).toHaveBeenCalledTimes(1);
  });

  it('cancel 杀死活动进程并稳定胜出 exit', async () => {
    const child = new FakeChild();
    const bridge = new ProviderLoginBridge(SPEC, {
      spawnProcess: spawnFixture(child), terminal: terminal(),
    });

    const result = bridge.login(STORE);
    bridge.cancel();
    child.emit('exit', null, 'SIGTERM');

    await expect(result).resolves.toEqual({status: 'cancelled', exitCode: null});
    expect(child.kill).toHaveBeenCalledTimes(1);
  });

  it('timeout 杀死活动进程且迟到 exit 不改写结果', async () => {
    vi.useFakeTimers();
    const child = new FakeChild();
    const tty = terminal();
    const bridge = new ProviderLoginBridge(SPEC, {
      timeoutMs: 1_000, spawnProcess: spawnFixture(child), terminal: tty,
    });

    const result = bridge.login(STORE);
    await vi.advanceTimersByTimeAsync(1_000);
    child.emit('exit', 0, null);

    await expect(result).resolves.toEqual({status: 'timed_out', exitCode: null});
    expect(child.kill).toHaveBeenCalledTimes(1);
    expect(tty.resume).toHaveBeenCalledTimes(1);
  });

  it('并发登录在 spawn 前即被拒绝', async () => {
    const child = new FakeChild();
    const bridge = new ProviderLoginBridge(SPEC, {
      spawnProcess: spawnFixture(child), terminal: terminal(),
    });

    const first = bridge.login(STORE);
    await expect(bridge.login(STORE)).rejects.toThrow('已有 Provider login 正在执行');
    child.emit('exit', 0, null);
    await expect(first).resolves.toEqual({status: 'succeeded', exitCode: 0});
  });

  it('STORE 在非 TTY fail closed，ENV 仅接受合法名称', async () => {
    const spawnProcess = spawnFixture(new FakeChild());
    const bridge = new ProviderLoginBridge(SPEC, {spawnProcess, terminal: terminal(false)});

    await expect(bridge.login(STORE)).rejects.toThrow(
      'STORE 登录需要可交互 TTY；可改用 /connect <provider> <profile> env <ENV_NAME>',
    );
    expect(spawnProcess).not.toHaveBeenCalled();
    await expect(bridge.login({...STORE, secretSource: 'env', environmentName: 'bad-name'}))
      .rejects.toThrow('ENV name 无效');
  });

  it('要求唯一主类并以唯一 --stdio 结尾，派生登录时不继承 workspace/model 文本', async () => {
    expect(() => new ProviderLoginBridge({...SPEC, args: [...SPEC.args, '--model', 'untrusted']}))
      .toThrow('以 --stdio 结尾');
    expect(() => new ProviderLoginBridge({...SPEC, args: ['-cp', 'x', MAIN, '--print']}))
      .toThrow('以 --stdio 结尾');
    const child = new FakeChild();
    const spawnProcess = spawnFixture(child);
    const bridge = new ProviderLoginBridge({...SPEC, args: [
      '-cp', 'fixed-classpath', MAIN, '--workspace', 'untrusted-workspace', '--model', 'untrusted-model', '--stdio',
    ]}, {spawnProcess, terminal: terminal()});
    const result = bridge.login(STORE);
    child.emit('exit', 0, null);
    await result;
    expect(spawnProcess.mock.calls[0]?.[1]).not.toContain('untrusted-workspace');
    expect(spawnProcess.mock.calls[0]?.[1]).not.toContain('untrusted-model');
  });
});
