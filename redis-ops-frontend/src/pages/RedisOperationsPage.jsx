import {useEffect,useMemo,useRef,useState} from 'react'
import {Alert,Button,Card,Collapse,Form,Input,InputNumber,Modal,Select,Space,Table,Tree,message} from 'antd'
import {api} from '../api.js'
import {actorLabel} from '../actor.js'
import {parseConsoleCommand} from '../consoleCommand.js'
import {commandTree,matchCommand,commandSyntax,commandSuggestions} from '../commandTree.js'

function output(operation){
  if(!operation.resultJson)return operation.status
  try{const r=JSON.parse(operation.resultJson);return r.success?(r.type==='null'?'(nil)':r.value??operation.status):'错误：'+(r.errorCode||operation.status)}catch{return operation.status}
}
export default function RedisOperationsPage(){
  const [clusters,setClusters]=useState([]),[commands,setCommands]=useState([]),[rows,setRows]=useState([])
  const [clusterId,setClusterId]=useState(),[db,setDb]=useState(0),[line,setLine]=useState('')
  const [busy,setBusy]=useState(false),[entries,setEntries]=useState([]),[pending,setPending]=useState(null)
  const history=useRef([]),index=useRef(0),lock=useRef(false),alive=useRef(true)
  const outputRef=useRef(null),inputRef=useRef(null),wasBusy=useRef(false)
  const [form]=Form.useForm()
  const cluster=clusters.find(c=>c.id===clusterId)
  const parsed=useMemo(()=>{try{return {...parseConsoleCommand(line),error:null}}catch(e){return {commandName:'',arguments:[],error:e.message}}},[line])
  const selected=matchCommand(commands,parsed)
  const load=async()=>{const [c,d,r]=await Promise.all([api.clusters({page:1,size:100}),api.operationCommands(true,true),api.operations()]);if(alive.current){setClusters(c?.items||c||[]);setCommands(d||[]);setRows(r||[])}}
  useEffect(()=>{alive.current=true;load().catch(e=>message.error(e.message));return()=>{alive.current=false;history.current=[]}},[])
  useEffect(()=>{const panel=outputRef.current;if(panel)panel.scrollTop=panel.scrollHeight},[entries])
  useEffect(()=>{if(wasBusy.current&&!busy&&!pending)inputRef.current?.focus({preventScroll:true});wasBusy.current=busy},[busy,pending])
  const append=entry=>{if(alive.current)setEntries(previous=>[...previous,entry].slice(-30))}
  const refresh=()=>api.operations().then(r=>{if(alive.current)setRows(r||[])}).catch(()=>{})
  const submit=async()=>{
    if(lock.current||pending||!cluster||!parsed.commandName||parsed.error)return
    lock.current=true;setBusy(true)
    const payload={clusterId,databaseNo:cluster.mode==='CLUSTER'?0:db,commandName:parsed.commandName,arguments:parsed.arguments}
    const snapshot={line,target:cluster.name+' / DB '+payload.databaseNo,clusterName:cluster.name,payload}
    try{
      const preview=await api.operationPreview(payload)
      if(!alive.current)return
      history.current=[...history.current,line].slice(-50);index.current=history.current.length
      if(preview.action!=='DIRECT'){form.resetFields();setPending({...snapshot,preview});return}
      const start=performance.now(),operation=await api.createOperation(payload)
      if(operation.status==='PENDING_CONFIRMATION'){form.resetFields();setPending({...snapshot,operation,preview:JSON.parse(operation.previewJson)});return}
      append({...snapshot,line:snapshot.line,payload:undefined,output:output(operation),status:operation.status,ms:Math.round(performance.now()-start)})
      setLine('');refresh()
    }catch(e){append({line:snapshot.line,target:snapshot.target,output:e.message,status:'ERROR'})}
    finally{lock.current=false;if(alive.current)setBusy(false)}
  }
  const confirm=async()=>{
    if(lock.current||!pending)return
    let fields;try{fields=await form.validateFields()}catch{return}
    lock.current=true;setBusy(true)
    const snapshot=pending,start=performance.now()
    try{
      const operation=snapshot.operation||await api.createOperation(snapshot.payload)
      if(operation.status!=='PENDING_CONFIRMATION'){append({line:snapshot.line,target:snapshot.target,output:output(operation),status:operation.status});return}
      const current=JSON.parse(operation.previewJson)
      if(current.policyFingerprint!==snapshot.preview.policyFingerprint||current.action!==snapshot.preview.action){await api.cancelOperation(operation.id,operation.version);throw new Error('执行策略已变更，请重新提交命令')}
      const confirmed=await api.confirmOperation(operation.id,operation.version,fields)
      const done=await api.executeOperation(confirmed.id,confirmed.version,snapshot.payload)
      append({line:snapshot.line,target:snapshot.target,output:output(done),status:done.status,ms:Math.round(performance.now()-start)})
      setLine('')
    }catch(e){append({line:snapshot.line,target:snapshot.target,output:e.message+'。如已发送执行请求，请核实 Redis 状态，不要直接重试。',status:'ERROR'})}
    finally{setPending(null);lock.current=false;if(alive.current)setBusy(false);refresh()}
  }
  const cancel=()=>{if(busy)return;if(pending?.operation)api.cancelOperation(pending.operation.id,pending.operation.version).catch(()=>{});setPending(null)}
  const keyDown=e=>{
    if(e.nativeEvent.isComposing||busy)return
    if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();submit()}
    else if(e.key==='Tab'){const matches=commandSuggestions(commands,line);if(matches.length===1){e.preventDefault();setLine(matches[0]+' ')}}
    else if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='l'){e.preventDefault();setEntries([])}
    else if(e.key==='ArrowUp'&&!line.includes('\n')){e.preventDefault();index.current=Math.max(0,index.current-1);setLine(history.current[index.current]||'')}
    else if(e.key==='ArrowDown'&&!line.includes('\n')){e.preventDefault();index.current=Math.min(history.current.length,index.current+1);setLine(history.current[index.current]||'')}
  }
  return <div className="redis-console-page">
    <div className="redis-console-heading"><div><div className="redis-console-title">Redis Console</div><div className="redis-console-subtitle">查询直接执行，修改操作需确认</div></div><Button onClick={()=>load().catch(e=>message.error(e.message))}>刷新</Button></div>
    <Card className="redis-console-shell" styles={{body:{padding:0}}}>
      <div className="redis-console-toolbar"><Space wrap><Select aria-label="目标集群" disabled={busy||!!pending} value={clusterId} onChange={value=>{setClusterId(value);setDb(0);setEntries([]);history.current=[];index.current=0}} placeholder="选择 Redis 集群" style={{width:280,maxWidth:'100%'}} options={clusters.map(c=>({value:c.id,label:c.name+' · '+c.environment+' · '+c.mode}))}/><span>DB</span><InputNumber aria-label="数据库" min={0} max={15} value={cluster?.mode==='CLUSTER'?0:db} disabled={busy||!!pending||cluster?.mode==='CLUSTER'} onChange={value=>setDb(value||0)}/></Space></div>
      <div className="redis-terminal"><div className="terminal-top"><span className="terminal-dot red"/><span className="terminal-dot yellow"/><span className="terminal-dot green"/><span className="terminal-caption">Redis Console</span><Button size="small" onClick={()=>setEntries([])}>清屏</Button></div><div className="terminal-body">
        <div className="terminal-context">{cluster?cluster.name+' · '+cluster.environment+' · DB '+(cluster.mode==='CLUSTER'?0:db):'请选择执行目标'}</div>
        <div ref={outputRef} className="terminal-results" role="log" aria-label="命令执行结果" aria-live="polite" tabIndex={0}>{entries.length===0&&<div className="terminal-empty">执行结果将显示在这里</div>}{entries.map((entry,i)=><div className="terminal-output" key={i}><div className="output-label">{entry.target} · {entry.status}{entry.ms!=null?' · '+entry.ms+' ms':''}</div><pre>{'redis> '+entry.line}</pre><pre>{entry.output}</pre></div>)}</div>
        <div className="terminal-prompt"><span>redis&gt;</span><Input.TextArea ref={inputRef} aria-label="Redis 命令" autoSize={{minRows:1,maxRows:6}} variant="borderless" value={line} maxLength={8192} disabled={busy||!!pending} onChange={e=>setLine(e.target.value)} onKeyDown={keyDown} placeholder="输入命令，例如 GET my-key"/></div>
        <div className="terminal-help">{parsed.error||(selected?commandSyntax(selected)+' · '+selected.accessMode+' · '+selected.riskLevel+(selected.enabled?'':' · 已禁用'):'Enter 执行 · Shift+Enter 换行 · ↑↓ 历史 · Tab 补全 · Ctrl+L 清屏')}</div>
        <Space wrap className="terminal-actions"><Button type="primary" loading={busy} disabled={!cluster||!parsed.commandName||!!parsed.error||!!pending} onClick={submit}>执行</Button>{commandSuggestions(commands,line).slice(0,6).map(name=><Button key={name} size="small" disabled={busy||!!pending} onClick={()=>setLine(name+' ')}>{name}</Button>)}</Space>
      </div></div>
    </Card>
    <Collapse items={[{key:'help',label:'命令帮助',children:<Tree treeData={commandTree(commands)} onSelect={(_,info)=>{if(!['CATEGORY','FAMILY','WILDCARD'].includes(info.node.nodeKind)&&!busy&&!pending)setLine(info.node.commandName+' ')}}/>},{key:'history',label:'操作记录',children:<Table size="small" rowKey="id" dataSource={rows} scroll={{x:900}} pagination={false} columns={[{title:'时间',dataIndex:'createdAt'},{title:'命令',dataIndex:'commandName'},{title:'状态',dataIndex:'status'},{title:'操作者',render:(_,r)=>actorLabel(r.operatorName,r.operatorSnapshot)},{title:'编号',dataIndex:'operationNo'}]}/>} ]}/>
    <Alert type="info" showIcon message="执行提示" description="一次执行一条命令。当前不支持 Cluster 无 Key 命令、跨请求事务或订阅；超时不代表操作未生效。当前页面的命令及结果离开后清除。"/>
    <Modal title={pending?.preview.action==='DANGER_CONFIRM'?'确认高危操作':'确认执行操作'} open={!!pending} onCancel={cancel} onOk={confirm} confirmLoading={busy} closable={!busy} maskClosable={false} okText="确认并执行" cancelText="取消" destroyOnHidden>
      <p>目标：{pending?.target}</p><p>命令：{pending?.preview.command} · 风险：{pending?.preview.riskLevel}</p><pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere',maxHeight:160,overflow:'auto'}}>{pending?.line}</pre>
      <Form form={form} layout="vertical">{pending?.preview.action==='DANGER_CONFIRM'&&<><Alert type="warning" showIcon message="请核实目标与影响范围，此操作可能无法撤销。"/><Form.Item name="targetName" label="输入集群名称确认" rules={[{required:true},{validator:(_,value)=>value===pending?.clusterName?Promise.resolve():Promise.reject(new Error('集群名称不一致'))}]}><Input autoComplete="off"/></Form.Item><Form.Item name="reason" label="操作原因（请勿填写 Key、value 或密码）" rules={[{required:true,whitespace:true,max:512}]}><Input.TextArea maxLength={512}/></Form.Item></>}</Form>
    </Modal>
  </div>
}
