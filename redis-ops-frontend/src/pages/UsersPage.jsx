import { useEffect, useState } from 'react'
import { Button, Card, Form, Input, Modal, Select, Space, Table, Tag, message } from 'antd'
import { request } from '../api.js'
import { randomUuid } from '../uuid.js'

export default function UsersPage(){
  const [data,setData]=useState({items:[],total:0}),[page,setPage]=useState(1),[edit,setEdit]=useState(null),[busy,setBusy]=useState(false)
  const [form]=Form.useForm()
  const refresh=()=>request(`/api/v1/users?page=${page}&size=20`).then(setData).catch(e=>message.error(e.message))
  useEffect(()=>{refresh()},[page])
  const open=(mode,account)=>{form.resetFields();form.setFieldsValue(account?.user||{role:'OPERATOR',status:'ACTIVE'});setEdit({mode,account,key:randomUuid()})}
  const save=async()=>{
    const values=await form.validateFields();setBusy(true)
    try{
      const id=edit.account?.user.id
      await request(edit.mode==='create'?'/api/v1/users':`/api/v1/users/${id}${edit.mode==='reset'?'/reset-password':''}`,{
        method:edit.mode==='edit'?'PATCH':'POST',headers:{'Idempotency-Key':edit.key,...(id?{'If-Match':String(edit.account.user.version)}:{})},
        body:JSON.stringify(edit.mode==='reset'?{password:values.password}:values)
      });setEdit(null);form.resetFields();message.success('已保存');refresh()
    }catch(e){message.error(e.message)}finally{setBusy(false)}
  }
  return <Card title="用户管理" extra={<Button type="primary" onClick={()=>open('create')}>添加本地用户</Button>}>
    <p className="muted">管理员负责用户授权；运维用户拥有现有运维功能。企业认证为后续扩展，当前仅支持本地账号。</p>
    <Table rowKey={r=>r.user.id} dataSource={data.items} scroll={{x:780}} pagination={{current:page,total:data.total,pageSize:20,onChange:setPage,showSizeChanger:false}} columns={[
      {title:'账号 / 来源',render:(_,r)=><>{r.login}<br/><span className="muted">{r.source}</span></>},
      {title:'显示名称',render:(_,r)=>r.user.displayName},{title:'角色',render:(_,r)=>r.user.role==='ADMIN'?'管理员':'运维用户'},
      {title:'状态',render:(_,r)=><Tag>{r.user.status==='ACTIVE'?'启用':'禁用'}</Tag>},
      {title:'最近登录',render:(_,r)=>r.user.lastLoginAt?new Date(r.user.lastLoginAt).toLocaleString():'尚未登录'},
      {title:'操作',render:(_,r)=><Space><Button onClick={()=>open('edit',r)}>编辑授权</Button><Button onClick={()=>open('reset',r)}>重置密码</Button></Space>}
    ]}/>
    <Modal title={edit?.mode==='create'?'添加本地用户':edit?.mode==='reset'?'重置临时密码':'编辑用户授权'} open={!!edit} onCancel={()=>{setEdit(null);form.resetFields()}} onOk={save} confirmLoading={busy} destroyOnClose>
      <Form form={form} layout="vertical">
        {edit?.mode==='create'&&<Form.Item name="login" label="账号（3–64 位字母、数字、点、下划线、连字符）" rules={[{required:true}]}><Input maxLength={64} autoComplete="off"/></Form.Item>}
        {edit?.mode!=='reset'&&<><Form.Item name="displayName" label="显示名称" rules={[{required:true}]}><Input maxLength={128}/></Form.Item><Form.Item name="role" label="角色" rules={[{required:true}]}><Select options={[{value:'OPERATOR',label:'运维用户'},{value:'ADMIN',label:'管理员'}]}/></Form.Item></>}
        {edit?.mode==='edit'?<Form.Item name="status" label="状态"><Select options={[{value:'ACTIVE',label:'启用'},{value:'DISABLED',label:'禁用'}]}/></Form.Item>:<Form.Item name="password" label="临时密码（至少 6 个字符，最多 72 UTF-8 字节，首次登录需修改）" rules={[{required:true}]}><Input.Password maxLength={72} autoComplete="new-password"/></Form.Item>}
      </Form>
    </Modal>
  </Card>
}
