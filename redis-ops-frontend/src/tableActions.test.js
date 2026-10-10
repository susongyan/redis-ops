import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

test('list action columns stay fixed on the right with a bounded width', () => {
  const pages = ['Clusters', 'Locations', 'Applications', 'Relations', 'SyncTasks',
    'RiskScans', 'KeyDistributions', 'ValidationTasks', 'AnalysisRuns',
    'Alerts', 'AnalysisAgents', 'CommandCatalog', 'Users']
  for (const page of pages) {
    const source = readFileSync(new URL(`./pages/${page}Page.jsx`, import.meta.url), 'utf8')
    const columns = [...source.matchAll(/title:\s*['"]操作['"]([^]*?)render:/g)]
    assert.ok(columns.length, `${page}: missing action column`)
    for (const [, properties] of columns) {
      assert.match(properties, /fixed:\s*['"]right['"]/, `${page}: not fixed`)
      assert.match(properties, /width:\s*\d+/, `${page}: missing width`)
      assert.match(properties, /align:\s*['"]center['"]/, `${page}: inconsistent alignment`)
    }
  }
})
