import { useEffect, useState } from 'react'
import { Alert, Button, Card, Form, Input, Space, Spin, message } from 'antd'
import { request } from './api.js'
import App from './App.jsx'

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
  return <div className="identity-login"><Card title={user?'修改密码':'Redis 运维平台登录'} style={{width:'100%',maxWidth:440}}>
    {user&&<Alert type="info" message={user.passwordChangeRequired?'首次登录或管理员重置后必须修改密码':'修改密码后需重新登录'} style={{marginBottom:20}}/>}
    <Form form={form} layout="vertical" onFinish={submit}>
      {!user&&<Form.Item name="username" label="本地账号" rules={[{required:true}]}><Input autoComplete="username" maxLength={64}/></Form.Item>}
      {user&&<Form.Item name="oldPassword" label="当前密码" rules={[{required:true}]}><Input.Password autoComplete="current-password" maxLength={72}/></Form.Item>}
      <Form.Item name="password" label={user?'新密码（至少 6 个字符，最多 72 UTF-8 字节）':'密码'} rules={[{required:true}]}><Input.Password autoComplete={user?'new-password':'current-password'} maxLength={72}/></Form.Item>
      {user&&<Form.Item name="confirm" label="确认新密码" dependencies={['password']} rules={[{required:true},({getFieldValue})=>({validator:(_,v)=>v===getFieldValue('password')?Promise.resolve():Promise.reject(new Error('两次密码不一致'))})]}><Input.Password autoComplete="new-password" maxLength={72}/></Form.Item>}
      <Space wrap><Button type="primary" htmlType="submit" loading={busy}>{user?'保存并重新登录':'登录'}</Button>{user&&<Button onClick={logout}>退出</Button>}{user&&!user.passwordChangeRequired&&<Button onClick={()=>setChanging(false)}>返回</Button>}</Space>
    </Form>
  </Card></div>
}
