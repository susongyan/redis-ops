// UI helpers only. The server independently resolves and authorizes every command.
export function commandTree(rows) {
  const byId = new Map(rows.map(row => [row.id, {...row, key:row.id, title:row.commandName, children:[]}]))
  const roots=[]
  for (const node of byId.values()) {
    const parent=byId.get(node.parentId)
    if(parent && parent!==node) parent.children.push(node)
    else roots.push(node)
  }
  for(const node of byId.values()) if(!node.children.length) delete node.children
  return roots
}

export function matchCommand(rows, parsed) {
  const root=rows.find(row=>row.commandName===parsed.commandName && ['COMMAND','FAMILY'].includes(row.nodeKind||'COMMAND'))
  if(!root) return null
  if(root.nodeKind!=='FAMILY') return root
  const exact=`${root.commandName} ${(parsed.arguments[0]||'').toUpperCase()}`
  return rows.find(row=>row.parentId===root.id && row.commandName===exact)
    || rows.find(row=>row.parentId===root.id && row.commandName===`${root.commandName} *`) || null
}

export function commandSuggestions(rows, input) {
  const prefix=input.trimStart().toUpperCase()
  return rows.filter(row=>row.enabled && !['CATEGORY','WILDCARD'].includes(row.nodeKind)
      && row.commandName.startsWith(prefix)).slice(0,20).map(row=>row.commandName)
}

export function commandSyntax(command) {
  if(!command) return ''
  try {
    const fields=JSON.parse(command.parameterSchemaJson)
    const head=command.commandName.split(' ')[0]
    return `${head} ${fields.map(f=>f.literal || (f.required?`<${f.name}${f.variadic?' …':''}>`:`[${f.name}${f.variadic?' …':''}]`)).join(' ')}`.trim()
  } catch { return command.commandName }
}

export function effectivePolicy(rows,node){
  const byId=new Map(rows.map(row=>[row.id,row])),seen=new Set()
  let current=node,action,source
  while(current){
    if(seen.has(current.id)||seen.size>=16)return {action:'DENY',source:'目录关系无效'}
    seen.add(current.id)
    if(!current.enabled)return {action:'DENY',source:current.commandName+'（已禁用）'}
    if(!action&&current.approvalPolicy!=='INHERIT'){action=current.approvalPolicy;source=current.commandName}
    const parent=current.parentId
    current=parent==null?null:byId.get(parent)
    if(parent!=null&&!current)return {action:'DENY',source:'父节点不存在'}
  }
  if(!action||action==='DENY')return {action:'DENY',source:source||'未配置策略'}
  if(node.riskLevel==='HIGH'||node.accessMode==='MANAGE'||node.nodeKind==='WILDCARD'||action==='APPROVAL')action='DANGER_CONFIRM'
  else if(node.accessMode!=='READ'&&action==='DIRECT')action='CONFIRM'
  return {action,source}
}
