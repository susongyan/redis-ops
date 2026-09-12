import {useEffect,useState} from 'react'
import {Alert,Button,Card,Form,Input,InputNumber,Modal,Select,Space,Switch,Table,Tag,message} from 'antd'
import {api} from '../api.js'

const protocols=['RULE','HTTP_JSON','A2A','ACP']
const types=['ALERT','SYNC','VALIDATION','RISK_SCAN','INCIDENT']
export default function AnalysisAgentsPage(){
  const [rows,setRows]=useState([]);const [open,setOpen]=useState(false);const [editing,setEditing]=useState(null);const [form]=Form.useForm()
  const load=async()=>{try{setRows(await api.analysisAgents(true))}catch(e){message.error(e.message)}}
  useEffect(()=>{load()},[])
  const edit=row=>{setEditing(row);form.setFieldsValue(row?{...row,supportedTypes:(JSON.parse(row.supportedTypesJson||'[]'))}:{enabled:true,protocol:'RULE',timeoutMs:5000,priority:100,supportedTypes:types});setOpen(true)}
  const save=async()=>{try{const value=await form.validateFields();const payload={...value,supportedTypesJson:JSON.stringify(value.supportedTypes||[])};delete payload.supportedTypes;if(editing)await api.updateAnalysisAgent(editing.id,editing.version,payload);else await api.createAnalysisAgent(payload);setOpen(false);form.resetFields();load();message.success('已保存')}catch(e){if(e?.errorFields)return;message.error(e.message)}}
  return <Card title="AI Agent 注册资料" extra={<Button type="primary" onClick={()=>edit(null)}>新增 Agent</Button>}>
    <Alert type="info" showIcon style={{marginBottom:16}} message="注册资料暂不驱动运行时调用。实际启停、地址和优先级以进程配置及适配器实现为准；A2A 尚未实现。"/>
    <Table scroll={{x:1000}} rowKey="id" dataSource={rows} columns={[{title:'名称',dataIndex:'name'},{title:'协议',dataIndex:'protocol',render:v=><Tag color={v==='RULE'?'default':'blue'}>{v}</Tag>},{title:'Endpoint',dataIndex:'endpoint',render:v=>v||'内置规则'},{title:'支持场景',dataIndex:'supportedTypesJson',render:v=>(JSON.parse(v||'[]')).join('、')||'—'},{title:'优先级',dataIndex:'priority'},{title:'登记状态',dataIndex:'enabled',render:(v,r)=><Switch size="small" checked={v} onChange={()=>edit(r)}/>},{title:'操作',render:(_,r)=><Button size="small" onClick={()=>edit(r)}>编辑</Button>}]}/>
    <Modal open={open} title={editing?'编辑 AI Agent':'新增 AI Agent'} onCancel={()=>setOpen(false)} onOk={save} width={620}>
      <Form form={form} layout="vertical"><Form.Item name="name" label="名称" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="protocol" label="协议" rules={[{required:true}]}><Select options={protocols.map(v=>({value:v,label:v}))}/></Form.Item><Form.Item name="endpoint" label="Endpoint"><Input placeholder="HTTP_JSON / A2A / ACP 使用；RULE 可留空"/></Form.Item><Form.Item name="supportedTypes" label="支持场景"><Select mode="multiple" options={types.map(v=>({value:v,label:v}))}/></Form.Item><Space><Form.Item name="timeoutMs" label="超时（ms）"><InputNumber min={100} max={120000}/></Form.Item><Form.Item name="priority" label="优先级"><InputNumber min={1} max={1000}/></Form.Item></Space><Form.Item name="enabled" label="启用" valuePropName="checked"><Switch/></Form.Item></Form>
    </Modal>
  </Card>
}
