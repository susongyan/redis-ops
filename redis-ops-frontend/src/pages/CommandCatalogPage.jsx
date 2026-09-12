import {useEffect,useState} from 'react'
import {Alert,Button,Card,Empty,InputNumber,Select,Space,Spin,Switch,Table,Tag,message} from 'antd'
import {api} from '../api.js'

export default function CommandCatalogPage(){
  const [rows,setRows]=useState([]),[drafts,setDrafts]=useState({}),[loading,setLoading]=useState(true),[error,setError]=useState('')
  const load=async()=>{
    setLoading(true)
    setError('')
    try{setRows(await api.operationCommands(true,true)||[])}catch(e){setError(e.message||'命令配置加载失败')}finally{setLoading(false)}
  }
  useEffect(()=>{load()},[])
  const edit=(row,patch)=>setDrafts(d=>({...d,[row.id]:{...row,...(d[row.id]||{}),...patch}}))
  const dataTypes=row=>{
    const value=drafts[row.id]?.allowedDataTypes
    if(Array.isArray(value))return value
    try{const parsed=JSON.parse(row.allowedDataTypesJson||'[]');return Array.isArray(parsed)?parsed:[]}catch{return[]}
  }
  const save=async row=>{const x=drafts[row.id]||row;try{await api.updateOperationCommand(row.id,row.version,{enabled:x.enabled,riskLevel:x.riskLevel,approvalPolicy:x.approvalPolicy,maxValueBytes:x.maxValueBytes,allowedDataTypes:dataTypes(row),missingKeyPolicy:row.missingKeyPolicy||'EXISTING_REQUIRED',blockedByDefault:x.blockedByDefault??row.blockedByDefault,changeReason:row.changeReason||''});message.success(`${row.commandName} 配置已保存`);setDrafts({});await load()}catch(e){message.error(e.message||'保存失败')}}
  const columns=[{title:'命令',dataIndex:'commandName',render:x=><span className="mono">{x}</span>},{title:'类型',dataIndex:'category'},{title:'启用',dataIndex:'enabled',render:(v,r)=><Switch checked={drafts[r.id]?.enabled??v} onChange={x=>edit(r,{enabled:x})}/>},{title:'风险',dataIndex:'riskLevel',render:(v,r)=><Select size="small" value={drafts[r.id]?.riskLevel??v} onChange={x=>edit(r,{riskLevel:x})} options={['LOW','MEDIUM','HIGH'].map(x=>({value:x,label:x}))}/>},{title:'确认策略',dataIndex:'approvalPolicy',render:(v,r)=><Select size="small" value={drafts[r.id]?.approvalPolicy??v} onChange={x=>edit(r,{approvalPolicy:x})} options={['DIRECT','CONFIRM','APPROVAL'].map(x=>({value:x,label:x}))}/>},{title:'Value 上限(B)',dataIndex:'maxValueBytes',render:(v,r)=><InputNumber size="small" min={0} max={1048576} value={drafts[r.id]?.maxValueBytes??v} onChange={x=>edit(r,{maxValueBytes:x??0})}/>},{title:'操作',render:(_,r)=><Button size="small" type="primary" onClick={()=>save(r)}>保存</Button>}]
  return <Space direction="vertical" style={{width:'100%'}} size="large"><Card title="Command catalog" extra={<Tag color="geekblue">redis console 配置</Tag>}><p className="muted">配置命令启用状态、风险、审批策略和 Value 上限。Redis 数据类型由命令语义自动约束，无需重复配置。高风险命令必须使用审批策略。</p>{error&&<Alert type="error" showIcon message="命令配置加载失败" description={error} action={<Button size="small" onClick={load}>重试</Button>}/>}<Spin spinning={loading}>{!loading&&!error&&!rows.length?<Empty description="暂无可配置命令"/>:<Table rowKey="id" dataSource={rows} pagination={false} size="small" scroll={{x:900}} columns={columns}/>}</Spin></Card></Space>
}
