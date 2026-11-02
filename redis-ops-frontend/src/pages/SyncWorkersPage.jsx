import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Descriptions, Progress, Space, Table, Tag, Tooltip } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { api } from '../api.js'
import { workerAddress, workerHealthUrl, workerRegistryStatus } from '../syncWorkerStatus.js'

const formatBytes = bytes => !Number.isFinite(bytes) || bytes < 0 ? '—'
  : bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(1)} GB`
  : bytes >= 1024 ** 2 ? `${(bytes / 1024 ** 2).toFixed(1)} MB`
  : `${Math.max(0, Math.round(bytes / 1024))} KB`
const formatPercent = value => (!Number.isFinite(value) || value < 0) ? '—' : `${value.toFixed(1)}%`

const TASK_STATUS_COLORS = {
  CHECKING: 'processing', READY: 'default', STARTING: 'processing', FULL_SYNCING: 'processing',
  INCR_SYNCING: 'processing', CAUGHT_UP: 'success', PAUSING: 'warning', PAUSED: 'warning',
  RESUMING: 'processing', STOPPING: 'warning', BLOCKED: 'error', FAILED: 'error',
  FINISHED: 'success', CANCELLED: 'default',
}

const usagePercent = (used, total) => total > 0 ? Math.min(100, Math.round(used / total * 100)) : null
const gauge = (label, percent, detail) => percent === null
  ? <Descriptions.Item key={label} label={label}>{detail}</Descriptions.Item>
  : (
    <Descriptions.Item key={label} label={label}>
      <Space direction="vertical" size={0} style={{ width: '100%' }}>
        <span className="muted">{detail}</span>
        <Progress percent={percent} size="small" status={percent >= 90 ? 'exception' : undefined} />
      </Space>
    </Descriptions.Item>
  )

function LoadPanel({ instance }) {
  const [state, setState] = useState({ loading: false, data: null, error: '' })

  const fetchLoad = useCallback(async () => {
    setState(prev => ({ ...prev, loading: true, error: '' }))
    try {
      setState({ loading: false, data: await api.syncWorkerLoad(instance), error: '' })
    } catch (error) {
      setState({ loading: false, data: null, error: error.message || '获取失败' })
    }
  }, [instance])

  // 展开即拉一次（每次展开重新挂载）；仍是按需实时获取，平台不存储。
  useEffect(() => { fetchLoad() }, [fetchLoad])

  return (
    <Card size="small" title="机器负载（实时获取，不存储）" loading={state.loading} extra={
      <Button size="small" icon={<ReloadOutlined />} loading={state.loading} onClick={fetchLoad}>刷新</Button>
    }>
      {state.error && <Alert type="warning" showIcon message={state.error} />}
      {state.data && (
        <Descriptions column={1} size="small" bordered>
          {gauge('系统 CPU', Number.isFinite(state.data.cpuSystemPercent) && state.data.cpuSystemPercent >= 0
            ? Math.round(state.data.cpuSystemPercent) : null, formatPercent(state.data.cpuSystemPercent))}
          <Descriptions.Item label="进程 CPU">{formatPercent(state.data.cpuProcessPercent)}</Descriptions.Item>
          {gauge('JVM 堆内存', usagePercent(state.data.heapUsedBytes, state.data.heapMaxBytes),
            `${formatBytes(state.data.heapUsedBytes)} / ${formatBytes(state.data.heapMaxBytes)}`)}
          {gauge('系统内存', usagePercent(
            Math.max(0, state.data.systemMemoryTotalBytes - state.data.systemMemoryFreeBytes),
            state.data.systemMemoryTotalBytes),
          `${formatBytes(state.data.systemMemoryTotalBytes - state.data.systemMemoryFreeBytes)} 已用 / ${formatBytes(state.data.systemMemoryTotalBytes)}`)}
          {gauge('磁盘（spool 目录所在文件系统）',
            usagePercent(Math.max(0, state.data.spoolTotalBytes - state.data.spoolUsableBytes),
              state.data.spoolTotalBytes),
          `${formatBytes(state.data.spoolTotalBytes - state.data.spoolUsableBytes)} 已用 / ${formatBytes(state.data.spoolTotalBytes)}，剩 ${formatBytes(state.data.spoolUsableBytes)}`)}
          <Descriptions.Item label="spool 目录占用">
            {formatBytes(state.data.spoolDirBytes ?? -1)}
            {state.data.spoolDirBytes >= 0 && state.data.spoolTotalBytes > 0
              ? `（占文件系统 ${(
                (state.data.spoolDirBytes / state.data.spoolTotalBytes) * 100).toFixed(1)}%）` : ''}
          </Descriptions.Item>
          <Descriptions.Item label="spool 目录">{state.data.spoolDir || '—'}</Descriptions.Item>
        </Descriptions>
      )}
    </Card>
  )
}

export default function SyncWorkersPage() {
  const [registry, setRegistry] = useState([])
  const [tasks, setTasks] = useState([])
  const [unavailable, setUnavailable] = useState(false)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    try {
      const [workers, runtimeRows] = await Promise.all([api.syncWorkers(), api.syncWorkerTasks()])
      setRegistry(workers)
      setTasks(runtimeRows)
      setUnavailable(false)
    } catch {
      setUnavailable(true)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
    const timer = window.setInterval(load, 15000)
    return () => window.clearInterval(timer)
  }, [load])

  const tasksByInstance = {}
  for (const row of tasks) {
    // lease_owner = <instance-id>-<UUID>; the stable name is everything before the trailing UUID.
    const instance = (row.leaseOwner || '').replace(/-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i, '')
    ;(tasksByInstance[instance] = tasksByInstance[instance] || []).push(row)
  }

  const columns = [
    {
      title: '实例', dataIndex: 'instanceName', key: 'instanceName',
      render: value => <Tooltip title="在同步任务列表中查看该实例执行的任务">
        <a onClick={() => { window.location.hash = `/syncTasks?worker=${encodeURIComponent(value)}` }}>{value}</a>
      </Tooltip>,
    },
    {
      title: '地址', key: 'address',
      render: (_, worker) => {
        const url = workerHealthUrl(worker)
        return url
          ? <a href={url} target="_blank" rel="noreferrer">{workerAddress(worker)}</a>
          : (workerAddress(worker) || '未上报')
      },
    },
    { title: '版本', dataIndex: 'version', key: 'version', render: version => version || '—' },
    {
      title: '状态', key: 'online',
      render: (_, worker) => {
        const state = workerRegistryStatus(worker, unavailable)
        return <Tag color={state.color}>{state.label}</Tag>
      },
    },
    { title: '首次注册', dataIndex: 'startedAt', key: 'startedAt', render: value => value ? new Date(value).toLocaleString() : '—' },
    { title: '最近心跳', dataIndex: 'heartbeatAt', key: 'heartbeatAt', render: value => value ? new Date(value).toLocaleString() : '—' },
    { title: '在跑任务', key: 'activeTasks', render: (_, worker) => <Tag color="blue">{(tasksByInstance[worker.instanceName] || []).length}</Tag> },
  ]

  const taskColumns = [
    {
      title: '任务', dataIndex: 'taskNo', key: 'taskNo',
      render: value => <a onClick={() => { window.location.hash = `/syncTasks?taskNo=${encodeURIComponent(value)}` }}>{value}</a>,
    },
    { title: '状态', dataIndex: 'status', key: 'status', render: status => <Tag color={TASK_STATUS_COLORS[status] || 'default'}>{status}</Tag> },
    { title: '相位', dataIndex: 'phase', key: 'phase', render: phase => phase || '—' },
    { title: 'spool 用量', dataIndex: 'spoolBytes', key: 'spoolBytes', render: value => formatBytes(value ?? -1) },
    { title: '租约到期', dataIndex: 'leaseUntil', key: 'leaseUntil', render: value => value ? new Date(value).toLocaleString() : '—' },
    { title: '指派', dataIndex: 'assignedWorker', key: 'assignedWorker', render: value => value || '自动' },
  ]

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {unavailable && <Alert type="error" showIcon message="worker 注册表获取失败" />}
      <Table
        rowKey="instanceName"
        loading={loading}
        size="middle"
        columns={columns}
        dataSource={registry}
        pagination={false}
        expandable={{
          expandedRowRender: worker => (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Table
                rowKey="taskId"
                size="small"
                columns={taskColumns}
                dataSource={tasksByInstance[worker.instanceName] || []}
                pagination={false}
                locale={{ emptyText: '该实例当前没有持有租约的任务' }}
              />
              <LoadPanel instance={worker.instanceName} />
            </Space>
          ),
        }}
      />
      <Tooltip title="离线实例在心跳超过保留期（默认 30 分钟）后由平台自动清理。">
        <span className="muted">注册表按 15 秒自动刷新；负载为点击时实时从 worker 获取，平台不存储。</span>
      </Tooltip>
    </Space>
  )
}
