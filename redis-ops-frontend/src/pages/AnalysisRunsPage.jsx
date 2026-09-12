import {useEffect,useState} from 'react'
import {Card,Descriptions,Drawer,Table,Tag,message} from 'antd'
import {api} from '../api.js'

export default function AnalysisRunsPage(){
  const [rows,setRows]=useState([]);const [detail,setDetail]=useState(null)
  useEffect(()=>{api.analysisRuns().then(setRows).catch(e=>message.error(e.message))},[])
  return <>
    <Card title="AI 分析结果" extra="点击记录查看完整判断依据">
      <Table scroll={{x:1000}} rowKey={row=>row.id||row.result.requestId} dataSource={rows} onRow={row=>({onClick:()=>setDetail(row)})} columns={[
        {title:'时间',render:(_,r)=>new Date(r.result.createdAt).toLocaleString()},
        {title:'场景',render:(_,r)=>r.request.type},
        {title:'资源',render:(_,r)=>`${r.request.resourceType||'-'} #${r.request.resourceId||'-'}`},
        {title:'Provider',render:(_,r)=><Tag color={r.result.protocol==='RULE'?'default':'blue'}>{r.result.provider}</Tag>},
        {title:'分析结论',render:(_,r)=><span>{r.result.summary}</span>},
        {title:'置信度',render:(_,r)=>`${Math.round(r.result.confidence*100)}%`},
        {title:'下一步建议',render:(_,r)=>r.result.recommendations?.map(x=>x.action).join('、')||'-'}
      ]}/>
    </Card>
    <Drawer width="min(680px, 100vw)" title="分析详情" open={!!detail} onClose={()=>setDetail(null)}>
      {detail&&<>
        <Descriptions bordered size="small" column={1}>
          <Descriptions.Item label="场景">{detail.request.type}</Descriptions.Item>
          <Descriptions.Item label="资源">{detail.request.resourceType||'-'} #{detail.request.resourceId||'-'}</Descriptions.Item>
          <Descriptions.Item label="分析结论">{detail.result.summary}</Descriptions.Item>
          <Descriptions.Item label="置信度">{Math.round(detail.result.confidence*100)}%</Descriptions.Item>
          <Descriptions.Item label="分析引擎">{detail.result.provider}（{detail.result.protocol}）</Descriptions.Item>
        </Descriptions>
        <h3>建议动作</h3>
        {detail.result.recommendations?.map((item,index)=><div key={index} style={{marginBottom:12}}><Tag color="orange">仅供参考，需人工确认</Tag><b style={{marginLeft:8}}>{item.action}</b><div className="muted" style={{marginTop:4}}>{item.reason}</div></div>)}
        <h3>判断依据</h3>
        {detail.result.evidence?.length?<Table size="small" rowKey={(row,index)=>`${row.reference}-${index}`} pagination={false} dataSource={detail.result.evidence} columns={[{title:'来源',dataIndex:'reference'},{title:'类型',dataIndex:'kind'},{title:'摘要',dataIndex:'summary'}]}/>:<span className="muted">暂无结构化证据</span>}
      </>}
    </Drawer>
  </>
}
