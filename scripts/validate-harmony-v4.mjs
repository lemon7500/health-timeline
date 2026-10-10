import fs from 'node:fs';
import path from 'node:path';
import { stripTypeScriptTypes } from 'node:module';
import { randomUUID } from 'node:crypto';

const root = process.cwd();
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'shared/spec/test-vectors/backup-v4-manifest.json'), 'utf8'));
const schema = JSON.parse(fs.readFileSync(path.join(root, 'shared/spec/backup-v4.schema.json'), 'utf8'));

function assert(condition, message) { if (!condition) throw new Error(message); }
assert(schema.properties.schemaVersion.const === 4, 'v4 schema constant is missing');
assert(manifest.schemaVersion === 4, 'golden manifest must be v4');
assert(['android', 'ios', 'harmony', 'test'].includes(manifest.sourcePlatform), 'source platform is invalid');
assert(Array.isArray(manifest.members) && manifest.members.length > 0, 'members are missing');
assert(manifest.members.some(member => !member.archived), 'an active member is required');
const ids = new Set();
for (const collection of ['members', 'conditions', 'records', 'attachments', 'followUps', 'occurrences', 'medications', 'medicationSchedules', 'medicationLogs']) {
  for (const item of manifest[collection]) { assert(!ids.has(`${collection}:${item.uuid}`), `${collection} contains duplicate UUID`); ids.add(`${collection}:${item.uuid}`); }
}
const memberIds = new Set(manifest.members.map(item => item.uuid));
for (const item of [...manifest.conditions, ...manifest.records, ...manifest.followUps, ...manifest.medications]) assert(memberIds.has(item.memberUuid), 'member reference is invalid');
for (const item of manifest.attachments) {
  assert(item.archivePath === `files/${item.uuid}`, 'attachment archive path is not canonical');
  assert(/^[0-9a-f]{64}$/.test(item.sha256), 'attachment hash is invalid');
  assert(item.sizeBytes >= 0 && item.sizeBytes <= 100 * 1024 * 1024, 'attachment size is invalid');
}
for (const item of manifest.medicationSchedules) {
  assert(/^\d{4}-\d{2}-\d{2}$/.test(item.effectiveFrom), 'schedule effectiveFrom is invalid');
  assert(/^\d{2}:\d{2}$/.test(item.localTime), 'schedule time is invalid');
  assert(item.doseAmountSnapshot.length > 0 && item.doseUnitSnapshot.length > 0, 'schedule dose snapshot is missing');
}
console.log(`Harmony v4 protocol check passed: ${manifest.records.length} records, ${manifest.medicationSchedules.length} schedule versions.`);

// Execute the actual SDK-independent ArkTS business code, not a second JS
// implementation. SDK imports are replaced only at the persistence boundary.
const harmonyRoot = path.join(root, 'apps/harmony/entry/src/main/ets');
function sourceModule(relative, prefix = '') {
  const source = fs.readFileSync(path.join(harmonyRoot, relative), 'utf8').replace(/^import .*;\r?\n/gm, '');
  return `data:text/javascript;base64,${Buffer.from(prefix + stripTypeScriptTypes(source, { mode: 'strip' })).toString('base64')}`;
}
globalThis.__harmonyUuid = randomUUID;
const modelsUrl = sourceModule('model/Models.ets', 'const util = { generateRandomUUID: () => globalThis.__harmonyUuid() };\n');
const historyUrl = sourceModule('domain/MedicationHistory.ets');
const { normalizeState, validateState } = await import(modelsUrl);
const { MedicationHistory } = await import(historyUrl);
const { BackupManifestV4 } = await import(sourceModule('backup/BackupManifestV4.ets', `import { validateState } from '${modelsUrl}';\n`));
globalThis.__harmonyRdb = { SecurityLevel: { S3: 3 } };
const { HealthStore } = await import(sourceModule('store/HealthStore.ets', `import { emptyState, normalizeState, validateState } from '${modelsUrl}';\nimport { MedicationHistory } from '${historyUrl}';\nconst relationalStore = globalThis.__harmonyRdb;\n`));

const day = '2026-09-06'; const now = '2026-09-06T23:59:59Z';
let passed = 0;
function fixture() {
  const state = structuredClone(manifest);
  state.installationId = state.sourceInstallationId;
  state.selectedMemberUuid = state.members[0].uuid;
  return normalizeState(state);
}
function equal(actual, expected, message) { assert(JSON.stringify(actual) === JSON.stringify(expected), message); }
function rejects(change, message) {
  let rejected = false;
  try { change(); } catch (_) { rejected = true; }
  assert(rejected, message);
}
async function test(name, run) { await run(); passed++; console.log(`PASS Harmony medication: ${name}`); }
function end(state) { MedicationHistory.endCourse(state, state.selectedMemberUuid, state.medications[0].uuid, day, now); }
function dose(state, overrides = {}) {
  const options = { member: state.selectedMemberUuid, medication: state.medications[0].uuid, schedule: state.medicationSchedules[0]?.uuid ?? '', date: day, status: 'TAKEN', actual: '08:10', log: randomUUID(), expected: '', updated: '', ...overrides };
  MedicationHistory.recordDose(state, options.member, options.medication, options.schedule, options.date, options.status, options.actual, day, now, options.log, options.expected, options.updated);
}

await test('closed schedule and log use historical dose, not current dose', () => {
  const state = fixture(); const old = state.medicationSchedules[0];
  old.enabled = false; old.effectiveTo = '2026-09-05'; state.medications[0].doseAmount = '9';
  state.medicationSchedules.push({ ...old, uuid: randomUUID(), enabled: true, effectiveFrom: day, effectiveTo: undefined, doseAmountSnapshot: '2' });
  const previous = MedicationHistory.entriesForDay(state, state.selectedMemberUuid, '2026-09-03');
  equal(previous.map(item => item.doseAmount), ['1'], 'saved historical dose must survive current medication changes');
  equal(MedicationHistory.entriesForDay(state, state.selectedMemberUuid, day).map(item => item.doseAmount), ['2'], 'new interval must use its own dose');
});
await test('end preserves today/history, v4 pause flags, and restore preserves IDs', () => {
  const state = fixture(); const originalSchedules = structuredClone(state.medicationSchedules); const originalLogs = structuredClone(state.medicationLogs);
  end(state); validateState(state);
  assert(MedicationHistory.entriesForDay(state, state.selectedMemberUuid, day).length === 1, 'ending a course must not hide today');
  assert(MedicationHistory.entriesForDay(state, state.selectedMemberUuid, '2026-09-03')[0].log.uuid === originalLogs[0].uuid, 'saved historical log must remain visible');
  assert(MedicationHistory.entriesForDay(state, state.selectedMemberUuid, '2026-09-07').length === 0, 'ended course must not generate future doses');
  const exported = BackupManifestV4.fromState(state, 'harmony'); BackupManifestV4.validate(exported);
  assert(exported.medicationSchedules[0].pausedByCourseEnd && !exported.medicationSchedules[0].enabled, 'backup must preserve paused flag');
  const serialized = JSON.stringify(state); rejects(() => end(state), 'repeated end must reject'); equal(JSON.stringify(state), serialized, 'repeated end must not replace original end metadata');
  const imported = fixture(); imported.medications = structuredClone(exported.medications); imported.medicationSchedules = structuredClone(exported.medicationSchedules); imported.medicationLogs = structuredClone(exported.medicationLogs);
  MedicationHistory.restoreCourse(imported, imported.selectedMemberUuid, imported.medications[0].uuid, now); validateState(imported);
  equal(imported.medicationSchedules.map(item => [item.uuid, item.effectiveFrom, item.effectiveTo, item.doseAmountSnapshot]), originalSchedules.map(item => [item.uuid, item.effectiveFrom, item.effectiveTo, item.doseAmountSnapshot]), 'restore must preserve schedule identity/interval/dose');
  equal(imported.medicationLogs, originalLogs, 'end and restore must not alter dose logs');
  assert(!imported.medications[0].archived && !imported.medications[0].endedAt && imported.medicationSchedules[0].enabled, 'restore must reenable the paused version');
});
await test('planned end date and already-ended intervals restore without guessing', () => {
  const state = fixture(); state.medications[0].endDate = day; state.medicationSchedules[0].effectiveTo = day;
  end(state); MedicationHistory.restoreCourse(state, state.selectedMemberUuid, state.medications[0].uuid, now); validateState(state);
  assert(state.medications[0].endDate === day && state.medicationSchedules[0].effectiveTo === day, 'restore must retain originally planned end date');
  state.medications[0].archived = true; state.medications[0].endedAt = now; state.medicationSchedules[0].enabled = false; state.medicationSchedules[0].pausedByCourseEnd = false;
  const before = JSON.stringify(state); rejects(() => MedicationHistory.restoreCourse(state, state.selectedMemberUuid, state.medications[0].uuid, now), 'unmarked legacy version cannot be guessed'); equal(JSON.stringify(state), before, 'unsafe restore must not mutate data');
});
await test('future-start and incompatible legacy disabled schedules cannot end', () => {
  const future = fixture(); future.medications[0].startDate = '2026-09-10'; future.medicationSchedules[0].effectiveFrom = '2026-09-10'; future.medicationLogs = [];
  const before = JSON.stringify(future); rejects(() => end(future), 'not-yet-started course cannot end'); equal(JSON.stringify(future), before, 'future course must remain untouched');
  const legacy = fixture(); legacy.medicationSchedules[0].enabled = false;
  const old = JSON.stringify(legacy); rejects(() => end(legacy), 'legacy disabled open interval cannot be archived into invalid backup'); equal(JSON.stringify(legacy), old, 'disabled interval cannot be guessed or overwritten');
});
await test('editing tomorrow retains today enablement and old/new snapshots', () => {
  const state = fixture(); state.medications[0].endDate = '2026-09-30'; state.medicationSchedules[0].effectiveTo = '2026-09-30';
  MedicationHistory.changeSchedule(state, state.selectedMemberUuid, state.medications[0].uuid, '测试药物', '2', '片', '20:05', day, '2026-09-07', now, randomUUID()); validateState(state);
  assert(state.medicationSchedules[0].enabled && state.medicationSchedules[0].effectiveTo === day, 'today schedule must still deliver notifications');
  equal(MedicationHistory.entriesForDay(state, state.selectedMemberUuid, day).map(item => item.doseAmount), ['1'], 'today dose remains old');
  equal(MedicationHistory.entriesForDay(state, state.selectedMemberUuid, '2026-09-07').map(item => [item.plannedAt.substring(11, 16), item.doseAmount]), [['20:05', '2']], 'tomorrow uses new version only');
  assert(state.medicationSchedules[1].effectiveTo === '2026-09-30', 'new version must not extend beyond planned course end');
});
await test('referenced future versions cannot be deleted when editing a plan', () => {
  const state = fixture(); const tomorrow = '2026-09-07'; const future = { ...state.medicationSchedules[0], uuid: randomUUID(), effectiveFrom: tomorrow };
  state.medicationSchedules[0].effectiveTo = day; state.medicationSchedules.push(future);
  state.medicationLogs.push({ ...state.medicationLogs[0], uuid: randomUUID(), scheduleUuid: future.uuid, scheduledAt: `${tomorrow}T08:05`, actualAt: `${tomorrow}T08:10` });
  const before = JSON.stringify(state);
  rejects(() => MedicationHistory.changeSchedule(state, state.selectedMemberUuid, state.medications[0].uuid, '测试药物', '2', '片', '20:05', day, tomorrow, now, randomUUID()), 'future referenced version cannot be discarded');
  equal(JSON.stringify(state), before, 'rejected future edit must keep schedule/log references');
});
await test('record, canonical duplicate rejection and explicit correction preserve snapshot', () => {
  const state = fixture(); dose(state); validateState(state); const log = state.medicationLogs[1]; const created = log.createdAt; const uuid = log.uuid;
  const before = JSON.stringify(state); rejects(() => dose(state), 'duplicate click must not insert another dose'); equal(JSON.stringify(state), before, 'duplicate must be non-mutating');
  end(state); dose(state, { status: 'SKIPPED', expected: uuid, updated: log.updatedAt }); validateState(state);
  equal([log.uuid, log.createdAt, log.doseAmountSnapshot, log.scheduledAt], [uuid, created, '1', `${day}T08:05:00`], 'correction must not replace identity/history');
  assert(log.status === 'SKIPPED' && log.actualAt === undefined, 'skipped correction cannot retain an actual dose time');
  const historic = fixture(); rejects(() => dose(historic, { date: '2026-09-03' }), 'imported HH:mm and HH:mm:00 must denote the same dose');
  historic.medicationLogs.push({ ...historic.medicationLogs[0], uuid: randomUUID(), scheduledAt: '2026-09-03T08:05:00' }); rejects(() => validateState(historic), 'validator must reject canonical duplicate timestamps');
});
await test('future/invalid time, stale correction and ended new-dose guards', () => {
  const state = fixture(); const before = JSON.stringify(state);
  for (const options of [{ date: '2026-09-07' }, { date: '2026-02-30' }, { actual: '08:07' }, { actual: '24:00' }, { expected: randomUUID() }, { expected: state.medicationLogs[0].uuid, date: '2026-09-03', updated: 'stale' }]) rejects(() => dose(state, options), 'invalid or stale dose must reject');
  equal(JSON.stringify(state), before, 'rejected dose changes must be non-mutating'); end(state);
  const ended = JSON.stringify(state); rejects(() => dose(state), 'ended course cannot receive a new dose'); equal(JSON.stringify(state), ended, 'ended guard must preserve data');
});
await test('member switching, archived member and foreign schedule stay isolated', () => {
  const state = fixture(); const primary = state.selectedMemberUuid;
  const other = { ...state.members[0], uuid: randomUUID(), name: '测试成员', relationship: '父亲', nickname: '父亲' }; state.members.push(other);
  const foreign = { ...state.medications[0], uuid: randomUUID(), memberUuid: other.uuid }; state.medications.push(foreign);
  const foreignSchedule = { ...state.medicationSchedules[0], uuid: randomUUID(), medicationUuid: foreign.uuid }; state.medicationSchedules.push(foreignSchedule);
  const before = JSON.stringify(state); rejects(() => dose(state, { medication: foreign.uuid, schedule: foreignSchedule.uuid }), 'foreign member dose must reject'); rejects(() => dose(state, { schedule: foreignSchedule.uuid }), 'foreign medication schedule must reject'); equal(JSON.stringify(state), before, 'foreign guards must not write');
  state.selectedMemberUuid = other.uuid; rejects(() => dose(state, { member: primary }), 'stale member confirmation must reject');
  state.selectedMemberUuid = primary; state.members[0].archived = true; rejects(() => dose(state), 'archived member dose must reject');
  equal(MedicationHistory.entriesForDay(state, primary, day), [], 'archived member cannot leak into day query');
});
await test('as-needed saved logs stay visible and unverifiable past dose is rejected', () => {
  const state = fixture(); state.medications[0].mode = 'AS_NEEDED'; state.medicationSchedules = []; state.medicationLogs[0].scheduleUuid = undefined;
  assert(MedicationHistory.entriesForDay(state, state.selectedMemberUuid, '2026-09-03')[0].doseAmount === '1', 'as-needed historical log uses snapshot');
  rejects(() => dose(state, { schedule: '', date: '2026-09-02' }), 'past as-needed dose before last edit cannot use current dose');
  dose(state, { schedule: '' }); validateState(state);
  assert(state.medicationLogs[1].scheduleUuid === undefined && state.medicationLogs[1].doseAmountSnapshot === '1', 'as-needed dose cannot invent a scheduled plan');
});
await test('v4 rejects impossible pause flags and v5 remains unsupported', () => {
  const state = fixture(); end(state); state.medicationSchedules[0].enabled = true;
  rejects(() => BackupManifestV4.fromState(state, 'harmony'), 'enabled+paused backup must reject');
  rejects(() => BackupManifestV4.validate({ ...manifest, schemaVersion: 5 }), 'v5 import must remain rejected');
  const future = { ...fixture(), schemaVersion: 5 }; const before = JSON.stringify(future); rejects(() => normalizeState(future), 'v5 local state cannot be silently downgraded'); equal(JSON.stringify(future), before, 'v5 rejection cannot change data');
});
await test('real HealthStore serializes concurrent writes and rolls back failures', async () => {
  const store = new HealthStore(); store.value = fixture(); store.initialized = true; let active = false; let fail = false; let committed = ''; let pending = ''; let rollbacks = 0;
  store.database = {
    beginTransaction() { assert(!active, 'concurrent native transactions are unsafe'); active = true; },
    async executeSql(_sql, args) { await Promise.resolve(); if (fail) throw new Error('synthetic disk full'); pending = args[0]; },
    commit() { committed = pending; active = false; },
    rollBack() { active = false; rollbacks++; }
  };
  await Promise.all([store.update(state => { state.medications[0].name = '第一次修改'; }), store.update(state => { state.medications[0].instructions = '第二次修改'; })]);
  assert(store.state.medications[0].name === '第一次修改' && store.state.medications[0].instructions === '第二次修改', 'concurrent updates must not lose the first committed edit');
  equal(JSON.parse(committed), store.state, 'native payload must equal published state');
  const before = JSON.stringify(store.state); fail = true;
  let rejected = false; try { await store.update(state => { state.medications[0].name = '不得保留'; }); } catch (_) { rejected = true; }
  assert(rejected && rollbacks === 1, 'failed transaction must roll back'); equal(JSON.stringify(store.state), before, 'failed write cannot publish partially modified state');
  fail = false; await store.update(state => { state.medications[0].instructions = '失败后仍可写入'; });
  assert(store.state.medications[0].instructions === '失败后仍可写入', 'a failed operation must not poison the queue');
  const durable = JSON.stringify(store.state); rejected = false;
  try { await store.update(state => { MedicationHistory.endCourse(state, state.selectedMemberUuid, state.medications[0].uuid, day, now); throw new Error('synthetic validation failure'); }); } catch (_) { rejected = true; }
  assert(rejected, 'mutation failure must reject'); equal(JSON.stringify(store.state), durable, 'failed end transaction must preserve original course and history');
});
await test('real HealthStore day query includes ended/disabled history and excludes foreign rows', () => {
  const store = new HealthStore(); store.value = fixture(); end(store.value);
  assert(store.medicationEntriesForDay(day).length === 1 && store.schedulesForDay(store.state.medications[0], day).length === 1, 'store integration must expose today after ending');
  const foreign = { ...store.state.medications[0], uuid: randomUUID(), memberUuid: randomUUID() };
  store.value.medications.push(foreign); store.value.medicationSchedules.push({ ...store.value.medicationSchedules[0], uuid: randomUUID(), medicationUuid: foreign.uuid });
  store.value.medicationLogs.push({ ...store.value.medicationLogs[0], uuid: randomUUID(), medicationUuid: foreign.uuid });
  equal(store.schedulesForDay(foreign, day), [], 'foreign medication schedule cannot be read through day helper');
  equal(store.logsForDay(foreign, '2026-09-03'), [], 'foreign medication log cannot be read through day helper');
  assert(store.medicationEntriesForDay(day).length === 1, 'selected-member projection cannot include foreign rows');
});
await test('failed startup cannot write fallback state or rebuild from a ready signal', async () => {
  const store = new HealthStore(); const oldState = { ...fixture(), schemaVersion: 3 }; const oldPayload = JSON.stringify(oldState); let writes = 0; let rollback = 0;
  globalThis.__harmonyRdb.getRdbStore = async () => ({
    async executeSql(sql) { if (sql.startsWith('INSERT')) { writes++; throw new Error('synthetic startup disk full'); } },
    async querySql(sql) { const text = sql.includes('quick_check') ? 'ok' : oldPayload; return { goToFirstRow: () => true, getString: () => text, close() {} }; },
    beginTransaction() {}, commit() { throw new Error('must not commit'); }, rollBack() { rollback++; }
  });
  const initialization = store.initialize({});
  const waiting = store.waitUntilInitialized();
  const results = await Promise.allSettled([initialization, waiting]);
  assert(results.every(item => item.status === 'rejected') && rollback === 1, 'failed initialization and waiting UI must reject together');
  assert(!store.initialized && store.database === undefined, 'failed startup cannot expose a writable database');
  const attempts = writes; let rejected = false;
  try { await store.update(state => { state.medications[0].name = '不得写入'; }); } catch (_) { rejected = true; }
  assert(rejected && writes === attempts, 'post-failure writes must stop before persistence');
  assert(JSON.stringify(oldState) === oldPayload, 'startup error cannot modify original durable payload');
});
console.log(`Harmony actual ArkTS business regressions passed: ${passed} tests (SDK/RDB mocked; not a DevEco build).`);
