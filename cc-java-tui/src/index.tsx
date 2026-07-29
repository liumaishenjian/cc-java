#!/usr/bin/env node
import process from 'node:process';
import {render} from 'ink';
import {AgentTui} from './app.js';
import {runNonInteractive} from './print-session.js';
import {
  installProcessExitGuard,
  StdioClient,
  type ChildProcessSpec,
} from './stdio-client.js';

const options = parseArguments(process.argv.slice(2));
delete process.env.CC_JAVA_SPIKE_COMMAND_BASE64;
delete process.env.CC_JAVA_SPIKE_PROMPT_BASE64;
const child = new StdioClient(options.child);
const removeExitGuard = installProcessExitGuard(child);

try {
  if (options.prompt !== undefined || !process.stdin.isTTY || !process.stdout.isTTY) {
    const prompt = options.prompt;
    if (prompt === undefined || prompt.trim().length === 0) {
      process.stderr.write('非交互模式必须提供 --prompt\n');
      process.exitCode = 2;
    } else {
      process.exitCode = await runNonInteractive(
        child,
        prompt,
        process.stdout,
        process.stderr,
      );
    }
  } else {
    const instance = render(<AgentTui client={child} />, {
      exitOnCtrlC: false,
      interactive: true,
    });
    await instance.waitUntilExit();
  }
} finally {
  removeExitGuard();
  child.terminate();
}

interface Options {
  readonly child: ChildProcessSpec;
  readonly prompt?: string;
}

function parseArguments(args: readonly string[]): Options {
  let childCommandBase64 = process.env.CC_JAVA_SPIKE_COMMAND_BASE64;
  let prompt = decodeOptionalBase64(process.env.CC_JAVA_SPIKE_PROMPT_BASE64);
  for (let index = 0; index < args.length; index++) {
    const argument = args[index];
    if (argument === '--child-command-base64') {
      childCommandBase64 = args[++index];
    } else if (argument === '--prompt') {
      prompt = args[++index];
    } else {
      throw new Error(`未知参数：${argument ?? ''}`);
    }
  }
  if (childCommandBase64 === undefined) {
    throw new Error('Spike 必须通过 --child-command-base64 提供结构化 Java 启动参数');
  }
  const childCommandJson = Buffer.from(childCommandBase64, 'base64').toString('utf8');
  const command = JSON.parse(childCommandJson) as unknown;
  if (
    !Array.isArray(command)
    || command.length < 1
    || command.length > 64
    || command.some(value => typeof value !== 'string' || value.length === 0)
  ) {
    throw new Error('--child-command-base64 必须解码为 1-64 个非空字符串组成的 JSON Array');
  }
  const executable = command[0];
  if (executable === undefined) {
    throw new Error('缺少 Java 可执行文件');
  }
  return {
    child: {
      executable,
      args: command.slice(1),
      cwd: process.cwd(),
    },
    ...(prompt === undefined ? {} : {prompt}),
  };
}

function decodeOptionalBase64(value: string | undefined): string | undefined {
  return value === undefined ? undefined : Buffer.from(value, 'base64').toString('utf8');
}
