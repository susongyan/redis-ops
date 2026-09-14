import { Component,lazy,Suspense,useEffect,useState } from 'react'
import { AlertOutlined, AppstoreOutlined, AuditOutlined, ClockCircleOutlined, ClusterOutlined, CodeOutlined, DatabaseOutlined, DeleteOutlined, EnvironmentOutlined, FileSearchOutlined, FileTextOutlined, FundProjectionScreenOutlined, LineChartOutlined, RobotOutlined, SafetyCertificateOutlined, SearchOutlined, SettingOutlined, SwapOutlined, SyncOutlined, TeamOutlined } from '@ant-design/icons'
import { Layout, Menu, Typography, Input, Select, Space } from 'antd'
const ClustersPage=lazy(()=>import('./pages/ClustersPage.jsx'))
const ApplicationsPage=lazy(()=>import('./pages/ApplicationsPage.jsx'))
const LocationsPage=lazy(()=>import('./pages/LocationsPage.jsx'))
const RelationsPage=lazy(()=>import('./pages/RelationsPage.jsx'))
const SyncTasksPage=lazy(()=>import('./pages/SyncTasksPage.jsx'))
const ValidationTasksPage=lazy(()=>import('./pages/ValidationTasksPage.jsx'))
const RiskScansPage=lazy(()=>import('./pages/RiskScansPage.jsx'))
const AlertsPage=lazy(()=>import('./pages/AlertsPage.jsx'))
const MetricsPage=lazy(()=>import('./pages/MetricsPage.jsx'))
const TtlGovernancePage=lazy(()=>import('./pages/TtlGovernancePage.jsx'))
const CleanupGovernancePage=lazy(()=>import('./pages/CleanupGovernancePage.jsx'))
const AuditsPage=lazy(()=>import('./pages/AuditsPage.jsx'))
const RedisOperationsPage=lazy(()=>import('./pages/RedisOperationsPage.jsx'))
const CommandCatalogPage=lazy(()=>import('./pages/CommandCatalogPage.jsx'))
const AnalysisAgentsPage=lazy(()=>import('./pages/AnalysisAgentsPage.jsx'))
const AnalysisRunsPage=lazy(()=>import('./pages/AnalysisRunsPage.jsx'))
const KeyDistributionsPage=lazy(()=>import('./pages/KeyDistributionsPage.jsx'))

const {Sider,Header,Content}=Layout
const pageFromHash=()=>{
  const page=window.location.hash.replace(/^#\/?/,'').split('?')[0]
  return ['clusters','locations','relations','syncTasks','validations','keyDistributions','riskScans','metrics','alerts','ttlGovernance','cleanupGovernance','redisOperations','commandCatalog','applications','audits','analysisAgents','analysisRuns'].includes(page)?page:'clusters'
}
class PageErrorBoundary extends Component{
  state={error:null}
  static getDerivedStateFromError(error){return{error}}
  render(){return this.state.error?<div className="muted">页面加载失败：{this.state.error.message}</div>:this.props.children}
}
export default function App(){
  const [page,setPage]=useState(pageFromHash)
  const [openMenuKeys,setOpenMenuKeys]=useState(['assets','observability','governance','platform'])
  const [operator,setOperator]=useState(localStorage.getItem('redis-ops-operator')||'local-admin')
  const pages={clusters:<ClustersPage/>,applications:<ApplicationsPage/>,locations:<LocationsPage/>,relations:<RelationsPage/>,syncTasks:<SyncTasksPage/>,validations:<ValidationTasksPage/>,keyDistributions:<KeyDistributionsPage/>,riskScans:<RiskScansPage/>,metrics:<MetricsPage/>,alerts:<AlertsPage/>,ttlGovernance:<TtlGovernancePage/>,cleanupGovernance:<CleanupGovernancePage/>,redisOperations:<RedisOperationsPage/>,commandCatalog:<CommandCatalogPage/>,audits:<AuditsPage/>,analysisAgents:<AnalysisAgentsPage/>,analysisRuns:<AnalysisRunsPage/>}
  const pageOptions=[
    {value:'clusters',label:'Redis 集群'},
    {value:'locations',label:'Region / IDC'},
    {value:'relations',label:'主备关系'},
    {value:'syncTasks',label:'同步任务'},
    {value:'validations',label:'数据校验'},
    {value:'riskScans',label:'风险扫描'},
    {value:'keyDistributions',label:'Key 分布分析'},
    {value:'metrics',label:'监控指标'},
    {value:'alerts',label:'告警中心'},
    {value:'ttlGovernance',label:'TTL 治理'},
    {value:'cleanupGovernance',label:'数据清理'},
    {value:'redisOperations',label:'redis console'},
    {value:'commandCatalog',label:'命令配置'},
    {value:'applications',label:'业务应用'},
    {value:'audits',label:'审计日志'},
    {value:'analysisAgents',label:'AI Agent'},
    {value:'analysisRuns',label:'分析结果'},
  ]
  const pageTitle=page==='redisOperations'?'Redis Console':'Redis 资源管理'
  const updateOperator=value=>{setOperator(value);localStorage.setItem('redis-ops-operator',value)}
  useEffect(()=>{
    const onHashChange=()=>setPage(pageFromHash())
    window.addEventListener('hashchange',onHashChange)
    if(!window.location.hash)window.location.hash='/clusters'
    return()=>window.removeEventListener('hashchange',onHashChange)
  },[])
  const navigate=next=>{
    if(next===page)return
    window.location.hash=`/${next}`
  }
  return <Layout className="app-shell">
    <Sider width={230} theme="light" className="sidebar">
      <div className="brand"><ClusterOutlined/><span>Redis Governance</span></div>
      <Menu mode="inline" selectedKeys={[page]} openKeys={openMenuKeys} onOpenChange={setOpenMenuKeys} onClick={({key})=>navigate(key)} items={[
        {key:'assets',icon:<DatabaseOutlined/>,label:'资产与同步',children:[
          {key:'clusters',icon:<ClusterOutlined/>,label:'Redis 集群'},
          {key:'locations',icon:<EnvironmentOutlined/>,label:'Region / IDC'},
          {key:'relations',icon:<SwapOutlined/>,label:'主备关系'},
          {key:'syncTasks',icon:<SyncOutlined/>,label:'同步任务'},
          {key:'validations',icon:<SafetyCertificateOutlined/>,label:'数据校验'}]},
        {key:'observability',icon:<LineChartOutlined/>,label:'采集与风险',children:[
          {key:'metrics',icon:<FundProjectionScreenOutlined/>,label:'监控指标'},
          {key:'riskScans',icon:<SearchOutlined/>,label:'风险扫描'},
          {key:'keyDistributions',icon:<SearchOutlined/>,label:'Key 分布分析'},
          {key:'alerts',icon:<AlertOutlined/>,label:'告警中心'}]},
        {key:'governance',icon:<SafetyCertificateOutlined/>,label:'质量治理',children:[
          {key:'ttlGovernance',icon:<ClockCircleOutlined/>,label:'TTL 治理'},
          {key:'cleanupGovernance',icon:<DeleteOutlined/>,label:'数据清理'},
          {key:'redisOperations',icon:<CodeOutlined/>,label:'redis console'},
          {key:'commandCatalog',icon:<SettingOutlined/>,label:'命令配置'}]},
        {key:'platform',icon:<TeamOutlined/>,label:'平台支撑',children:[
          {key:'applications',icon:<AppstoreOutlined/>,label:'业务应用'},
          {key:'audits',icon:<AuditOutlined/>,label:'审计日志'},
          {key:'analysisAgents',icon:<RobotOutlined/>,label:'AI Agent'},
          {key:'analysisRuns',icon:<FileTextOutlined/>,label:'分析结果'}]}]}/>
    </Sider>
    <Layout><Header className="topbar"><Typography.Title level={4} className="page-title">{pageTitle}</Typography.Title>
      <Select className="mobile-page-select" value={page} onChange={navigate} options={pageOptions}/>
      <Space><span className="muted">当前操作者</span><Input value={operator} onChange={e=>updateOperator(e.target.value)} style={{width:180}}/></Space>
    </Header><Content className="content"><PageErrorBoundary key={page}><Suspense fallback={<div className="muted">加载中…</div>}>{pages[page]}</Suspense></PageErrorBoundary></Content></Layout>
  </Layout>
}
