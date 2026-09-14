import { test } from 'node:test'
import assert from 'node:assert/strict'
import { syncWorkerStatus } from './syncWorkerStatus.js'

test('unknown, released, expired and valid leases are distinct', () => {
  assert.equal(syncWorkerStatus(null).label, '状态未获取')
  assert.equal(syncWorkerStatus({}).label, '未分配 / 已释放')
  const worker = { leaseOwner: 'worker-a', leaseStatus: 'VALID', leaseUntil: '2026-09-13T00:00:30Z' }
  assert.equal(syncWorkerStatus(worker, false, Date.parse('2026-09-13T00:00:00Z')).label, '租约有效')
  assert.equal(syncWorkerStatus(worker, false, Date.parse(worker.leaseUntil)).label, '租约已过期')
  assert.equal(syncWorkerStatus(worker, true).label, '状态获取失败')
  assert.equal(syncWorkerStatus({ ...worker, leaseStatus: 'EXPIRED' }, false, 0).label, '租约已过期')
})
