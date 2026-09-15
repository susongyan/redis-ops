import test from 'node:test'
import assert from 'node:assert/strict'
import {api} from './api.js'

test('collector metrics uses authenticated JSON API and no cache',async()=>{
  const original=globalThis.fetch,calls=[]
  globalThis.fetch=async(url,options)=>{calls.push({url,options});return {ok:true,status:200,json:async()=>({data:{'1':{redis_ops_collector_up:1}}})}}
  try{
    assert.deepEqual(await api.collectorMetrics(),{'1':{redis_ops_collector_up:1}})
    assert.equal(calls[0].url,'/api/v1/collector/metrics')
    assert.equal(calls[0].options.credentials,'same-origin')
    assert.equal(calls[0].options.cache,'no-store')
  }finally{globalThis.fetch=original}
})
