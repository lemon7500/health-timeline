import fs from 'node:fs';
import path from 'node:path';

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
