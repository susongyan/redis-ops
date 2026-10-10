import test from 'node:test'
import assert from 'node:assert/strict'
import { canDeleteExecutionRecord } from './executionRecords.js'
test('only finished read-only records are deletable', () => {
  for (const type of ['RISK_SCAN', 'KEY_DISTRIBUTION', 'VALIDATION']) {
    assert.equal(canDeleteExecutionRecord(type, { id: 1, status: 'FAILED' }), true)
    for (const status of ['RUNNING', 'PAUSED', 'QUEUED', 'CREATED', 'CHECKING', 'READY'])
      assert.equal(canDeleteExecutionRecord(type, { id: 1, status }), false)
  }
  assert.equal(canDeleteExecutionRecord('ANALYSIS', { id: 1, result: { status: 'COMPLETED' } }), true)
})
test('Redis modifying histories and sync validation evidence are retained', () => {
  for (const type of ['TTL', 'CLEANUP', 'SYNC', 'CONSOLE'])
    assert.equal(canDeleteExecutionRecord(type, { id: 1, status: 'COMPLETED' }), false)
  assert.equal(canDeleteExecutionRecord('VALIDATION', { id: 1, status: 'PASSED', syncTaskId: 2 }), false)
})
