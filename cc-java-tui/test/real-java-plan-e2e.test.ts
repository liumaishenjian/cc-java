import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {describe, expect, it} from 'vitest';
import {StdioClient} from '../src/stdio-client.js';
import type {ProtocolEvent} from '../src/protocol.js';

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const workspacePath = path.resolve(testDirectory, '..', '..');
const moduleClassDirectories = [
  'cc-java-cli', 'cc-java-core', 'cc-java-domain', 'cc-java-model-spring-ai', 'cc-java-tools-local',
  'cc-java-tools-web', 'cc-java-mcp', 'cc-java-protocol', 'cc-java-sdk',
].map(module => path.resolve(workspacePath, module, 'target', 'classes'));

/** Cross-process contract: this deliberately starts the compiled Java CLI, not the fake child. */
describe('real Java stdio plan flow', () => {
  it('starts natural-language Plan runtime and keeps approval bound to the proposal', async () => {
    const classpath = process.env.CC_JAVA_TEST_CLASSPATH;
    expect(classpath, 'CC_JAVA_TEST_CLASSPATH must point to compiled Java classes and dependencies').toBeTruthy();
    const workspace = workspacePath.replaceAll('\\', '/');
    const dependencyClasspath = process.env.CC_JAVA_TEST_DEPENDENCY_CLASSPATH;
    const planFakeClasspath = process.env.CC_JAVA_PLAN_FAKE_CLASSPATH;
    const effectiveClasspath = dependencyClasspath === undefined
      ? classpath!
      : [...moduleClassDirectories, dependencyClasspath].join(path.delimiter);
    expect(planFakeClasspath,
      'CC_JAVA_PLAN_FAKE_CLASSPATH must point to the deterministic Plan model fixture').toBeTruthy();
    const launchClasspath = [planFakeClasspath!, effectiveClasspath].join(path.delimiter);
    const client = new StdioClient({
      executable: 'java',
      args: ['-cp', launchClasspath,
        'io.github.liumaishenjian.ccjava.cli.stdio.StdioProtocolFixtureMain', 'plan-runtime', workspace],
      cwd: workspace,
      env: {...process.env, CC_JAVA_PLAN_FAKE_CLASSPATH: planFakeClasspath!},
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

      const requestId = client.startPlan('分析当前项目并给出只读实施计划');
      await waitFor(() => events.some(event => event.type === 'plan.review.requested'
        && event.requestId === requestId), () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'run.completed' && event.requestId === requestId));
      const review = events.find(event => event.type === 'plan.review.requested'
        && event.requestId === requestId)!;
      const planId = String(review.payload.planId);
      const revision = Number(review.payload.revision);
      const contentDigest = String(review.payload.contentDigest);
      const workspaceDigest = String(review.payload.workspaceDigest);
      expect(String(review.payload.markdown)).toContain('Cross-process plan');
      expect(revision).toBe(3); // plan + evidence declaration + review transition
      expect(events.some(event => event.type === 'plan.proposed')).toBe(false);
      expect(events.some(event => event.type === 'model.text.delta' && event.requestId === requestId)).toBe(false);

      const executionRequest = client.resolvePlanReview({
        planId, revision, contentDigest, workspaceDigest,
        decision: 'APPROVE_USER', contextPolicy: 'KEEP', feedback: '',
      });
      await waitFor(() => events.some(event => event.type === 'plan.execution.accepted'
        && event.requestId === executionRequest), () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'tool.completed'
        && event.requestId === executionRequest && event.payload.toolName === 'git_status'),
      () => diagnostic(events, failures, exit));
      await waitFor(() => events.some(event => event.type === 'run.completed'
        && event.requestId === executionRequest), () => diagnostic(events, failures, exit));
      expect(events.some(event => event.type === 'run.completed'
        && event.requestId === executionRequest
        && String(event.payload.finalText).includes('approved plan executed'))).toBe(true);
      expect(events.filter(event => event.type === 'tool.completed'
        && event.requestId === executionRequest && event.payload.toolName === 'git_status')).toHaveLength(1);
      await waitFor(() => events.some(event => event.type === 'plan.verification.completed'
        && event.requestId === executionRequest), () => diagnostic(events, failures, exit));
      expect(events.some(event => event.type === 'plan.verification.required')).toBe(false);
      expect(events.filter(event => event.requestId === executionRequest)
        .some(event => event.type === 'plan.proposed')).toBe(false);
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
    if (Date.now() >= deadline) throw new Error(onTimeout?.() ?? '等待真实 Java stdio 事件超时');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}
function diagnostic(
  events: readonly ProtocolEvent[], failures: readonly string[],
  exit: {code: number | null; signal: NodeJS.Signals | null; stderrBytes: number} | undefined,
): string {
  const eventSummary = events.map(event => `${event.type}#${event.requestId}(${event.sequence})`).join(', ');
  return `等待真实 Java 事件超时；events=[${eventSummary}], failures=[${failures.join(' | ')}], exit=${JSON.stringify(exit)}`;
}
