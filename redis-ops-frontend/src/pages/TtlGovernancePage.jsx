import GovernanceTaskDrawer from '../components/GovernanceTaskDrawer.jsx'
import GovernancePreflightActions from '../components/GovernancePreflightActions.jsx'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Select, Space, Table, Tag, message } from 'antd'
import { api } from '../api.js'

const statusColor = { CREATED: 'default', DRY_RUN_PAUSED: 'orange', DRY_RUN: 'processing', AWAITING_APPROVAL: 'gold', APPROVED: 'blue', RUNNING: 'processing', PAUSED: 'orange', COMPLETED: 'success', CANCELLED: 'default', FAILED: 'error' }
const statusLabel = { CREATED: '待预检', DRY_RUN_PAUSED: '预检已暂停', DRY_RUN: 'Dry Run 中', AWAITING_APPROVAL: '待审批', APPROVED: '已审批', RUNNING: '执行中', PAUSED: '已暂停', COMPLETED: '已完成', CANCELLED: '已取消', FAILED: '失败' }
const terminal = ['COMPLETED', 'CANCELLED', 'FAILED']
const formatTime = value => value ? new Date(value).toLocaleString() : '-'

export default function TtlGovernancePage() {
  const [tasks, setTasks] = useState([])
  const [clusters, setClusters] = useState([])
  const [detail, setDetail] = useState(null)
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [form] = Form.useForm()
  const selectedClusterId = Form.useWatch('clusterId', form)
  const selectedCluster = useMemo(() => clusters.find(cluster => cluster.id === selectedClusterId), [clusters, selectedClusterId])

  const load = useCallback(async () => {
    const [taskResult, clusterResult] = await Promise.allSettled([api.ttlGovernanceTasks(), api.clusters({ size: 200 })])
    if (taskResult.status === 'fulfilled') setTasks(taskResult.value || [])
    if (clusterResult.status === 'fulfilled') setClusters(clusterResult.value.items || [])
    if (taskResult.status === 'rejected' && clusterResult.status === 'rejected') message.error(taskResult.reason.message)
  }, [])

  const refreshDetail = useCallback(async id => {
    try {
      const next = await api.ttlGovernanceTask(id)
      setDetail(next)
    } catch (error) {
      message.error(error.message)
    }
  }, [])

  useEffect(() => {
    load()
    const timer = setInterval(load, 5000)
    return () => clearInterval(timer)
  }, [load])

  useEffect(() => {
    const id = detail?.task?.id
    const status = detail?.task?.status
    if (!id || terminal.includes(status) || status === 'PAUSED') return undefined
    const timer = setInterval(() => refreshDetail(id), 3000)
    return () => clearInterval(timer)
  }, [detail?.task?.id, detail?.task?.status, refreshDetail])

  const show = id => refreshDetail(id)
  const runAction = async (action, id) => {
    setLoading(true)
    try {
      await action()
      message.success('操作已提交')
      await Promise.all([load(), refreshDetail(id)])
    } catch (error) {
      message.error(error.message)
    } finally {
      setLoading(false)
    }
  }
  const confirmAction = (title, content, action, id) => Modal.confirm({ title, content, okText: '确认', cancelText: '取消', onOk: () => runAction(action, id) })
  const action = task => {
    if (['CREATED', 'DRY_RUN', 'DRY_RUN_PAUSED', 'FAILED'].includes(task.status)) return <GovernancePreflightActions task={task} kind="ttl" onChanged={id => Promise.all([load(), refreshDetail(id)])} />
    if (task.status === 'AWAITING_APPROVAL') return <Button size="small" type="primary" onClick={() => confirmAction('确认审批 TTL 治理任务？', '审批只代表允许执行，仍需再次点击“开始执行”才会修改 Redis。', () => api.approveTtlGovernance(task.id, task.version), task.id)}>审批通过</Button>
    if (task.status === 'APPROVED') return <Button size="small" type="primary" onClick={() => confirmAction('确认开始修改 Redis TTL？', '任务将对当前仍然无 TTL 的 Key 执行 EXPIRE。', () => api.startTtlGovernance(task.id, task.version), task.id)}>开始执行</Button>
    if (task.status === 'RUNNING') return <Space><Button size="small" onClick={() => runAction(() => api.pauseTtlGovernance(task.id, task.version), task.id)}>暂停</Button><Button size="small" danger onClick={() => confirmAction('确认取消治理任务？', '取消后不会继续处理新的 Key，已执行的 TTL 不会回滚。', () => api.cancelTtlGovernance(task.id, task.version), task.id)}>取消</Button></Space>
    if (task.status === 'PAUSED') return <Space><Button size="small" type="primary" onClick={() => runAction(() => api.startTtlGovernance(task.id, task.version), task.id)}>恢复执行</Button><Button size="small" danger onClick={() => confirmAction('确认取消治理任务？', '已执行的 TTL 不会回滚。', () => api.cancelTtlGovernance(task.id, task.version), task.id)}>取消</Button></Space>
    return null
  }

  const create = async values => {
    try {
      await api.createTtlGovernanceTask(values)
      message.success('治理任务已创建，可运行预检或明确跳过')
      setOpen(false)
      form.resetFields()
      load()
    } catch (error) {
      message.error(error.message)
    }
  }

  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Card title="TTL 治理" extra={<Button type="primary" onClick={() => { form.resetFields(); form.setFieldsValue({ databaseNo: 0, includePattern: '*', targetTtlSeconds: 86400, scanRatePerSecond: 500, maxKeys: 100000 }); setOpen(true) }}>新建治理任务</Button>}>
      <Alert type="info" showIcon message="安全门禁" description="可预检后审批执行，或填写原因并确认跳过预检直接执行。所有执行保留限速和数量限制，可暂停或取消。" />
      <Table loading={loading} style={{ marginTop: 16 }} rowKey="id" dataSource={tasks} scroll={{ x: 1100 }} columns={[
        { title: '任务', dataIndex: 'taskNo' },
        { title: '集群', dataIndex: 'clusterId', render: value => clusters.find(cluster => cluster.id === value)?.name || `#${value}` },
        { title: '目标 TTL', dataIndex: 'targetTtlSeconds', render: value => `${value}s` },
        { title: '范围', dataIndex: 'includePattern', ellipsis: true },
        { title: '状态', dataIndex: 'status', render: value => <Tag color={statusColor[value]}>{statusLabel[value] || value}</Tag> },
        { title: '审批', dataIndex: 'approvalStatus', render: value => <Tag color={value === 'APPROVED' ? 'green' : 'default'}>{value}</Tag> },
        { title: '创建时间', dataIndex: 'createdAt', render: formatTime },
        { title: '操作', fixed: 'right', render: (_, row) => <Button size="small" onClick={() => show(row.id)}>详情</Button> }
      ]} pagination={false} />
    </Card>

    <GovernanceTaskDrawer detail={detail} clusters={clusters} kind="ttl" actions={action} onClose={() => setDetail(null)} />

    <Modal open={open} title="新建 TTL 治理任务" onCancel={() => setOpen(false)} footer={null} destroyOnHidden>
      <Form form={form} layout="vertical" onFinish={create}>
        <Form.Item name="clusterId" label="集群" rules={[{ required: true, message: '请选择集群' }]}><Select showSearch optionFilterProp="label" onChange={() => form.setFieldValue('databaseNo', 0)} options={clusters.map(cluster => ({ value: cluster.id, label: `${cluster.name} · ${cluster.mode} · ${cluster.environment}` }))} /></Form.Item>
        <Form.Item name="databaseNo" label="数据库" extra={selectedCluster?.mode === 'CLUSTER' ? 'Cluster 模式固定使用 DB 0' : 'Standalone/Sentinel 可选择 DB 0-15'}><InputNumber min={0} max={15} disabled={selectedCluster?.mode === 'CLUSTER'} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="includePattern" label="Key Pattern" rules={[{ required: true }]}><Input placeholder="例如 order:*，默认 *" /></Form.Item>
        <Form.Item name="targetTtlSeconds" label="目标 TTL（秒）" rules={[{ required: true, type: 'number', min: 1, max: 31536000 }]}><InputNumber min={1} max={31536000} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="scanRatePerSecond" label="扫描/执行速率（Key/s）" rules={[{ required: true, type: 'number', min: 1, max: 100000 }]}><InputNumber min={1} max={100000} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="maxKeys" label="最大处理 Key 数" rules={[{ required: true, type: 'number', min: 1, max: 10000000 }]}><InputNumber min={1} max={10000000} style={{ width: '100%' }} /></Form.Item>
        <Alert type="warning" showIcon message="任务创建不会立即修改 Redis" description="创建后可运行预检再审批执行，或填写原因并确认跳过预检直接执行。" />
        <Form.Item style={{ margin: '16px 0 0', textAlign: 'right' }}><Space><Button onClick={() => setOpen(false)}>取消</Button><Button type="primary" htmlType="submit">创建任务</Button></Space></Form.Item>
      </Form>
    </Modal>
  </Space>
}
