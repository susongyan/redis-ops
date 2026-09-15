import test from 'node:test'
import assert from 'node:assert/strict'
import {addBindings} from './bindingBatch.js'
test('many targets are added without replacing existing relations',async()=>{
  const called=[]
  const result=await addBindings([1,2,3,3],new Set([1]),async id=>called.push(id))
  assert.deepEqual(called,[2,3]);assert.deepEqual(result,{added:2,skipped:1,failed:[]})
})
test('partial failure retains successes and only retries failed targets',async()=>{
  const existing=new Set()
  const result=await addBindings([1,2,3],existing,async id=>{if(id===2)throw Error();existing.add(id)})
  assert.deepEqual(result,{added:2,skipped:0,failed:[2]})
  const retried=await addBindings(result.failed,existing,async id=>existing.add(id))
  assert.equal(retried.added,1);assert.deepEqual([...existing].sort(),[1,2,3])
})
