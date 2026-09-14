import test from 'node:test'
import assert from 'node:assert/strict'
import { request } from './api.js'
test('writes fetch a CSRF token and do not trust X-Operator', async()=>{
  const original=globalThis.fetch,calls=[]
  globalThis.fetch=async(url,options)=>{calls.push({url,options});return {ok:true,status:200,json:async()=>({data:url.endsWith('/csrf')?{token:'test-token',headerName:'X-CSRF-TOKEN'}:{saved:true}})}}
  try{
    assert.deepEqual(await request('/api/v1/users',{method:'POST',body:'{}'}),{saved:true})
    assert.equal(calls.length,2);assert.equal(calls[1].options.headers['X-CSRF-TOKEN'],'test-token')
    assert.equal(calls[1].options.headers['X-Operator'],undefined)
    assert.equal(calls[1].options.credentials,'same-origin')
  }finally{globalThis.fetch=original}
})
test('expired business session signals unmount; invalid login does not loop',async()=>{
  const original=globalThis.fetch,oldWindow=globalThis.window,events=[]
  globalThis.window={dispatchEvent:event=>events.push(event.type)}
  globalThis.fetch=async()=>({ok:false,status:401,json:async()=>({code:'SESSION_EXPIRED'})})
  try{await assert.rejects(request('/api/v1/auth/me'));assert.deepEqual(events,['identity-expired'])
    await assert.rejects(request('/api/v1/auth/login'));assert.equal(events.length,1)
  }finally{globalThis.fetch=original;globalThis.window=oldWindow}
})
