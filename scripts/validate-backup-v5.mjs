import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'shared/spec/test-vectors/backup-v5-manifest.json'), 'utf8'));
const schema = JSON.parse(fs.readFileSync(path.join(root, 'shared/spec/backup-v5.schema.json'), 'utf8'));

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

assert(schema.properties.schemaVersion.const === 5, 'v5 schema constant is missing');
assert(manifest.schemaVersion === 5, 'golden manifest must be v5');
assert(['android', 'ios', 'harmony', 'test'].includes(manifest.sourcePlatform), 'source platform is invalid');
assert(Array.isArray(manifest.members) && manifest.members.some(member => !member.archived), 'an active member is required');

const collections = ['members', 'conditions', 'records', 'attachments', 'followUps', 'occurrences', 'medications', 'medicationSchedules', 'medicationLogs'];
for (const collection of collections) {
  assert(Array.isArray(manifest[collection]), `${collection} must be an array`);
  const uuids = new Set();
  for (const item of manifest[collection]) {
    assert(!uuids.has(item.uuid), `${collection} contains duplicate UUID`);
    uuids.add(item.uuid);
  }
}

const memberIds = new Set(manifest.members.map(item => item.uuid));
for (const item of [...manifest.conditions, ...manifest.records, ...manifest.followUps, ...manifest.medications]) {
  assert(memberIds.has(item.memberUuid), 'member reference is invalid');
}

const recordByUuid = new Map(manifest.records.map(item => [item.uuid, item]));
for (const item of manifest.attachments) {
  const record = recordByUuid.get(item.recordUuid);
  assert(record, 'attachment record reference is invalid');
  assert(item.archivePath === `files/${item.uuid}`, 'attachment archive path is not canonical');
  assert(/^[0-9a-f]{64}$/.test(item.sha256), 'attachment hash is invalid');
  assert(item.sizeBytes >= 0 && item.sizeBytes <= 100 * 1024 * 1024, 'attachment size is invalid');
  if (record.deletedAt) assert(item.deletedAt, 'attachment of a trashed record must also be trashed');
}

const deletedItems = [...manifest.records, ...manifest.attachments, ...manifest.followUps, ...manifest.medications]
  .filter(item => item.deletedAt);
assert(deletedItems.length > 0, 'golden v5 manifest must exercise trash data');
for (const item of deletedItems) {
  assert(!Number.isNaN(Date.parse(item.deletedAt)), 'deletedAt must be an ISO-8601 timestamp');
}

console.log(`Backup v5 protocol check passed: ${deletedItems.length} trashed items are preserved.`);
