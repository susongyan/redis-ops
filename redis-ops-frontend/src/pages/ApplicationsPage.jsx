import { useEffect,useState } from 'react'
import { Button,Form,Input,message,Modal,Popconfirm,Select,Space,Table,Tag } from 'antd'
import { DeleteOutlined,EditOutlined,LinkOutlined,PlusOutlined } from '@ant-design/icons'
import { api } from '../api.js'
import ApplicationBindings from '../components/ApplicationBindings.jsx'

export default function ApplicationsPage(){
  const [rows,setRows]=useState([]),[editing,setEditing]=useState(null),[open,setOpen]=useState(false),[binding,setBinding]=useState(null);const[form]=Form.useForm()
  const load=async()=>{try{setRows(await api.applications())}catch(e){message.error(e.message)}};useEffect(()=>{load()},[])
  const edit=row=>{setEditing(row||null);form.resetFields();form.setFieldsValue(row||{status:'ACTIVE'});setOpen(true)}
  const save=async()=>{try{const v=await form.validateFields();editing?await api.updateApplication(editing.id,editing.version,v):await api.createApplication(v);message.success('保存成功');setOpen(false);load()}catch(e){if(!e.errorFields)message.error(e.message)}}
  const remove=async r=>{try{await api.deleteApplication(r.id,r.version);message.success('已删除');load()}catch(e){message.error(e.message)}}
  const showBind=row=>setBinding(row)
  const cols=[{title:'应用编码',dataIndex:'code'},{title:'应用名称',dataIndex:'name'},{title:'业务线',dataIndex:'businessLine'},{title:'负责人',dataIndex:'owner'},{title:'状态',dataIndex:'status',render:v=><Tag color={v==='ACTIVE'?'green':'default'}>{v}</Tag>},{title:'操作',render:(_,r)=><Space><Button icon={<LinkOutlined/>} onClick={()=>showBind(r)}>管理关联集群</Button><Button icon={<EditOutlined/>} onClick={()=>edit(r)}/><Popconfirm title="确认删除此应用？" onConfirm={()=>remove(r)}><Button danger icon={<DeleteOutlined/>}/></Popconfirm></Space>}]
  return <><div className="toolbar"><div/><Button type="primary" icon={<PlusOutlined/>} onClick={()=>edit(null)}>新增应用</Button></div><Table rowKey="id" dataSource={rows} columns={cols}/>
    <Modal title={editing?'编辑应用':'新增应用'} open={open} onCancel={()=>setOpen(false)} onOk={save}><Form form={form} layout="vertical"><Form.Item name="code" label="应用编码" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="name" label="应用名称" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="businessLine" label="业务线"><Input/></Form.Item><Form.Item name="owner" label="负责人"><Input/></Form.Item><Form.Item name="status" label="状态"><Select options={['ACTIVE','INACTIVE'].map(value=>({value}))}/></Form.Item></Form></Modal>
    <Modal title={`管理关联集群 · ${binding?.name||''}`} open={!!binding} onCancel={()=>setBinding(null)} footer={null} width="min(860px, 100vw)" destroyOnClose>{binding&&<ApplicationBindings key={binding.id} side="application" id={binding.id}/>}</Modal></>
}
