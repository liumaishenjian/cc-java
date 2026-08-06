import readline from 'node:readline';

let eventSequence = 1;
let sessionId;
let activeRunId;
let timer;
const mode = process.argv[2] ?? 'normal';

const reader = readline.createInterface({input: process.stdin, crlfDelay: Infinity});
reader.on('line', line => {
  const command = JSON.parse(line);
  if (command.type === 'initialize') {
    sessionId = 'session-1';
    emit('initialized', command.requestId, {protocolVersion: 0}, sessionId);
  } else if (command.type === 'run.start') {
    activeRunId = 'run-1';
    emit('run.started', command.requestId, {promptChars: command.payload.prompt.length}, sessionId, activeRunId);
    if (mode === 'crash') {
      process.exit(17);
    }
    if (mode === 'ignore-cancel') {
      return;
    }
    if (mode === 'bad-sequence') {
      eventSequence++;
      emit('model.text.delta', command.requestId, {text: 'bad'}, sessionId, activeRunId);
      return;
    }
    if (mode === 'time-limit') {
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
  } else if (command.type === 'session.command') {
    if (mode === 'command-delay') {
      return;
    }
    const requestId = mode === 'command-wrong-request' ? 'wrong-request' : command.requestId;
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
    if (mode === 'ignore-shutdown') {
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
