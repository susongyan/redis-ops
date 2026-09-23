import test from 'node:test'
import assert from 'node:assert/strict'
import { api } from './api.js'

test('cluster details are read without caching or a separate password request', async () => {
  const original = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return { ok: true, status: 200, json: async () => ({ data: { password: 'test-only-secret' } }) }
  }
  try {
    assert.equal((await api.cluster(7)).password, 'test-only-secret')
    assert.equal(calls.length, 1)
    assert.equal(calls[0].url, '/api/v1/clusters/7')
    assert.equal(calls[0].options.cache, 'no-store')
  } finally { globalThis.fetch = original }
})
