import test from 'node:test'
import assert from 'node:assert/strict'
import { exportableValidation, loadValidationExport, validationCsv, validationFileName, EXPORT_DIFF_LIMIT } from './validationExport.js'

const detail = (task = {}, latestRun = {}) => ({
  task: { id: 9, taskNo: 'VT-1', version: 2, status: 'FAILED', sourceClusterId: 1, targetClusterId: 2, sourceDb: 0, targetDb: 0, syncTaskId: null, samplingMode: 'PERCENTAGE', samplePercentage: 10, sampleLimit: 1000, strictness: 'REPORT', ...task },
  latestRun: { id: 4, plannedKeys: 100, scannedKeys: 100, comparedKeys: 98, differenceCount: 3, degradedCount: 1, unverifiableCount: 0, inconclusiveCount: 0, startedAt: '2026-10-28T10:00:00Z', finishedAt: '2026-10-28T10:01:00Z', ...latestRun },
})

test('exportableValidation only allows terminal states', () => {
  for (const status of ['PASSED', 'FAILED', 'INCONCLUSIVE', 'CANCELLED']) assert.ok(exportableValidation({ task: { status } }))
  for (const status of ['CREATED', 'CHECKING', 'READY', 'RUNNING']) assert.ok(!exportableValidation({ task: { status } }))
  assert.ok(!exportableValidation(null))
})

test('validationFileName sanitizes cluster names and stamps run start', () => {
  assert.equal(validationFileName(detail(), 'demo:6379#3', 'local-c1'), 'validation-demo_6379_3-to-local_c1-20261028-1000.csv')
  assert.equal(validationFileName(detail(), '源集群', '目标集群'), 'validation-源集群-to-目标集群-20261028-1000.csv')
  assert.equal(validationFileName(detail(), '', ''), 'validation-cluster1-to-cluster2-20261028-1000.csv')
})

test('pages differences at backend cap of 100 and renders csv with BOM', async () => {
  const rows = Array.from({ length: 150 }, (_, i) => ({ id: i + 1, runId: 4, differenceType: 'VALUE_DIFF', keyName: `k:${i}`, keyHash: `h${i}`, redisType: 'STRING', sourceSize: 1, targetSize: 2, sourceTtlSeconds: -1, targetTtlSeconds: -1, comparisonLevel: 'SHALLOW', degradedReason: null, createdAt: '2026-10-28T10:00:30Z' }))
  rows[0].keyName = null
  const calls = []
  const result = await loadValidationExport({
    validationTask: async () => detail(),
    validationDifferences: async (_, page, size) => { calls.push([page, size]); return { total: 150, items: rows.slice((page - 1) * size, page * size) } },
  }, 9)
  assert.deepEqual(calls, [[1, 100], [2, 100]])
  assert.equal(result.truncated, false)
  assert.equal(result.total, 150)
  assert.equal(result.differences.length, 150)
  const csv = validationCsv(result.detail, result.differences, result.truncated, result.total, '源集群', '目标集群')
  assert.ok(csv.startsWith('﻿'))
  assert.ok(csv.includes('数据校验报告'))
  assert.ok(csv.includes('"源集群","源集群#1"'))
  assert.ok(csv.includes('"目标集群","目标集群#2"'))
  assert.ok(csv.includes('10% 抽样'))
  assert.ok(csv.includes('k:149'))
  assert.ok(csv.includes('历史记录仅保留摘要'))
  assert.ok(!csv.includes('仅导出前'))
})

test('truncates at 10000 rows with tail note', async () => {
  const page = Array.from({ length: 100 }, (_, i) => ({ id: i + 1 }))
  const calls = []
  const result = await loadValidationExport({
    validationTask: async () => detail(),
    validationDifferences: async (_, pageNumber, size) => { calls.push(pageNumber); return { total: 12345, items: page.map(row => ({ ...row, id: (pageNumber - 1) * size + row.id })) } },
  }, 9)
  assert.equal(calls.length, EXPORT_DIFF_LIMIT / 100)
  assert.equal(result.truncated, true)
  assert.equal(result.total, 12345)
  assert.equal(result.differences.length, 10000)
  const csv = validationCsv(result.detail, result.differences, result.truncated, result.total, '', '')
  assert.ok(csv.includes('差异共 12345 条，仅导出前 10000 条（按时间倒序）'))
})

test('rejects running tasks, drift, empty pages and changed versions', async () => {
  await assert.rejects(loadValidationExport({
    validationTask: async () => detail({ status: 'RUNNING' }),
    validationDifferences: async () => ({ total: 0, items: [] }),
  }, 9))
  let call = 0
  await assert.rejects(loadValidationExport({
    validationTask: async () => detail(),
    validationDifferences: async () => { call++; return call === 1 ? { total: 200, items: Array.from({ length: 100 }, (_, i) => ({ id: i + 1 })) } : { total: 210, items: [] } },
  }, 9))
  await assert.rejects(loadValidationExport({
    validationTask: async () => detail(),
    validationDifferences: async (_, page) => page === 1 ? { total: 5, items: [{ id: 1 }] } : { total: 5, items: [] },
  }, 9))
  let reads = 0
  await assert.rejects(loadValidationExport({
    validationTask: async () => detail({ version: ++reads + 1 }),
    validationDifferences: async () => ({ total: 0, items: [] }),
  }, 9))
})
