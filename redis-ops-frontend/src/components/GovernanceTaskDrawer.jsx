import { Alert, Badge, Descriptions, Divider, Drawer, Progress, Space, Table, Tag, Typography } from 'antd'
import { useEffect, useRef, useState } from 'react'
import ScanReadNotice, { scanTargetLabel } from './ScanReadNotice.jsx'

export const governanceLabels = { CREATED: '待预检', DRY_RUN: '预检中', DRY_RUN_PAUSED: '预检已暂停', AWAITING_APPROVAL: '待审批', APPROVED: '已审批', RUNNING: '执行中', PAUSED: '执行已暂停', COMPLETED: '已完成', CANCELLED: '已取消', FAILED: '失败' }
export const governanceColors = { DRY_RUN: 'processing', DRY_RUN_PAUSED: 'orange', AWAITING_APPROVAL: 'gold', APPROVED: 'blue', RUNNING: 'processing', PAUSED: 'orange', COMPLETED: 'success', FAILED: 'error' }
const time = value => value ? new Date(value).toLocaleString() : '—'
const fmt = n => n == null ? '—' : Number(n).toLocaleString('zh-CN')
const duration = v => v == null ? '—' : v >= 3600 ? `~${Math.round(v / 3600)} 小时` : v >= 60 ? `~${Math.ceil(v / 60)} 分钟` : `${Math.ceil(v)} 秒`

export default function GovernanceTaskDrawer({ detail, clusters, kind, actions, onClose }) {
  const task = detail?.task, run = detail?.latestRun, checkpoints = detail?.checkpoints || []
  const runs = detail?.runs || []
  const ttl = kind === 'ttl'
  const preflight = task && ['CREATED', 'DRY_RUN', 'DRY_RUN_PAUSED', 'AWAITING_APPROVAL'].includes(task.status)
  const paused = task?.status === 'DRY_RUN_PAUSED'
  const executing = task?.status === 'DRY_RUN' || task?.status === 'RUNNING'
  // 执行阶段保留上一轮（预检）结果做对比：runs 按 id 倒序，第一个非当前 run 的即上一轮
  const prevEntry = run ? runs.find(r => r.run.id !== run.id) : null
  const compare = prevEntry && ['RUNNING', 'PAUSED', 'COMPLETED'].includes(task?.status)
  // 已批未执行的 APPROVED 仍显示预检 run；FAILED/CANCELLED 无法判断挂在哪一阶段，用中性标题
  const progressTitle = preflight || task?.status === 'APPROVED' ? '预检进度'
    : ['RUNNING', 'PAUSED', 'COMPLETED'].includes(task?.status) ? '执行进度' : '扫描进度'
  // 治理 checkpoint 存的是全局累计匹配数（每个分片行同一值，求和会成倍虚增），
  // 分子用 run.scannedKeys：pattern 为 * 时即精确遍历数，窄 Pattern 时偏保守但不会虚高
  const processed = run?.scannedKeys || 0
  // 上限先于全库遍历触发时（maxKeys/影响上限 < DBSIZE），run 会在中途正常结束，
  // 用"本次最多处理的量"当分母，进度才能到 100%
  const cap = ttl ? task?.maxKeys : task?.impactLimit
  const denominator = run?.plannedKeys ? Math.min(run.plannedKeys, cap || run.plannedKeys) : 0
  // 分子是 Pattern 命中数而分母是全库遍历域：窄 Pattern 全库扫完也可能停在 70% 上下，
  // 所以执行中的百分比只是估算（供速率/ETA 用），run 一结束就钉到 100%
  const finished = ['COMPLETED', 'FAILED', 'CANCELLED'].includes(run?.status)
  const progress = finished ? 100 : denominator ? Math.min(100, processed / denominator * 100) : 0
  const allCursorsZero = checkpoints.length > 0 && checkpoints.every(c => c.cursor === '0')
  const hitCap = run?.status === 'COMPLETED' && !allCursorsZero && processed >= cap
  // 校验弹窗同款口径：速率=处理数/耗时，剩余=(分母-已处理)/速率；结束后耗时取起止时间差
  const startMs = run?.startedAt ? new Date(run.startedAt).getTime() : null
  const endMs = run?.completedAt ? new Date(run.completedAt).getTime() : null
  const elapsed = startMs ? ((endMs ?? Date.now()) - startMs) / 1000 : 0
  const rate = executing && elapsed > 3 && processed > 0 ? processed / elapsed : 0
  const remaining = denominator && rate > 0 && processed < denominator ? (denominator - processed) / rate : null
  // 轮询驱动的计数变化 → 标题旁脉冲一次（风扫抽屉同款）
  const [flash, setFlash] = useState(0)
  const signature = `${run?.scannedKeys ?? ''}|${run?.candidateKeys ?? ''}|${run?.appliedKeys ?? ''}|${run?.deletedKeys ?? ''}|${run?.status ?? ''}|${checkpoints.map(c => `${c.shardId}:${c.cursor}`).join(';')}`
  const signatureRef = useRef('')
  useEffect(() => {
    if (signatureRef.current && signatureRef.current !== signature) setFlash(f => f + 1)
    signatureRef.current = signature
  }, [signature])
  return <Drawer open={!!task} onClose={onClose} title={`${ttl ? 'TTL 治理' : '数据清理'}详情 · ${task?.taskNo || ''}`} width="min(920px, 100vw)" footer={task && <Space wrap>{actions(task)}</Space>} destroyOnHidden>
    {task && <>
      <Space wrap style={{ marginBottom: 16 }}><Tag color={governanceColors[task.status]}>{governanceLabels[task.status] || task.status}</Tag><Tag>{task.approvalStatus === 'APPROVED' ? '执行已授权' : '尚未授权执行'}</Tag></Space>
      {paused && <Alert type="info" showIcon message="预检已暂停，统计可能尚在完成当前批次" description="可以恢复只读预检，或填写原因并确认跳过剩余预检直接执行。部分统计不能代表全量分布；跳过需等待上一批次退出。" style={{ marginBottom: 16 }} />}
      <Descriptions title="任务配置" bordered size="small" column={{ xs: 1, sm: 2 }} styles={{ content: { overflowWrap: 'anywhere' } }}>
        <Descriptions.Item label="状态">{executing ? <Badge status="processing" text={<Tag color={governanceColors[task.status]}>{governanceLabels[task.status]}</Tag>} /> : <Tag color={governanceColors[task.status]}>{governanceLabels[task.status] || task.status}</Tag>}</Descriptions.Item>
        <Descriptions.Item label="集群">{clusters.find(c => c.id === task.clusterId)?.name || `#${task.clusterId}`}</Descriptions.Item>
        <Descriptions.Item label="DB">{task.databaseNo}</Descriptions.Item>
        <Descriptions.Item label="Key Pattern"><code>{task.includePattern}</code></Descriptions.Item>
        <Descriptions.Item label="扫描/执行速率">{fmt(task.scanRatePerSecond)} Key/s</Descriptions.Item>
        <Descriptions.Item label={ttl ? '最大处理 Key 数' : '影响上限'}>{fmt(ttl ? task.maxKeys : task.impactLimit)}</Descriptions.Item>
        {ttl && <Descriptions.Item label="目标 TTL">{fmt(task.targetTtlSeconds)} 秒</Descriptions.Item>}
        <Descriptions.Item label="创建时间">{time(task.createdAt)}</Descriptions.Item>
        {task.approvalNote && <Descriptions.Item label="授权说明" span={2}>{task.approvalNote}</Descriptions.Item>}
      </Descriptions>
      {run && <>
        <Divider />
        <h3 className="sync-live-heading">{progressTitle}{flash > 0 && <span key={flash} className="sync-update-pulse" aria-label="治理进度已更新" />}</h3>
        <div key={`governance-progress-${flash}`} className={`sync-live-panel ${flash > 0 ? 'sync-live-panel-updated' : ''}`}>
          {denominator ? <div style={{ marginBottom: 12 }}>
            <Progress percent={progress} size="small" status={run.status === 'FAILED' ? 'exception' : executing ? 'active' : 'success'} format={p => `${p.toFixed(1)}%`} />
            <div style={{ color: 'rgba(0,0,0,0.45)', fontSize: 12, marginTop: 2 }}>
              {executing ? `速率 ${rate > 0 ? rate.toFixed(1) : '—'} key/s · 预计剩余 ${duration(remaining)}` : `总耗时 ${duration(elapsed)}`}
              {hitCap && ' · 达到处理上限，未遍历全库'}
            </div>
          </div> : null}
          <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }}>
            <Descriptions.Item label="总 Key（扫描前）">{run.plannedKeys ? fmt(run.plannedKeys) : '统计中'}</Descriptions.Item>
            <Descriptions.Item label="已处理（Pattern 命中）">{fmt(processed)}</Descriptions.Item>
            <Descriptions.Item label="候选 Key">{fmt(run.candidateKeys)}</Descriptions.Item>
            <Descriptions.Item label="跳过">{fmt(run.skippedKeys)}</Descriptions.Item>
            {!preflight && <Descriptions.Item label={ttl ? '已设置 TTL' : '已删除'}>{fmt(ttl ? run.appliedKeys : run.deletedKeys)}</Descriptions.Item>}
            <Descriptions.Item label="失败">{fmt(run.failedKeys)}</Descriptions.Item>
            <Descriptions.Item label="运行编号">{run.runNo}</Descriptions.Item>
            <Descriptions.Item label="开始时间">{time(run.startedAt)}</Descriptions.Item>
            <Descriptions.Item label="结束时间">{time(run.completedAt)}</Descriptions.Item>
          </Descriptions>
          {run.errorCode && <Alert type="error" showIcon message={run.errorCode} style={{ marginTop: 12 }} />}
          <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
            {preflight ? '预检只读，不修改 Redis。统计仅代表已检查范围，不保证覆盖全部 Key。' : '正式执行从头扫描并使用独立计数。'}
          </Typography.Paragraph>
        </div>
        <ScanReadNotice governance checkpoints={checkpoints} />
        <Typography.Title level={5} style={{ marginTop: 16 }}>分片进度</Typography.Title>
        <Table size="small" rowKey="shardId" pagination={false} dataSource={checkpoints} scroll={{ x: 540 }} columns={[
          { title: '扫描节点 / 分片', dataIndex: 'shardId', render: value => <span style={{ overflowWrap: 'anywhere' }}>{scanTargetLabel(value)}</span> },
          { title: 'Cursor', dataIndex: 'cursor' },
          { title: '累计已处理', dataIndex: 'scannedKeys', render: fmt },
          { title: '状态', dataIndex: 'status', render: value => <Tag color={governanceColors[value]}>{governanceLabels[value] || value}</Tag> }
        ]} />
        <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>Cursor 归零表示该分片遍历结束（各分片行的"累计已处理"是同一全局计数的快照）。运行结束也可能是达到处理上限，不等于全库遍历完成。</Typography.Paragraph>
        {compare && <>
          <Divider />
          <h3>预检结果（对比）<Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}> · {prevEntry.run.runNo} · {time(prevEntry.run.startedAt)}</Typography.Text></h3>
          <Table size="small" pagination={false} columns={[
            { title: '指标', dataIndex: 'metric' },
            { title: '预检', dataIndex: 'pre', align: 'right' },
            { title: '执行', dataIndex: 'cur', align: 'right' }
          ]} dataSource={[
            { key: 'planned', metric: '总 Key（扫描前）', pre: fmt(prevEntry.run.plannedKeys), cur: fmt(run.plannedKeys) },
            { key: 'scanned', metric: '已处理（Pattern 命中）', pre: fmt(prevEntry.run.scannedKeys), cur: fmt(processed) },
            { key: 'candidates', metric: '候选 Key', pre: fmt(prevEntry.run.candidateKeys), cur: fmt(run.candidateKeys) },
            { key: 'skipped', metric: '跳过', pre: fmt(prevEntry.run.skippedKeys), cur: fmt(run.skippedKeys) },
            { key: 'applied', metric: ttl ? '已设置 TTL' : '已删除', pre: fmt(ttl ? prevEntry.run.appliedKeys : prevEntry.run.deletedKeys), cur: fmt(ttl ? run.appliedKeys : run.deletedKeys) },
            { key: 'failed', metric: '失败', pre: fmt(prevEntry.run.failedKeys), cur: fmt(run.failedKeys) },
            { key: 'elapsed', metric: '耗时', pre: duration(prevEntry.run.completedAt ? (new Date(prevEntry.run.completedAt) - new Date(prevEntry.run.startedAt)) / 1000 : null), cur: duration(elapsed) }
          ]} />
          <Typography.Title level={5} style={{ marginTop: 16 }}>预检分片</Typography.Title>
          <Table size="small" rowKey="shardId" pagination={false} dataSource={prevEntry.checkpoints} scroll={{ x: 540 }} columns={[
            { title: '扫描节点 / 分片', dataIndex: 'shardId', render: value => <span style={{ overflowWrap: 'anywhere' }}>{scanTargetLabel(value)}</span> },
            { title: 'Cursor', dataIndex: 'cursor' },
            { title: '累计已处理', dataIndex: 'scannedKeys', render: fmt },
            { title: '状态', dataIndex: 'status', render: value => <Tag color={governanceColors[value]}>{governanceLabels[value] || value}</Tag> }
          ]} />
          <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>预检读副本、执行读主库，两边各自全量扫描：候选数一致说明执行期间键空间未漂移；执行侧"已设置 TTL / 已删除"是对预检候选的实际处理结果。</Typography.Paragraph>
        </>}
      </>}
      {!run && <>
        <Divider />
        <ScanReadNotice governance checkpoints={checkpoints} />
        <Alert type="info" showIcon message="暂无运行记录" description="可以运行只读预检，或核对范围后选择跳过预检并执行。" />
      </>}
    </>}
  </Drawer>
}
