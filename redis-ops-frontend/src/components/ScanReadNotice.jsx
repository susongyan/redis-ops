import { Alert, Space, Tag } from 'antd'
import { scanReadTargets } from '../scanReadTargets'
export { scanTargetLabel } from '../scanReadTargets'

export default function ScanReadNotice({ nodes = [], checkpoints = [], governance = false }) {
  const targets = scanReadTargets(nodes, checkpoints)
  return <Alert type="info" showIcon style={{ marginBottom: 16 }} message={governance ? '预检读取节点' : '扫描读取节点'} description={<>
    <div>优先扫描健康 Replica，无可用副本时扫描 Master。副本结果可能存在复制延迟。{governance && '正式执行在 Master 上重新扫描和校验。'}</div>
    {!!targets.length && <div style={{ marginTop: 8 }}><div>实际扫描节点（IP:端口）</div><Space wrap style={{ marginTop: 4, maxWidth: '100%' }}>{targets.map(t => <Tag key={t} style={{ maxWidth: '100%', whiteSpace: 'normal', overflowWrap: 'anywhere' }}>{t}</Tag>)}</Space></div>}
    {!targets.length && !!checkpoints.length && <div style={{ marginTop: 8 }}>历史扫描记录未保存节点地址。</div>}
  </>} />
}
