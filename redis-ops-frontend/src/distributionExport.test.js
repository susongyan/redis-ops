import test from 'node:test'
import assert from 'node:assert/strict'
import { csvCell, distributionCsv, loadDistributionExport } from './distributionExport.js'
test('CSV quoting and formula protection', () => {
  assert.equal(csvCell('a,"b\nc'), '"a,""b\nc"')
  for (const value of ['=1', '+cmd', '-1', '@SUM(A1)', '  =1', '\tvalue']) assert.ok(csvCell(value).startsWith('"\''))
})
const task = { id: 5, version: 1, status: 'COMPLETED', observed: 21, spec: { clusterId: 1, rules: [], mode: 'FIXED' } }
test('exports all pages with metadata and BOM', async () => {
  const rows = Array.from({ length: 21 }, (_, i) => ({ group: { text: `group${i}` }, count: 1, error: 0 }))
  const pages = []
  const result = await loadDistributionExport({ distributionTask: async () => task, distributionGroups: async (_, page) => {
    pages.push(page); return { total: 21, items: rows.slice((page - 1) * 20, page * 20) }
  } }, 5)
  assert.deepEqual(pages, [1, 2])
  const csv = distributionCsv(result.task, result.groups)
  assert.ok(csv.startsWith('\ufeff'))
  assert.ok(csv.includes('group20'))
  assert.ok(csv.includes('非精确独立 Key 数'))
})
test('rejects live tasks, excessive groups and changed versions', async () => {
  await assert.rejects(loadDistributionExport({ distributionTask: async () => ({ ...task, status: 'RUNNING' }) }, 5))
  await assert.rejects(loadDistributionExport({ distributionTask: async () => task, distributionGroups: async () => ({ total: 1101, items: [] }) }, 5))
  let reads = 0
  await assert.rejects(loadDistributionExport({ distributionTask: async () => ({ ...task, version: ++reads }), distributionGroups: async () => ({ total: 0, items: [] }) }, 5))
})
