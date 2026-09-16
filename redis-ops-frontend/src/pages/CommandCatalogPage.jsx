import {useEffect,useState} from 'react'
import {Alert,Button,Card,Form,Input,InputNumber,Modal,Select,Space,Switch,Table,Tag,message} from 'antd'
import {api} from '../api.js'
import {actorLabel} from '../actor.js'

const keySchema=JSON.stringify([{name:'key',type:'REDIS_KEY',required:true}],null,2)
export default function CommandCatalogPage(){
  const [rows,setRows]=useState([]),[loading,setLoading]=useState(false),[open,setOpen]=useState(false),[editing,setEditing]=useState(null),[saving,setSaving]=useState(false)
  const [form]=Form.useForm()
  const load=async()=>{setLoading(true);try{setRows(await api.operationCommands(true,true)||[])}catch(e){message.error(e.message)}finally{setLoading(false)}}
  useEffect(()=>{load()},[])
  const edit=row=>{setEditing(row);form.setFieldsValue(row?{...row,parameterSchemaJson:JSON.stringify(JSON.parse(row.parameterSchemaJson),null,2),changeReason:''}:{commandName:'',category:'CUSTOM',accessMode:'READ',riskLevel:'LOW',enabled:false,parameterSchemaJson:keySchema,keyPosition:1,routingPolicy:'SINGLE_KEY',approvalPolicy:'CONFIRM',maxValueBytes:4096,changeReason:''});setOpen(true)}
  const save=async()=>{
    const values=await form.validateFields()
    Modal.confirm({title:editing?'确认修改命令准入配置？':'确认新增命令定义？',content:'命令能力和风险由运维配置决定。请确认参数、读写属性及审批策略准确；变更会使旧的待执行请求失效。新增命令默认禁用。',onOk:async()=>{setSaving(true);try{if(editing)await api.defineOperationCommand(editing.id,editing.version,values);else await api.createOperationCommand(values);setOpen(false);message.success('命令配置已保存');await load()}catch(e){message.error(e.message);throw e}finally{setSaving(false)}}})
  }
  return <Space direction="vertical" style={{width:'100%'}} size="large">
    <Alert type="info" showIcon message="命令配置说明" description="管理 Redis Console 可执行的命令及审批策略。新增命令默认禁用，请核对参数与风险后再启用。"/>
    <Card title="命令配置" extra={<Space wrap><Button onClick={load}>刷新</Button><Button type="primary" onClick={()=>edit(null)}>新增命令</Button></Space>}>
      <Table rowKey="id" loading={loading} dataSource={rows} pagination={{pageSize:20}} scroll={{x:1100}} columns={[
        {title:'命令',dataIndex:'commandName',render:v=><code>{v}</code>},{title:'分类',dataIndex:'category'},
        {title:'读写',dataIndex:'accessMode'},{title:'风险',dataIndex:'riskLevel'},{title:'审批策略',dataIndex:'approvalPolicy'},
        {title:'路由',dataIndex:'routingPolicy',render:(v,r)=>v==='NO_KEY'?'无 Key（非 Cluster）':`单 Key，第 ${r.keyPosition} 个参数`},
        {title:'状态',dataIndex:'enabled',render:v=><Tag color={v?'green':'default'}>{v?'已启用':'已禁用'}</Tag>},
        {title:'修改人',dataIndex:'updatedBy',render:(v,r)=>actorLabel(v,r.updatedBySnapshot)},
        {title:'操作',render:(_,r)=><Button onClick={()=>edit(r)}>编辑定义</Button>}
      ]}/>
    </Card>
    <Modal title={editing?`编辑 ${editing.commandName}`:'新增命令（默认禁用）'} open={open} onCancel={()=>setOpen(false)} onOk={()=>save().catch(()=>{})} confirmLoading={saving} width={760} destroyOnHidden>
      <Form form={form} layout="vertical">
        <div className="form-grid">
          <Form.Item name="commandName" label="命令名称（子命令放入参数）" rules={[{required:true},{pattern:/^[a-zA-Z][a-zA-Z0-9_.-]{0,31}$/,message:'仅字母、数字、点、下划线、连字符'}]}><Input disabled={!!editing}/></Form.Item>
          <Form.Item name="category" label="分类" rules={[{required:true,max:32}]}><Input/></Form.Item>
          <Form.Item name="accessMode" label="读写属性" rules={[{required:true}]}><Select options={['READ','WRITE'].map(value=>({value}))}/></Form.Item>
          <Form.Item name="riskLevel" label="风险等级" rules={[{required:true}]}><Select options={['LOW','MEDIUM','HIGH'].map(value=>({value}))}/></Form.Item>
          <Form.Item name="approvalPolicy" label="执行策略" rules={[{required:true}]}><Select options={[{value:'DIRECT',label:'DIRECT：提交即执行'},{value:'CONFIRM',label:'CONFIRM：确认后执行'},{value:'APPROVAL',label:'APPROVAL：审批后执行'}]}/></Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch disabled={!editing}/></Form.Item>
          <Form.Item name="routingPolicy" label="路由" rules={[{required:true}]}><Select onChange={v=>form.setFieldValue('keyPosition',v==='NO_KEY'?0:1)} options={[{value:'SINGLE_KEY',label:'单 Key'},{value:'NO_KEY',label:'无 Key（仅 Standalone / Sentinel）'}]}/></Form.Item>
          <Form.Item name="keyPosition" label="Key 参数位置（从 1 开始，无 Key 填 0）" rules={[{required:true}]}><InputNumber min={0} max={32}/></Form.Item>
          <Form.Item name="maxValueBytes" label="VALUE 参数最大字节数" rules={[{required:true}]}><InputNumber min={0} max={1048576}/></Form.Item>
        </div>
        <Form.Item name="parameterSchemaJson" label="参数定义 JSON（按参数顺序）" rules={[{required:true},{validator:(_,v)=>{try{if(!Array.isArray(JSON.parse(v)))throw Error();return Promise.resolve()}catch{return Promise.reject(new Error('请输入 JSON 数组'))}}}]}><Input.TextArea rows={9}/></Form.Item>
        <p className="muted">每项包含 name、type、required；type 支持 REDIS_KEY / TEXT / VALUE / INTEGER / DECIMAL。尾部参数可选，最后一项可设 variadic: true。literal 可固定子命令或选项值。无参数命令填 []。不支持多 Key 路由。</p>
        <Form.Item name="changeReason" label="变更原因" rules={[{required:true,max:512}]}><Input.TextArea rows={2}/></Form.Item>
      </Form>
    </Modal>
  </Space>
}
