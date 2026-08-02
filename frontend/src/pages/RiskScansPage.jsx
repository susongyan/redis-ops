import {useEffect,useState} from 'react'
import {Button,Drawer,Form,Input,InputNumber,Modal,Select,Space,Table,Tag,message} from 'antd'
import {PlusOutlined,PlayCircleOutlined,StopOutlined} from '@ant-design/icons'
import {api} from '../api'
const statusColor={CREATED:'default',RUNNING:'processing',COMPLETED:'success',FAILED:'error',CANCELLED:'default',BLOCKED:'warning'}
const bytes=n=>n==null?'-':n>=1024*1024?`${(n/1024/1024).toFixed(1)} MiB`:`${n} B`
export default function RiskScansPage(){
  const [rows,setRows]=useState([]),[clusters,setClusters]=useState([]),[open,setOpen]=useState(false),[detail,setDetail]=useState(),[findings,setFindings]=useState({items:[]}),[riskType,setRiskType]=useState(),[riskFlash,setRiskFlash]=useState(0),[form]=Form.useForm()
  const load=async()=>{const [tasks,assets]=await Promise.allSettled([api.riskScanTasks(),api.clusters({size:200})]);if(tasks.status==='fulfilled')setRows(tasks.value);if(assets.status==='fulfilled')setClusters(assets.value.items||[])}
  useEffect(()=>{load();const timer=setInterval(load,5000);return()=>clearInterval(timer)},[])
  const show=async id=>{try{const [task,result]=await Promise.all([api.riskScanTask(id),api.riskFindings(id,1,20,riskType)]);setDetail(task);setFindings(result)}catch(e){message.error(e.message)}}
  useEffect(()=>{
    const id=detail?.task?.id
    if(!id || detail.task.status !== 'RUNNING')return undefined
    const refresh=async()=>{
      try{
        const [task,result]=await Promise.all([api.riskScanTask(id),api.riskFindings(id,1,20,riskType)])
        setDetail(task)
        setFindings(result)
        setRiskFlash(value=>value+1)
      }catch(e){/* 页面已打开时，单次刷新失败不打断后续轮询 */}
    }
    const timer=setInterval(refresh,5000)
    return()=>clearInterval(timer)
  },[detail?.task?.id,detail?.task?.status,riskType])
  const create=()=>form.validateFields().then(api.createRiskScanTask).then(()=>{setOpen(false);load()}).catch(e=>message.error(e.message))
  const start=row=>api.startRiskScanTask(row.id,row.version).then(load).catch(e=>message.error(e.message))
  const cancel=row=>api.cancelRiskScanTask(row.id,row.version).then(load).catch(e=>message.error(e.message))
  const run=detail?.latestRun, summary=detail?.summary, progress=run?.plannedKeys?Math.min(100,run.scannedKeys/run.plannedKeys*100):0
  return <><Space style={{marginBottom:16}}><Button type="primary" icon={<PlusOutlined/>} onClick={()=>{form.resetFields();form.setFieldsValue({includePattern:'*',largeKeyThresholdBytes:67108864,scanRatePerSecond:1000,maxFindings:1000});setOpen(true)}}>新建风险扫描</Button><span className="muted">只读扫描，不读取完整 Value。</span></Space>
    <Table rowKey="id" dataSource={rows} onRow={row=>({onClick:()=>show(row.id)})} columns={[{title:'任务编号',dataIndex:'taskNo'},{title:'集群',dataIndex:'clusterId'},{title:'Pattern',dataIndex:'includePattern'},{title:'阈值',dataIndex:'largeKeyThresholdBytes',render:bytes},{title:'状态',dataIndex:'status',render:v=><Tag color={statusColor[v]||'default'}>{v}</Tag>},{title:'操作',render:(_,row)=><Space><Button size="small" icon={<PlayCircleOutlined/>} disabled={row.status==='RUNNING'} onClick={e=>{e.stopPropagation();start(row)}}>启动</Button>{row.status==='RUNNING'&&<Button size="small" danger icon={<StopOutlined/>} onClick={e=>{e.stopPropagation();cancel(row)}}>取消</Button>}</Space>}]}/>
    <Modal open={open} title="新建风险扫描" onOk={create} onCancel={()=>setOpen(false)}><Form form={form} layout="vertical"><Form.Item name="clusterId" label="集群" rules={[{required:true,message:'请选择集群'}]}><Select showSearch optionFilterProp="label" options={clusters.map(c=>({value:c.id,label:`${c.name} · ${c.mode} · ${c.environment}`}))}/></Form.Item><Form.Item name="databaseNo" label="DB"><InputNumber min={0} style={{width:'100%'}}/></Form.Item><Form.Item name="includePattern" label="Key Pattern"><Input/></Form.Item><Form.Item name="largeKeyThresholdBytes" label="大 Key 阈值"><InputNumber min={1} style={{width:'100%'}}/></Form.Item><Form.Item name="scanRatePerSecond" label="扫描速率（Key/s）"><InputNumber min={1} style={{width:'100%'}}/></Form.Item><Form.Item name="maxFindings" label="最多结果数"><InputNumber min={1} style={{width:'100%'}}/></Form.Item></Form></Modal>
    <Drawer open={!!detail} width={900} title="风险扫描结果" onClose={()=>setDetail(null)}>{detail&&<><h3 className="sync-live-heading">扫描进度{riskFlash>0&&<span key={riskFlash} className="sync-update-pulse" aria-label="风险扫描进度已更新" />}</h3><div key={`risk-progress-${riskFlash}`} className={`sync-live-panel ${riskFlash>0?'sync-live-panel-updated':''}`}><p>状态：<Tag color={statusColor[detail.task.status]||'default'}>{detail.task.status}</Tag>　总 Key：{run?.plannedKeys||'采集中'}　已扫描：{run?.scannedKeys??'-'}　进度：{run?.plannedKeys?`${progress.toFixed(1)}%`:'-'}　发现：{run?.findingCount??'-'}</p><p>无 TTL：<b>{summary?.noTtlCount??0}</b>（{((summary?.noTtlRatio||0)*100).toFixed(1)}%）　大 Key：<b>{summary?.largeKeyCount??0}</b></p></div><Space style={{marginBottom:12}}><span>结果筛选：</span><Button.Group><Button type={!riskType?'primary':'default'} onClick={()=>{setRiskType(undefined);api.riskFindings(detail.task.id,1,20).then(setFindings)}}>全部</Button><Button type={riskType==='LARGE_KEY'?'primary':'default'} onClick={()=>{setRiskType('LARGE_KEY');api.riskFindings(detail.task.id,1,20,'LARGE_KEY').then(setFindings)}}>大 Key</Button><Button type={riskType==='NO_TTL'?'primary':'default'} onClick={()=>{setRiskType('NO_TTL');api.riskFindings(detail.task.id,1,20,'NO_TTL').then(setFindings)}}>无 TTL</Button></Button.Group></Space><Table rowKey="id" dataSource={findings.items} pagination={{current:findings.page,pageSize:findings.size,total:findings.total,onChange:(p,s)=>api.riskFindings(detail.task.id,p,s,riskType).then(setFindings)}} columns={[{title:'风险',dataIndex:'riskType'},{title:'等级',dataIndex:'riskLevel',render:v=><Tag color={v==='CRITICAL'?'error':v==='HIGH'?'warning':v==='MEDIUM'?'orange':'blue'}>{v}</Tag>},{title:'Key',dataIndex:'keyName',render:v=>v||'历史记录仅保留摘要'},{title:'类型',dataIndex:'redisType'},{title:'内存',dataIndex:'memoryBytes',render:bytes},{title:'TTL',dataIndex:'ttlSeconds'}]}/></>}</Drawer>
  </>
}
