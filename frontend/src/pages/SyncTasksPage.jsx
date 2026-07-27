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
  Row,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
} from 'antd'
import { InfoCircleOutlined, PlusOutlined, SettingOutlined } from '@ant-design/icons'
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
  const [form] = Form.useForm()
  const [tuningForm] = Form.useForm()
  const [startForm] = Form.useForm()
  const [finishForm] = Form.useForm()
  const relationId = Form.useWatch('relationId', form)
  const selectedSourceClusterId = Form.useWatch('sourceClusterId', form)
  const selectedTargetClusterId = Form.useWatch('targetClusterId', form)
  const selectedRelation = relations.find((relation) => relation.id === relationId)
  const sourceCluster = clusters.find((cluster) => (
    cluster.id === (selectedRelation?.primaryClusterId || selectedSourceClusterId)
  ))
  const targetCluster = clusters.find((cluster) => (
    cluster.id === (selectedRelation?.standbyClusterId || selectedTargetClusterId)
  ))
  const sourceDbRequired = ['STANDALONE', 'SENTINEL'].includes(sourceCluster?.mode)
  const targetDbRequired = ['STANDALONE', 'SENTINEL'].includes(targetCluster?.mode)

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

  const applyDetailSnapshot = (result, animate = true) => {
    const nextChannelSignature = channelSignature(result.channels)
    const nextMetricSignature = metricSignature(result.metrics)
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
    })
    setOpen(true)
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

      <Modal title="新增同步任务" width={720} open={open} onCancel={() => setOpen(false)} onOk={create}>
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
                message={detail.task.blockedReason || '同步任务执行失败'}
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
              ]}
            />
          </>
        )}
        <h3>预检查</h3>
        {detail?.precheck ? (
          <Descriptions
            bordered
            size="small"
            column={3}
            items={[
              { key: 'status', label: '结果', children: <Tag color={detail.precheck.status === 'PASSED' ? 'success' : 'error'}>{detail.precheck.status}</Tag> },
              { key: 'checkedAt', label: '检查时间', children: detail.precheck.checkedAt },
              { key: 'validUntil', label: '有效期至', children: detail.precheck.validUntil },
            ]}
          />
        ) : <div className="muted">尚未执行预检查</div>}

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
    </>
  )
}
