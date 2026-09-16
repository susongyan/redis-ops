import test from 'node:test'
import assert from 'node:assert/strict'
import {auditDetails,auditValue} from './auditDetails.js'
test('audit details tolerate historical and invalid data without inventing history',()=>{
  assert.equal(auditDetails(null),null)
  assert.equal(auditDetails('bad json'),null)
  assert.equal(auditDetails('{}'),null)
  const detail=auditDetails(JSON.stringify({summary:'修改命令',changes:[{field:'启用',before:false,after:true}]}))
  assert.equal(detail.changes[0].before,false)
  assert.equal(auditValue(false),'否')
  assert.equal(auditValue(0),'0')
  assert.equal(auditValue(null),'—')
})
