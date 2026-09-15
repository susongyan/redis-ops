import { useEffect, useState } from 'react'
import { Alert, Button, Form, Input, Popconfirm, Select, Space, Table, message } from 'antd'
import { api } from '../api.js'
import { addBindings } from '../bindingBatch.js'

export default function ApplicationBindings({ side, id }) {
  const [bindings,setBindings]=useState([]),[options,setOptions]=useState([]),[selected,setSelected]=useState([]),[busy,setBusy]=useState(false),[ready,setReady]=useState(false),[error,setError]=useState('')
  const [form]=Form.useForm()
  const applicationSide=side==='application'
  const targetId=row=>applicationSide?row.clusterId:row.applicationId
  const read=async()=>((applicationSide?await api.application(id):await api.cluster(id)).bindings)
  useEffect(()=>{
    let alive=true
    setReady(false);setSelected([]);setError('');setBindings([]);setOptions([]);form.resetFields()
    const load=async()=>{
      let targets=[]
      if(applicationSide){
        for(let page=1;;page++){
          const data=await api.clusters({page,size:200});targets.push(...data.items)
          if(targets.length>=data.total)break
          if(!data.items.length||page>=50)throw new Error('集群列表过大或分页不完整，请缩小资产范围后重试')
        }
      }else targets=await api.applications()
      const rows=await read()
      if(alive){setOptions(targets);setBindings(rows);setReady(true)}
    }
    load().catch(e=>{if(alive)setError(e.message)})
    return()=>{alive=false}
  },[side,id,form])
  const add=async()=>{
    if(!selected.length)return
    const values=await form.validateFields();setBusy(true);setError('')
    try{
      const existing=new Set((await read()).map(targetId))
      const {added,skipped,failed}=await addBindings(selected,existing,target=>api.bindApplication(applicationSide?id:target,applicationSide?target:id,values))
      setSelected(failed)
      if(failed.length)setError(`新增 ${added} 项，已存在 ${skipped} 项，失败 ${failed.length} 项。成功项已保留；可重试选中的失败项。`)
      else message.success(`新增 ${added} 项${skipped?`，跳过 ${skipped} 项已有关系`:''}`)
      setBindings(await read())
    }catch(e){setError(e.message)}finally{setBusy(false)}
  }
  const remove=async row=>{
    setBusy(true);setError('')
    try{await api.unbindApplication(row.applicationId,row.clusterId);setBindings(await read());message.success('已解除关联，资产和 Redis 数据未删除')}
    catch(e){setError(e.message)}finally{setBusy(false)}
  }
  const bound=new Set(bindings.map(targetId))
  const label=target=>applicationSide?`${target.name} (${target.environment})`:`${target.name} · ${target.code}`
  return <div>
    <p className="muted">支持多对多关联。多选新增逐项保存，已有关系不会被替换；下方客户端信息应用于本次所有新增关系。</p>
    {error&&<Alert type="error" showIcon message={error} style={{marginBottom:12}}/>}
    <Form form={form} layout="vertical" initialValues={{poolConfig:'{}'}}>
      <Form.Item label={applicationSide?'新增关联集群（可多选）':'新增关联应用（可多选）'}>
        <Select aria-label={applicationSide?'新增关联集群':'新增关联应用'} mode="multiple" showSearch optionFilterProp="label" value={selected} onChange={setSelected} disabled={!ready||busy} style={{width:'100%'}} options={options.map(target=>({value:target.id,label:label(target)+(bound.has(target.id)?' · 已关联':''),disabled:bound.has(target.id)}))}/>
      </Form.Item>
      <Space wrap align="start"><Form.Item name="clientType" label="客户端类型"><Input disabled={busy} placeholder="Lettuce / Jedis / Redisson"/></Form.Item><Form.Item name="clientVersion" label="客户端版本"><Input disabled={busy}/></Form.Item></Space>
      <Form.Item name="poolConfig" label="连接池配置 JSON" rules={[{validator:(_,value)=>{try{if(value)JSON.parse(value);return Promise.resolve()}catch{return Promise.reject(new Error('请输入合法 JSON'))}}}]}><Input.TextArea disabled={busy} rows={2}/></Form.Item>
      <Button type="primary" onClick={add} loading={busy} disabled={!ready||!selected.length}>新增关联（{selected.length}）</Button>
    </Form>
    <Table style={{marginTop:20}} size="small" rowKey={r=>`${r.applicationId}-${r.clusterId}`} dataSource={bindings} pagination={{pageSize:10,showSizeChanger:false}} scroll={{x:600}} columns={[
      {title:applicationSide?'关联集群':'关联应用',render:(_,row)=>{const target=options.find(o=>o.id===targetId(row));return target?label(target):`ID ${targetId(row)}`}},
      ...(!applicationSide?[{title:'负责人',render:(_,row)=>options.find(o=>o.id===row.applicationId)?.owner||'—'}]:[]),
      {title:'客户端',dataIndex:'clientType'},{title:'版本',dataIndex:'clientVersion'},
      {title:'操作',render:(_,row)=><Popconfirm title="仅解除关联，不删除应用、集群或 Redis 数据，确认？" onConfirm={()=>remove(row)} disabled={busy}><Button danger disabled={busy}>解绑</Button></Popconfirm>}
    ]}/>
  </div>
}
