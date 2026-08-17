import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {describe, expect, it} from 'vitest';
import {StdioClient} from '../src/stdio-client.js';
import type {ProtocolEvent} from '../src/protocol.js';

/** Cross-process contract: this deliberately starts the compiled Java CLI, not the fake child. */
describe('real Java stdio plan flow', () => {
  it('correlates plan commands and keeps side effects gated by approval', async () => {
    const classpath = process.env.CC_JAVA_TEST_CLASSPATH;
    expect(classpath, 'CC_JAVA_TEST_CLASSPATH must point to compiled Java classes and dependencies').toBeTruthy();
    const workspacePath = path.resolve(process.cwd(), '..');
    const workspace = workspacePath.replaceAll('\\', '/');
    const dependencyClasspath = process.env.CC_JAVA_TEST_DEPENDENCY_CLASSPATH;
    const effectiveClasspath = dependencyClasspath === undefined ? classpath! : [
      'cc-java-cli/target/classes', 'cc-java-core/target/classes', 'cc-java-domain/target/classes',
      'cc-java-model-spring-ai/target/classes', 'cc-java-tools-local/target/classes',
      'cc-java-tools-web/target/classes', 'cc-java-mcp/target/classes', 'cc-java-protocol/target/classes',
      'cc-java-sdk/target/classes', dependencyClasspath,
    ].join(path.delimiter);
    const workspaceDigest = gitWorkspaceDigest(workspacePath);
    expect(workspaceDigest).toMatch(/^[a-f0-9]{64}$/u);
    const client = new StdioClient({
      executable: 'java',
      args: ['-cp', effectiveClasspath, 'io.github.liumaishenjian.ccjava.cli.CcJavaCliMain', '--stdio', '--workspace', workspace],
      cwd: workspace,
    }, {shutdownTimeoutMs: 2_000});
    const events: ProtocolEvent[] = [];
    const failures: string[] = [];
    let exit: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number} | undefined;
    client.onEvent(event => events.push(event));
    client.onFailure(message => failures.push(message));
    client.onExit(result => { exit = result; });
    try {
      client.initialize();
      await waitFor(() => events.some(event => event.type === 'initialized'),
        () => diagnostic(events, failures, exit));

      const send = (id: string, intent: Parameters<StdioClient['sessionCommand']>[1], arguments_: Record<string, unknown>) => {
      const requestId = client.sessionCommand(id, intent, arguments_);
      return waitForEvent(events, requestId);
    };
    const plan = await send('plan-create', 'plan', {
      objective: 'cross-process plan', workspaceDigest: workspaceDigest, steps: [
        {ordinal: 1, title: 'inspect', detail: 'read', expectedDigest: workspaceDigest},
        {ordinal: 2, title: 'verify', detail: 'test', expectedDigest: workspaceDigest},
      ],
    });
    expect(plan.payload.status).toBe('succeeded');
    expect((plan.payload.result as Record<string, unknown>).activeStep).toBeNull();

    const denied = await send('plan-begin-before', 'plan-step-begin', {workspaceDigest: '0'.repeat(64)});
    expect(denied.payload.status).toBe('rejected');
    expect(denied.payload.code).not.toBe('ok');

    const approved = await send('plan-approve', 'plan-approve', {workspaceDigest: workspaceDigest});
    expect(approved.payload.status).toBe('succeeded');
    const begun = await send('plan-begin-after', 'plan-step-begin', {workspaceDigest: workspaceDigest});
    expect(begun.payload.status).toBe('succeeded');
    const active = begun.payload.result as Record<string, unknown>;
    expect(active.activeStep).toBe(1);
    expect(active.steps).toHaveLength(2);
    expect([active.activeStep]).toHaveLength(1);

    const completed = await send('plan-step-complete', 'plan-step-complete', {workspaceDigest});
    expect(completed.payload.status).toBe('succeeded');
    const final = completed.payload.result as Record<string, unknown>;
    expect(final.activeStep).toBeNull();
    expect(final.status).toBe('APPROVED');
    const finalPlan = await send('plan-status', 'plan-status', {});
    expect((finalPlan.payload.result as Record<string, unknown>).nextStep).toBe(2);

    const duplicate = await send('plan-status-duplicate', 'plan-status', {});
    expect(duplicate.payload.status).toBe('succeeded');
    const stale = await send('plan-stale', 'plan-step-begin', {workspaceDigest: '0'.repeat(64)});
    expect(stale.payload.status).toBe('rejected');
    expect(stale.payload.code).not.toBe('ok');
    expect(failures).toEqual([]);
    } finally {
      await client.shutdown();
    }
    await waitFor(() => exit !== undefined, () => diagnostic(events, failures, exit));
    expect(exit?.code).toBe(0);
    expect(exit?.signal).toBeNull();
    expect(exit?.stderrBytes).toBe(0);
  }, 30_000);
});

async function waitForEvent(events: ProtocolEvent[], requestId: string): Promise<ProtocolEvent> {
  await waitFor(() => events.some(event => event.type === 'session.command.result' && event.requestId === requestId));
  return events.find(event => event.type === 'session.command.result' && event.requestId === requestId)!;
}
async function waitFor(predicate: () => boolean, onTimeout?: () => string): Promise<void> {
  const deadline = Date.now() + 15_000;
  while (!predicate()) {
    if (Date.now() >= deadline) {
      throw new Error(onTimeout?.() ?? '等待真实 Java stdio 事件超时');
    }
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}

function gitWorkspaceDigest(workspace: string): string {
  const stdout = execFileSync('git', ['status', '--porcelain=v1', '--branch', '--untracked-files=normal'], {
    cwd: workspace,
    encoding: 'utf8',
    windowsHide: true,
  });
  const lines = stdout.split(/\r?\n/u).filter(line => line.length > 0);
  let branch = 'unknown';
  let staged = 0;
  let unstaged = 0;
  let untracked = 0;
  for (const line of lines) {
    if (line.startsWith('## ')) branch = line.slice(3).trim();
    else if (line.length >= 2) {
      if (line.startsWith('??')) untracked++;
      else {
        if (line[0] !== ' ') staged++;
        if (line[1] !== ' ') unstaged++;
      }
    }
  }
  return createHash('sha256').update(`${true}\n${branch}\n${staged}\n${unstaged}\n${untracked}`, 'utf8').digest('hex');
}

function diagnostic(
  events: readonly ProtocolEvent[],
  failures: readonly string[],
  exit: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number} | undefined,
): string {
  const eventSummary = events.map(event => `${event.type}#${event.requestId ?? '-'}(${event.sequence})`).join(', ');
  const failureSummary = failures.length === 0 ? 'none' : failures.join(' | ');
  const exitSummary = exit === undefined ? 'not observed' : JSON.stringify(exit);
  return `等待真实 Java initialized 超时；events=[${eventSummary}], failures=[${failureSummary}], exit=${exitSummary}`;
}
