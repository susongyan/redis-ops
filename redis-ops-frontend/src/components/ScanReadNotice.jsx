import { Alert, Space, Tag } from 'antd'

export const scanTargetLabel = value => {
  const match = /^(replica|master)@([^#]+)#/.exec(value || '')
  return match ? `${match[1] === 'replica' ? 'Replica' : 'Master'} · ${match[2]}` : value
}
export default function ScanReadNotice({ nodes = [], checkpoints = [], governance = false }) {
  const targets = [...new Set(nodes.length
    ? nodes.map(n => `${n.role === 'replica' ? 'Replica' : 'Master'} · ${n.endpoint}`)
    : checkpoints.filter(c => /^(replica|master)@/.test(c.shardId)).map(c => scanTargetLabel(c.shardId)))]
  return <Alert type="info" showIcon style={{ marginBottom: 16 }} message={governance ? '预检读取节点' : '扫描读取节点'} description={<>
    <div>优先扫描健康 Replica，无可用副本时扫描 Master。副本结果可能存在复制延迟。{governance && '正式执行在 Master 上重新扫描和校验。'}</div>
    {!!targets.length && <Space wrap style={{ marginTop: 8, maxWidth: '100%' }}>{targets.map(t => <Tag key={t} style={{ maxWidth: '100%', whiteSpace: 'normal', overflowWrap: 'anywhere' }}>{t}</Tag>)}</Space>}
  </>} />
}
