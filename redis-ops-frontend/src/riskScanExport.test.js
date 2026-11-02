import test from 'node:test'
import assert from 'node:assert/strict'
import { exportableRiskScan, loadRiskScanExport, riskScanCsv, riskScanFileName } from './riskScanExport.js'

const detail = (task = {}, latestRun = {}) => ({
  task: { id: 7, taskNo: 'RS-1', version: 3, status: 'COMPLETED', clusterId: 4, databaseNo: 0, includePattern: '*', largeKeyThresholdBytes: 1048576, scanRatePerSecond: 1000, maxFindings: 2000, groupDelimiter: ':', groupSegments: 2, ...task },
  latestRun: { id: 11, plannedKeys: 1000, scannedKeys: 900, findingCount: 21, noTtlTotal: 5000, largeKeyTotal: 3, startedAt: '2026-10-28T10:00:00Z', completedAt: '2026-10-28T10:01:00Z', ...latestRun },
  checkpoints: [{ scannedKeys: 400 }, { scannedKeys: 500 }],
  summary: { noTtlCount: 3000, noTtlRatio: 0.3, largeKeyCount: 3 },
})

test('exportableRiskScan only allows terminal states', () => {
  for (const status of ['COMPLETED', 'FAILED', 'CANCELLED']) assert.ok(exportableRiskScan({ task: { status } }))
  for (const status of ['CREATED', 'RUNNING']) assert.ok(!exportableRiskScan({ task: { status } }))
  assert.ok(!exportableRiskScan(null))
})

test('riskScanFileName sanitizes cluster name and stamps run start', () => {
  assert.equal(riskScanFileName(detail(), 'local-c1'), 'risk-scan-local_c1-20261028-1000.csv')
  assert.equal(riskScanFileName(detail(), 'p2:6380#3'), 'risk-scan-p2_6380_3-20261028-1000.csv')
  assert.equal(riskScanFileName(detail(), ''), 'risk-scan-cluster4-20261028-1000.csv')
  assert.equal(riskScanFileName(detail({ databaseNo: 3 }), 'local-c1'), 'risk-scan-local_c1-db3-20261028-1000.csv')
})

test('riskScanCsv renders summary, groups and findings with BOM', () => {
  const findings = [
    { id: 1, runId: 11, riskType: 'LARGE_KEY', riskLevel: 'HIGH', keyName: 'big:key', keyHash: 'h1', redisType: 'STRING', memoryBytes: 2, elementCount: 1, ttlSeconds: -1, nodeId: 'n1', createdAt: '2026-10-28T10:00:30Z' },
    { id: 2, runId: 11, riskType: 'NO_TTL', riskLevel: 'MEDIUM', keyName: null, keyHash: 'h2', redisType: 'HASH', memoryBytes: 3, elementCount: 4, ttlSeconds: -1, nodeId: 'n2', createdAt: '2026-10-28T10:00:40Z' },
  ]
  const groups = [{ runId: 11, riskType: 'NO_TTL', groupText: 'user', hitCount: 100, sampleKey: 'user:1', maxMemoryBytes: 5 }]
  const csv = riskScanCsv(detail(), findings, groups, '测试集群')
  assert.ok(csv.startsWith('﻿'))
  assert.ok(csv.includes('风险扫描报告'))
  assert.ok(csv.includes('测试集群'))
  assert.ok(csv.includes('已遍历（checkpoint 合计）","900'))
  assert.ok(csv.includes('无 TTL 模式聚合'))
  assert.ok(csv.includes('"user","100","user:1","5"'))
  assert.ok(csv.includes('发现明细（按内存降序）'))
  assert.ok(csv.includes('big:key'))
  assert.ok(csv.includes('历史记录仅保留摘要'))
})

test('loads findings and groups page-wise at size 1000', async () => {
  const rows = Array.from({ length: 1001 }, (_, i) => ({ id: i + 1 }))
  const findingsCalls = [], groupCalls = []
  const result = await loadRiskScanExport({
    riskScanTask: async () => detail(),
    riskFindings: async (_, page, size) => { findingsCalls.push([page, size]); return { total: 1001, items: rows.slice((page - 1) * size, page * size) } },
    riskNoTtlGroups: async (_, page, size) => { groupCalls.push([page, size]); return { total: 2, items: [{ groupText: 'a' }, { groupText: 'b' }] } },
  }, 7)
  assert.deepEqual(findingsCalls, [[1, 1000], [2, 1000]])
  assert.deepEqual(groupCalls, [[1, 1000]])
  assert.equal(result.findings.length, 1001)
  assert.equal(result.groups.length, 2)
})

test('legacy runs without noTtlTotal skip group fetch', async () => {
  let groupCalls = 0
  const result = await loadRiskScanExport({
    riskScanTask: async () => detail({}, { noTtlTotal: null }),
    riskFindings: async () => ({ total: 0, items: [] }),
    riskNoTtlGroups: async () => { groupCalls++; return { total: 0, items: [] } },
  }, 7)
  assert.equal(groupCalls, 0)
  assert.equal(result.groups.length, 0)
})

test('rejects running tasks, drift, empty pages and changed versions', async () => {
  await assert.rejects(loadRiskScanExport({
    riskScanTask: async () => detail({ status: 'RUNNING' }),
    riskFindings: async () => ({ total: 0, items: [] }),
  }, 7))
  let call = 0
  await assert.rejects(loadRiskScanExport({
    riskScanTask: async () => detail(),
    riskFindings: async () => { call++; return call === 1 ? { total: 2, items: [{ id: 1 }] } : { total: 3, items: [{ id: 2 }] } },
    riskNoTtlGroups: async () => ({ total: 0, items: [] }),
  }, 7))
  await assert.rejects(loadRiskScanExport({
    riskScanTask: async () => detail(),
    riskFindings: async (_, page) => page === 1 ? { total: 5, items: [{ id: 1 }] } : { total: 5, items: [] },
    riskNoTtlGroups: async () => ({ total: 0, items: [] }),
  }, 7))
  await assert.rejects(loadRiskScanExport({
    riskScanTask: async () => detail(),
    riskFindings: async () => ({ total: 0, items: [] }),
    riskNoTtlGroups: async () => ({ total: 1101, items: [] }),
  }, 7))
  let reads = 0
  await assert.rejects(loadRiskScanExport({
    riskScanTask: async () => detail({ version: ++reads + 2 }),
    riskFindings: async () => ({ total: 0, items: [] }),
    riskNoTtlGroups: async () => ({ total: 0, items: [] }),
  }, 7))
})
