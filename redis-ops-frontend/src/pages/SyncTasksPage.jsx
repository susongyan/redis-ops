import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Checkbox,
  Col,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  message,
  Modal,
  Popconfirm,
  Progress,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
} from 'antd'
import {
  InfoCircleOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import { api } from '../api.js'

const editableFullApplyStatuses = new Set(['CREATED', 'CHECKING', 'READY', 'FAILED', 'BLOCKED'])
const activeStatuses = new Set([
  'CHECKING',
  'STARTING',
  'FULL_SYNCING',
  'INCR_SYNCING',
  'CAUGHT_UP',
  'PAUSING',
  'PAUSED',
  'RESUMING',
  'STOPPING',
])
const terminalStatuses = new Set(['FINISHED', 'CANCELLED'])
const statusMeta = {
  CREATED: ['已创建', 'default'],
  CHECKING: ['预检查中', 'processing'],
  READY: ['可启动', 'cyan'],
  STARTING: ['启动中', 'processing'],
  FULL_SYNCING: ['全量同步', 'blue'],
  INCR_SYNCING: ['增量同步', 'geekblue'],
  CAUGHT_UP: ['已追平', 'success'],
  PAUSING: ['暂停中', 'processing'],
  PAUSED: ['已暂停', 'warning'],
  RESUMING: ['恢复中', 'processing'],
  STOPPING: ['收尾中', 'processing'],
  BLOCKED: ['已阻塞', 'error'],
  FAILED: ['失败', 'error'],
  FINISHED: ['已完成', 'success'],
  CANCELLED: ['已取消', 'default'],
}
const mib = 1024 * 1024
const gib = 1024 * 1024 * 1024
const capabilityMeta = {
  SUPPORTED: ['直接同步', 'success'],
  TRANSFORMABLE: ['转换后同步', 'warning'],
  HARD_BLOCKED: ['硬阻塞', 'error'],
  POLICY_BLOCKED: ['策略阻塞', 'error'],
  UNKNOWN_BLOCKED: ['未知即阻塞', 'error'],
  IGNORED: ['协议忽略', 'default'],
}
const fullStageMeta = {
  RECEIVING_RDB: '接收 RDB',
  PARSING_RDB: '解析 RDB',
  RESTORING: '并发写入',
  COMPLETED: '全量完成',
}

const fullPercent = (row) => {
  if (row.status === 'COMPLETED') return 100
  if (row.stage === 'RECEIVING_RDB') {
    return row.totalBytes > 0 ? Math.min(30, Math.round(row.receivedBytes * 30 / row.totalBytes)) : 0
  }
  if (row.totalKeys != null) {
    return row.totalKeys === 0 ? 100 : Math.min(99, 50 + Math.round(row.appliedKeys * 50 / row.totalKeys))
  }
  if (row.totalBytes > 0) {
    return Math.min(49, 30 + Math.round(row.parsedBytes * 20 / row.totalBytes))
  }
  return 30
}

const bytesLabel = (value) => {
  if (value == null) return '-'
  if (value >= gib) return `${(value / gib).toFixed(1)} GiB`
  return `${(value / mib).toFixed(1)} MiB`
}

function TipLabel({ label, tip }) {
  return (
    <span className="metric-tip-label">
      {label}
      <Tooltip title={tip}>
        <InfoCircleOutlined className="metric-tip-icon" />
      </Tooltip>
    </span>
  )
}

export default function SyncTasksPage() {
  const [rows, setRows] = useState([])
  const [relations, setRelations] = useState([])
  const [clusters, setClusters] = useState([])
  const [idcs, setIdcs] = useState([])
  const [open, setOpen] = useState(false)
  const [tuning, setTuning] = useState(null)
  const [detail, setDetail] = useState(null)
  const [eventRows, setEventRows] = useState([])
  const [eventTotal, setEventTotal] = useState(0)
  const [eventPage, setEventPage] = useState(1)
  const [eventPageSize, setEventPageSize] = useState(20)
  const [channelFlash, setChannelFlash] = useState(0)
  const [metricFlash, setMetricFlash] = useState(0)
  const channelSignatureRef = useRef('')
  const metricSignatureRef = useRef('')
  const [startTask, setStartTask] = useState(null)
  const [finishTask, setFinishTask] = useState(null)
  const [capabilities, setCapabilities] = useState(null)
  const [capabilitiesOpen, setCapabilitiesOpen] = useState(false)
  const [capabilityLoading, setCapabilityLoading] = useState(false)
  const [capabilityContext, setCapabilityContext] = useState('')
  const [capabilityCategory, setCapabilityCategory] = useState()
  const [form] = Form.useForm()
  const [tuningForm] = Form.useForm()
  const [startForm] = Form.useForm()
  const [finishForm] = Form.useForm()
  const relationId = Form.useWatch('relationId', form)
  const selectedSourceClusterId = Form.useWatch('sourceClusterId', form)
  const selectedTargetClusterId = Form.useWatch('targetClusterId', form)
  const allowDestructiveCommands = Form.useWatch(
    ['commandPolicy', 'allowDestructiveCommands'],
    form,
  )
  const selectedRelation = relations.find((relation) => relation.id === relationId)
  const sourceCluster = clusters.find((cluster) => (
    cluster.id === (selectedRelation?.primaryClusterId || selectedSourceClusterId)
  ))
  const targetCluster = clusters.find((cluster) => (
    cluster.id === (selectedRelation?.standbyClusterId || selectedTargetClusterId)
  ))
  const sourceDbRequired = ['STANDALONE', 'SENTINEL'].includes(sourceCluster?.mode)
  const targetDbRequired = ['STANDALONE', 'SENTINEL'].includes(targetCluster?.mode)
  const clusterTarget = targetCluster?.mode === 'CLUSTER'

  useEffect(() => {
    if (clusterTarget && allowDestructiveCommands) {
      form.setFieldValue(['commandPolicy', 'allowDestructiveCommands'], false)
    }
  }, [clusterTarget, allowDestructiveCommands, form])

  const commandPolicy = (task) => {
    if (!task?.commandPolicyJson) return {}
    try {
      return JSON.parse(task.commandPolicyJson)
    } catch {
      return {}
    }
  }

  const blockedReasonLabel = (reason) => ({
    BLOCKED_UNSUPPORTED_COMMAND: '检测到不兼容或被策略屏蔽的 Redis 命令',
    BLOCKED_FILTER_BOUNDARY: '命令跨越 Key 过滤边界，无法保证等价同步',
    BLOCKED_REQUIRES_FULL_RESYNC: '复制积压不足，需要人工确认重新全量同步',
    BLOCKED_RESERVED_NAMESPACE: '目标端保留命名空间存在冲突',
  }[reason] || reason || '同步任务执行失败')

  const precheckChecks = (report) => {
    try {
      return JSON.parse(report?.reportJson || '{}').checks || []
    } catch {
      return []
    }
  }

  const precheckWarningCount = (report) => precheckChecks(report)
    .filter((check) => check.status === 'WARNING').length

  const channelSignature = (channels = []) => channels.map((channel) => [
    channel.channelId,
    channel.status,
    channel.receivedOffset,
    channel.appliedOffset,
    channel.lastHeartbeatAt,
  ].join(':')).join('|')

  const metricSignature = (metrics = []) => {
    const metric = metrics[0]
    return metric ? [
      metric.channelId,
      metric.estimatedLagSeconds,
      metric.offsetGapBytes,
      metric.sourceBytesPerSecond,
      metric.targetApplyBytesPerSecond,
      metric.catchUpEtaSeconds,
      metric.collectedAt,
    ].join(':') : ''
  }

  const fullProgressSignature = (progress = []) => progress.map((item) => [
    item.channelId,
    item.lane,
    item.stage,
    item.receivedBytes,
    item.parsedBytes,
    item.totalKeys,
    item.appliedKeys,
    item.appliedBytes,
    item.status,
    item.updatedAt,
  ].join(':')).join('|')

  const applyDetailSnapshot = (result, animate = true) => {
    const nextChannelSignature = channelSignature(result.channels)
    const nextMetricSignature = `${metricSignature(result.metrics)}|${fullProgressSignature(result.fullProgress)}`
    if (animate && channelSignatureRef.current && channelSignatureRef.current !== nextChannelSignature) {
      setChannelFlash((value) => value + 1)
    }
    if (animate && metricSignatureRef.current && metricSignatureRef.current !== nextMetricSignature) {
      setMetricFlash((value) => value + 1)
    }
    channelSignatureRef.current = nextChannelSignature
    metricSignatureRef.current = nextMetricSignature
    setDetail(result)
  }

  const load = async () => {
    try {
      const [tasks, relationRows, clusterPage, idcRows] = await Promise.all([
        api.syncTasks(),
        api.relations(),
        api.clusters({ page: 1, size: 200 }),
        api.idcs(),
      ])
      setRows(tasks)
      setRelations(relationRows)
      setClusters(clusterPage.items)
      setIdcs(idcRows)
    } catch (error) {
      message.error(error.message)
    }
  }

  useEffect(() => {
    load()
  }, [])

  useEffect(() => {
    if (!rows.some((task) => activeStatuses.has(task.status))) return undefined
    const timer = window.setInterval(async () => {
      try {
        const tasks = await api.syncTasks()
        setRows(tasks)
        if (detail?.task?.id) applyDetailSnapshot(await api.syncTask(detail.task.id))
      } catch {
        // Keep the last usable snapshot; explicit actions still surface errors.
      }
    }, 5000)
    return () => window.clearInterval(timer)
  }, [rows, detail?.task?.id])

  const name = (id) => clusters.find((cluster) => cluster.id === id)?.name || id
  const mode = (id) => clusters.find((cluster) => cluster.id === id)?.mode
  const clusterLabel = (id) => {
    const cluster = clusters.find((item) => item.id === id)
    const idc = idcs.find((item) => item.id === cluster?.idcId)
    return cluster
      ? `${cluster.name}（${idc ? `${idc.regionName} / ${idc.name}` : '未配置机房'}）`
      : id
  }

  const create = async () => {
    try {
      const values = await form.validateFields()
      const payload = {
        ...values,
        sourceDb: sourceDbRequired ? values.sourceDb : 0,
        targetDb: targetDbRequired ? values.targetDb : 0,
        bandwidthLimitBytesPerSecond: values.bandwidthLimitMiB * mib,
        spoolLimitBytes: values.spoolLimitGiB * gib,
      }
      delete payload.bandwidthLimitMiB
      delete payload.spoolLimitGiB
      if (payload.relationId) {
        delete payload.sourceClusterId
        delete payload.targetClusterId
        payload.purpose = 'DISASTER_RECOVERY'
      }
      await api.createSyncTask(payload)
      message.success('同步任务已创建')
      setOpen(false)
      load()
    } catch (error) {
      if (!error.errorFields) message.error(error.message)
    }
  }

  const openCreate = () => {
    form.resetFields()
    form.setFieldsValue({
      purpose: 'ADHOC',
      syncMode: 'FULL_AND_INCREMENTAL',
      sourceDb: 0,
      targetDb: 0,
      rateLimitOps: 50000,
      bandwidthLimitMiB: 100,
      spoolLimitGiB: 50,
      fullApplyConcurrency: 4,
      fullApplyPipelineSize: 100,
      commandPolicy: {
        allowDestructiveCommands: false,
        allowSafeSplit: true,
        additionalBlockedCommands: [],
      },
    })
    setOpen(true)
  }

  const showCapabilities = async (targetMode = targetCluster?.mode, policy, context = '新建任务') => {
    try {
      if (!targetMode) {
        message.warning('请先选择目标集群')
        return
      }
      setCapabilityLoading(true)
      const values = policy || form.getFieldValue('commandPolicy') || {}
      setCapabilities(await api.syncCommandCapabilities(targetMode, values))
      setCapabilityContext(`${context} · ${targetMode}`)
      setCapabilityCategory(undefined)
      setCapabilitiesOpen(true)
    } catch (error) {
      message.error(error.message)
    } finally {
      setCapabilityLoading(false)
    }
  }

  const openTuning = (task) => {
    setTuning(task)
    tuningForm.setFieldsValue({
      rateLimitOps: task.rateLimitOps,
      bandwidthLimitMiB: task.bandwidthLimitBytesPerSecond / mib,
      spoolLimitGiB: task.spoolLimitBytes / gib,
      fullApplyConcurrency: task.fullApplyConcurrency,
      fullApplyPipelineSize: task.fullApplyPipelineSize,
    })
  }

  const saveTuning = async () => {
    try {
      const values = await tuningForm.validateFields()
      await api.updateSyncLimits(tuning.id, tuning.version, {
        rateLimitOps: values.rateLimitOps,
        bandwidthLimitBytesPerSecond: values.bandwidthLimitMiB * mib,
        spoolLimitBytes: values.spoolLimitGiB * gib,
        fullApplyConcurrency: values.fullApplyConcurrency,
        fullApplyPipelineSize: values.fullApplyPipelineSize,
      })
      message.success('同步参数已更新')
      setTuning(null)
      load()
    } catch (error) {
      if (!error.errorFields) message.error(error.message)
    }
  }

  const show = async (task) => {
    try {
      const result = await api.syncTask(task.id)
      applyDetailSnapshot(result, false)
      setChannelFlash(0)
      setMetricFlash(0)
      setEventRows(result.events || [])
      setEventTotal(result.eventTotal || 0)
      setEventPage(1)
      setEventPageSize(20)
    } catch (error) {
      message.error(error.message)
    }
  }

  const loadEventPage = async (page, size) => {
    if (!detail?.task?.id) return
    try {
      const result = await api.syncTaskEvents(detail.task.id, page, size)
      setEventRows(result.items)
      setEventTotal(result.total)
      setEventPage(result.page)
      setEventPageSize(result.size)
    } catch (error) {
      message.error(error.message)
    }
  }

  const refreshAfterAction = async (taskId, successMessage) => {
    message.success(successMessage)
    await load()
    if (detail?.task?.id === taskId) applyDetailSnapshot(await api.syncTask(taskId))
  }

  const runAction = async (task, action, successMessage) => {
    try {
      await action(task.id, task.version)
      await refreshAfterAction(task.id, successMessage)
    } catch (error) {
      message.error(error.message)
    }
  }

  const openStart = (task) => {
    startForm.resetFields()
    startForm.setFieldsValue({ confirmationTaskNo: '' })
    setStartTask(task)
  }

  const submitStart = async () => {
    try {
      const values = await startForm.validateFields()
      await api.startSyncTask(startTask.id, startTask.version, {
        writeFenced: values.writeFenced,
        writeFenceNote: values.writeFenceNote,
        allowTargetFlush: values.allowTargetFlush,
        confirmationTaskNo: values.confirmationTaskNo,
      })
      setStartTask(null)
      await refreshAfterAction(startTask.id, '启动命令已提交')
    } catch (error) {
      if (!error.errorFields) message.error(error.message)
    }
  }

  const openFinish = (task) => {
    finishForm.resetFields()
    setFinishTask(task)
  }

  const submitFinish = async () => {
    try {
      const values = await finishForm.validateFields()
      await api.finishSyncTask(finishTask.id, finishTask.version, {
        sourceWriteFenced: values.sourceWriteFenced,
        sourceFenceNote: values.sourceFenceNote,
      })
      setFinishTask(null)
      await refreshAfterAction(finishTask.id, '结束命令已提交，将等待最终 offset 追平')
    } catch (error) {
      if (!error.errorFields) message.error(error.message)
    }
  }

  const lifecycleActions = (task) => (
    <Space wrap>
      <Button onClick={() => { window.location.hash = `/validations?syncTaskId=${task.id}` }}>数据校验</Button>
      {['CREATED', 'READY', 'FAILED', 'BLOCKED'].includes(task.status) && (
        <Button onClick={() => runAction(task, api.precheckSyncTask, '预检查命令已提交')}>
          {task.status === 'READY' ? '重新预检' : '预检查'}
        </Button>
      )}
      {task.status === 'READY' && <Button type="primary" onClick={() => openStart(task)}>启动</Button>}
      {['FULL_SYNCING', 'INCR_SYNCING', 'CAUGHT_UP'].includes(task.status) && (
        <Button onClick={() => runAction(task, api.pauseSyncTask, '暂停命令已提交')}>暂停</Button>
      )}
      {['PAUSED', 'BLOCKED'].includes(task.status) && (
        <Button type="primary" onClick={() => runAction(task, api.resumeSyncTask, '恢复命令已提交')}>
          恢复
        </Button>
      )}
      {['INCR_SYNCING', 'CAUGHT_UP'].includes(task.status) && (
        <Button onClick={() => openFinish(task)}>结束同步</Button>
      )}
      {!terminalStatuses.has(task.status) && (
        <Popconfirm
          title="确认取消同步任务？"
          description="取消会立即停止任务，诊断与事件记录仍会保留。"
          onConfirm={() => runAction(task, api.cancelSyncTask, '任务已取消')}
        >
          <Button danger>取消</Button>
        </Popconfirm>
      )}
    </Space>
  )

  const renderStatus = (value) => {
    const [label, color] = statusMeta[value] || [value, 'default']
    return <Tag color={color}>{label}</Tag>
  }

  const normalizeBlockedCommands = (commands = []) => [...new Set(commands
    .map((command) => command.trim().toUpperCase())
    .filter(Boolean))]

  const visibleCapabilities = (capabilities?.commands || [])
    .filter((item) => !capabilityCategory || item.category === capabilityCategory)

  const capabilityCounts = (capabilities?.commands || []).reduce((counts, item) => ({
    ...counts,
    [item.category]: (counts[item.category] || 0) + 1,
  }), {})

  const columns = [
    {
      title: '任务号',
      dataIndex: 'taskNo',
      render: (value, task) => <Button type="link" onClick={() => show(task)}>{value}</Button>,
    },
    { title: '类型', dataIndex: 'purpose' },
    {
      title: '关系',
      dataIndex: 'relationId',
      render: (id) => relations.find((relation) => relation.id === id)?.name || '-',
    },
    {
      title: '方向',
      render: (_, task) => `${name(task.sourceClusterId)} → ${name(task.targetClusterId)}`,
    },
    {
      title: '全量并发',
      render: (_, task) => `${task.fullApplyConcurrency} × ${task.fullApplyPipelineSize}`,
    },
    {
      title: '状态',
      dataIndex: 'status',
      render: renderStatus,
    },
    {
      title: <TipLabel label="RPO" tip="目标端相对源端的数据时间延迟；0 秒表示当前采样时刻已追平。" />,
      dataIndex: 'lastRpoSeconds',
      render: (value) => value == null ? '-' : `${value}s`,
    },
    {
      title: '操作',
      render: (_, task) => (
        <Space>
          <Button onClick={() => show(task)}>管理</Button>
          <Button icon={<SettingOutlined />} onClick={() => openTuning(task)}>参数</Button>
        </Space>
      ),
    },
  ]

  const fullApplyEditable = !tuning || editableFullApplyStatuses.has(tuning.status)

  return (
    <>
      <div className="toolbar">
        <div className="muted">全量阶段支持任务级并发 RESTORE；增量阶段保持 offset 有序提交</div>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增同步任务</Button>
      </div>
      <Table rowKey="id" dataSource={rows} columns={columns} scroll={{ x: 1100 }} />

      <Modal
        title="新增同步任务"
        width={760}
        open={open}
        okText="创建任务"
        cancelText="取消"
        styles={{ body: { maxHeight: '72vh', overflowY: 'auto', paddingRight: 8 } }}
        onCancel={() => setOpen(false)}
        onOk={create}
      >
        <Form form={form} layout="vertical">
          <Form.Item name="relationId" label="长期主备关系（可选）">
            <Select
              allowClear
              onChange={(value) => value && form.setFieldsValue({
                sourceClusterId: undefined,
                targetClusterId: undefined,
                purpose: 'DISASTER_RECOVERY',
              })}
              options={relations
                .filter((relation) => relation.status === 'ACTIVE')
                .map((relation) => ({ value: relation.id, label: relation.name }))}
            />
          </Form.Item>
          {selectedRelation ? (
            <Form.Item label="同步方向" extra="源和目标由主备关系确定，无需重复选择">
              <Input
                readOnly
                value={`${clusterLabel(selectedRelation.primaryClusterId)} → ${clusterLabel(selectedRelation.standbyClusterId)}`}
              />
            </Form.Item>
          ) : (
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item name="sourceClusterId" label="临时任务源集群" rules={[{ required: true }]}>
                  <Select
                    showSearch
                    optionFilterProp="label"
                    options={clusters.map((cluster) => ({
                      value: cluster.id,
                      label: clusterLabel(cluster.id),
                    }))}
                  />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="targetClusterId" label="临时任务目标集群" rules={[{ required: true }]}>
                  <Select
                    showSearch
                    optionFilterProp="label"
                    options={clusters.map((cluster) => ({
                      value: cluster.id,
                      label: clusterLabel(cluster.id),
                    }))}
                  />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="purpose" label="任务用途" rules={[{ required: true }]}>
                  <Select options={['MIGRATION', 'ADHOC'].map((value) => ({ value }))} />
                </Form.Item>
              </Col>
            </Row>
          )}

          {(sourceDbRequired || targetDbRequired) && <Row gutter={16}>
            {sourceDbRequired && <Col span={12}>
              <Form.Item name="sourceDb" label="源 DB" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>}
            {targetDbRequired && <Col span={12}>
              <Form.Item name="targetDb" label="目标 DB" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>}
          </Row>}

          <h3>全量应用</h3>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="fullApplyConcurrency"
                label="RESTORE 并发连接数"
                extra="建议从 4 开始，根据目标 Redis CPU 和网络调整"
                rules={[{ required: true }]}
              >
                <InputNumber min={1} max={64} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="fullApplyPipelineSize"
                label="单连接 Pipeline"
                extra="单批最多发送的 RESTORE 数量"
                rules={[{ required: true }]}
              >
                <InputNumber min={1} max={10000} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>

          <h3>限速与存储</h3>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item name="rateLimitOps" label="最大 ops/s" rules={[{ required: true }]}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="bandwidthLimitMiB" label="带宽 MiB/s" rules={[{ required: true }]}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="spoolLimitGiB" label="Spool 上限 GiB" rules={[{ required: true }]}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <section className="sync-command-policy-section">
            <div className="sync-command-policy-header">
              <div>
                <h3>命令兼容策略</h3>
                <div className="muted">策略会随任务固化，任务创建后不再跟随平台默认值变化</div>
              </div>
              <Button
                icon={<SafetyCertificateOutlined />}
                loading={capabilityLoading}
                onClick={() => showCapabilities()}
              >
                查看完整命令清单
              </Button>
            </div>
            <Alert
              type="info"
              showIcon
              message="未知命令和无法安全转换的命令始终阻塞任务"
              description="配置只能收紧策略，或显式允许已知的安全拆分和危险命令；硬阻塞命令不能放开。"
              style={{ marginBottom: 16 }}
            />
            <Row gutter={16}>
              <Col xs={24} md={12}>
                <div className="sync-policy-option">
                  <Form.Item
                    name={['commandPolicy', 'allowSafeSplit']}
                    valuePropName="checked"
                    noStyle
                  >
                    <Checkbox>允许安全拆分多 Key 命令</Checkbox>
                  </Form.Item>
                  <div className="sync-policy-option-help">
                    MSET、DEL、UNLINK 可按 Key/Slot 拆分，但不保留跨 Key 原子性。
                  </div>
                </div>
              </Col>
              <Col xs={24} md={12}>
                <div className={`sync-policy-option ${clusterTarget ? 'sync-policy-option-disabled' : ''}`}>
                  <Form.Item
                    name={['commandPolicy', 'allowDestructiveCommands']}
                    valuePropName="checked"
                    noStyle
                  >
                    <Checkbox disabled={clusterTarget}>允许 FLUSHDB / FLUSHALL</Checkbox>
                  </Form.Item>
                  <div className="sync-policy-option-help">
                    {clusterTarget
                      ? 'Cluster 目标始终禁止，无法在增量流中原子清空全部 Master。'
                      : '默认禁止；仅在确认源端清空操作应同步到目标时启用。'}
                  </div>
                </div>
              </Col>
            </Row>
            {allowDestructiveCommands && !clusterTarget && (
              <Alert
                type="warning"
                showIcon
                icon={<WarningOutlined />}
                message="已允许危险清空命令"
                description="同步流中的 FLUSHDB / FLUSHALL 会清空目标 DB，请确认这符合迁移或容灾语义。"
                style={{ marginTop: 12 }}
              />
            )}
            <Form.Item
              name={['commandPolicy', 'additionalBlockedCommands']}
              label="额外屏蔽命令"
              extra="输入命令名后回车，最多 100 个；只会进一步收紧，不会覆盖硬阻塞规则"
              rules={[
                {
                  validator: (_, commands = []) => commands.length <= 100
                    ? Promise.resolve()
                    : Promise.reject(new Error('最多配置 100 个额外屏蔽命令')),
                },
                {
                  validator: (_, commands = []) => commands.every(
                    (command) => /^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(command),
                  )
                    ? Promise.resolve()
                    : Promise.reject(new Error('命令名只能包含字母、数字、下划线和连字符')),
                },
              ]}
            >
              <Select
                mode="tags"
                tokenSeparators={[',', ' ']}
                placeholder="例如 DEL、EXPIRE"
                onChange={(commands) => form.setFieldValue(
                  ['commandPolicy', 'additionalBlockedCommands'],
                  normalizeBlockedCommands(commands),
                )}
              />
            </Form.Item>
          </section>
          <Form.Item name="syncMode" hidden><Input /></Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`同步参数 · ${tuning?.taskNo || ''}`}
        width={640}
        open={!!tuning}
        onCancel={() => setTuning(null)}
        onOk={saveTuning}
      >
        <Form form={tuningForm} layout="vertical">
          {!fullApplyEditable && (
            <div className="muted" style={{ marginBottom: 16 }}>
              任务已经启动，全量并发参数不可修改；运行限速仍可调整。
            </div>
          )}
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="fullApplyConcurrency" label="RESTORE 并发连接数" rules={[{ required: true }]}>
                <InputNumber disabled={!fullApplyEditable} min={1} max={64} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="fullApplyPipelineSize" label="单连接 Pipeline" rules={[{ required: true }]}>
                <InputNumber disabled={!fullApplyEditable} min={1} max={10000} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="rateLimitOps" label="最大 ops/s" rules={[{ required: true }]}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="bandwidthLimitMiB" label="带宽 MiB/s" rules={[{ required: true }]}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="spoolLimitGiB" label="Spool GiB" rules={[{ required: true }]}>
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      <Modal
        title={`启动全量同步 · ${startTask?.taskNo || ''}`}
        open={!!startTask}
        width={620}
        okText="确认清空目标并启动"
        okButtonProps={{ danger: true }}
        onCancel={() => setStartTask(null)}
        onOk={submitStart}
      >
        <Alert
          type="warning"
          showIcon
          message="该操作会清空目标端所选 DB"
          description="启动前必须确认目标写入已经隔离，并输入任务号进行二次确认。预检查结果需在 10 分钟有效期内。"
          style={{ marginBottom: 16 }}
        />
        <Form form={startForm} layout="vertical">
          <Form.Item
            name="writeFenced"
            valuePropName="checked"
            rules={[{ validator: (_, value) => value ? Promise.resolve() : Promise.reject(new Error('请确认目标写入已隔离')) }]}
          >
            <Checkbox>目标端业务写入已经隔离</Checkbox>
          </Form.Item>
          <Form.Item name="writeFenceNote" label="写隔离依据" rules={[{ required: true, whitespace: true }]}>
            <Input placeholder="例如：change-ticket-123" />
          </Form.Item>
          <Form.Item
            name="allowTargetFlush"
            valuePropName="checked"
            rules={[{ validator: (_, value) => value ? Promise.resolve() : Promise.reject(new Error('请确认允许清空目标')) }]}
          >
            <Checkbox>我确认允许清空目标 DB</Checkbox>
          </Form.Item>
          <Form.Item
            name="confirmationTaskNo"
            label={`请输入任务号 ${startTask?.taskNo || ''}`}
            rules={[
              { required: true },
              { validator: (_, value) => value === startTask?.taskNo
                ? Promise.resolve()
                : Promise.reject(new Error('任务号不一致')) },
            ]}
          >
            <Input />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`结束同步 · ${finishTask?.taskNo || ''}`}
        open={!!finishTask}
        width={560}
        okText="等待追平并结束"
        onCancel={() => setFinishTask(null)}
        onOk={submitFinish}
      >
        <Alert
          type="info"
          showIcon
          message="Worker 将获取最终源 offset，等待目标应用完成后结束"
          style={{ marginBottom: 16 }}
        />
        <Form form={finishForm} layout="vertical">
          <Form.Item
            name="sourceWriteFenced"
            valuePropName="checked"
            rules={[{ validator: (_, value) => value ? Promise.resolve() : Promise.reject(new Error('请确认源端写入已隔离')) }]}
          >
            <Checkbox>源端业务写入已经停止并隔离</Checkbox>
          </Form.Item>
          <Form.Item name="sourceFenceNote" label="源端写隔离依据" rules={[{ required: true, whitespace: true }]}>
            <Input placeholder="例如：change-ticket-456" />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={detail?.task?.taskNo}
        extra={detail?.task && lifecycleActions(detail.task)}
        open={!!detail}
        width={980}
        onClose={() => setDetail(null)}
      >
        {detail?.task && (
          <>
            {(detail.task.blockedReason || detail.task.lastError) && (
              <Alert
                type="error"
                showIcon
                message={blockedReasonLabel(detail.task.blockedReason)}
                description={detail.task.lastError}
                style={{ marginBottom: 16 }}
              />
            )}
            <Descriptions
              bordered
              column={2}
              items={[
                { key: 'direction', label: '同步方向', children: `${name(detail.task.sourceClusterId)} → ${name(detail.task.targetClusterId)}` },
                { key: 'status', label: '状态', children: renderStatus(detail.task.status) },
                { key: 'db', label: 'DB 映射', children: `${detail.task.sourceDb} → ${detail.task.targetDb}` },
                { key: 'desired', label: '期望动作', children: detail.task.desiredAction || '-' },
                { key: 'concurrency', label: 'RESTORE 并发', children: detail.task.fullApplyConcurrency },
                { key: 'pipeline', label: 'Pipeline', children: detail.task.fullApplyPipelineSize },
                { key: 'rate', label: '最大 ops/s', children: detail.task.rateLimitOps },
                { key: 'bandwidth', label: '带宽', children: `${detail.task.bandwidthLimitBytesPerSecond / mib} MiB/s` },
                {
                  key: 'commandPolicy',
                  label: '命令策略',
                  children: (() => {
                    const policy = commandPolicy(detail.task)
                    return (
                      <Space wrap>
                        <Tag color={policy.allowSafeSplit === false ? 'default' : 'blue'}>
                          安全拆分：{policy.allowSafeSplit === false ? '关闭' : '开启'}
                        </Tag>
                        <Tag color={policy.allowDestructiveCommands ? 'warning' : 'success'}>
                          清空命令：{policy.allowDestructiveCommands ? '允许' : '禁止'}
                        </Tag>
                        <Tag>额外屏蔽：{(policy.additionalBlockedCommands || []).length}</Tag>
                        <Button
                          type="link"
                          size="small"
                          onClick={() => showCapabilities(
                            mode(detail.task.targetClusterId),
                            policy,
                            detail.task.taskNo,
                          )}
                        >
                          查看清单
                        </Button>
                      </Space>
                    )
                  })(),
                },
              ]}
            />
          </>
        )}
        <h3>预检查</h3>
        {detail?.precheck ? (
          <>
            <Descriptions
              bordered
              size="small"
              column={3}
              items={[
                {
                  key: 'status',
                  label: '结果',
                  children: (
                    <Space>
                      <Tag color={detail.precheck.status === 'PASSED' ? 'success' : 'error'}>
                        {detail.precheck.status}
                      </Tag>
                      {precheckWarningCount(detail.precheck) > 0 && (
                        <Tag color="warning">{precheckWarningCount(detail.precheck)} 项风险提示</Tag>
                      )}
                    </Space>
                  ),
                },
                { key: 'checkedAt', label: '检查时间', children: detail.precheck.checkedAt },
                { key: 'validUntil', label: '有效期至', children: detail.precheck.validUntil },
              ]}
            />
            <Table
              rowKey="name"
              size="small"
              pagination={false}
              style={{ marginTop: 12 }}
              dataSource={precheckChecks(detail.precheck)}
              columns={[
                { title: '检查项', dataIndex: 'name', width: 210 },
                {
                  title: '结果',
                  dataIndex: 'status',
                  width: 100,
                  render: (value) => <Tag color={value === 'PASSED' ? 'success' : value === 'WARNING' ? 'warning' : 'error'}>{value}</Tag>,
                },
                {
                  title: '说明',
                  render: (_, row) => (
                    <div>
                      <div>{row.message}</div>
                      {(row.risks || []).map((risk) => (
                        <Tag color="warning" key={risk.command} style={{ marginTop: 6 }}>
                          {risk.command} · 历史 {risk.calls} 次 · {risk.reason}
                        </Tag>
                      ))}
                    </div>
                  ),
                },
              ]}
            />
          </>
        ) : <div className="muted">尚未执行预检查</div>}

        <h3 className="sync-live-heading">
          全量同步进度
          {metricFlash > 0 && detail?.fullProgress?.length > 0 && (
            <span key={`full-${metricFlash}`} className="sync-update-pulse" aria-label="全量同步进度已更新" />
          )}
        </h3>
        {detail?.fullProgress?.length > 0 ? (() => {
          const channels = detail.fullProgress.filter((item) => item.lane === -1)
          const lanes = detail.fullProgress.filter((item) => item.lane >= 0)
          const overall = channels.length === 0
            ? 0
            : Math.round(channels.reduce((sum, item) => sum + fullPercent(item), 0) / channels.length)
          const failed = channels.some((item) => item.status === 'FAILED')
          const completed = channels.length > 0 && channels.every((item) => item.status === 'COMPLETED')
          return (
            <div
              key={`full-progress-${metricFlash}`}
              className={`sync-live-panel ${metricFlash > 0 ? 'sync-live-panel-updated' : ''}`}
            >
              <Descriptions
                bordered
                size="small"
                column={3}
                items={[
                  {
                    key: 'overall',
                    label: <TipLabel label="总体进度" tip="接收 RDB 占前 30%，解析占 20%，并发 RESTORE 写入占后 50%；多源通道取平均值。" />,
                    children: (
                      <Progress
                        percent={overall}
                        size="small"
                        status={failed ? 'exception' : completed ? 'success' : 'active'}
                      />
                    ),
                  },
                  { key: 'channels', label: '源通道数', children: channels.length },
                  {
                    key: 'applied',
                    label: '已写入 Key',
                    children: channels.reduce((sum, item) => sum + item.appliedKeys, 0),
                  },
                ]}
              />
              <Table
                style={{ marginTop: 12 }}
                rowKey="channelId"
                size="small"
                pagination={false}
                dataSource={channels}
                columns={[
                  { title: '源通道', dataIndex: 'channelId' },
                  {
                    title: '阶段',
                    render: (_, row) => (
                      <Tag color={row.status === 'FAILED' ? 'error' : row.status === 'COMPLETED' ? 'success' : 'processing'}>
                        {fullStageMeta[row.stage] || row.stage}
                      </Tag>
                    ),
                  },
                  {
                    title: <TipLabel label="通道进度" tip="固定长度 RDB 显示接收字节进度；解析完成后根据 RESTORE Key 数显示写入进度。" />,
                    width: 190,
                    render: (_, row) => <Progress percent={fullPercent(row)} size="small" />,
                  },
                  {
                    title: 'RDB 接收',
                    render: (_, row) => `${bytesLabel(row.receivedBytes)} / ${bytesLabel(row.totalBytes)}`,
                  },
                  { title: '已解析 Key', dataIndex: 'parsedKeys' },
                  {
                    title: '已写入 Key',
                    render: (_, row) => `${row.appliedKeys}${row.totalKeys == null ? '' : ` / ${row.totalKeys}`}`,
                  },
                  { title: '更新时间', dataIndex: 'updatedAt' },
                ]}
              />
              {lanes.length > 0 && (
                <>
                  <h4>RESTORE 并发 Lane</h4>
                  <Table
                    rowKey={(row) => `${row.channelId}-${row.lane}`}
                    size="small"
                    pagination={false}
                    dataSource={lanes}
                    columns={[
                      { title: '源通道', dataIndex: 'channelId' },
                      { title: 'Lane', dataIndex: 'lane', render: (lane) => `Lane ${lane + 1}` },
                      { title: '已写入 Key', dataIndex: 'appliedKeys' },
                      { title: '写入数据量', dataIndex: 'appliedBytes', render: bytesLabel },
                      {
                        title: '状态',
                        dataIndex: 'status',
                        render: (status) => <Tag color={status === 'FAILED' ? 'error' : status === 'COMPLETED' ? 'success' : 'processing'}>{status}</Tag>,
                      },
                      { title: '更新时间', dataIndex: 'updatedAt' },
                    ]}
                  />
                </>
              )}
            </div>
          )
        })() : <div className="muted">全量同步启动后显示 RDB 接收、解析和并发 RESTORE 进度</div>}

        <h3>运行实例</h3>
        {detail?.runtime ? (
          <Descriptions
            bordered
            size="small"
            column={3}
            items={[
              { key: 'phase', label: '阶段', children: detail.runtime.phase },
              { key: 'owner', label: 'Worker', children: detail.runtime.leaseOwner },
              {
                key: 'generation',
                label: <TipLabel label="租约 Generation" tip="Worker 从 MySQL 领取运行租约时递增的代次；代次越大表示接管越新。" />,
                children: detail.runtime.fencingGeneration,
              },
              {
                key: 'targetFence',
                label: <TipLabel label="目标 Fence" tip="已发布到目标 Redis 的写入代次。只有与该代次和运行实例一致的事务才能提交。" />,
                children: detail.runtime.targetFenceGeneration ?? '-',
              },
              {
                key: 'lease',
                label: <TipLabel label="租约至" tip="当前 Worker 在 MySQL 中的执行权截止时间；到期且未续租后允许新 Worker 接管。" />,
                children: detail.runtime.leaseUntil || '-',
              },
              {
                key: 'heartbeat',
                label: <TipLabel label="Worker 心跳" tip="由 Sync Worker 在成功续租时写入，反映控制面最后一次确认该运行实例存活的时间。" />,
                children: detail.runtime.heartbeatAt || '-',
              },
              {
                key: 'fenceAt',
                label: 'Fence 发布时间',
                children: detail.runtime.fencePublishedAt || '-',
              },
              {
                key: 'recovery',
                label: <TipLabel label="恢复来源" tip="接管时优先使用本地加密 Spool；不可用时从目标 Checkpoint 发起 PSYNC。" />,
                children: detail.runtime.recoveryAction || '-',
              },
              {
                key: 'takeovers',
                label: '接管次数',
                children: detail.runtime.takeoverCount ?? 0,
              },
              {
                key: 'spool',
                label: <TipLabel label="Spool" tip="Worker 本地加密保存、已从源端接收但可能尚未应用到目标端的数据量。" />,
                children: `${(detail.runtime.spoolBytes / mib).toFixed(1)} MiB`,
              },
            ]}
          />
        ) : <div className="muted">当前没有运行实例</div>}

        <h3 className="sync-live-heading">
          同步通道
          {channelFlash > 0 && (
            <span key={channelFlash} className="sync-update-pulse" aria-label="同步通道数据已更新" />
          )}
        </h3>
        <div
          key={`channels-${channelFlash}`}
          className={`sync-live-panel ${channelFlash > 0 ? 'sync-live-panel-updated' : ''}`}
        >
          <Table
            rowKey="channelId"
            size="small"
            pagination={false}
            dataSource={detail?.channels || []}
            columns={[
              { title: '通道', dataIndex: 'channelId' },
              { title: '状态', dataIndex: 'status' },
              {
                title: <TipLabel label="Replication ID" tip="Redis 主节点复制流的唯一标识；主从切换或全量重同步时可能变化。" />,
                dataIndex: 'replicationId',
                ellipsis: true,
              },
              {
                title: <TipLabel label="接收 Offset" tip="Worker 已从源 Redis 持久化接收的复制流字节位置。" />,
                dataIndex: 'receivedOffset',
              },
              {
                title: <TipLabel label="应用 Offset" tip="已成功写入目标端并提交 checkpoint 的复制流字节位置。" />,
                dataIndex: 'appliedOffset',
              },
              {
                title: <TipLabel label="积压" tip="接收 Offset 减去应用 Offset，表示已接收但尚未落到目标端的字节数。" />,
                render: (_, row) => row.receivedOffset - row.appliedOffset,
              },
              {
                title: <TipLabel label="心跳" tip="该复制通道最后一次向平台报告存活状态的时间。" />,
                dataIndex: 'lastHeartbeatAt',
              },
            ]}
          />
        </div>

        <h3 className="sync-live-heading">
          最新指标
          {metricFlash > 0 && (
            <span key={metricFlash} className="sync-update-pulse" aria-label="同步指标已更新" />
          )}
        </h3>
        <div
          key={`metrics-${metricFlash}`}
          className={`sync-live-panel ${metricFlash > 0 ? 'sync-live-panel-updated' : ''}`}
        >
          {detail?.metrics?.[0] ? (
            <Descriptions
              bordered
              size="small"
              column={3}
              items={[
                {
                  key: 'rpo',
                  label: <TipLabel label="RPO" tip="Recovery Point Objective 的实时观测值，表示目标端相对源端的数据时间延迟。" />,
                  children: (detail.metrics[0].timestampLagSeconds ?? detail.metrics[0].estimatedLagSeconds) == null
                    ? '-'
                    : `${detail.metrics[0].timestampLagSeconds ?? detail.metrics[0].estimatedLagSeconds}s`,
                },
                {
                  key: 'gap',
                  label: <TipLabel label="Offset Gap" tip="源端已接收 Offset 与目标端已应用 Offset 的差值，单位为字节。" />,
                  children: detail.metrics[0].offsetGapBytes,
                },
                {
                  key: 'source',
                  label: <TipLabel label="源吞吐" tip="Worker 当前从源 Redis 接收复制流的速度。" />,
                  children: `${(detail.metrics[0].sourceBytesPerSecond / mib).toFixed(1)} MiB/s`,
                },
                {
                  key: 'target',
                  label: <TipLabel label="目标应用" tip="Worker 当前向目标 Redis 成功应用数据的速度。" />,
                  children: `${(detail.metrics[0].targetApplyBytesPerSecond / mib).toFixed(1)} MiB/s`,
                },
                {
                  key: 'eta',
                  label: <TipLabel label="追平 ETA" tip="按当前净追赶速度估算清空 Offset Gap 还需多久；无法可靠估算时显示“-”。" />,
                  children: detail.metrics[0].catchUpEtaSeconds == null ? '-' : `${detail.metrics[0].catchUpEtaSeconds}s`,
                },
                {
                  key: 'collected',
                  label: <TipLabel label="采集时间" tip="本组指标在 Worker 侧完成计算并写入平台的时间。" />,
                  children: detail.metrics[0].collectedAt,
                },
              ]}
            />
          ) : <div className="muted">暂无运行指标</div>}
        </div>
        <h3>任务事件</h3>
        <Table
          rowKey="id"
          pagination={{
            current: eventPage,
            pageSize: eventPageSize,
            total: eventTotal,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
            onChange: loadEventPage,
          }}
          dataSource={eventRows}
          columns={[
            { title: '时间', dataIndex: 'createdAt' },
            { title: '原状态', dataIndex: 'fromStatus' },
            { title: '新状态', dataIndex: 'toStatus' },
            { title: '操作人', dataIndex: 'operator' },
            { title: '说明', dataIndex: 'message' },
          ]}
        />
      </Drawer>
      <Modal
        title="同步命令能力清单"
        open={capabilitiesOpen}
        width={960}
        footer={null}
        styles={{ body: { maxHeight: '72vh', overflowY: 'auto', paddingRight: 8 } }}
        onCancel={() => setCapabilitiesOpen(false)}
      >
        <div className="sync-capability-toolbar">
          <div>
            <div className="sync-capability-context">{capabilityContext}</div>
            <Space wrap size={[6, 6]}>
              {Object.entries(capabilityCounts).map(([category, count]) => (
                <Tag
                  key={category}
                  color={capabilityMeta[category]?.[1]}
                  className="sync-capability-summary-tag"
                  onClick={() => setCapabilityCategory(
                    capabilityCategory === category ? undefined : category,
                  )}
                >
                  {capabilityMeta[category]?.[0] || category} {count}
                </Tag>
              ))}
            </Space>
          </div>
          <Select
            allowClear
            value={capabilityCategory}
            placeholder="筛选处理方式"
            style={{ width: 180 }}
            onChange={setCapabilityCategory}
            options={Object.entries(capabilityMeta).map(([value, meta]) => ({
              value,
              label: meta[0],
            }))}
          />
        </div>
        <Alert
          type="warning"
          showIcon
          message={`未知命令处理：${capabilities?.unknownCommandPolicy === 'BLOCK' ? '阻塞任务' : capabilities?.unknownCommandPolicy}`}
          description="源端 INFO commandstats 只能提供历史风险提示，真正进入复制流时仍以本任务的策略快照判定。"
          style={{ marginBottom: 16 }}
        />
        <Table
          rowKey="command"
          size="small"
          pagination={{
            pageSize: 15,
            showSizeChanger: false,
            showTotal: (total) => `共 ${total} 条`,
          }}
          dataSource={visibleCapabilities}
          columns={[
            { title: '命令', dataIndex: 'command', width: 150 },
            {
              title: '处理方式',
              dataIndex: 'category',
              width: 170,
              render: (value) => (
                <Tag color={capabilityMeta[value]?.[1]}>
                  {capabilityMeta[value]?.[0] || value}
                </Tag>
              ),
            },
            { title: '说明', dataIndex: 'reason' },
            {
              title: '可配置',
              dataIndex: 'configurable',
              width: 90,
              render: (value) => value ? '是' : '否',
            },
          ]}
        />
      </Modal>
    </>
  )
}
