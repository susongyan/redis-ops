import test from 'node:test'
import assert from 'node:assert/strict'
import {editableParameters,encodeDefinition,groupCommands} from './commandDefinition.js'
const base={commandName:'GET',enabled:true,accessMode:'READ',riskLevel:'LOW',approvalPolicy:'DIRECT',parameters:[{name:'key',type:'REDIS_KEY',required:true}],changeReason:'test'}
test('groups follow manual assignments and missing parents go to ungrouped',()=>{
  const rows=[{id:1,nodeKind:'CATEGORY',commandName:'String'},{id:2,nodeKind:'CATEGORY',commandName:'空分组'},
    {id:3,nodeKind:'COMMAND',commandName:'GET',parentId:1,enabled:true},
    {id:4,nodeKind:'SUBCOMMAND',commandName:'CLUSTER INFO',parentId:99,enabled:false}]
  assert.deepEqual(groupCommands(rows).map(g=>[g.name,g.commands.length]),[['String',1],['空分组',0],['未分组',1]])
  assert.deepEqual(groupCommands(rows,{search:'get'}).map(g=>g.key),['1'])
  assert.deepEqual(groupCommands(rows,{enabled:false}).map(g=>g.key),['0'])
  assert.equal(groupCommands(rows,{groupId:2})[0].commands.length,0)
  assert.equal(groupCommands(rows,{search:'missing'}).length,0)
})
test('new commands may be enabled and have independent manual groups',()=>{
  const d=encodeDefinition({...base,parentId:2},[{id:2,commandName:'手动分组'}])
  assert.equal(d.enabled,true);assert.equal(d.category,'手动分组');assert.equal(d.keyPosition,1)
})
test('subcommand literal is hidden and round trips without a family',()=>{
  const d=encodeDefinition({...base,commandName:'CLUSTER INFO',parameters:[]},[])
  assert.equal(d.nodeKind,'SUBCOMMAND');assert.equal(d.parentId,null);assert.equal(d.keyPosition,0)
  assert.deepEqual(editableParameters(d),[]);assert.equal(JSON.parse(d.parameterSchemaJson)[0].literal,'INFO')
})
test('group has no executable parameters or policy controls',()=>{
  const d=encodeDefinition({nodeKind:'CATEGORY',commandName:'String',changeReason:'test'},[])
  assert.equal(d.routingPolicy,'CONTAINER');assert.equal(d.parameterSchemaJson,'[]')
})
test('invalid key and optional layouts rejected before sending',()=>{
  assert.throws(()=>encodeDefinition({...base,parameters:[...base.parameters,...base.parameters]},[]))
  assert.throws(()=>encodeDefinition({...base,parameters:[{name:'x',type:'TEXT'},...base.parameters]},[]))
  assert.throws(()=>encodeDefinition({...base,commandName:'CLUSTER *'},[]))
})
