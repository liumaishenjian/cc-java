import {PassThrough} from 'node:stream';
import {fileURLToPath} from 'node:url';
import {describe, expect, it} from 'vitest';
import {runNonInteractive} from '../src/print-session.js';
import {StdioClient} from '../src/stdio-client.js';

const fixture = fileURLToPath(new URL('./fixtures/fake-stdio-child.mjs', import.meta.url));

describe('runNonInteractive', () => {
  it('非 TTY 路径输出纯文本而不是 ANSI', async () => {
    const output = new PassThrough();
    let text = '';
    output.setEncoding('utf8');
    output.on('data', chunk => {
      text += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture],
      cwd: process.cwd(),
    });

    const code = await runNonInteractive(client, '你好', output);

    expect(code).toBe(0);
    expect(text).toBe('你好 agent\n');
    expect(text).not.toMatch(/\u001B\[/u);
  });

  it('非 TTY 超时输出固定诊断并返回失败退出码', async () => {
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let text = '';
    let diagnostic = '';
    output.setEncoding('utf8');
    diagnosticOutput.setEncoding('utf8');
    output.on('data', chunk => {
      text += chunk;
    });
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture, 'time-limit'],
      cwd: process.cwd(),
    });

    const code = await runNonInteractive(
      client,
      'timeout',
      output,
      diagnosticOutput,
    );

    expect(code).toBe(1);
    expect(text).toBe('');
    expect(diagnostic).toBe('cc-java: run timed out\n');
  });

  it('非 TTY 输出长度终止使用固定诊断', async () => {
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let diagnostic = '';
    diagnosticOutput.setEncoding('utf8');
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture, 'output-limit'],
      cwd: process.cwd(),
    });

    const code = await runNonInteractive(
      client,
      'long answer',
      output,
      diagnosticOutput,
    );

    expect(code).toBe(1);
    expect(diagnostic).toBe('cc-java: output limit reached\n');
  });

  it('Java 在活动 Run 中崩溃时返回固定失败而不是输出 Node 堆栈', async () => {
    const output = new PassThrough();
    const diagnosticOutput = new PassThrough();
    let diagnostic = '';
    diagnosticOutput.setEncoding('utf8');
    diagnosticOutput.on('data', chunk => {
      diagnostic += chunk;
    });
    const client = new StdioClient({
      executable: process.execPath,
      args: [fixture, 'crash'],
      cwd: process.cwd(),
    });

    const code = await runNonInteractive(
      client,
      'crash',
      output,
      diagnosticOutput,
    );

    expect(code).toBe(1);
    expect(diagnostic).toMatch(
      /^cc-java: Java 子进程意外退出（exit=17，stderr=0 bytes）\n$/u,
    );
    expect(diagnostic).not.toContain('Error:');
    expect(client.isClosed()).toBe(true);
  });
});
