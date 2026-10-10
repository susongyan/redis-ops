export function editableParameters(command) {
  const fields=JSON.parse(command?.parameterSchemaJson||'[]')
  return command?.nodeKind==='SUBCOMMAND'?fields.slice(1):fields
}
export function groupCommands(rows,{search='',groupId,enabled}={}){
  const groups=rows.filter(r=>r.nodeKind==='CATEGORY').map(g=>({key:String(g.id),name:g.commandName,commands:[]}))
  const byId=new Map(groups.map(g=>[g.key,g])),ungrouped={key:'0',name:'未分组',commands:[]}
  const filtering=!!search.trim()||enabled!==undefined
  for(const row of rows){
    if(!['COMMAND','SUBCOMMAND'].includes(row.nodeKind))continue
    if(!row.commandName.includes(search.trim().toUpperCase())||(enabled!==undefined&&row.enabled!==enabled))continue
    const group=byId.get(String(row.parentId))||ungrouped
    group.commands.push(row)
  }
  return [...groups,ungrouped].filter(g=>(groupId===undefined||g.key===String(groupId))&&(!filtering||g.commands.length>0)&&(g.key!=='0'||g.commands.length>0||groupId===0))
}
export function encodeDefinition(v,groups){
  const name=v.commandName?.trim()||''
  if(v.nodeKind==='CATEGORY')return {commandName:name,nodeKind:'CATEGORY',parentId:null,category:'CUSTOM',enabled:true,accessMode:'READ',riskLevel:'LOW',approvalPolicy:'DIRECT',routingPolicy:'CONTAINER',keyPosition:0,maxValueBytes:4096,parameterSchemaJson:'[]',changeReason:v.changeReason}
  const parts=name.toUpperCase().split(/\s+/)
  if(parts.length>2||parts.some(p=>!/^[A-Z][A-Z0-9_.-]{0,31}$/.test(p)))throw Error('请输入命令名，例如 GET 或 CLUSTER INFO')
  const fields=(v.parameters||[]).map(f=>{const x={...f,required:!!f.required,variadic:!!f.variadic};if(x.literal===''||x.literal==null)delete x.literal;return x})
  if(fields.length>31)throw Error('参数最多 31 项')
  if(parts.length===2)fields.unshift({name:'subcommand',type:'TEXT',required:true,literal:parts[1]})
  const keys=fields.map((f,i)=>f.type==='REDIS_KEY'?i:-1).filter(i=>i>=0)
  if(keys.length>1||keys.some(i=>!fields[i].required||fields[i].variadic))throw Error('当前仅支持一个必填、不可重复的 Key 参数')
  let optional=false
  fields.forEach((f,i)=>{if(!f.name?.trim()||!f.type)throw Error('请填写参数名称和类型');if(optional&&f.required)throw Error('可选参数必须排在最后');optional||=!f.required;if(f.variadic&&i!==fields.length-1)throw Error('仅最后一个参数可重复')})
  return {commandName:parts.join(' '),nodeKind:parts.length===2?'SUBCOMMAND':'COMMAND',parentId:v.parentId??null,category:groups.find(g=>g.id===v.parentId)?.commandName||'未分组',enabled:!!v.enabled,accessMode:v.accessMode,riskLevel:v.riskLevel,approvalPolicy:v.approvalPolicy,maxValueBytes:v.maxValueBytes??4096,keyPosition:keys.length?keys[0]+1:0,routingPolicy:keys.length?'SINGLE_KEY':'NO_KEY',parameterSchemaJson:JSON.stringify(fields),changeReason:v.changeReason}
}
