import { useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, Space, Spin, message } from 'antd'
import { request } from './api.js'
import App from './App.jsx'
import { ClusterOutlined, LockOutlined, UserOutlined, ArrowRightOutlined } from '@ant-design/icons'

export default function IdentityGate() {
  const [user,setUser]=useState(null), [loading,setLoading]=useState(true), [changing,setChanging]=useState(false), [busy,setBusy]=useState(false)
  const [form]=Form.useForm()
  useEffect(()=>{
    let alive=true
    const refresh=()=>request('/api/v1/auth/me').then(value=>{if(alive)setUser(value)}).catch(()=>{if(alive)setUser(null)}).finally(()=>{if(alive)setLoading(false)})
    const expire=()=>{setUser(null);setChanging(false);form.resetFields()}
    window.addEventListener('identity-expired',expire);refresh()
    localStorage.removeItem('redis-ops-operator')
    return()=>{alive=false;window.removeEventListener('identity-expired',expire)}
  },[form])
  const logout=async()=>{try{await request('/api/v1/auth/logout',{method:'POST'})}catch(e){message.error(e.message)}finally{setUser(null);setChanging(false);form.resetFields()}}
  const submit=async values=>{
    setBusy(true)
    try {
      if(user){await request('/api/v1/auth/password',{method:'POST',headers:{'If-Match':String(user.version)},body:JSON.stringify({oldPassword:values.oldPassword,newPassword:values.password})});setUser(null);setChanging(false);message.success('密码已修改，请重新登录')}
      else setUser(await request('/api/v1/auth/login',{method:'POST',body:JSON.stringify(values)}))
      form.resetFields()
    } catch(e){message.error(e.message);form.setFieldsValue({password:'',oldPassword:'',confirm:''})} finally{setBusy(false)}
  }
  if(loading)return <div className="identity-login"><Spin tip="检查登录状态"/></div>
  if(user&&!user.passwordChangeRequired&&!changing)return <App user={user} onLogout={logout} onPassword={()=>{form.resetFields();setChanging(true)}}/>
  return <main className="identity-page">
    <section className="identity-story" aria-label="Redis Ops 平台介绍">
      <div className="identity-brand"><span className="identity-brand-icon"><ClusterOutlined/></span><span>Redis Ops<small>运维与治理平台</small></span></div>
      <div className="identity-story-content"><div className="identity-eyebrow">ONE PLACE. CLEAR CONTROL.</div><h1>让 Redis 运维<br/>更清晰，更从容。</h1><p>从资产到同步，从观测到治理。<br/>在一个工作台中，掌握每个集群。</p>
        <div className="identity-topology" aria-hidden="true"><div className="identity-node identity-node-main"><ClusterOutlined/> Redis Ops</div><div className="identity-topology-branches"><span>资产管理</span><span>数据同步</span><span>分析治理</span></div></div>
      </div><div className="identity-story-footer">REDIS OPERATIONS & GOVERNANCE</div>
    </section>
    <section className="identity-access" aria-label={user?'修改密码':'账号登录'}><Card className="identity-card" bordered={false}>
      <div className="identity-card-icon"><LockOutlined/></div><h2>{user?'设置您的密码':'欢迎回来'}</h2><p className="identity-card-subtitle">{user?'更新登录凭据，继续使用运维工作台。':'登录 Redis Ops，进入您的运维工作台。'}</p>
    {user&&<Alert type="info" message={user.passwordChangeRequired?'首次登录或管理员重置后必须修改密码':'修改密码后需重新登录'} style={{marginBottom:20}}/>}
    <Form form={form} layout="vertical" onFinish={submit}>
      {!user&&<Form.Item name="username" label="本地账号" rules={[{required:true,message:'请输入本地账号'}]}><Input size="large" prefix={<UserOutlined/>} placeholder="请输入账号" autoComplete="username" maxLength={64}/></Form.Item>}
      {user&&<Form.Item name="oldPassword" label="当前密码" rules={[{required:true}]}><Input.Password autoComplete="current-password" maxLength={72}/></Form.Item>}
      <Form.Item name="password" label={user?'新密码（至少 6 个字符，最多 72 UTF-8 字节）':'密码'} rules={[{required:true,message:'请输入密码'}]}><Input.Password size="large" prefix={<LockOutlined/>} placeholder={user?'请输入新密码':'请输入密码'} autoComplete={user?'new-password':'current-password'} maxLength={72}/></Form.Item>
      {user&&<Form.Item name="confirm" label="确认新密码" dependencies={['password']} rules={[{required:true},({getFieldValue})=>({validator:(_,v)=>v===getFieldValue('password')?Promise.resolve():Promise.reject(new Error('两次密码不一致'))})]}><Input.Password autoComplete="new-password" maxLength={72}/></Form.Item>}
      <Button className="identity-submit" block size="large" type="primary" htmlType="submit" loading={busy}>{user?'保存并重新登录':<>登录工作台 <ArrowRightOutlined/></>}</Button>
      {user&&<Space wrap style={{marginTop:16}}><Button onClick={logout}>退出</Button>{!user.passwordChangeRequired&&<Button onClick={()=>setChanging(false)}>返回</Button>}</Space>}
    </Form>
      {!user&&<div className="identity-help">账号由平台管理员开通。<br/>如需访问权限或重置密码，请联系管理员。</div>}
    </Card><footer className="identity-access-footer">仅限授权用户访问 · 请妥善保管您的登录凭据</footer></section>
  </main>
}
