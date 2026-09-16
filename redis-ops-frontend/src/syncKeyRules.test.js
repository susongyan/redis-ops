import test from 'node:test'
import assert from 'node:assert/strict'
import { previewSyncKeys, syncKeyDecision, syncKeyMatches } from './syncKeyRules.js'

test('same byte-level vectors as Worker BinaryGlob', () => {
  for (const [pattern, value, expected] of [
    ['biz:*', 'biz:order:1', true], ['biz:?', 'biz:中', false], ['biz:???', 'biz:中', true],
    ['biz:\\*', 'biz:*', true], ['biz:\\*', 'biz:a', false], ['[ab]', 'a', false],
    ['[ab]', '[ab]', true], ['a**?b', 'axxb', true], ['*a*b', 'zaayb', true],
    ['a\\', 'a\\', true], ['*', '', true], ['?', '', false],
  ]) assert.equal(syncKeyMatches(pattern, value), expected)
  assert.equal(syncKeyMatches('?', Uint8Array.of(255)), true)
})
test('exclude wins, empty includes means all, local preview is bounded', () => {
  assert.equal(syncKeyDecision('biz:private:x', ['biz:*'], ['*:private:*']), '排除规则命中')
  assert.equal(syncKeyDecision('other', [], []), '包含')
  assert.equal(syncKeyDecision('other', ['biz:*'], []), '未命中包含规则')
  assert.equal(previewSyncKeys(Array(1000).fill('biz:a').join('\n')).length, 20)
})
test('many stars do not recursively explode', () => {
  assert.equal(syncKeyMatches('*a'.repeat(100) + 'b', 'a'.repeat(1000)), false)
})
test('oversized preview work is refused rather than reported as nonmatching', () => {
  const result = previewSyncKeys('a'.repeat(16000), ['*a'.repeat(500) + 'b'])
  assert.match(result[0].decision, /预算超限/)
})
