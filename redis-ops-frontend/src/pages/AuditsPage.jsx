import { useEffect,useState } from 'react'
import { Button,Input,message,Select,Space,Table,Tag } from 'antd'
import { ReloadOutlined,SearchOutlined } from '@ant-design/icons'
import { api } from '../api.js'

const resourceTypes=['REDIS_CLUSTER','APPLICATION','REGION','IDC']

export default function AuditsPage(){
  const [rows,setRows]=useState([]),[loading,setLoading]=useState(false),[filters,setFilters]=useState({})
  const load=async(query=filters)=>{setLoading(true);try{setRows(await api.audits({...query,limit:200}))}catch(e){message.error(e.message)}finally{setLoading(false)}}
  useEffect(()=>{load()},[])
  const columns=[
    {title:'时间',dataIndex:'createdAt',width:190},
    {title:'操作人',dataIndex:'operator',width:140},
    {title:'动作',dataIndex:'action',width:230},
    {title:'资源类型',dataIndex:'resourceType',width:150},
    {title:'资源 ID',dataIndex:'resourceId',width:120},
    {title:'结果',dataIndex:'result',width:100,render:value=><Tag color={value==='SUCCESS'?'green':'red'}>{value}</Tag>}
  ]
  return <><div className="toolbar"><Space wrap>
    <Input placeholder="操作人" value={filters.operator} onChange={e=>setFilters({...filters,operator:e.target.value})}/>
    <Select allowClear placeholder="资源类型" style={{width:180}} value={filters.resourceType} onChange={value=>setFilters({...filters,resourceType:value})} options={resourceTypes.map(value=>({value}))}/>
    <Input placeholder="资源 ID" value={filters.resourceId} onChange={e=>setFilters({...filters,resourceId:e.target.value})}/>
    <Button icon={<SearchOutlined/>} onClick={load}>查询</Button>
    <Button icon={<ReloadOutlined/>} onClick={()=>{setFilters({});load({})}}>刷新</Button>
  </Space></div><Table rowKey="id" loading={loading} dataSource={rows} columns={columns} scroll={{x:930}} pagination={{pageSize:20,showSizeChanger:false}}/></>
}
