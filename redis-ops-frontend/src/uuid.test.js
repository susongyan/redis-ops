import test from 'node:test'
import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'
import { randomUuid } from './uuid.js'
import { api } from './api.js'

test('prefers native UUID and preserves the crypto receiver', () => {
  const provider = { randomUUID() { assert.equal(this, provider); return 'native-uuid' } }
  assert.equal(randomUuid(provider), 'native-uuid')
})

test('HTTP fallback sets UUID v4 version and variant bits', () => {
  const provider = { getRandomValues(bytes) {
    assert.equal(this, provider)
    assert.equal(bytes.length, 16)
    return bytes.fill(255)
  } }
  assert.equal(randomUuid(provider), 'ffffffff-ffff-4fff-bfff-ffffffffffff')
  assert.equal(randomUuid({ getRandomValues: bytes => bytes.fill(0) }), '00000000-0000-4000-8000-000000000000')
})

test('HTTP fallback generates independent well-formed IDs', () => {
  const provider = { getRandomValues: bytes => webcrypto.getRandomValues(bytes) }
  const ids = Array.from({ length: 1000 }, () => randomUuid(provider))
  assert.equal(new Set(ids).size, ids.length)
  for (const id of ids) assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
})

test('fails explicitly if no secure random source is available', () => {
  assert.throws(() => randomUuid({}), /浏览器不支持安全随机数/)
  assert.throws(() => randomUuid(null), /浏览器不支持安全随机数/)
})

test('write requests keep unique idempotency keys when randomUUID is unavailable', async () => {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'crypto')
  const originalFetch = globalThis.fetch
  const writes = []
  Object.defineProperty(globalThis, 'crypto', {
    configurable: true,
    value: { getRandomValues: bytes => webcrypto.getRandomValues(bytes) },
  })
  globalThis.fetch = async (url, options) => {
    if (options.method === 'POST') writes.push(options)
    return { ok: true, status: 200, json: async () => ({ data: url.endsWith('/csrf')
      ? { headerName: 'X-CSRF-TOKEN', token: 'test-token' } : { id: 1 } }) }
  }
  try {
    await api.createDistribution({})
    await api.createDistribution({})
    assert.equal(writes.length, 2)
    for (const request of writes) {
      assert.match(request.headers['Idempotency-Key'], /^[0-9a-f-]{36}$/)
      assert.equal(request.headers['X-CSRF-TOKEN'], 'test-token')
    }
    assert.notEqual(writes[0].headers['Idempotency-Key'], writes[1].headers['Idempotency-Key'])
  } finally {
    globalThis.fetch = originalFetch
    if (descriptor) Object.defineProperty(globalThis, 'crypto', descriptor)
    else delete globalThis.crypto
  }
})
