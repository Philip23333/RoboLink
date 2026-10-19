import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Action, CommandPhase, Envelope } from '../src/generated/robolink';
import { CommandBook, MessageBook } from '../src/commands';
import { TraceBook } from '../src/traces';

function status(phase: CommandPhase) { return Envelope.fromPartial({ protocolVersion: 1, robotId: 'RB-001',
  payload: { $case: 'commandStatus', value: { commandId: 'first', phase, reason: 'test' } } }); }
test('ACK and result are distinct; terminal does not reopen on late data', () => {
  const book = new CommandBook(); book.add('first', 'RB-001', Action.ACTION_MOVE_FORWARD);
  book.apply(Envelope.fromPartial({ robotId: 'RB-001', payload: { $case: 'commandAck', value: { commandId: 'first', accepted: true } } }));
  assert.equal(book.entries[0].phase, CommandPhase.COMMAND_PHASE_CREATED);
  assert.ok(book.entries[0].ackAt);
  book.apply(status(CommandPhase.COMMAND_PHASE_ACKED));
  book.apply(status(CommandPhase.COMMAND_PHASE_SENT));
  assert.equal(book.entries[0].phase, CommandPhase.COMMAND_PHASE_ACKED);
  book.apply(status(CommandPhase.COMMAND_PHASE_SUCCEEDED));
  book.apply(status(CommandPhase.COMMAND_PHASE_ACKED));
  assert.equal(book.entries[0].phase, CommandPhase.COMMAND_PHASE_SUCCEEDED);
});
test('disconnect marks unknown, blocks motion; successful STOP clears uncertainty', () => {
  const book = new CommandBook(); book.add('first', 'RB-001', Action.ACTION_MOVE_FORWARD);
  book.disconnected(); book.apply(status(CommandPhase.COMMAND_PHASE_SUCCEEDED));
  assert.equal(book.entries[0].phase, CommandPhase.COMMAND_PHASE_UNKNOWN);
  assert.ok(book.uncertain.has('RB-001'));
  book.add('stop', 'RB-001', Action.ACTION_STOP);
  const message = status(CommandPhase.COMMAND_PHASE_SUCCEEDED);
  if (message.payload?.$case === 'commandStatus') message.payload.value.commandId = 'stop';
  book.apply(message); assert.equal(book.uncertain.size, 0);
});
test('command history is bounded without evicting active commands', () => {
  const book = new CommandBook();
  for (let index = 0; index < 100; index++) assert.ok(book.add(String(index), 'RB-001', Action.ACTION_STOP));
  assert.equal(book.add('overflow', 'RB-001', Action.ACTION_STOP), false);
  book.disconnected(); assert.ok(book.add('new', 'RB-001', Action.ACTION_STOP));
  assert.equal(book.entries.length, 100);
});
test('message history excludes credentials and coalesces telemetry', () => {
  const book = new MessageBook();
  book.add(Envelope.fromPartial({ payload: { $case: 'controlAuthenticate', value: { credential: 'never-log-this' } } }), 10, 'out');
  assert.equal(book.entries.length, 0);
  for (let index = 0; index < 500; index++) book.add(Envelope.fromPartial({ robotId: 'RB-001', seq: BigInt(index),
    payload: { $case: 'telemetry', value: { batteryPercent: index % 100 } } }), 10, 'in');
  assert.equal(book.entries.length, 1); assert.equal(book.entries[0].reference, '499');
  for (let index = 0; index < 200; index++) book.add(status(CommandPhase.COMMAND_PHASE_SENT), 10, 'in');
  assert.equal(book.entries.length, 120);
  assert.ok(!JSON.stringify(book.entries).includes('never-log-this'));
});

test('trace sources require actual observations and missing stages stay missing', () => {
  const book = new TraceBook();
  book.observe(status(CommandPhase.COMMAND_PHASE_SENT), 'in');
  assert.equal(book.entries.length, 0);
  book.observe(Envelope.fromPartial({ protocolVersion: 1, robotId: 'RB-001', sessionId: 'actual-session',
    payload: { $case: 'commandTrace', value: { commandId: 'first', stage: 'GATEWAY_TCP_WRITE_COMPLETED',
      observedAtMs: 1234n, wireSeq: 5n } } }), 'in');
  assert.equal(book.entries.length, 1); assert.equal(book.entries[0].source, 'gateway');
  assert.equal(book.entries[0].at, 1234); assert.equal(book.entries[0].seq, '5');
  assert.ok(!book.entries.some(event => event.source === 'device'));
});
test('trace history is bounded and duplicate polling cannot multiply events', () => {
  const book = new TraceBook();
  const event = { command_id: 'first', robot: 'RB-001', source: 'device' as const, stage: 'DEVICE_COMMAND_RECEIVED',
    session: 'session', seq: '1', at: 1234, pid: 42 };
  for (let index = 0; index < 500; index++) book.add(event);
  assert.equal(book.entries.length, 1);
  for (let index = 0; index < 1000; index++) book.add({ ...event, command_id: String(index) });
  assert.equal(book.entries.length, 600);
});
