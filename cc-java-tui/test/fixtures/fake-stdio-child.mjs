import readline from 'node:readline';

let eventSequence = 1;
let sessionId;
let activeRunId;
let timer;
let inputAssembly;
const mode = process.argv[2] ?? 'normal';

const reader = readline.createInterface({input: process.stdin, crlfDelay: Infinity});
if (mode === 'time-limit-ignore-shutdown' || mode === 'bad-sequence-stay-alive'
  || mode === 'protocol-error-stay-alive' || mode === 'initialize-protocol-failure') {
  setInterval(() => {}, 1_000);
}
reader.on('line', line => {
  if (Buffer.byteLength(`${line}\n`, 'utf8') >= 64 * 1024) process.exit(23);
  let command = JSON.parse(line);
  if (command.type === 'initialize') {
    if (mode === 'initialize-protocol-failure') {
      process.stdout.write('{invalid-json}\n');
      return;
    }
    sessionId = 'session-1';
    emit('initialized', command.requestId, {protocolVersion: 0}, sessionId);
  } else if (command.type === 'file.suggest') {
    if (mode === 'suggest-error') {
      emit('protocol.error', command.requestId, {code: 'FILE_SUGGEST_UNAVAILABLE'}, sessionId);
      return;
    }
    const candidates = command.payload.query.includes('space')
      ? ['dir/file name.md'] : [`src/${command.payload.query || 'App'}.java`];
    if (mode === 'suggest-unknown-request') {
      emit('file.suggestions', 'unknown-request', {query: command.payload.query, candidates}, sessionId);
      return;
    }
    if (mode === 'suggest-wrong-query') {
      emit('file.suggestions', command.requestId, {query: 'other', candidates}, sessionId);
      return;
    }
    if (mode === 'suggest-wrong-session') {
      emit('file.suggestions', command.requestId, {query: command.payload.query, candidates}, 'session-stale');
      return;
    }
    emit('file.suggestions', command.requestId, {query: command.payload.query, candidates}, sessionId);
    if (mode === 'suggest-duplicate') {
      emit('file.suggestions', command.requestId, {query: command.payload.query, candidates}, sessionId);
    }
  } else if (command.type === 'input.begin') {
    inputAssembly = {requestId: command.requestId, inputId: command.payload.inputId, chunks: []};
    if (mode === 'chunk-error-begin') {
      emit('protocol.error', command.requestId, {code: 'INPUT_BEGIN_REJECTED'}, sessionId);
      inputAssembly = undefined;
    }
  } else if (command.type === 'input.chunk') {
    if (mode === 'chunk-error-chunk' && inputAssembly?.chunks.length === 0) {
      emit('protocol.error', command.requestId, {code: 'INPUT_CHUNK_REJECTED'}, sessionId);
      inputAssembly = undefined;
    } else if (inputAssembly !== undefined) {
      inputAssembly.chunks.push(command.payload.text);
    }
  } else if (command.type === 'input.commit') {
    if (mode === 'chunk-error-commit') {
      emit('protocol.error', command.requestId, {code: 'INPUT_COMMIT_REJECTED'}, sessionId);
      inputAssembly = undefined;
      return;
    }
    if (inputAssembly === undefined) return;
    command = {
      ...command,
      type: 'run.start',
      requestId: inputAssembly.requestId,
      payload: {prompt: inputAssembly.chunks.join('')},
    };
    inputAssembly = undefined;
  }
  if (command.type === 'plan.review.resolve') {
    if (command.payload.decision === 'CONTINUE_PLANNING') {
      activeRunId = 'run-plan-feedback';
      emit('run.started', command.requestId, {}, sessionId, activeRunId);
    } else if (mode === 'plan-review-unexpected-start') {
      activeRunId = 'run-plan-unexpected';
      emit('run.started', command.requestId, {}, sessionId, activeRunId);
    } else {
      emit('protocol.error', command.requestId, {code: 'PLAN_REVIEW_RESOLVED'}, sessionId);
    }
    return;
  }
  if (command.type === 'run.start') {
    if (activeRunId !== undefined && mode.startsWith('steering')) {
      if (mode === 'steering-invalid-payload') {
        emit('steering.queued', command.requestId, {queueDepth: 1, prompt: 'LEAK'}, sessionId);
      } else if (mode === 'steering-wrong-request') {
        emit('steering.queued', 'wrong-request', {queueDepth: 1}, sessionId);
      } else if (mode === 'steering-wrong-session') {
        emit('steering.queued', command.requestId, {queueDepth: 1}, 'session-stale');
      } else if (mode === 'steering-queue-full' && command.payload.prompt === 'rejected') {
        emit('protocol.error', command.requestId, {code: 'STEERING_QUEUE_FULL'}, sessionId);
      } else if (mode === 'steering-queue-full-late-start' && command.payload.prompt === 'rejected') {
        emit('protocol.error', command.requestId, {code: 'STEERING_QUEUE_FULL'}, sessionId);
        emit('run.started', command.requestId, {promptChars: command.payload.prompt.length}, sessionId, 'run-2');
      } else if (mode === 'steering-duplicate-queued') {
        emit('steering.queued', command.requestId, {queueDepth: 1}, sessionId);
        emit('steering.queued', command.requestId, {queueDepth: 1}, sessionId);
      } else if (mode === 'steering-discarded-before-queued') {
        emit('steering.discarded', command.requestId, {reason: 'clear'}, sessionId);
      } else if (mode === 'steering-duplicate-discarded') {
        emit('steering.queued', command.requestId, {queueDepth: 1}, sessionId);
        emit('steering.discarded', command.requestId, {reason: 'clear'}, sessionId);
        emit('steering.discarded', command.requestId, {reason: 'clear'}, sessionId);
      } else if (mode === 'steering-start-before-queued') {
        emit('run.started', command.requestId, {promptChars: command.payload.prompt.length}, sessionId, 'run-2');
      } else {
        emit('steering.queued', command.requestId, {queueDepth: 1}, sessionId);
        emit('steering.discarded', command.requestId, {reason: 'clear'}, sessionId);
      }
      return;
    }
    activeRunId = 'run-1';
    if (mode === 'steering-race') {
      timer = setTimeout(() => {
        emit('run.started', command.requestId, {promptChars: command.payload.prompt.length}, sessionId, activeRunId);
      }, 40);
      return;
    }
    emit('run.started', command.requestId, {promptChars: command.payload.prompt.length}, sessionId, activeRunId);
    if (mode === 'crash') {
      process.exit(17);
    }
    if (mode === 'ignore-cancel' || mode === 'never-terminal' || mode.startsWith('steering')) {
      return;
    }
    if (mode === 'bad-sequence' || mode === 'bad-sequence-stay-alive') {
      eventSequence++;
      emit('model.text.delta', command.requestId, {text: 'bad'}, sessionId, activeRunId);
      return;
    }
    if (mode === 'protocol-error-stay-alive') {
      emit('protocol.error', command.requestId, {code: 'RUN_REJECTED'}, sessionId, activeRunId);
      activeRunId = undefined;
      return;
    }
    if (mode === 'time-limit' || mode === 'time-limit-ignore-shutdown') {
      emit(
        'run.failed',
        command.requestId,
        {stopReason: 'time_limit_reached'},
        sessionId,
        activeRunId,
      );
      activeRunId = undefined;
      return;
    }
    if (mode === 'output-limit') {
      emit(
        'run.failed',
        command.requestId,
        {stopReason: 'output_limit_reached'},
        sessionId,
        activeRunId,
      );
      activeRunId = undefined;
      return;
    }
    if (mode === 'provider-configuration-required' || mode === 'provider-error') {
      emit(
        'run.failed',
        command.requestId,
        {
          stopReason: 'model_error',
          modelTurns: 1,
          toolCalls: 0,
          modelFailure: {
            category: mode === 'provider-error' ? 'provider_error' : 'configuration_required',
            attempts: 1,
            receivedOutput: false,
          },
        },
        sessionId,
        activeRunId,
      );
      activeRunId = undefined;
      return;
    }
    if (mode === 'approval') {
      emit(
        'approval.requested',
        command.requestId,
        {
          approvalId: 'approval-1',
          ordinal: 1,
          toolName: 'apply_patch',
          effect: 'write_workspace',
          target: 'src/App.java',
          operation: 'modify',
          removedLines: 1,
          addedLines: 2,
        },
        sessionId,
        activeRunId,
      );
      return;
    }
    timer = setTimeout(() => {
      emit('model.text.delta', command.requestId, {text: '你好 '}, sessionId, activeRunId);
      timer = setTimeout(() => {
        emit('model.text.delta', command.requestId, {text: 'agent'}, sessionId, activeRunId);
        emit('run.completed', command.requestId, {stopReason: 'completed'}, sessionId, activeRunId);
        activeRunId = undefined;
      }, 20);
    }, 20);
  } else if (command.type === 'run.cancel') {
    clearTimeout(timer);
    if (mode === 'ignore-cancel') {
      return;
    }
    emit('run.cancelled', command.requestId, {stopReason: 'cancelled'}, sessionId, activeRunId);
    activeRunId = undefined;
  } else if (command.type === 'approval.resolve') {
    if (mode !== 'approval'
      || command.payload.approvalId !== 'approval-1'
      || command.payload.decision !== 'allow_once') {
      process.exit(19);
      return;
    }
    emit(
      'tool.completed',
      command.requestId,
      {
        ordinal: 1,
        toolName: 'apply_patch',
        status: 'success',
        returnedCharacters: 10,
        returnedItems: 1,
        filteredItems: 0,
        truncated: false,
        truncationReason: 'none',
      },
      sessionId,
      activeRunId,
    );
    emit('run.completed', command.requestId, {stopReason: 'completed'}, sessionId, activeRunId);
    activeRunId = undefined;
  } else if (command.type.startsWith('task.')) {
    if (mode !== 'task-actions'
      || command.payload.taskId !== 'task-a'
      || (command.type === 'task.wait' && command.payload.timeoutMillis !== 1500)) {
      process.exit(29);
      return;
    }
    const disposition = command.type === 'task.keep'
      ? 'kept'
      : command.type === 'task.remove' ? 'removed' : undefined;
    if (disposition !== undefined) {
      emit('task.worktree', command.requestId, {
        taskId: command.payload.taskId,
        disposition,
      }, sessionId);
    } else {
      emit('task.status', command.requestId, {
        taskId: command.payload.taskId,
        definitionId: 'e2e',
        status: command.type === 'task.cancel' ? 'cancelled' : 'running',
        failure: 'none',
        modelTurns: 0,
        toolCalls: 0,
        estimatedTokens: 0,
        elapsedMillis: 1,
        summary: '',
        verified: false,
        worktreeDisposition: null,
      }, sessionId);
    }
  } else if (command.type === 'provider.control') {
    if (mode !== 'provider-model-results') {
      emit('provider.control.result', command.requestId, {
        controlId: command.payload.controlId,
        intent: command.payload.intent,
        status: 'rejected',
        code: 'INVALID_ARGUMENT',
        result: {},
      }, sessionId);
      return;
    }
    const results = {
      'models.add': {providerId: 'anthropic', modelId: 'model-added', setDefault: true},
      'models.remove': {providerId: 'anthropic', modelId: 'model-removed'},
      'models.use': {providerId: 'anthropic', profileId: 'default', modelId: 'model-used', setDefault: true},
    };
    const result = results[command.payload.intent];
    if (result === undefined) process.exit(31);
    emit('provider.control.result', command.requestId, {
      controlId: command.payload.controlId,
      intent: command.payload.intent,
      status: 'succeeded',
      code: 'OK',
      result,
    }, sessionId);
  } else if (command.type === 'session.command') {
    if (mode === 'command-delay') {
      return;
    }
    const requestId = mode === 'command-wrong-request' ? 'wrong-request' : command.requestId;
    if (mode === 'resume-mismatched-previous') {
      emit('session.command.result', requestId, {
        commandId: command.payload.commandId,
        intent: 'resume',
        status: 'succeeded',
        code: 'ok',
        result: {previousSessionId: 'session-stale', resumedSessionId: 'session-2'},
      }, 'session-2');
      return;
    }
    emit('session.command.result', requestId, {
      commandId: command.payload.commandId,
      intent: command.payload.intent,
      status: 'rejected',
      code: 'deferred',
      result: {},
    }, sessionId);
    if (mode === 'command-duplicate-result') {
      emit('session.command.result', requestId, {
        commandId: command.payload.commandId,
        intent: command.payload.intent,
        status: 'rejected',
        code: 'deferred',
        result: {},
      }, sessionId);
    }
  } else if (command.type === 'shutdown') {
    clearTimeout(timer);
    if (mode === 'ignore-shutdown' || mode === 'time-limit-ignore-shutdown') {
      return;
    }
    process.stdout.write('', () => process.exit(0));
  }
});

function emit(type, requestId, payload, eventSessionId, runId) {
  const event = {
    version: 0,
    type,
    requestId,
    ...(eventSessionId === undefined ? {} : {sessionId: eventSessionId}),
    ...(runId === undefined ? {} : {runId}),
    sequence: eventSequence++,
    payload,
  };
  process.stdout.write(`${JSON.stringify(event)}\n`);
}
