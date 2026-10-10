import test from 'node:test'
import assert from 'node:assert/strict'
import { scanReadTargets, scanTargetLabel } from './scanReadTargets.js'

test('distribution uses the selected replica endpoint, not logical primary id', () => {
  assert.deepEqual(scanReadTargets([{ id: 'primary-id', nodeId: 'replica-id', role: 'replica', endpoint: '10.0.0.2:6380' }]), ['Slave · 10.0.0.2:6380'])
})
test('risk checkpoints display actual endpoints without fingerprint', () => {
  assert.deepEqual(scanReadTargets([], [{ shardId: 'replica@10.0.0.2:6380#fingerprint' }, { shardId: 'master@10.0.0.3:6379#fingerprint' }]), ['Slave · 10.0.0.2:6380', 'Master · 10.0.0.3:6379'])
})
test('legacy ids are not presented as known replica addresses', () => {
  assert.deepEqual(scanReadTargets([], [{ shardId: 'legacy-node-id' }]), [])
  assert.equal(scanTargetLabel('legacy-node-id'), 'legacy-node-id')
})
