import {useCallback,useEffect,useState} from 'react'
import {Button,Card,Form,Input,InputNumber,Modal,Select,Space,Table,Tag,message,Switch} from 'antd'
import {api} from '../api.js'

const statusColor={OPEN:'red',ACKNOWLEDGED:'gold',RESOLVED:'green'}
export default function AlertsPage(){
  const [events,setEvents]=useState({items:[],total:0,page:1,size:20})
  const [rules,setRules]=useState([]);const [channels,setChannels]=useState([]);const [deliveries,setDeliveries]=useState({items:[],total:0,page:1,size:10});const [status,setStatus]=useState();const [open,setOpen]=useState(false);const [channelOpen,setChannelOpen]=useState(false);const [editingChannel,setEditingChannel]=useState(null);const [form]=Form.useForm();const [channelForm]=Form.useForm()
  const load=useCallback(async()=>{try{const [e,r,c,d]=await Promise.all([api.alerts(status,events.page,events.size),api.alertRules(),api.notificationChannels(),api.notificationDeliveries(deliveries.page,deliveries.size)]);setEvents(e);setRules(r);setChannels(c);setDeliveries(d)}catch(e){message.error(e.message)}},[status,events.page,events.size,deliveries.page,deliveries.size])
  useEffect(()=>{load();const timer=setInterval(load,10000);return()=>clearInterval(timer)},[load])
  const action=async(fn)=>{try{await fn();message.success('操作已提交');load()}catch(e){message.error(e.message)}}
  return <Space direction="vertical" size="middle" style={{width:'100%'}}>
    <Card title="告警中心" extra={<Space><Button onClick={()=>{setEditingChannel(null);channelForm.resetFields();setChannelOpen(true)}}>Webhook 通道</Button><Select allowClear placeholder="全部状态" style={{width:130}} onChange={v=>{setStatus(v);setEvents(x=>({...x,page:1}))}} options={['OPEN','ACKNOWLEDGED','RESOLVED'].map(x=>({value:x,label:x}))}/><Button type="primary" onClick={()=>setOpen(true)}>新建规则</Button></Space>}>
      <Table rowKey="id" dataSource={events.items} pagination={{current:events.page,pageSize:events.size,total:events.total,onChange:p=>setEvents(x=>({...x,page:p}))}} columns={[
        {title:'告警',dataIndex:'title'}, {title:'级别',dataIndex:'severity',render:v=><Tag color={v==='P1'?'red':v==='P2'?'orange':'blue'}>{v}</Tag>},
        {title:'状态',dataIndex:'status',render:v=><Tag color={statusColor[v]}>{v}</Tag>},{title:'资源',render:(_,r)=>`${r.resourceType} #${r.resourceId}`},
        {title:'最近触发',dataIndex:'lastSeenAt',render:v=>v&&new Date(v).toLocaleString()},
        {title:'操作',render:(_,r)=><Space>{r.status==='OPEN'&&<Button size="small" onClick={()=>action(()=>api.acknowledgeAlert(r.id,r.version))}>确认</Button>}{r.status!=='RESOLVED'&&<Button size="small" onClick={()=>action(()=>api.resolveAlert(r.id,r.version))}>恢复</Button>}</Space>}
      ]}/>
    </Card>
    <Card title="告警规则"><Table rowKey="id" size="small" pagination={false} dataSource={rules} columns={[{title:'名称',dataIndex:'name'},{title:'类型',dataIndex:'ruleType'},{title:'阈值',dataIndex:'thresholdValue'},{title:'持续秒数',dataIndex:'durationSeconds'},{title:'级别',dataIndex:'severity'},{title:'启用',dataIndex:'enabled',render:(v,r)=><Switch size="small" checked={v} onChange={enabled=>action(()=>api.updateAlertRule(r.id,r.version,{name:r.name,ruleType:r.ruleType,severity:r.severity,enabled,thresholdValue:r.thresholdValue,durationSeconds:r.durationSeconds,channelId:r.channelId}))}/>} ]}/></Card>
    <Modal open={open} title="新建告警规则" onCancel={()=>setOpen(false)} onOk={()=>form.validateFields().then(v=>action(async()=>{await api.createAlertRule(v);setOpen(false);form.resetFields()}))}>
      <Form form={form} layout="vertical" initialValues={{ruleType:'COLLECTOR_UNAVAILABLE',severity:'P2',durationSeconds:0}}>
        <Form.Item name="name" label="规则名称" rules={[{required:true}]}><Input/></Form.Item>
        <Form.Item name="ruleType" label="规则类型" rules={[{required:true}]}><Select options={['COLLECTOR_UNAVAILABLE','REDIS_MEMORY_HIGH','LARGE_KEY_FOUND','SYNC_FAILED','SYNC_RPO_EXCEEDED','SPOOL_HIGH_WATERMARK'].map(v=>({value:v,label:v}))}/></Form.Item>
        <Form.Item name="severity" label="级别"><Select options={['P1','P2','P3'].map(v=>({value:v,label:v}))}/></Form.Item>
        <Form.Item name="thresholdValue" label="触发阈值（留空则任意值触发）"><InputNumber style={{width:'100%'}} min={0}/></Form.Item>
        <Form.Item name="durationSeconds" label="持续秒数"><InputNumber style={{width:'100%'}} min={0}/></Form.Item><Form.Item name="channelId" label="通知通道"><Select allowClear options={channels.map(c=>({value:c.id,label:`${c.name}（${c.configured?'已配置':'未配置'}）`}))}/></Form.Item>
      </Form>
    </Modal>
    <Card title="Webhook 通道"><Table size="small" rowKey="id" dataSource={channels} pagination={false} columns={[{title:'名称',dataIndex:'name'},{title:'类型',dataIndex:'type'},{title:'状态',dataIndex:'status',render:v=><Tag color={v==='ACTIVE'?'green':'default'}>{v}</Tag>},{title:'URL',render:()=> '已配置（不回显）'},{title:'操作',render:(_,r)=><Button size="small" onClick={()=>{setEditingChannel(r);channelForm.setFieldsValue({name:r.name,status:r.status,webhookUrl:undefined});setChannelOpen(true)}}>编辑</Button>}]}/></Card>
    <Card title="Webhook 投递历史"><Table size="small" rowKey="id" dataSource={deliveries.items} pagination={{current:deliveries.page,pageSize:deliveries.size,total:deliveries.total,onChange:page=>setDeliveries(x=>({...x,page}))}} columns={[{title:'记录',dataIndex:'id'},{title:'告警事件',dataIndex:'alertEventId'},{title:'通道',dataIndex:'channelId'},{title:'状态',dataIndex:'status',render:v=><Tag color={v==='SENT'?'green':v==='RETRYING'?'orange':'blue'}>{v}</Tag>},{title:'重试次数',dataIndex:'attemptCount'},{title:'最近错误',dataIndex:'lastError',render:v=>v||'-'},{title:'创建时间',dataIndex:'createdAt',render:v=>v&&new Date(v).toLocaleString()}]}/></Card>
    <Modal open={channelOpen} title={editingChannel?'编辑 Generic Webhook 通道':'新增 Generic Webhook 通道'} onCancel={()=>setChannelOpen(false)} onOk={()=>channelForm.validateFields().then(v=>action(async()=>{if(editingChannel)await api.updateNotificationChannel(editingChannel.id,editingChannel.version,v);else await api.createNotificationChannel(v);setChannelOpen(false);channelForm.resetFields()}))}>
      <Form form={channelForm} layout="vertical"><Form.Item name="name" label="通道名称" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="webhookUrl" label="Webhook URL（留空表示保持原值）" rules={[{type:'url'}]}><Input.Password/></Form.Item>{editingChannel&&<Form.Item name="status" label="状态"><Select options={[{value:'ACTIVE',label:'启用'},{value:'DISABLED',label:'停用'}]}/></Form.Item>}</Form>
    </Modal>
  </Space>
}
