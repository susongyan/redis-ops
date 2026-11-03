import IconActionButton from '../components/IconActionButton.jsx'
import {useEffect,useState} from 'react'
import {Alert,Button,Collapse,Empty,Form,Input,InputNumber,Modal,Select,Space,Spin,Switch,Table,Tag,message} from 'antd'
import {PlusOutlined} from '@ant-design/icons'
import {api} from '../api.js'
import {editableParameters,encodeDefinition,groupCommands} from '../commandDefinition.js'
const access=[{value:'READ',label:'只读'},{value:'WRITE',label:'写入'},{value:'MANAGE',label:'管理'}]
const risks=[{value:'LOW',label:'低'},{value:'MEDIUM',label:'中'},{value:'HIGH',label:'高'}]
const actions=[{value:'DIRECT',label:'无需确认'},{value:'CONFIRM',label:'二次确认'},{value:'DANGER_CONFIRM',label:'高危确认'},{value:'DENY',label:'禁止执行'}]
const types=[{value:'REDIS_KEY',label:'Key'},{value:'TEXT',label:'文本'},{value:'VALUE',label:'值'},{value:'INTEGER',label:'整数'},{value:'DECIMAL',label:'小数'}]
const label=(items,value)=>items.find(i=>i.value===value)?.label||value
export default function CommandCatalogPage(){
  const [rows,setRows]=useState([]),[loading,setLoading]=useState(false),[open,setOpen]=useState(false),[editing,setEditing]=useState(null),[saving,setSaving]=useState(false)
  const [groupsOpen,setGroupsOpen]=useState(false),[removing,setRemoving]=useState(null),[reason,setReason]=useState('')
  const [search,setSearch]=useState(''),[groupFilter,setGroupFilter]=useState(),[enabledFilter,setEnabledFilter]=useState()
  const [expanded,setExpanded]=useState([])
  const [form]=Form.useForm(),kind=Form.useWatch('nodeKind',form),mode=Form.useWatch('accessMode',form),risk=Form.useWatch('riskLevel',form)
  const isGroup=kind==='CATEGORY',groups=rows.filter(r=>r.nodeKind==='CATEGORY')
  const load=async()=>{setLoading(true);try{setRows(await api.operationCommands(true,true)||[])}catch(e){message.error(e.message)}finally{setLoading(false)}}
  useEffect(()=>{load()},[])
  const edit=(row,group=false)=>{setEditing(row);form.resetFields();form.setFieldsValue(row?{...row,parameters:editableParameters(row),changeReason:''}:{nodeKind:group?'CATEGORY':'COMMAND',commandName:'',parentId:null,enabled:true,accessMode:'READ',riskLevel:'LOW',approvalPolicy:'DIRECT',parameters:[],maxValueBytes:4096});setOpen(true)}
  const save=async()=>{try{const data=encodeDefinition(await form.validateFields(),groups);setSaving(true);if(editing)await api.defineOperationCommand(editing.id,editing.version,data);else await api.createOperationCommand(data);setOpen(false);message.success('配置已保存');await load()}catch(e){if(!e.errorFields)message.error(e.message)}finally{setSaving(false)}}
  const remove=async()=>{if(!reason.trim()){message.warning('请填写删除原因');return}setSaving(true);try{await api.deleteOperationCommand(removing.id,removing.version,reason);setRemoving(null);await load();message.success('已删除')}catch(e){message.error(e.message)}finally{setSaving(false)}}
  const deletion=row=>{setReason('');setRemoving(row)}
  const sections=groupCommands(rows,{search,groupId:groupFilter,enabled:enabledFilter})
  const sectionKeys=sections.map(g=>g.key).join(',')
  useEffect(()=>{if(search.trim()||groupFilter!==undefined||enabledFilter!==undefined)setExpanded(sectionKeys?sectionKeys.split(','):[])},[search,groupFilter,enabledFilter,sectionKeys])
  return <>
    <div className="toolbar"><div className="toolbar-row"><Space wrap><Input aria-label="搜索命令" placeholder="搜索命令" value={search} onChange={e=>setSearch(e.target.value)}/><Select aria-label="筛选分组" style={{minWidth:160}} placeholder="全部分组" allowClear value={groupFilter} onChange={setGroupFilter} options={[{value:0,label:'未分组'},...groups.map(g=>({value:g.id,label:g.commandName}))]}/><Select aria-label="筛选状态" style={{minWidth:120}} placeholder="全部状态" allowClear value={enabledFilter} onChange={setEnabledFilter} options={[{value:true,label:'已启用'},{value:false,label:'已禁用'}]}/><Button onClick={load}>刷新</Button><Button onClick={()=>setGroupsOpen(true)}>管理分组</Button></Space><Button type="primary" icon={<PlusOutlined/>} onClick={()=>edit(null)}>新增命令</Button></div></div>
      <Space wrap style={{marginBottom:12}}><Button size="small" onClick={()=>setExpanded(sections.map(g=>g.key))}>全部展开</Button><Button size="small" onClick={()=>setExpanded([])}>全部收起</Button><span className="muted">共 {sections.length} 个分组，{sections.reduce((n,g)=>n+g.commands.length,0)} 条命令</span></Space>
      <Spin spinning={loading}>{sections.length?<Collapse activeKey={expanded} onChange={setExpanded} items={sections.map(group=>({key:group.key,label:<Space wrap><strong>{group.name}</strong><Tag>{group.commands.length} 条命令</Tag></Space>,children:<Table rowKey="id" size="middle" dataSource={group.commands} pagination={group.commands.length>20?{pageSize:20}:false} scroll={{x:760}} columns={[
        {title:'命令',dataIndex:'commandName',render:v=><code>{v}</code>},
        {title:'读写属性',dataIndex:'accessMode',render:v=>label(access,v)},
        {title:'风险',dataIndex:'riskLevel',render:v=>label(risks,v)},
        {title:'执行确认',dataIndex:'approvalPolicy',render:v=>label(actions,v)},
        {title:'状态',dataIndex:'enabled',render:v=><Tag color={v?'green':'default'}>{v?'已启用':'已禁用'}</Tag>},
        {title:'操作',fixed:'right',width:96,align:'center',render:(_,r)=><Space><IconActionButton action="edit" onClick={()=>edit(r)}/><IconActionButton action="delete" onClick={()=>deletion(r)}/></Space>}
      ]}/>}))}/>:<Empty description="没有匹配的命令分组"/>}</Spin>
    <Modal title="管理分组" open={groupsOpen} onCancel={()=>setGroupsOpen(false)} footer={null} width={600}>
      <p>分组仅用于归类展示，不影响命令执行策略。</p><Button type="primary" onClick={()=>edit(null,true)}>新增分组</Button>
      <Table rowKey="id" dataSource={groups} scroll={{x:500}} pagination={{pageSize:8}} columns={[{title:'分组名称',dataIndex:'commandName'},{title:'命令数',render:(_,r)=>rows.filter(c=>c.parentId===r.id).length},{title:'操作',fixed:'right',width:96,align:'center',render:(_,r)=><Space wrap><IconActionButton action="edit" label="重命名" onClick={()=>edit(r,true)}/><IconActionButton action="delete" onClick={()=>deletion(r)}/></Space>}]}/>
    </Modal>
    <Modal title={(editing?'编辑':'新增')+(isGroup?'分组':'命令')} open={open} onCancel={()=>!saving&&setOpen(false)} onOk={save} confirmLoading={saving} width={760} destroyOnHidden>
      <Form form={form} layout="vertical">
        <Form.Item name="nodeKind" hidden><Input/></Form.Item>
        <Form.Item name="commandName" label={isGroup?'分组名称':'命令名称'} rules={[{required:true,whitespace:true,max:isGroup?32:65}]}><Input disabled={!!editing&&!isGroup} placeholder={isGroup?'例如 String、集群诊断':'例如 GET、CLUSTER INFO'}/></Form.Item>
        {!isGroup&&<>
          <div className="form-grid">
            <Form.Item name="parentId" label="所属分组"><Select allowClear placeholder="未分组" options={groups.map(g=>({value:g.id,label:g.commandName}))}/></Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch/></Form.Item>
            <Form.Item name="accessMode" label="读写属性" rules={[{required:true}]}><Select options={access} onChange={value=>{if(value==='MANAGE')form.setFieldValue('approvalPolicy','DANGER_CONFIRM');else if(value==='WRITE'&&form.getFieldValue('approvalPolicy')==='DIRECT')form.setFieldValue('approvalPolicy','CONFIRM')}}/></Form.Item>
            <Form.Item name="riskLevel" label="风险等级" rules={[{required:true}]}><Select options={risks} onChange={value=>{if(value==='HIGH')form.setFieldValue('approvalPolicy','DANGER_CONFIRM')}}/></Form.Item>
            <Form.Item name="approvalPolicy" label="执行确认" rules={[{required:true}]}><Select options={actions.filter(a=>a.value==='DENY'||a.value==='DANGER_CONFIRM'||(risk!=='HIGH'&&mode!=='MANAGE'&&(a.value!=='DIRECT'||mode==='READ')))}/></Form.Item>
            <Form.Item name="maxValueBytes" label="单个值最大字节数" rules={[{required:true}]}><InputNumber min={0} max={1048576}/></Form.Item>
          </div>
          <h4>参数设置</h4><p className="muted">按顺序添加额外参数。CLUSTER INFO 无需再填写 INFO。仅支持一个必填 Key，可选参数放在最后。</p>
          <Form.List name="parameters">{(fields,{add,remove})=><>
            {fields.map(field=><div key={field.key} className="command-parameter-row">
              <Form.Item name={[field.name,'name']} label="名称" rules={[{required:true,whitespace:true,max:64}]}><Input/></Form.Item>
              <Form.Item name={[field.name,'type']} label="类型" rules={[{required:true}]}><Select options={types}/></Form.Item>
              <Form.Item name={[field.name,'required']} label="必填" valuePropName="checked"><Switch/></Form.Item>
              <Form.Item name={[field.name,'variadic']} label="可重复" valuePropName="checked"><Switch/></Form.Item>
              <Form.Item name={[field.name,'literal']} label="固定值（可选）"><Input/></Form.Item>
              <IconActionButton action="delete" label="删除参数" onClick={()=>remove(field.name)}/>
            </div>)}
            <Button disabled={fields.length>=31} onClick={()=>add({name:'',type:'TEXT',required:true,variadic:false})}>添加参数</Button>
          </>}</Form.List>
          <Alert style={{marginTop:16}} type="info" showIcon message="Cluster 命令路由" description="有 Key 的命令按 Key 路由。已启用且配置为「只读 + 无 Key」的命令自动选择一个健康主节点执行，不广播，结果不代表集群汇总。无 Key 的写入或管理类命令暂不支持。请准确配置读写属性，系统不自动识别；执行仍受命令策略约束。"/>
        </>}
        <Form.Item style={{marginTop:16}} name="changeReason" label="变更原因" rules={[{required:true,whitespace:true,max:512}]}><Input.TextArea rows={2}/></Form.Item>
      </Form>
    </Modal>
    <Modal title={'删除'+(removing?.nodeKind==='CATEGORY'?'分组':'命令')+'：'+(removing?.commandName||'')} open={!!removing} onCancel={()=>!saving&&setRemoving(null)} onOk={remove} confirmLoading={saving} okButtonProps={{danger:true}} okText="确认删除">
      <p>{removing?.nodeKind==='CATEGORY'?'组内命令将移入未分组，不会删除命令。':'删除后不再允许执行，待执行确认失效；已发送的命令不会撤回。历史记录保留。'}</p>
      <Input.TextArea aria-label="删除原因" placeholder="请填写删除原因" maxLength={512} value={reason} onChange={e=>setReason(e.target.value)}/>
    </Modal>
  </>
}
