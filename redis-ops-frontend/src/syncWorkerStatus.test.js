import { test } from 'node:test'
import assert from 'node:assert/strict'
import { syncWorkerStatus, workerRegistryStatus, syncWorkerOptions, workerAddress, workerHealthUrl } from './syncWorkerStatus.js'

test('unknown, released, expired and valid leases are distinct', () => {
  assert.equal(syncWorkerStatus(null).label, '状态未获取')
  assert.equal(syncWorkerStatus({}).label, '未分配 / 已释放')
  const worker = { leaseOwner: 'worker-a', leaseStatus: 'VALID', leaseUntil: '2026-09-13T00:00:30Z' }
  assert.equal(syncWorkerStatus(worker, false, Date.parse('2026-09-13T00:00:00Z')).label, '租约有效')
  assert.equal(syncWorkerStatus(worker, false, Date.parse(worker.leaseUntil)).label, '租约已过期')
  assert.equal(syncWorkerStatus(worker, true).label, '状态获取失败')
  assert.equal(syncWorkerStatus({ ...worker, leaseStatus: 'EXPIRED' }, false, 0).label, '租约已过期')
})

test('registry liveness tags online, offline and missing rows', () => {
  assert.equal(workerRegistryStatus(null).label, '未知实例')
  assert.equal(workerRegistryStatus({ online: true }).label, '在线')
  assert.equal(workerRegistryStatus({ online: false }).label, '离线')
  assert.equal(workerRegistryStatus({ online: true }, true).label, '状态获取失败')
})

test('registry select options disable offline workers and format labels', () => {
  const registry = [
    { instanceName: 'worker-a', workerIp: '10.0.0.1', workerPort: 19090, online: true },
    { instanceName: 'worker-b', workerIp: '10.0.0.2', workerPort: 19090, online: false },
    { instanceName: 'worker-c', workerIp: null, online: true },
  ]
  const options = syncWorkerOptions(registry, false)
  assert.deepEqual(options.map(option => option.value), ['worker-a', 'worker-b', 'worker-c'])
  assert.equal(options[0].disabled, false)
  assert.equal(options[1].disabled, true)
  assert.match(options[1].label, /（离线）$/)
  assert.equal(options[0].label, 'worker-a · 10.0.0.1:19090')
  assert.equal(options[2].label, 'worker-c')
  assert.deepEqual(syncWorkerOptions([], false), [])
  assert.deepEqual(syncWorkerOptions(registry, true), [])
})

test('worker address and health url degrade gracefully on partial registry rows', () => {
  assert.equal(workerAddress({ workerIp: '10.0.0.1', workerPort: 19090 }), '10.0.0.1:19090')
  assert.equal(workerAddress({ workerIp: '10.0.0.1' }), '10.0.0.1')
  assert.equal(workerAddress({ workerIp: null, workerPort: 19090 }), '端口 19090')
  assert.equal(workerAddress(null), '')
  assert.equal(workerHealthUrl({ workerIp: '10.0.0.1', workerPort: 19090 }), 'http://10.0.0.1:19090/actuator/health')
  assert.equal(workerHealthUrl({ workerIp: '10.0.0.1' }), null)
  assert.equal(workerHealthUrl({ workerPort: 19090 }), null)
  assert.equal(workerHealthUrl(null), null)
})
