import React,{useEffect,useState} from 'react'
import ReactDOM from 'react-dom/client'
import { ConfigProvider,theme as antdTheme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import App from './IdentityGate.jsx'
import { readStoredTheme,persistTheme,applyTheme,themeToken,toggleTheme } from './theme.js'
import './styles.css'

// 明/暗主题状态只放这一层：antd 算法切换 + html[data-theme]（styles.css --ops-* 变量）+ localStorage 记忆
function Root() {
  const [mode,setMode]=useState(readStoredTheme)
  useEffect(()=>{applyTheme(mode);persistTheme(mode)},[mode])
  return <React.StrictMode><ConfigProvider locale={zhCN} theme={{token:themeToken(mode),algorithm:mode==='dark'?antdTheme.darkAlgorithm:antdTheme.defaultAlgorithm}}><App themeMode={mode} onToggleTheme={()=>setMode(toggleTheme)}/></ConfigProvider></React.StrictMode>
}

ReactDOM.createRoot(document.getElementById('root')).render(<Root/>)
