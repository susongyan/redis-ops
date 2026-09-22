import test from 'node:test'
import assert from 'node:assert/strict'
import { api } from './api.js'

test('both skip-and-start requests carry explicit consent, reason, CSRF and version', async () => {
  const original = globalThis.fetch, calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return { ok: true, status: 200, json: async () => ({ data: url.endsWith('/csrf') ? { headerName: 'X-CSRF-TOKEN', token: 'test-token' } : { status: 'RUNNING' } }) }
  }
  try {
    await api.skipTtlGovernanceDryRunAndStart(1, 7, 'CHG-1')
    await api.skipCleanupGovernanceDryRunAndStart(2, 8, 'CHG-2')
    const writes = calls.filter(c => c.options.method === 'POST')
    assert.equal(writes.length, 2)
    for (const [index, call] of writes.entries()) {
      assert.ok(call.url.endsWith('/skip-dry-run-and-start'))
      assert.deepEqual(JSON.parse(call.options.body), { reason: `CHG-${index + 1}`, confirmed: true })
      assert.equal(call.options.headers['If-Match'], String(7 + index))
      assert.ok(call.options.headers['Idempotency-Key'])
      assert.equal(call.options.headers['X-CSRF-TOKEN'], 'test-token')
    }
  } finally { globalThis.fetch = original }
})
