import { Component,lazy,Suspense,useState } from 'react'
import { AppstoreOutlined,AuditOutlined,ClusterOutlined } from '@ant-design/icons'
import { Layout, Menu, Typography, Input, Space } from 'antd'
const ClustersPage=lazy(()=>import('./pages/ClustersPage.jsx'))
const ApplicationsPage=lazy(()=>import('./pages/ApplicationsPage.jsx'))
const LocationsPage=lazy(()=>import('./pages/LocationsPage.jsx'))
const RelationsPage=lazy(()=>import('./pages/RelationsPage.jsx'))
const SyncTasksPage=lazy(()=>import('./pages/SyncTasksPage.jsx'))
const AuditsPage=lazy(()=>import('./pages/AuditsPage.jsx'))

const {Sider,Header,Content}=Layout
class PageErrorBoundary extends Component{
  state={error:null}
  static getDerivedStateFromError(error){return{error}}
  render(){return this.state.error?<div className="muted">页面加载失败：{this.state.error.message}</div>:this.props.children}
}
export default function App(){
  const [page,setPage]=useState('clusters')
  const [operator,setOperator]=useState(localStorage.getItem('redis-ops-operator')||'local-admin')
  const pages={clusters:<ClustersPage/>,applications:<ApplicationsPage/>,locations:<LocationsPage/>,relations:<RelationsPage/>,syncTasks:<SyncTasksPage/>,audits:<AuditsPage/>}
  const updateOperator=value=>{setOperator(value);localStorage.setItem('redis-ops-operator',value)}
  return <Layout className="app-shell">
    <Sider width={230} theme="light" className="sidebar">
      <div className="brand"><ClusterOutlined/><span>Redis Governance</span></div>
      <Menu mode="inline" selectedKeys={[page]} onClick={({key})=>setPage(key)} items={[
        {key:'clusters',icon:<ClusterOutlined/>,label:'Redis 集群'},
        {key:'locations',label:'Region / IDC'},
        {key:'relations',label:'主备关系'},
        {key:'syncTasks',label:'同步任务'},
        {key:'applications',icon:<AppstoreOutlined/>,label:'业务应用'},
        {key:'audits',icon:<AuditOutlined/>,label:'审计日志'}]}/>
    </Sider>
    <Layout><Header className="topbar"><Typography.Title level={4} className="page-title">Redis 资源管理</Typography.Title>
      <Space><span className="muted">当前操作者</span><Input value={operator} onChange={e=>updateOperator(e.target.value)} style={{width:180}}/></Space>
    </Header><Content className="content"><PageErrorBoundary key={page}><Suspense fallback={<div className="muted">加载中…</div>}>{pages[page]}</Suspense></PageErrorBoundary></Content></Layout>
  </Layout>
}
