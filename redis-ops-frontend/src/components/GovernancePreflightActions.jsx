import { useState } from 'react'
import { Alert, Button, Checkbox, Form, Input, Modal, Space, message } from 'antd'
import { api } from '../api.js'

export default function GovernancePreflightActions({ task, kind, onChanged }) {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [form] = Form.useForm()
  const ttl = kind === 'ttl'
  const dryRun = ttl ? api.dryRunTtlGovernance : api.dryRunCleanupGovernance
  const pause = ttl ? api.pauseTtlGovernance : api.pauseCleanupGovernance
  const cancel = ttl ? api.cancelTtlGovernance : api.cancelCleanupGovernance
  const skip = ttl ? api.skipTtlGovernanceDryRunAndStart : api.skipCleanupGovernanceDryRunAndStart
  const invoke = async fn => {
    setBusy(true)
    try { await fn(); setOpen(false); form.resetFields(); message.success('操作已提交'); await onChanged(task.id) }
    catch (error) { message.error(error.message) }
    finally { setBusy(false) }
  }
  if (!['CREATED', 'DRY_RUN', 'DRY_RUN_PAUSED', 'FAILED'].includes(task.status)) return null
  return <>
    <Space wrap>
      {task.status === 'DRY_RUN'
        ? <Button size="small" loading={busy} onClick={() => invoke(() => pause(task.id, task.version))}>暂停预检</Button>
        : <Button size="small" loading={busy} onClick={() => invoke(() => dryRun(task.id, task.version))}>{task.status === 'DRY_RUN_PAUSED' ? '恢复预检' : '运行预检'}</Button>}
      {['CREATED', 'DRY_RUN_PAUSED'].includes(task.status) && <Button size="small" onClick={() => setOpen(true)}>{task.status === 'DRY_RUN_PAUSED' ? '跳过剩余预检并执行' : '跳过预检并执行'}</Button>}
      {['DRY_RUN', 'DRY_RUN_PAUSED'].includes(task.status) && <Button size="small" danger disabled={busy} onClick={() => Modal.confirm({ title: '确认取消预检任务？', content: '取消后不能审批或执行此任务，已有预检统计会保留。', okText: '确认取消', cancelText: '返回', onOk: () => invoke(() => cancel(task.id, task.version)) })}>取消任务</Button>}
    </Space>
    <Modal open={open} title="跳过预检并执行" okText="确认执行" cancelText="返回" confirmLoading={busy} onCancel={() => setOpen(false)} onOk={async () => {
      try { const values = await form.validateFields(); await invoke(() => skip(task.id, task.version, values.reason.trim())) }
      catch { /* validation errors are displayed inline */ }
    }} destroyOnHidden>
      <Alert type="warning" showIcon message="确认后直接提交正式执行，不再单独审批" description={ttl ? '未经完整扫描，无法确定候选数量。正式执行会从头扫描，对无 TTL 的 Key 设置过期时间，数据随后可能过期删除。' : '未经完整扫描，无法确定候选数量。正式执行会从头扫描匹配规则并删除数据；删除不可自动恢复。'} />
      <p style={{ overflowWrap: 'anywhere' }}>任务：{task.taskNo} · 集群 #{task.clusterId} · DB {task.databaseNo}<br />执行速率：{task.scanRatePerSecond} Key/s<br />规则：{task.includePattern}<br />{ttl ? `目标 TTL：${task.targetTtlSeconds} 秒；匹配检查上限：${task.maxKeys}` : `影响上限：${task.impactLimit}`}</p>
      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item name="reason" label="跳过原因（记录到审计）" rules={[{ required: true, whitespace: true, message: '请填写跳过原因' }, { max: 500 }]} extra="可填写变更单号和业务确认依据；不要填写实际 Key、密码或业务数据。"><Input.TextArea rows={3} maxLength={500} showCount /></Form.Item>
        <Form.Item name="confirmed" valuePropName="checked" rules={[{ validator: (_, value) => value ? Promise.resolve() : Promise.reject(new Error('请确认影响范围和风险')) }]}><Checkbox>我已核对范围并了解风险，授权跳过预检并执行本任务</Checkbox></Form.Item>
      </Form>
    </Modal>
  </>
}
