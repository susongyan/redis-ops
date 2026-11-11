import test from 'node:test'
import assert from 'node:assert/strict'
import {parseSplitSkip,skipReasonTag,rdbTypeName,skipBytes,skipKeyLabel,skipElementsLabel} from './syncSkipList.js'

test('parseSplitSkip reads v2 key-name messages and legacy fingerprint ones',()=>{
  // v2：key 原名在行尾（容忍空格）、集合类带声明元素个数
  const v2=parseSplitSkip('SPLIT_KEY_SKIPPED rdbType=4 keyBytes=22 elements=20010000 payloadBytes=536870912 reason=OVER_HARD_CAP key=biz:user:2024:followers')
  assert.deepEqual(v2,{key:'biz:user:2024:followers',fingerprint:null,keyBytes:22,rdbType:4,elements:20010000,payloadBytes:536870912,reason:'OVER_HARD_CAP'})
  const spaced=parseSplitSkip('SPLIT_KEY_SKIPPED rdbType=1 keyBytes=5 elements=3 payloadBytes=32 reason=ELEMENT_OVER_BULK_LIMIT key=a b c')
  assert.equal(spaced.key,'a b c')
  assert.equal(skipKeyLabel(spaced),'a b c')
  // v1 存量事件仍可解析，展示回退指纹
  const v1=parseSplitSkip('SPLIT_KEY_SKIPPED keyFingerprint=0123456789abcdef keyBytes=32 rdbType=4 payloadBytes=536870912 reason=OVER_HARD_CAP')
  assert.equal(v1.key,null)
  assert.equal(v1.elements,0)
  assert.equal(skipKeyLabel(v1),'0123456789abcdef')
  assert.equal(skipElementsLabel(v1),'—')
  assert.equal(skipElementsLabel(v2),'20,010,000')
  assert.equal(skipKeyLabel(null),'-')
  assert.equal(parseSplitSkip('别的消息'),null)
  assert.equal(parseSplitSkip(null),null)
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
