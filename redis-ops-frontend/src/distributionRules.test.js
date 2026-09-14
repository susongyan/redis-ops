import { test } from 'node:test'
import assert from 'node:assert/strict'
import { classifySample } from './distributionRules.js'
test('same rule vectors as DistributionCoreTest', () => {
  const vectors = [['order:detail:123', ':', 2, 'order:detail'], ['user||profile||42', '||', 2, 'user||profile'], ['a::b', ':', 2, 'a:'], ['a.b.c', '.', 1, 'a'], ['foo', ':', 2, 'STRUCTURE_MISMATCH'], [':x', ':', 1, '']]
  for (const [key, delimiter, segments, expected] of vectors) assert.equal(classifySample(key, [{ id: 'r', name: '业务', prefix: '', kind: 'SEGMENTS', delimiter, segments }]).text, expected)
})
test('bounded key and group and first-match semantics', () => {
  const rules = [{ id: 'first', kind: 'FIXED', name: '订单', prefix: 'order:' }, { id: 'r', kind: 'SEGMENTS', prefix: '', delimiter: ':', segments: 1 }]
  assert.equal(classifySample('order:1', rules).ruleId, 'first')
  assert.equal(classifySample('x'.repeat(4097), rules).text, 'KEY_TOO_LONG')
  assert.equal(classifySample('x'.repeat(257) + ':id', rules).text, 'GROUP_TOO_LONG')
})
