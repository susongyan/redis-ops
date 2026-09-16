import test from 'node:test'
import assert from 'node:assert/strict'
import { actorLabel } from './actor.js'
test('actor snapshots retain names and preserve legacy/system identities', () => {
  assert.equal(actorLabel('user:1', '{"login":"admin","displayName":"管理员"}'), '管理员（admin）')
  assert.equal(actorLabel('user:1', {login:'admin',displayName:'admin'}), 'admin')
  assert.equal(actorLabel('user:1', null), 'user:1')
  assert.equal(actorLabel('worker:local', null), 'worker:local')
  assert.equal(actorLabel('user:1', 'bad'), 'user:1')
  assert.equal(actorLabel(null, null), '—')
})
