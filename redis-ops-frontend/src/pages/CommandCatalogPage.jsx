import {useEffect,useState} from 'react'
import {Alert,Button,Card,Form,Input,InputNumber,Modal,Select,Space,Switch,Table,Tag,message} from 'antd'
import {api} from '../api.js'
import {commandTree,effectivePolicy} from '../commandTree.js'
import {actorLabel} from '../actor.js'

const keySchema=JSON.stringify([{name:'key',type:'REDIS_KEY',required:true}],null,2)
export default function CommandCatalogPage(){
  const [rows,setRows]=useState([]),[loading,setLoading]=useState(false),[open,setOpen]=useState(false),[editing,setEditing]=useState(null),[saving,setSaving]=useState(false)
  const [form]=Form.useForm()
  const kind=Form.useWatch('nodeKind',form)||'COMMAND'
  const container=['CATEGORY','FAMILY'].includes(kind)
  const parents=rows.filter(r=>r.id!==editing?.id && r.nodeKind===(['SUBCOMMAND','WILDCARD'].includes(kind)?'FAMILY':'CATEGORY'))
  const load=async()=>{setLoading(true);try{setRows(await api.operationCommands(true,true)||[])}catch(e){message.error(e.message)}finally{setLoading(false)}}
  useEffect(()=>{load()},[])
  const edit=row=>{setEditing(row);form.setFieldsValue(row?{...row,parameterSchemaJson:JSON.stringify(JSON.parse(row.parameterSchemaJson),null,2),changeReason:''}:{commandName:'',category:'CUSTOM',accessMode:'READ',riskLevel:'LOW',enabled:false,parameterSchemaJson:keySchema,keyPosition:1,routingPolicy:'SINGLE_KEY',approvalPolicy:'CONFIRM',maxValueBytes:4096,changeReason:'',nodeKind:'COMMAND',parentId:null});setOpen(true)}
  const save=async()=>{
    const values=await form.validateFields()
    const affected=new Set(editing?[editing.id]:[]);for(let i=0;i<16;i++)for(const row of rows)if(affected.has(row.parentId))affected.add(row.id)
    const impact=rows.filter(row=>affected.has(row.id)).map(row=>row.commandName)
    Modal.confirm({title:editing?'确认修改命令准入配置？':'确认新增命令定义？',content:<div><p>变更会使关联的待执行请求失效；新增节点默认禁用。</p>{impact.length>0&&<p>影响分支：{impact.slice(0,20).join('、')}{impact.length>20?` 等 ${impact.length} 个节点`:''}</p>}</div>,onOk:async()=>{setSaving(true);try{if(editing)await api.defineOperationCommand(editing.id,editing.version,values);else await api.createOperationCommand(values);setOpen(false);message.success('命令配置已保存');await load()}catch(e){message.error(e.message);throw e}finally{setSaving(false)}}})
  }
  return <Space direction="vertical" style={{width:'100%'}} size="large">
    <Alert type="info" showIcon message="命令配置说明" description="按分类和子命令维护执行范围。新增节点默认禁用，启用及调整生效策略需管理员操作。"/>
    <Card title="命令树与策略" extra={<Space wrap><Button onClick={load}>刷新</Button><Button onClick={()=>{edit(null);form.setFieldsValue({nodeKind:'CATEGORY',commandName:'',routingPolicy:'CONTAINER',keyPosition:0,parameterSchemaJson:'[]',approvalPolicy:'INHERIT'})}}>新增分组</Button><Button type="primary" onClick={()=>edit(null)}>新增命令</Button></Space>}>
      <Table rowKey="id" loading={loading} dataSource={commandTree(rows)} pagination={{pageSize:20}} scroll={{x:1500}} columns={[
        {title:'命令',dataIndex:'commandName',width:220,render:v=><code style={{whiteSpace:'nowrap'}}>{v}</code>},{title:'分类',dataIndex:'category'},
        {title:'读写',dataIndex:'accessMode'},{title:'风险',dataIndex:'riskLevel'},{title:'配置策略',dataIndex:'approvalPolicy'},{title:'最终策略／来源',render:(_,row)=>{const policy=effectivePolicy(rows,row);return <span>{policy.action}<br/><small>{policy.source}</small></span>}},{title:'节点类型',dataIndex:'nodeKind'},
        {title:'路由',dataIndex:'routingPolicy',render:(v,r)=>v==='CONTAINER'?'目录节点':v==='NO_KEY'?'无 Key（非 Cluster）':`单 Key，第 ${r.keyPosition} 个参数`},
        {title:'状态',dataIndex:'enabled',render:v=><Tag color={v?'green':'default'}>{v?'已启用':'已禁用'}</Tag>},
        {title:'修改人',dataIndex:'updatedBy',render:(v,r)=>actorLabel(v,r.updatedBySnapshot)},
        {title:'操作',render:(_,r)=><Button onClick={()=>edit(r)}>编辑定义</Button>}
      ]}/>
    </Card>
    <Modal title={editing?`编辑 ${editing.commandName}`:kind==='CATEGORY'?'新增分组（默认禁用）':'新增命令（默认禁用）'} open={open} onCancel={()=>setOpen(false)} onOk={()=>save().catch(()=>{})} confirmLoading={saving} width={760} destroyOnHidden>
      <Form form={form} layout="vertical">
        <div className="form-grid">
          <Form.Item name="nodeKind" label="节点类型" rules={[{required:true}]}><Select disabled={!!editing} options={[{value:'CATEGORY',label:'分类'},{value:'FAMILY',label:'命令族'},{value:'COMMAND',label:'命令'},{value:'SUBCOMMAND',label:'具体子命令'},{value:'WILDCARD',label:'全部子命令（高危）'}]} onChange={value=>{const directory=['CATEGORY','FAMILY'].includes(value);form.setFieldsValue({parentId:null,routingPolicy:directory?'CONTAINER':'NO_KEY',keyPosition:0,parameterSchemaJson:directory?'[]':'[{"name":"subcommand","type":"TEXT","required":true}]',accessMode:value==='WILDCARD'?'MANAGE':'READ',riskLevel:value==='WILDCARD'?'HIGH':'LOW',approvalPolicy:value==='WILDCARD'?'DANGER_CONFIRM':'DENY'})}}/></Form.Item>
          <Form.Item name="parentId" label="父节点"><Select allowClear options={parents.map(row=>({value:row.id,label:row.commandName}))} onChange={id=>{if(kind==='WILDCARD'){const parent=rows.find(r=>r.id===id);if(parent)form.setFieldValue('commandName',parent.commandName+' *')}}}/></Form.Item>
          <Form.Item name="commandName" label="名称（例如 CLUSTER INFO 或 CLUSTER *）" rules={[{required:true},{pattern:/^[a-zA-Z][a-zA-Z0-9_.-]{0,31}( (\*|[a-zA-Z][a-zA-Z0-9_.-]{0,31}))?$/,message:'请输入命令名或命令与子命令'}]}><Input disabled={!!editing}/></Form.Item>
          <Form.Item name="category" label="分类" rules={[{required:true,max:32}]}><Input/></Form.Item>
          <Form.Item name="accessMode" label="读写属性" rules={[{required:true}]}><Select options={['READ','WRITE','MANAGE'].map(value=>({value}))}/></Form.Item>
          <Form.Item name="riskLevel" label="风险等级" rules={[{required:true}]}><Select options={['LOW','MEDIUM','HIGH'].map(value=>({value}))}/></Form.Item>
          <Form.Item name="approvalPolicy" label="执行策略" rules={[{required:true}]}><Select options={[{value:'DIRECT',label:'DIRECT：提交即执行'},{value:'CONFIRM',label:'CONFIRM：确认后执行'},{value:'DANGER_CONFIRM',label:'高危：输入目标名称并填写原因'},{value:'INHERIT',label:'继承父节点策略'},{value:'DENY',label:'禁止执行'}]}/></Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch disabled={!editing}/></Form.Item>
          <Form.Item name="routingPolicy" label="路由" rules={[{required:true}]}><Select onChange={v=>form.setFieldValue('keyPosition',v==='NO_KEY'?0:1)} disabled={container} options={[{value:'CONTAINER',label:'目录（不执行）'},{value:'SINGLE_KEY',label:'单 Key'},{value:'NO_KEY',label:'无 Key（仅 Standalone / Sentinel）'}]}/></Form.Item>
          <Form.Item name="keyPosition" label="Key 参数位置（从 1 开始，无 Key 填 0）" rules={[{required:true}]}><InputNumber min={0} max={32}/></Form.Item>
          <Form.Item name="maxValueBytes" label="VALUE 参数最大字节数" rules={[{required:true}]}><InputNumber min={0} max={1048576}/></Form.Item>
        </div>
        <Form.Item name="parameterSchemaJson" label="参数定义 JSON（按参数顺序）" rules={[{required:true},{validator:(_,v)=>{try{if(!Array.isArray(JSON.parse(v)))throw Error();return Promise.resolve()}catch{return Promise.reject(new Error('请输入 JSON 数组'))}}}]}><Input.TextArea rows={9}/></Form.Item>
        <p className="muted">每项包含 name、type、required；type 支持 REDIS_KEY / TEXT / VALUE / INTEGER / DECIMAL。尾部参数可选，最后一项可设 variadic: true。具体子命令的第一项必须 required: true，literal 为大写子命令。通配节点第一项为必填 TEXT 子命令，后续参数按需配置。无参数命令填 []。不支持多 Key 路由。</p>
        <Form.Item name="changeReason" label="变更原因" rules={[{required:true,max:512}]}><Input.TextArea rows={2}/></Form.Item>
      </Form>
    </Modal>
  </Space>
}
