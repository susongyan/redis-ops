import test from 'node:test'
import assert from 'node:assert/strict'
import {commandTree,matchCommand,commandSuggestions,commandSyntax,effectivePolicy} from './commandTree.js'
const rows=[
  {id:1,commandName:'CLUSTER',nodeKind:'FAMILY',enabled:true},
  {id:2,parentId:1,commandName:'CLUSTER INFO',nodeKind:'SUBCOMMAND',enabled:false,parameterSchemaJson:'[{"name":"subcommand","literal":"INFO"}]'},
  {id:3,parentId:1,commandName:'CLUSTER *',nodeKind:'WILDCARD',enabled:true},
]
test('disabled exact definition is not hidden by wildcard in UI',()=>{
  assert.equal(matchCommand(rows,{commandName:'CLUSTER',arguments:['info']}).id,2)
  assert.equal(matchCommand(rows,{commandName:'CLUSTER',arguments:['nodes']}).id,3)
  assert.equal(matchCommand([rows[0]],{commandName:'CLUSTER',arguments:['nodes']}),null)
})
test('effective display cannot present a write as direct or ignore disabled ancestor',()=>{
  const root={id:1,commandName:'GROUP.STRING',enabled:true,approvalPolicy:'DIRECT'}
  const child={id:2,parentId:1,commandName:'SET',enabled:true,accessMode:'WRITE',riskLevel:'LOW',approvalPolicy:'INHERIT'}
  assert.equal(effectivePolicy([root,child],child).action,'CONFIRM')
  assert.equal(effectivePolicy([{...root,enabled:false},child],child).action,'DENY')
})
test('tree, bounded suggestions and syntax come from definitions',()=>{
  assert.equal(commandTree(rows)[0].children.length,2)
  assert.deepEqual(commandSuggestions(rows,'cl'),['CLUSTER'])
  assert.equal(commandSyntax(rows[1]),'CLUSTER INFO')
  assert.equal(commandSuggestions(Array.from({length:50},(_,i)=>({commandName:`TEST${i}`,enabled:true})),'').length,20)
})
