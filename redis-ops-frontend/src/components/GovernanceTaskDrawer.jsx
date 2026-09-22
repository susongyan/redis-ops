import { Alert, Descriptions, Divider, Drawer, Space, Statistic, Table, Tag, Typography } from 'antd'

export const governanceLabels = { CREATED: '待预检', DRY_RUN: '预检中', DRY_RUN_PAUSED: '预检已暂停', AWAITING_APPROVAL: '待审批', APPROVED: '已审批', RUNNING: '执行中', PAUSED: '执行已暂停', COMPLETED: '已完成', CANCELLED: '已取消', FAILED: '失败' }
export const governanceColors = { DRY_RUN: 'processing', DRY_RUN_PAUSED: 'orange', AWAITING_APPROVAL: 'gold', APPROVED: 'blue', RUNNING: 'processing', PAUSED: 'orange', COMPLETED: 'success', FAILED: 'error' }
const time = value => value ? new Date(value).toLocaleString() : '—'

export default function GovernanceTaskDrawer({ detail, clusters, kind, actions, onClose }) {
  const task = detail?.task, run = detail?.latestRun
  const ttl = kind === 'ttl'
  const preflight = task && ['CREATED', 'DRY_RUN', 'DRY_RUN_PAUSED', 'AWAITING_APPROVAL'].includes(task.status)
  const paused = task?.status === 'DRY_RUN_PAUSED'
  return <Drawer open={!!task} onClose={onClose} title={`${ttl ? 'TTL 治理' : '数据清理'}详情 · ${task?.taskNo || ''}`} width="min(920px, 100vw)" footer={task && <Space wrap>{actions(task)}</Space>} destroyOnHidden>
    {task && <>
      <Space wrap style={{ marginBottom: 16 }}><Tag color={governanceColors[task.status]}>{governanceLabels[task.status] || task.status}</Tag><Tag>{task.approvalStatus === 'APPROVED' ? '执行已授权' : '尚未授权执行'}</Tag></Space>
      {paused && <Alert type="info" showIcon message="预检已暂停，统计可能尚在完成当前批次" description="可以恢复只读预检，或填写原因并确认跳过剩余预检直接执行。部分统计不能代表全量分布；跳过需等待上一批次退出。" style={{ marginBottom: 16 }} />}
      <Descriptions title="任务配置" bordered size="small" column={{ xs: 1, sm: 2 }} styles={{ content: { overflowWrap: 'anywhere' } }}>
        <Descriptions.Item label="集群">{clusters.find(c => c.id === task.clusterId)?.name || `#${task.clusterId}`}</Descriptions.Item>
        <Descriptions.Item label="数据库">{task.databaseNo}</Descriptions.Item>
        <Descriptions.Item label="Key 规则" span={2}>{task.includePattern}</Descriptions.Item>
        {ttl && <Descriptions.Item label="目标 TTL">{task.targetTtlSeconds} 秒</Descriptions.Item>}
        <Descriptions.Item label="速率限制">{task.scanRatePerSecond} Key/s</Descriptions.Item>
        <Descriptions.Item label={ttl ? '匹配检查上限' : '影响上限'}>{ttl ? task.maxKeys : task.impactLimit}</Descriptions.Item>
        <Descriptions.Item label="创建时间">{time(task.createdAt)}</Descriptions.Item>
        {task.approvalNote && <Descriptions.Item label="授权说明" span={2}>{task.approvalNote}</Descriptions.Item>}
      </Descriptions>
      <Divider />
      <Typography.Title level={5}>{preflight ? '预检统计' : '最近一次运行统计'}</Typography.Title>
      <Typography.Paragraph type="secondary">{preflight ? '预检只读，不修改 Redis。统计仅代表已检查范围，不保证覆盖全部 Key。' : '正式执行从头扫描并使用独立计数。任务刚提交时，下面可能仍显示上一轮预检统计。'}</Typography.Paragraph>
      {run ? <>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 145px), 1fr))', gap: 20, marginBottom: 20 }}>
          <Statistic title="已匹配检查" value={run.scannedKeys} />
          <Statistic title="候选 Key" value={run.candidateKeys} />
          <Statistic title="跳过" value={run.skippedKeys} />
          {!preflight && <Statistic title={ttl ? '已设置 TTL' : '已删除'} value={ttl ? run.appliedKeys : run.deletedKeys} />}
          <Statistic title="失败" value={run.failedKeys} />
        </div>
        <Descriptions size="small" column={{ xs: 1, sm: 2 }}>
          <Descriptions.Item label="运行编号">{run.runNo}</Descriptions.Item>
          <Descriptions.Item label="扫描前 DB Key 数">{run.plannedKeys}</Descriptions.Item>
          <Descriptions.Item label="开始时间">{time(run.startedAt)}</Descriptions.Item>
          <Descriptions.Item label="结束时间">{time(run.completedAt)}</Descriptions.Item>
        </Descriptions>
        {run.errorCode && <Alert type="error" showIcon message={run.errorCode} style={{ marginBottom: 16 }} />}
        <Typography.Title level={5}>分片进度</Typography.Title>
        <Table size="small" rowKey="shardId" pagination={false} dataSource={detail.checkpoints || []} scroll={{ x: 540 }} columns={[
          { title: '分片', dataIndex: 'shardId', render: value => <span style={{ overflowWrap: 'anywhere' }}>{value}</span> },
          { title: 'Cursor', dataIndex: 'cursor' },
          { title: '累计匹配检查', dataIndex: 'scannedKeys' },
          { title: '状态', dataIndex: 'status', render: value => <Tag color={governanceColors[value]}>{governanceLabels[value] || value}</Tag> }
        ]} />
        <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>Cursor 归零表示该分片遍历结束。运行结束也可能是达到数量上限，不等于全库遍历完成。</Typography.Paragraph>
      </> : <Alert type="info" showIcon message="暂无运行记录" description="可以运行只读预检，或核对范围后选择跳过预检并执行。" />}
    </>}
  </Drawer>
}
