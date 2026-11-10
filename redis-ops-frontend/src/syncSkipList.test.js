import test from 'node:test'
import assert from 'node:assert/strict'
import {parseSplitSkip,skipReasonTag,rdbTypeName,skipBytes} from './syncSkipList.js'

test('parseSplitSkip accepts the worker message and ignores appended fields',()=>{
  const parsed=parseSplitSkip('SPLIT_KEY_SKIPPED keyFingerprint=0123456789abcdef keyBytes=32 rdbType=4 payloadBytes=536870912 reason=OVER_HARD_CAP')
  assert.deepEqual(parsed,{fingerprint:'0123456789abcdef',keyBytes:32,rdbType:4,payloadBytes:536870912,reason:'OVER_HARD_CAP'})
  // 契约 8：新字段只能追加在行尾，解析不锚定结尾
  assert.equal(parseSplitSkip('SPLIT_KEY_SKIPPED keyFingerprint=ab keyBytes=1 rdbType=0 payloadBytes=2 reason=UNSUPPORTED_TYPE extra=3').reason,'UNSUPPORTED_TYPE')
  assert.equal(parseSplitSkip('别的消息'),null)
  assert.equal(parseSplitSkip(null),null)
  assert.equal(parseSplitSkip('SPLIT_KEY_SKIPPED keyFingerprint=zz keyBytes=x rdbType=4 payloadBytes=2 reason=R'),null)
})

test('skip reason tags and rdb type names cover the known vocabulary',()=>{
  assert.deepEqual(skipReasonTag('OVER_HARD_CAP'),['超硬上限','error'])
  assert.deepEqual(skipReasonTag('UNSUPPORTED_TYPE'),['不支持类型','warning'])
  assert.deepEqual(skipReasonTag('ELEMENT_OVER_BULK_LIMIT'),['单元素超限','error'])
  assert.deepEqual(skipReasonTag('SOMETHING_NEW'),['SOMETHING_NEW','default'])
  assert.equal(rdbTypeName(0),'string')
  assert.equal(rdbTypeName(4),'hash')
  assert.equal(rdbTypeName(14),'list')
  assert.equal(rdbTypeName(17),'zset')
  assert.equal(rdbTypeName(7),'module')
  assert.equal(rdbTypeName(21),'stream')
  assert.equal(rdbTypeName(25),'hash（字段级 TTL）')
  assert.equal(rdbTypeName(99),'type 99')
})

test('skipBytes renders human readable sizes down to bytes',()=>{
  assert.equal(skipBytes(null),'-')
  assert.equal(skipBytes(512),'512 B')
  assert.equal(skipBytes(4096),'4.0 KiB')
  assert.equal(skipBytes(5*1024*1024),'5.0 MiB')
  assert.equal(skipBytes(3*1024**3),'3.0 GiB')
})
