import {useEffect,useRef,useState} from 'react'
import {Badge,Button,Descriptions,Drawer,Form,Input,InputNumber,Modal,Progress,Select,Space,Table,Tag,message} from 'antd'
import {PlusOutlined,PlayCircleOutlined,StopOutlined} from '@ant-design/icons'
import {api} from '../api'
import {exportableRiskScan,loadRiskScanExport,riskScanCsv,riskScanFileName} from '../riskScanExport.js'
import DeleteExecutionRecordButton from '../components/DeleteExecutionRecordButton.jsx'
import ScanReadNotice from '../components/ScanReadNotice.jsx'
const statusColor={CREATED:'default',RUNNING:'processing',COMPLETED:'success',FAILED:'error',CANCELLED:'default',BLOCKED:'warning'}
const bytes=n=>n==null?'-':n>=1073741824?`${(n/1073741824).toFixed(1)} GiB`:n>=1048576?`${(n/1048576).toFixed(1)} MiB`:n>=1024?`${(n/1024).toFixed(1)} KiB`:`${n} B`
const byteUnits=[{value:1,label:'B'},{value:1024,label:'KiB'},{value:1048576,label:'MiB'},{value:1073741824,label:'GiB'}]
export default function RiskScansPage(){
  const [rows,setRows]=useState([]),[clusters,setClusters]=useState([]),[open,setOpen]=useState(false),[detail,setDetail]=useState(),[findings,setFindings]=useState({items:[]}),[groups,setGroups]=useState({items:[]}),[riskType,setRiskType]=useState(),[riskFlash,setRiskFlash]=useState(0),[exporting,setExporting]=useState(false),[form]=Form.useForm()
  const findingCountRef=useRef(0)
  const load=async()=>{const [tasks,assets]=await Promise.allSettled([api.riskScanTasks(),api.clusters({size:200})]);if(tasks.status==='fulfilled')setRows(tasks.value);if(assets.status==='fulfilled')setClusters(assets.value.items||[])}
  useEffect(()=>{load();const timer=setInterval(load,5000);return()=>clearInterval(timer)},[])
  // 新 run（noTtlTotal 非空）的无 TTL 视图是模式聚合表；旧 run 保持逐 key 明细
  const noTtlGrouped=t=>t?.latestRun?.noTtlTotal!=null
  const loadResults=async(t,p=1,s=20,type)=>{
    if(type==='NO_TTL'&&noTtlGrouped(t))setGroups(await api.riskNoTtlGroups(t.task.id,p,s))
    else setFindings(await api.riskFindings(t.task.id,p,s,type))
  }
  const show=async id=>{try{const task=await api.riskScanTask(id);setDetail(task);findingCountRef.current=task.latestRun?.findingCount??0;await loadResults(task,1,20,riskType)}catch(e){message.error(e.message)}}
  useEffect(()=>{
    const id=detail?.task?.id
    if(!id || detail.task.status !== 'RUNNING')return undefined
    const refresh=async()=>{
      try{
        const task=await api.riskScanTask(id)
        setDetail(task)
        setRiskFlash(value=>value+1)
        // 与校验弹窗同款：结果列表仅在发现数变化时刷新，轮询加密到 2s 也不放大请求量
        const count=task.latestRun?.findingCount??0
        if(count!==findingCountRef.current){
          findingCountRef.current=count
          await loadResults(task,1,20,riskType)
        }
      }catch(e){/* 页面已打开时，单次刷新失败不打断后续轮询 */}
    }
    const timer=setInterval(refresh,2000)
    return()=>clearInterval(timer)
  },[detail?.task?.id,detail?.task?.status,riskType])
  const create=()=>form.validateFields().then(({largeKeyValue,largeKeyUnit,...v})=>api.createRiskScanTask({...v,largeKeyThresholdBytes:largeKeyValue*largeKeyUnit})).then(()=>{setOpen(false);load()}).catch(e=>message.error(e.message))
  const start=row=>api.startRiskScanTask(row.id,row.version).then(load).catch(e=>message.error(e.message))
  const cancel=row=>api.cancelRiskScanTask(row.id,row.version).then(load).catch(e=>message.error(e.message))
  const analyze=async()=>{try{await api.analyze({type:'RISK_SCAN',resourceType:'RISK_SCAN_TASK',resourceId:String(detail.task.id),facts:{status:detail.task.status,plannedKeys:String(run?.plannedKeys??''),scannedKeys:String(run?.scannedKeys??''),findingCount:String(run?.findingCount??''),largeKeyCount:String(summary?.largeKeyCount??0),noTtlCount:String(summary?.noTtlCount??0)},evidence:[{reference:`risk-scan:${detail.task.id}`,kind:'SCAN_RESULT',summary:`扫描状态 ${detail.task.status}，发现 ${run?.findingCount??0} 项风险`}]});message.success('分析已完成');window.location.hash='/analysisRuns'}catch(e){message.error(e.message)}}
  const run=detail?.latestRun, summary=detail?.summary
  const exportCsv=async()=>{
    const id=detail.task.id
    setExporting(true)
    try{
      const loaded=await loadRiskScanExport(api,id)
      const name=clusters.find(c=>c.id===loaded.detail.task.clusterId)?.name||''
      const csv=riskScanCsv(loaded.detail,loaded.findings,loaded.groups,name)
      const url=URL.createObjectURL(new Blob([csv],{type:'text/csv;charset=utf-8'}))
      const link=document.createElement('a')
      link.href=url;link.download=riskScanFileName(loaded.detail,name)
      document.body.appendChild(link);link.click();link.remove()
      setTimeout(()=>URL.revokeObjectURL(url),1000)
      message.success(`已导出 ${loaded.findings.length} 条明细${loaded.groups.length?`、${loaded.groups.length} 个无 TTL 聚合`:''}`)
    }catch(e){message.error(e.message)}
    finally{setExporting(false)}
  }
  // 进度分母是启动时的 DBSIZE，分子必须用 checkpoint 的 SCAN 遍历数（同口径）；
  // run.scannedKeys 只是 Pattern 命中数，大库+窄 Pattern 时几乎不增长，会一直显示 0%
  const traversed=(detail?.checkpoints||[]).reduce((sum,c)=>sum+(c.scannedKeys||0),0)
  const progress=run?.plannedKeys?Math.min(100,traversed/run.plannedKeys*100):0
  const fmt=n=>n==null?'-':Number(n).toLocaleString('zh-CN')
  // 校验弹窗同款口径：速率=遍历数/耗时，剩余=(计划-已遍历)/速率；结束后耗时取起止时间差
  const running=detail?.task?.status==='RUNNING'
  const startMs=run?.startedAt?new Date(run.startedAt).getTime():null
  const endMs=run?.completedAt?new Date(run.completedAt).getTime():null
  const elapsed=startMs?((endMs??Date.now())-startMs)/1000:0
  const rate=running&&elapsed>3&&traversed>0?traversed/elapsed:0
  const remaining=run?.plannedKeys&&rate>0&&traversed<run.plannedKeys?(run.plannedKeys-traversed)/rate:null
  const duration=v=>v==null?'—':v>=3600?`~${Math.round(v/3600)} 小时`:v>=60?`~${Math.ceil(v/60)} 分钟`:`${Math.ceil(v)} 秒`
  return <><ScanReadNotice /><Space style={{marginBottom:16}}><Button type="primary" icon={<PlusOutlined/>} onClick={()=>{form.resetFields();form.setFieldsValue({includePattern:'*',largeKeyValue:64,largeKeyUnit:1048576,scanRatePerSecond:1000,maxFindings:1000,groupDelimiter:':',groupSegments:2});setOpen(true)}}>新建风险扫描</Button><span className="muted">只读扫描，不读取完整 Value。</span></Space>
    <Table scroll={{x:900}} rowKey="id" dataSource={rows} columns={[{title:'任务编号',dataIndex:'taskNo',render:(v,row)=><Button type="link" onClick={()=>show(row.id)}>{v}</Button>},{title:'集群',dataIndex:'clusterId',render:(v,row)=>{const c=clusters.find(x=>x.id===v);return c?`${c.name}（${c.environment||'-'}/${c.mode}）`:`#${v}`}},{title:'Pattern',dataIndex:'includePattern'},{title:'阈值',dataIndex:'largeKeyThresholdBytes',render:bytes},{title:'状态',dataIndex:'status',render:v=><Tag color={statusColor[v]||'default'}>{v}</Tag>},{title:'操作',fixed:'right',width:180, align: 'center', render:(_,row)=><Space wrap><Button size="small" icon={<PlayCircleOutlined/>} disabled={row.status==='RUNNING'} onClick={e=>{e.stopPropagation();start(row)}}>启动</Button>{row.status==='RUNNING'&&<Button size="small" danger icon={<StopOutlined/>} onClick={e=>{e.stopPropagation();cancel(row)}}>取消</Button>}<DeleteExecutionRecordButton type="RISK_SCAN" record={row} onDeleted={id=>{if(detail?.task?.id===id)setDetail(null);load()}}/></Space>}]}/>
    <Modal open={open} title="新建风险扫描" onOk={create} onCancel={()=>setOpen(false)}><Form form={form} layout="vertical"><Form.Item name="clusterId" label="集群" rules={[{required:true,message:'请选择集群'}]}><Select showSearch optionFilterProp="label" options={clusters.map(c=>({value:c.id,label:`${c.name} · ${c.mode} · ${c.environment}`}))}/></Form.Item><Form.Item name="databaseNo" label="DB"><InputNumber min={0} style={{width:'100%'}}/></Form.Item><Form.Item name="includePattern" label="Key Pattern"><Input/></Form.Item><Form.Item label="大 Key 阈值"><Space.Compact block><Form.Item name="largeKeyValue" noStyle><InputNumber min={1} style={{width:'100%'}}/></Form.Item><Form.Item name="largeKeyUnit" noStyle><Select style={{width:96}} options={byteUnits}/></Form.Item></Space.Compact></Form.Item><Form.Item name="scanRatePerSecond" label="扫描速率（Key/s）"><InputNumber min={1} style={{width:'100%'}}/></Form.Item><Form.Item name="maxFindings" label="最多明细数（大 Key）"><InputNumber min={1} style={{width:'100%'}}/></Form.Item><Form.Item name="groupDelimiter" label="分组分隔符（无 TTL 聚合）"><Input maxLength={8}/></Form.Item><Form.Item name="groupSegments" label="分组段数（无 TTL 聚合）"><InputNumber min={1} max={8} style={{width:'100%'}}/></Form.Item></Form></Modal>
    <Drawer open={!!detail} width="min(900px, 100vw)" title="风险扫描结果" extra={detail&&<Space><Button type="primary" onClick={analyze}>辅助分析当前结果</Button><Button loading={exporting} disabled={!exportableRiskScan(detail)} onClick={exportCsv}>导出 CSV</Button></Space>} onClose={()=>setDetail(null)}>{detail&&<><ScanReadNotice checkpoints={detail.checkpoints || []} /><h3 className="sync-live-heading">扫描进度{riskFlash>0&&<span key={riskFlash} className="sync-update-pulse" aria-label="风险扫描进度已更新" />}</h3><div key={`risk-progress-${riskFlash}`} className={`sync-live-panel ${riskFlash>0?'sync-live-panel-updated':''}`}><Descriptions bordered size="small" column={2}><Descriptions.Item label="状态">{detail.task.status==='RUNNING'?<Badge status="processing" text={<Tag color={statusColor[detail.task.status]}>{detail.task.status}</Tag>} />:<Tag color={statusColor[detail.task.status]||'default'}>{detail.task.status}</Tag>}</Descriptions.Item><Descriptions.Item label="Pattern"><code>{detail.task.includePattern}</code></Descriptions.Item><Descriptions.Item label="大 Key 阈值">{bytes(detail.task.largeKeyThresholdBytes)}</Descriptions.Item><Descriptions.Item label="扫描速率">{fmt(detail.task.scanRatePerSecond)} key/s</Descriptions.Item><Descriptions.Item label="最多明细数（大 Key）">{fmt(detail.task.maxFindings)}</Descriptions.Item><Descriptions.Item label="分组分隔符"><code>{detail.task.groupDelimiter}</code></Descriptions.Item><Descriptions.Item label="分组段数">{detail.task.groupSegments}</Descriptions.Item><Descriptions.Item label="DB">{detail.task.databaseNo}</Descriptions.Item><Descriptions.Item label="总 Key（启动时估算）">{run?.plannedKeys?fmt(run.plannedKeys):'采集中'}</Descriptions.Item><Descriptions.Item label="已遍历">{fmt(traversed)}</Descriptions.Item><Descriptions.Item label="Pattern 命中">{fmt(run?.scannedKeys)}</Descriptions.Item><Descriptions.Item label="已存明细">{fmt(run?.findingCount)}</Descriptions.Item><Descriptions.Item label="无 TTL"><b>{fmt(summary?.noTtlCount??0)}</b>（{((summary?.noTtlRatio||0)*100).toFixed(1)}%，{run?.noTtlTotal!=null?'全库精确':'已扫样本'}）</Descriptions.Item><Descriptions.Item label="大 Key"><b>{fmt(summary?.largeKeyCount??0)}</b></Descriptions.Item></Descriptions>{run?.plannedKeys?<div style={{marginTop:12}}><Progress percent={progress} size="small" status={running?'active':undefined} format={p=>`${p.toFixed(1)}%`} /><div style={{color:'rgba(0,0,0,0.45)',fontSize:12,marginTop:2}}>{running?`速率 ${rate>0?rate.toFixed(1):'—'} key/s · 预计剩余 ${duration(remaining)}`:`总耗时 ${duration(elapsed)}`}</div></div>:null}</div><Space style={{marginBottom:12}}><span>结果筛选：</span><Button.Group><Button type={!riskType?'primary':'default'} onClick={()=>{setRiskType(undefined);loadResults(detail,1,20,undefined)}}>全部</Button><Button type={riskType==='LARGE_KEY'?'primary':'default'} onClick={()=>{setRiskType('LARGE_KEY');loadResults(detail,1,20,'LARGE_KEY')}}>大 Key</Button><Button type={riskType==='NO_TTL'?'primary':'default'} onClick={()=>{setRiskType('NO_TTL');loadResults(detail,1,20,'NO_TTL')}}>无 TTL</Button></Button.Group>{riskType==='NO_TTL'&&noTtlGrouped(detail)&&<span className="muted">按模式聚合（前 {detail.task.groupSegments} 段，分隔符 “{detail.task.groupDelimiter}”）</span>}</Space>{riskType==='NO_TTL'&&noTtlGrouped(detail)?<Table scroll={{x:900}} rowKey="groupText" dataSource={groups.items} pagination={{current:groups.page,pageSize:groups.size,total:groups.total,onChange:(p,s)=>api.riskNoTtlGroups(detail.task.id,p,s).then(setGroups)}} columns={[{title:'模式',dataIndex:'groupText',render:v=><code>{v}</code>},{title:'数量',dataIndex:'hitCount',render:fmt},{title:'占比',render:(_,g)=>run?.noTtlTotal?`${(g.hitCount/run.noTtlTotal*100).toFixed(1)}%`:'-'},{title:'样例 Key',dataIndex:'sampleKey'},{title:'最大内存',dataIndex:'maxMemoryBytes',render:bytes}]}/>:<Table scroll={{x:900}} rowKey="id" dataSource={findings.items} pagination={{current:findings.page,pageSize:findings.size,total:findings.total,onChange:(p,s)=>api.riskFindings(detail.task.id,p,s,riskType).then(setFindings)}} columns={[{title:'风险',dataIndex:'riskType'},{title:'等级',dataIndex:'riskLevel',render:v=><Tag color={v==='CRITICAL'?'error':v==='HIGH'?'warning':v==='MEDIUM'?'orange':'blue'}>{v}</Tag>},{title:'Key',dataIndex:'keyName',render:v=>v||'历史记录仅保留摘要'},{title:'类型',dataIndex:'redisType'},{title:'内存',dataIndex:'memoryBytes',render:bytes},{title:'TTL',dataIndex:'ttlSeconds'}]}/>}</>}</Drawer>
  </>
}
