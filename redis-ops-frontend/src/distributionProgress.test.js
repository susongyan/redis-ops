import { test } from 'node:test'
import assert from 'node:assert/strict'
import { distributionProgress, formatDuration, distributionErrorText } from './distributionProgress.js'

const task = over => ({ status: 'RUNNING', observed: 500, estimatedTotal: 1000, elapsedMillis: 10000, reason: null, spec: { maxObservations: 0 }, ...over })

test('progress uses dbsize denominator and caps at 100', () => {
  const p = distributionProgress(task())
  assert.equal(p.denominator, 1000)
  assert.equal(p.percent, 50)
  assert.equal(p.rate, 50)
  assert.equal(p.remaining, 10)
  assert.equal(p.budgetCapped, false)
  const over = distributionProgress(task({ observed: 1500 }))
  assert.equal(over.percent, 100)
  assert.equal(over.remaining, null)
})

test('unknown denominator yields indeterminate progress without rate loss', () => {
  const p = distributionProgress(task({ estimatedTotal: 0 }))
  assert.equal(p.denominator, 0)
  assert.equal(p.percent, null)
  assert.equal(p.rate, 50)
  assert.equal(p.remaining, null)
})

test('maxObservations below the estimate truncates the denominator', () => {
  const p = distributionProgress(task({ spec: { maxObservations: 600 } }))
  assert.equal(p.denominator, 600)
  const capped = distributionProgress(task({ observed: 600, status: 'INCOMPLETE', reason: 'BUDGET_REACHED', spec: { maxObservations: 600 } }))
  assert.equal(capped.percent, 100)
  assert.equal(capped.budgetCapped, true)
})

test('rate stays hidden until elapsed passes the 3s warmup even while running', () => {
  const early = distributionProgress(task({ elapsedMillis: 3000 }))
  assert.equal(early.rate, null)
  assert.equal(distributionProgress(task({ elapsedMillis: 2999 })).rate, null)
  assert.equal(distributionProgress(task({ status: 'PAUSED' })).rate, null)
})

test('completed reaches 100 via the counter, failures keep honest coverage', () => {
  assert.equal(distributionProgress(task({ observed: 1000, status: 'COMPLETED' })).percent, 100)
  assert.equal(distributionProgress(task({ observed: 300, status: 'FAILED' })).percent, 30)
  assert.equal(distributionProgress(task({ observed: 300, status: 'CANCELLED' })).percent, 30)
})

test('duration formatting matches the risk-scan wording', () => {
  assert.equal(formatDuration(null), '—')
  assert.equal(formatDuration(59), '59 秒')
  assert.equal(formatDuration(60), '~1 分钟')
  assert.equal(formatDuration(5400), '~2 小时')
})

test('distribution error codes map to actionable copy', () => {
  assert.match(distributionErrorText({ code: 'DISTRIBUTION_BUSY', message: 'x' }), /并发槽已满/)
  assert.match(distributionErrorText({ code: 'DISTRIBUTION_CLUSTER_BUSY', message: 'x' }), /该集群已有/)
  assert.match(distributionErrorText({ code: 'REQUEST_IN_PROGRESS', message: 'x' }), /通道正忙/)
  assert.match(distributionErrorText({ code: 'DISTRIBUTION_PREVIEW_FAILED', message: 'x' }), /采样预览失败/)
  assert.equal(distributionErrorText({ code: 'OTHER', message: '后端文案' }), '后端文案')
  assert.equal(distributionErrorText(null), '请求失败')
})
