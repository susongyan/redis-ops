import {useEffect,useMemo,useState} from 'react'
import {Alert,Card,Col,Progress,Row,Space,Table,Tag,Tooltip,message} from 'antd'
import {InfoCircleOutlined} from '@ant-design/icons'
import {api} from '../api'

const metricNames={
  redis_ops_collector_up:['可用性','up'],
  redis_ops_redis_collector_nodes:['采集节点','count'],
  redis_ops_redis_used_memory_bytes:['已用内存','bytes'],
  redis_ops_redis_max_memory_bytes:['最大可用内存','bytes'],
  redis_ops_redis_connected_clients:['连接数','count'],
  redis_ops_redis_ops_per_second:['Ops/s','rate'],
  redis_ops_redis_keyspace_hits:['命中数','count'],
  redis_ops_redis_keyspace_misses:['未命中数','count'],
  redis_ops_redis_command_calls:['命令调用数','count'],
  redis_ops_redis_command_usec:['命令耗时（μs）','count'],
  redis_ops_redis_replication_backlog_bytes:['复制 backlog','bytes'],
  redis_ops_redis_slowlog_length:['Slowlog 数量','count']
}
const parseMetrics=text=>{
  const result={}
  for(const line of text.split('\n')){
    const match=line.match(/^(redis_ops_[a-z0-9_]+)\{([^}]*)\}\s+([-+0-9.eE]+)$/)
    if(!match)continue
    const labels={}
    for(const pair of match[2].matchAll(/([a-zA-Z_][a-zA-Z0-9_]*)="([^"]*)"/g))labels[pair[1]]=pair[2]
    const clusterId=labels.cluster_id
    if(!clusterId||!metricNames[match[1]])continue
    result[clusterId]??={}
    result[clusterId][match[1]]=Number(match[3])
  }
  return result
}
const format=(value,type)=>{
  if(value==null)return '-'
  if(type==='bytes')return value>=1024**3?`${(value/1024**3).toFixed(1)} GiB`:value>=1024**2?`${(value/1024**2).toFixed(1)} MiB`:`${Math.round(value/1024)} KiB`
  if(type==='rate')return value.toLocaleString(undefined,{maximumFractionDigits:1})
  return value.toLocaleString(undefined,{maximumFractionDigits:0})
}
function BarChart({title,rows,field,type,maxField}){
  const max=Math.max(...rows.map(row=>row[field]||0),1)
  return <Card title={title} size="small"><Space direction="vertical" style={{width:'100%'}} size={10}>{rows.map(row=>{const denominator=maxField?row[maxField]||0:max;const ratio=maxField?(row[field]||0)/Math.max(denominator,1):(row[field]||0)/max;return <div className="metric-bar-row" key={row.id}><span className="metric-bar-label" title={row.name}>{row.name}</span><div className="metric-bar-track"><div className="metric-bar-fill" style={{width:`${Math.max(2,ratio*100)}%`}}/></div><span className="metric-bar-value">{maxField?`${format(row[field],type)} / ${format(row[maxField],type)}`:format(row[field],type)}</span></div>})}</Space></Card>
}
export default function MetricsPage(){
  const [clusters,setClusters]=useState([]),[metrics,setMetrics]=useState({}),[nodes,setNodes]=useState({}),[loading,setLoading]=useState(true),[error,setError]=useState(''),[flash,setFlash]=useState(0)
  const load=async()=>{try{const [assets,text]=await Promise.all([api.clusters({size:200}),api.collectorMetrics()]);const next=parseMetrics(text);const items=assets.items||[];const nodeEntries=await Promise.all(items.map(async cluster=>[String(cluster.id),await api.collectorNodes(cluster.id).catch(()=>[])]));setClusters(items);setNodes(Object.fromEntries(nodeEntries));setMetrics(previous=>{if(JSON.stringify(previous)!==JSON.stringify(next))setFlash(value=>value+1);return next});setError('')}catch(e){setError(e.message)}finally{setLoading(false)}}
  useEffect(()=>{load();const timer=setInterval(load,15000);return()=>clearInterval(timer)},[])
  const rows=useMemo(()=>clusters.map(cluster=>({key:cluster.id,id:String(cluster.id),name:cluster.name,mode:cluster.mode,environment:cluster.environment,...(metrics[String(cluster.id)]||{})})),[clusters,metrics])
  const columns=[{title:'集群',dataIndex:'name',width:220},{title:'模式',dataIndex:'mode',width:110},{title:'状态',render:(_,row)=>row.redis_ops_collector_up==null?<Tag>暂无数据</Tag>:<Tag color={row.redis_ops_collector_up?'green':'red'}>{row.redis_ops_collector_up?'UP':'DOWN'}</Tag>},{title:'已用内存',render:(_,row)=>format(row.redis_ops_redis_used_memory_bytes,'bytes')},{title:'连接数',render:(_,row)=>format(row.redis_ops_redis_connected_clients,'count')},{title:'Ops/s',render:(_,row)=>format(row.redis_ops_redis_ops_per_second,'rate')},{title:'Slowlog',render:(_,row)=>format(row.redis_ops_redis_slowlog_length,'count')}]
  return <Space direction="vertical" size={18} style={{width:'100%'}}><div className="toolbar"><div><h2 style={{margin:0}}>监控指标</h2><span className="muted">Platform Collector · 每 15 秒刷新 · 当前快照对比</span></div>{flash>0&&<span key={flash} className="sync-update-pulse" aria-label="监控指标已更新"/>}</div>{error&&<Alert type="warning" showIcon message="Collector 指标暂不可用" description={error}/>}<Table loading={loading} rowKey="id" dataSource={rows} columns={columns} scroll={{x:1000}} pagination={false}/><Row gutter={[16,16]}><Col xs={24} lg={8}><BarChart title="内存使用 / 最大可用" rows={rows} field="redis_ops_redis_used_memory_bytes" maxField="redis_ops_redis_max_memory_bytes" type="bytes"/></Col><Col xs={24} lg={8}><BarChart title="Ops/s 对比" rows={rows} field="redis_ops_redis_ops_per_second" type="rate"/></Col><Col xs={24} lg={8}><BarChart title="连接数对比" rows={rows} field="redis_ops_redis_connected_clients" type="count"/></Col></Row><Row gutter={[16,16]}>{rows.map(row=><Col xs={24} lg={12} key={row.id}><Card title={<Space>{row.name}<Tag>{row.mode}</Tag><Tooltip title="指标来自 /actuator/prometheus，标签只包含集群 ID；节点明细单独展示"><InfoCircleOutlined className="metric-tip-icon"/></Tooltip></Space>} size="small"><Row gutter={[12,12]}>{Object.entries(metricNames).filter(([name])=>name!=='redis_ops_collector_up').map(([name,[label,type]])=><Col span={8} key={name}><div className="muted">{label}</div><strong>{format(row[name],type)}</strong></Col>)}</Row>{row.redis_ops_redis_keyspace_hits!=null&&row.redis_ops_redis_keyspace_misses!=null&&<div style={{marginTop:16}}><div className="muted">Key 命中率</div><Progress percent={Math.round(row.redis_ops_redis_keyspace_hits/(row.redis_ops_redis_keyspace_hits+row.redis_ops_redis_keyspace_misses||1)*1000)/10} size="small" status="active"/> </div>}<Table size="small" style={{marginTop:16}} rowKey="nodeId" pagination={false} dataSource={nodes[row.id]||[]} columns={[{title:'节点',dataIndex:'nodeId',ellipsis:true},{title:'角色',dataIndex:'role'},{title:'连接数',dataIndex:'connectedClients',render:v=>format(v,'count')},{title:'内存',dataIndex:'usedMemoryBytes',render:v=>format(v,'bytes')},{title:'Ops/s',dataIndex:'opsPerSecond',render:v=>format(v,'rate')}]} /></Card></Col>)}</Row></Space>
}
