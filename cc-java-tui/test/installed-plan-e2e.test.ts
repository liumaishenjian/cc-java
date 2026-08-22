import {spawnSync} from 'node:child_process';
import {lstat, mkdir, realpath} from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {describe, expect, it} from 'vitest';
import type {ProtocolEvent} from '../src/protocol.js';

const INSTALLED_ROOT_ENV = 'CODEJ_INSTALLED_E2E_ROOT';
const FIXTURE_CLASSES_ENV = 'CODEJ_INSTALLED_E2E_TEST_CLASSES';

/**
 * 安装包闭环必须显式启用：测试只加载安装目录中的 TUI JavaScript，并只把测试 Fixture
 * classes 与安装包 app/*.jar 交给 Java，避免源码 classes 或 Maven dependency classpath 掩盖漏包。
 */
describe('installed Plan TUI to Java flow', () => {
  it('drives natural Plan review through installed Ink and executes installed git_status', async () => {
    const installedRootValue = process.env[INSTALLED_ROOT_ENV];
    const fixtureClassesValue = process.env[FIXTURE_CLASSES_ENV];
    expect(installedRootValue, `${INSTALLED_ROOT_ENV} must name the validated installed copy`).toBeTruthy();
    expect(fixtureClassesValue, `${FIXTURE_CLASSES_ENV} must name compiled fixture test-classes`).toBeTruthy();

    const installedRoot = await realpath(path.resolve(installedRootValue!));
    const fixtureClasses = await realpath(path.resolve(fixtureClassesValue!));
    expect((await lstat(installedRoot)).isSymbolicLink()).toBe(false);
    expect((await lstat(fixtureClasses)).isDirectory()).toBe(true);
    const workspace = path.resolve(installedRoot, 'installed-plan-workspace');
    expect(path.relative(installedRoot, workspace)).toBe('installed-plan-workspace');
    await mkdir(workspace, {recursive: false});
    expect(await realpath(workspace)).toBe(workspace);
    await import('node:fs/promises').then(fs => fs.writeFile(
      path.join(workspace, 'installed-evidence.txt'), 'installed Plan E2E\n', 'utf8',
    ));
    const git = spawnSync('git', ['init', '--quiet'], {cwd: workspace, shell: false, encoding: 'utf8'});
    expect(git.status, `git init failed: ${git.stderr}`).toBe(0);

    const appUrl = pathToFileURL(path.join(installedRoot, 'tui', 'dist', 'src', 'app.js')).href;
    const clientUrl = pathToFileURL(path.join(installedRoot, 'tui', 'dist', 'src', 'stdio-client.js')).href;
    const reactUrl = pathToFileURL(path.join(installedRoot, 'tui', 'node_modules', 'react', 'index.js')).href;
    const testingUrl = pathToFileURL(path.join(
      installedRoot, 'tui', 'node_modules', 'ink-testing-library', 'build', 'index.js',
    )).href;
    const [{AgentTui}, {StdioClient}, reactModule, {render}] = await Promise.all([
      import(appUrl) as Promise<typeof import('../src/app.js')>,
      import(clientUrl) as Promise<typeof import('../src/stdio-client.js')>,
      import(reactUrl),
      import(testingUrl) as Promise<typeof import('ink-testing-library')>,
    ]);
    const React = (reactModule.default ?? reactModule) as typeof import('react');
    const launchClasspath = [fixtureClasses, path.join(installedRoot, 'app', '*')]
      .join(path.delimiter);
    const client = new StdioClient({
      executable: 'java',
      args: ['-cp', launchClasspath,
        'io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolFixtureMain',
        'plan-runtime', workspace],
      cwd: workspace,
      env: {...process.env, CC_JAVA_PLAN_FAKE_CLASSPATH: fixtureClasses},
    }, {shutdownTimeoutMs: 2_000});
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    let exitResult: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number} | undefined;
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.onExit(result => { exitResult = result; });
    const view = render(React.createElement(AgentTui, {client}));

    try {
      await waitFor(() => view.lastFrame()?.includes('就绪') === true,
        () => diagnostic(view.lastFrame(), events, failures, exitResult));
      view.stdin.write('/plan safe task');
      view.stdin.write('\r');
      await waitFor(() => view.lastFrame()?.includes('实施计划 · revision 3') === true
        && view.lastFrame()?.includes('批准并自动执行') === true,
      () => diagnostic(view.lastFrame(), events, failures, exitResult));
      await new Promise(resolve => setTimeout(resolve, 50));

      view.stdin.write('\r');
      await waitFor(() => events.some(event => event.type === 'plan.execution.accepted'),
        () => diagnostic(view.lastFrame(), events, failures, exitResult));
      const executionRequestId = events.find(event => event.type === 'plan.execution.accepted')!.requestId;
      await waitFor(() => events.some(event => event.type === 'tool.completed'
        && event.requestId === executionRequestId && event.payload.toolName === 'git_status'),
      () => diagnostic(view.lastFrame(), events, failures, exitResult));
      await waitFor(() => events.some(event => event.type === 'run.completed'
        && event.requestId === executionRequestId
        && String(event.payload.finalText).includes('approved plan executed')),
      () => diagnostic(view.lastFrame(), events, failures, exitResult));
      await waitFor(() => events.some(event => event.type === 'plan.verification.completed'
        && event.requestId === executionRequestId),
      () => diagnostic(view.lastFrame(), events, failures, exitResult));
      await waitFor(() => view.lastFrame()?.includes('检查工作区') === true
        && view.lastFrame()?.includes('approved plan executed') === true
        && view.lastFrame()?.includes('已完成') === true,
      () => diagnostic(view.lastFrame(), events, failures, exitResult));

      const finalFrame = view.lastFrame() ?? '';
      expect(finalFrame).not.toContain('无法关联');
      expect(finalFrame).not.toContain('连接已关闭');
      expect(finalFrame).toContain('计划证据已验证');
      expect(events.filter(event => event.type === 'tool.completed'
        && event.requestId === executionRequestId && event.payload.toolName === 'git_status')).toHaveLength(1);
      expect(events.some(event => event.type === 'plan.verification.required'
        && event.requestId === executionRequestId)).toBe(false);
      expect(events.some(event => event.type === 'tool.completed'
        && event.requestId === executionRequestId && event.payload.toolName === 'git_status')).toBe(true);
      expect(failures).toEqual([]);
    } finally {
      await client.shutdown();
      view.unmount();
    }
    await waitFor(() => exitResult !== undefined,
      () => diagnostic(view.lastFrame(), events, failures, exitResult));
    expect(exitResult?.code).toBe(0);
    expect(exitResult?.signal).toBeNull();
    expect(exitResult?.stderrBytes).toBe(0);
  }, 30_000);
});

async function waitFor(predicate: () => boolean, onTimeout: () => string): Promise<void> {
  const deadline = Date.now() + 15_000;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(onTimeout());
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}

function diagnostic(
  frame: string | undefined,
  events: readonly ProtocolEvent[],
  failures: readonly string[],
  exitResult: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number} | undefined,
): string {
  const counts = new Map<string, number>();
  for (const event of events) counts.set(event.type, (counts.get(event.type) ?? 0) + 1);
  const eventCounts = [...counts.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([type, count]) => `${safeEventType(type)}=${count}`)
    .join(',');
  const frameMetadata = frame === undefined
    ? 'absent'
    : `present:chars=${Math.min(frame.length, 999_999)}:lines=${Math.min(frame.split('\n').length, 9_999)}`;
  const exitMetadata = exitResult === undefined
    ? 'pending'
    : `code=${exitResult.code ?? 'null'}:signal=${safeSignal(exitResult.signal)}:stderrBytes=${Math.min(exitResult.stderrBytes, 999_999)}`;
  return `installed Plan E2E timeout; frame=${frameMetadata}; eventCount=${events.length}; eventTypes=[${eventCounts}]; failureCount=${failures.length}; exit=${exitMetadata}`;
}

function safeEventType(type: string): string {
  const known = new Set([
    'plan.execution.accepted', 'plan.verification.completed', 'plan.verification.required',
    'run.completed', 'run.failed', 'session.command.result', 'tool.completed',
  ]);
  return known.has(type) ? type : 'other';
}

function safeSignal(signal: NodeJS.Signals | null): string {
  return signal !== null && /^SIG[A-Z0-9]+$/.test(signal) ? signal : 'null-or-other';
}
