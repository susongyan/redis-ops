import {parseConsoleCommand} from '../consoleCommand.js'
import { actorLabel } from '../actor.js'
import {useEffect,useMemo,useState} from 'react'
import {Alert,Button,Card,Input,InputNumber,Select,Space,Switch,Table,Tag,message} from 'antd'
import {CodeOutlined,PlayCircleOutlined,ReloadOutlined,SecurityScanOutlined} from '@ant-design/icons'
import {api} from '../api.js'

const riskColor={LOW:'cyan',MEDIUM:'orange',HIGH:'red'}

export default function RedisOperationsPage(){
  const [clusters,setClusters]=useState([]),[commands,setCommands]=useState([]),[rows,setRows]=useState([])
  const [clusterId,setClusterId]=useState(),[databaseNo,setDatabaseNo]=useState(0),[commandName,setCommandName]=useState(),[commandLine,setCommandLine]=useState(''),[args,setArgs]=useState([])
  const [parseError,setParseError]=useState('')
  const [preview,setPreview]=useState(),[operation,setOperation]=useState(),[loading,setLoading]=useState(false),[drafts,setDrafts]=useState({})
  const cluster=clusters.find(x=>x.id===clusterId)
  const activeCommands=commands.filter(x=>x.enabled)
  const command=useMemo(()=>activeCommands.find(x=>x.commandName===commandName),[activeCommands,commandName])
  const fields=useMemo(()=>{try{return command?JSON.parse(command.parameterSchemaJson):[]}catch{return[]}},[command])
  const valueMax=command?.maxValueBytes>0?command.maxValueBytes:4096
  const allowedTypes=()=>{try{return command?JSON.parse(command.allowedDataTypesJson||'[]').join(', '):''}catch{return''}}
  const load=async()=>{const [c,cmd,r]=await Promise.all([api.clusters({page:1,size:100}),api.operationCommands(true,true),api.operations()]);setClusters(c?.items||c||[]);setCommands(cmd||[]);setRows(r||[])}
  useEffect(()=>{load().catch(e=>message.error(e.message))},[])
  const setCommand=value=>{const normalized=(value||'').toUpperCase();setCommandName(normalized);const next=activeCommands.find(x=>x.commandName===normalized);setArgs(next?new Array(JSON.parse(next.parameterSchemaJson).length).fill(''):[]);setPreview();setOperation()}
  const parseCommandLine=value=>{setCommandLine(value);setPreview();setOperation();try{const parsed=parseConsoleCommand(value);setCommandName(parsed.commandName);setArgs(parsed.arguments);setParseError('')}catch(e){setParseError(e.message);setArgs([])}}
  const payload=()=>({clusterId,databaseNo:cluster?.mode==='CLUSTER'?0:databaseNo,commandName,arguments:args})
  const previewCommand=async()=>{try{setPreview(await api.operationPreview(payload()))}catch(e){message.error(e.message)}}
  const submit=async()=>{try{setLoading(true);setOperation(await api.createOperation(payload()));await load();message.success('命令已提交')}catch(e){message.error(e.message)}finally{setLoading(false)}}
  const confirm=async()=>{try{setOperation(await api.confirmOperation(operation.id,operation.version));message.success('已确认，等待执行')}catch(e){message.error(e.message)}}
  const approve=async()=>{try{setOperation(await api.approveOperation(operation.id,operation.version,'console approval'));message.success('已审批，等待执行')}catch(e){message.error(e.message)}}
  const execute=async()=>{try{setLoading(true);setOperation(await api.executeOperation(operation.id,operation.version,payload()));await load();message.success('执行完成')}catch(e){message.error(e.message)}finally{setLoading(false)}}
  const canExecute=operation&&['APPROVED','CONFIRMED'].includes(operation.status)
  return <div className="redis-console-page">
    <div className="redis-console-heading"><div><div className="redis-console-title"><CodeOutlined/> Redis Console</div><div className="redis-console-subtitle">执行 Redis 命令，查看操作记录</div></div><Button icon={<ReloadOutlined/>} onClick={()=>load()}>刷新记录</Button></div>
    <Card className="redis-console-shell" bodyStyle={{padding:0}}>
      <div className="redis-console-toolbar"><Space wrap><span className="console-label">TARGET</span><Select value={clusterId} onChange={v=>{setClusterId(v);setPreview();setOperation()}} placeholder="选择 Redis 集群" style={{width:280}} options={clusters.map(c=>({value:c.id,label:`${c.name}  ·  ${c.mode}`}))}/><span className="console-label">DB</span><InputNumber min={0} max={15} value={cluster?.mode==='CLUSTER'?0:databaseNo} disabled={cluster?.mode==='CLUSTER'} onChange={v=>setDatabaseNo(v||0)}/>{cluster?.mode==='CLUSTER'&&<Tag color="purple">Cluster 固定 DB 0</Tag>}</Space></div>
      <div className="redis-terminal"><div className="terminal-top"><span className="terminal-dot red"/><span className="terminal-dot yellow"/><span className="terminal-dot green"/><span className="terminal-caption">redis-console / structured mode</span></div><div className="terminal-body"><div className="terminal-context">连接目标：{cluster?`${cluster.name} (${cluster.mode})`:'未选择'}　数据库：{cluster?.mode==='CLUSTER'?0:databaseNo}</div><div className="terminal-prompt"><span>$</span><Input bordered={false} value={commandLine} onChange={e=>parseCommandLine(e.target.value)} placeholder="输入命令，例如 GET my-key、SET my-key hello world" list="redis-command-list"/><datalist id="redis-command-list">{commands.map(c=><option key={c.commandName} value={c.commandName}/>)}</datalist></div><div className="terminal-help">{parseError|| (command?`${command.commandName}  ·  ${command.category}  ·  风险 ${command.riskLevel}  ·  ${command.approvalPolicy}${fields.some(f=>f.type==='VALUE')?`  ·  Value ≤ ${valueMax} B（支持空格）`:''}`:'命令名不区分大小写；包含空格的参数请用引号包裹，例如 SET my-key "hello world"。')}</div><Space className="terminal-actions"><Button ghost icon={<SecurityScanOutlined/>} onClick={previewCommand} disabled={!command||!clusterId||!!parseError}>预览</Button><Button type="primary" ghost icon={<PlayCircleOutlined/>} loading={loading} onClick={submit} disabled={!command||!clusterId||!!parseError}>提交</Button></Space>{preview&&<div className="terminal-output"><div className="output-label">PREVIEW</div><pre>{JSON.stringify(preview,null,2)}</pre></div>}{operation&&<div className="terminal-output"><div className="output-label">OPERATION {operation.operationNo}</div><pre>{JSON.stringify(operation.resultJson?JSON.parse(operation.resultJson):{status:operation.status,risk:operation.riskLevel},null,2)}</pre><Space>{operation.status==='PENDING_CONFIRMATION'&&<Button size="small" onClick={confirm}>确认写入</Button>}{operation.status==='PENDING_APPROVAL'&&<Button size="small" onClick={approve}>审批</Button>}{canExecute&&<Button size="small" type="primary" onClick={execute} loading={loading}>执行命令</Button>}</Space></div>}</div></div>
    </Card>
    <Card className="redis-console-history" title="Command history" extra={<Tag color="blue">{rows.length} records</Tag>}><Table rowKey="id" dataSource={rows} scroll={{x:1300}} pagination={false} size="small" columns={[{title:'时间',dataIndex:'createdAt',width:190},{title:'命令',dataIndex:'commandName',render:(x,r)=><span className="mono">{x} <Tag color={riskColor[r.riskLevel]}>{r.riskLevel}</Tag></span>},{title:'状态',dataIndex:'status',render:x=><Tag>{x}</Tag>},{title:'发起人',dataIndex:'operatorName',width:180,render:(value,row)=>actorLabel(value,row.operatorSnapshot)},{title:'审批／确认人',dataIndex:'approverName',width:180,render:(value,row)=>actorLabel(value,row.approverSnapshot)},{title:'执行人',dataIndex:'executorName',width:180,render:(value,row)=>actorLabel(value,row.executorSnapshot)},{title:'编号',dataIndex:'operationNo',width:320,render:x=><span className="mono">{x}</span>}]}/></Card>
    <Alert className="redis-console-notice" type="info" showIcon message="执行提示" description="仅可执行已启用的命令，部分操作需要确认或审批。不支持多 Key 路由、Cluster 无 Key 命令、事务或订阅会话。超时或响应超限不代表执行失败，请核实结果后再决定是否重试。"/>
  </div>
}
