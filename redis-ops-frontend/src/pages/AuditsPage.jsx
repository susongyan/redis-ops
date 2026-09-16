import { actorLabel } from '../actor.js'
import { useEffect,useState } from 'react'
import { Button,Input,message,Select,Space,Table,Tag,Modal } from 'antd'
import {auditDetails,auditValue} from '../auditDetails.js'
import { ReloadOutlined,SearchOutlined } from '@ant-design/icons'
import { api } from '../api.js'

const resourceTypes=['REDIS_CLUSTER','APPLICATION','REGION','IDC']

export default function AuditsPage(){
  const [rows,setRows]=useState([]),[loading,setLoading]=useState(false),[filters,setFilters]=useState({})
  const [detail,setDetail]=useState(null)
  const load=async(query=filters)=>{setLoading(true);try{setRows(await api.audits({...query,limit:200}))}catch(e){message.error(e.message)}finally{setLoading(false)}}
  useEffect(()=>{load()},[])
  const columns=[
    {title:'时间',dataIndex:'createdAt',width:190},
    {title:'操作人',dataIndex:'operator',render:(value,row)=>actorLabel(value,row.operatorSnapshot),width:140},
    {title:'操作内容',dataIndex:'detailsJson',width:300,render:value=>{
      const parsed=auditDetails(value)
      return parsed?<Space direction="vertical" style={{maxWidth:'100%',overflowWrap:'anywhere'}}>
        <span>{parsed.summary}</span><Button type="link" size="small" onClick={()=>setDetail(parsed)}>查看变更</Button>
      </Space>:<span className="muted">未记录详情</span>
    }},
    {title:'动作',dataIndex:'action',width:230},
    {title:'资源类型',dataIndex:'resourceType',width:150},
    {title:'资源 ID',dataIndex:'resourceId',width:120},
    {title:'结果',dataIndex:'result',width:100,render:value=><Tag color={value==='SUCCESS'?'green':'red'}>{value}</Tag>}
  ]
  return <><div className="toolbar"><Space wrap>
    <Input placeholder="账号、显示名称或用户 ID" value={filters.operator} onChange={e=>setFilters({...filters,operator:e.target.value})}/>
    <Select allowClear placeholder="资源类型" style={{width:180}} value={filters.resourceType} onChange={value=>setFilters({...filters,resourceType:value})} options={resourceTypes.map(value=>({value}))}/>
    <Input placeholder="资源 ID" value={filters.resourceId} onChange={e=>setFilters({...filters,resourceId:e.target.value})}/>
    <Button icon={<SearchOutlined/>} onClick={()=>load()}>查询</Button>
    <Button icon={<ReloadOutlined/>} onClick={()=>{setFilters({});load({})}}>刷新</Button>
  </Space></div><Table rowKey="id" loading={loading} dataSource={rows} columns={columns} scroll={{x:1230}} pagination={{pageSize:20,showSizeChanger:false}}/>
    <Modal title="操作详情" open={!!detail} onCancel={()=>setDetail(null)} footer={<Button onClick={()=>setDetail(null)}>关闭</Button>} width={860}>
      <p style={{overflowWrap:'anywhere'}}>{detail?.summary}</p>
      {detail?.reason&&<p style={{overflowWrap:'anywhere'}}>变更原因：{detail.reason}</p>}
      <Table size="small" dataSource={detail?.changes||[]} rowKey="field" pagination={false} scroll={{x:550}} locale={{emptyText:'无可展示的字段差异'}} columns={[
        {title:'字段',dataIndex:'field',width:150},
        ...['before','after'].map(key=>({title:key==='before'?'操作前':'操作后',dataIndex:key,render:value=><span style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere'}}>{auditValue(value)}</span>}))
      ]}/>
    </Modal></>
}
