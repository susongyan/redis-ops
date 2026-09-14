import { useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Card, Checkbox, Drawer, Input, InputNumber, Modal, Select, Space, Table, Tag, message } from 'antd'
import { api } from '../api.js'
import { byteLength, classifySample } from '../distributionRules.js'

const newRule = () => ({ id: crypto.randomUUID().replaceAll('-', ''), name: '业务分组', prefix: '', kind: 'SEGMENTS', delimiter: ':', segments: 1 })
const statusNames = { QUEUED: '排队', RUNNING: '运行中', PAUSED: '已暂停', COMPLETED: '遍历完成', INCOMPLETE: '不完整', FAILED: '失败', CANCELLED: '已取消' }
const bucketNames = { OTHER: '其他', GROUP_LIMIT: '分组超限', KEY_TOO_LONG: 'Key 过长', GROUP_TOO_LONG: '分组过长', BINARY_KEY: '二进制 Key', STRUCTURE_MISMATCH: '结构不匹配', INVALID_RULE: '规则不合法' }
const groupLabel = group => group.system ? bucketNames[group.text] || group.text : `${group.ruleId.slice(0, 6)} / ${group.text || '(空段)'}`

export default function KeyDistributionsPage() {
  const [clusters, setClusters] = useState([]), [clusterId, setClusterId] = useState(), [database, setDatabase] = useState(0)
  const [rules, setRules] = useState([newRule()]), [mode, setMode] = useState('FIXED'), [capacity, setCapacity] = useState(1000)
  const [full, setFull] = useState(false), [maxObservations, setMaxObservations] = useState(1000000), [durationSeconds, setDuration] = useState(1800), [rate, setRate] = useState(1000)
  const [samples, setSamples] = useState([]), [manual, setManual] = useState(''), [expires, setExpires] = useState(0), [previewMeta, setPreviewMeta] = useState(null)
  const [previewBusy, setPreviewBusy] = useState(false), [creating, setCreating] = useState(false)
  const [tasks, setTasks] = useState({ items: [], total: 0 }), [page, setPage] = useState(1), [selected, setSelected] = useState(null), [groups, setGroups] = useState({ items: [], total: 0 }), [groupPage, setGroupPage] = useState(1)
  const previewRequest = useRef(null), alive = useRef(true), contextVersion = useRef(0)
  const isCluster = clusters.find(c => c.id === clusterId)?.mode === 'CLUSTER'
  useEffect(() => { alive.current = true; api.clusters({ page: 1, size: 200 }).then(r => { if (alive.current) setClusters(r.items) }).catch(e => message.error(e.message)); return () => { alive.current = false; previewRequest.current?.abort() } }, [])
  useEffect(() => { contextVersion.current++; previewRequest.current?.abort(); setSamples([]); setManual(''); setPreviewMeta(null); setExpires(0) }, [clusterId, database])
  useEffect(() => { if (isCluster) setDatabase(0) }, [isCluster])
  useEffect(() => { if (!expires) return; const timer = setTimeout(() => { setSamples([]); setManual(''); setPreviewMeta(null); setExpires(0) }, Math.max(0, expires - Date.now())); return () => clearTimeout(timer) }, [expires])
  useEffect(() => {
    let stopped = false, fetching = false
    const refresh = async () => {
      if (fetching) return; fetching = true
      try {
        const result = await api.distributionTasks(page)
        if (!stopped) setTasks(result)
        if (selected?.id) {
          const [task, resultGroups] = await Promise.all([api.distributionTask(selected.id), api.distributionGroups(selected.id, groupPage)])
          if (!stopped) { setSelected(task); setGroups(resultGroups) }
        }
      } catch (e) { if (!stopped) message.error(e.message) } finally { fetching = false }
    }
    refresh(); const timer = setInterval(refresh, 5000)
    return () => { stopped = true; clearInterval(timer) }
  }, [page, selected?.id, groupPage])
  const previewRows = useMemo(() => {
    const input = manual ? manual.split('\n').slice(0, 1000) : samples
    return input.map((key, i) => ({ id: i, key, group: classifySample(key, rules) }))
  }, [manual, samples, rules])
  const sampleGroups = new Set(previewRows.map(r => JSON.stringify(r.group))).size
  const sampleOther = previewRows.filter(r => r.group.system && r.group.text === 'OTHER').length
  const sampleInvalid = previewRows.filter(r => r.group.system && r.group.text !== 'OTHER').length
  const setRule = (index, field, value) => setRules(old => old.map((rule, i) => i === index ? { ...rule, [field]: value } : rule))
  const preview = async () => {
    if (!clusterId) return message.warning('先选择集群')
    const controller = new AbortController(); previewRequest.current = controller
    const generation = contextVersion.current; setPreviewBusy(true)
    try {
      const result = await api.distributionPreview({ clusterId, database }, controller.signal)
      if (!alive.current || generation !== contextVersion.current) return
      setManual(''); setSamples(result.samples); setPreviewMeta(result); setExpires(Date.now() + 300000)
    } catch (e) { if (e.name !== 'AbortError' && alive.current) message.error(e.message) }
    finally { if (alive.current) setPreviewBusy(false) }
  }
  const create = () => {
    if (!clusterId) return message.warning('先选择集群')
    if (rules.some(r => !r.name.trim() || byteLength(r.name) > 256 || byteLength(r.prefix) > 256 || r.kind === 'SEGMENTS' && (!r.delimiter || byteLength(r.delimiter) > 8 || !r.segments))) return message.error('请检查规则名称、前缀、分隔符和取段数')
    Modal.confirm({ title: '确认开始只读 Key 分布分析', content: `不读取 value。范围：${full ? '完整遍历' : `最多 ${maxObservations} 次观测`}，最长 ${durationSeconds} 秒，目标 ${rate} Key/s。前缀过滤不等于减少遍历工作量；分组容量 ${capacity}。`, onOk: async () => {
      setCreating(true)
      try { const task = await api.createDistribution({ clusterId, database, rules, mode, capacity, maxObservations: full ? 0 : maxObservations, durationSeconds, keysPerSecond: rate }); setSelected(task); setGroupPage(1); setGroups({ items: [], total: 0 }); message.success('任务已创建') }
      catch (e) { message.error(e.message); throw e } finally { setCreating(false) }
    } })
  }
  const control = async (task, action) => {
    try { const updated = await api.controlDistribution(task.id, task.version, action); if (selected?.id === task.id) setSelected(updated); setTasks(await api.distributionTasks(page)) }
    catch (e) { message.error(e.message) }
  }
  return <div className="distribution-page">
    <Alert type="info" showIcon message="Key 前缀／业务归属分布" description="只统计 SCAN 观测次数，不读取 value、不作精确去重，不是实时一致性快照。规则和容量决定分组；Top-K 的区间只表示算法误差，不包含 SCAN 重复或数据变化误差。" />
    <Card title="1. 选择范围与预览（可跳过）">
      <Space wrap><Select aria-label="分析集群" placeholder="选择集群" style={{ width: 280 }} value={clusterId} onChange={setClusterId} options={clusters.map(c => ({ value: c.id, label: `${c.name} (${c.mode})` }))} />
        <span>DB</span><InputNumber aria-label="数据库" min={0} max={15} disabled={isCluster} value={database} onChange={v => setDatabase(v ?? 0)} />
        <Button loading={previewBusy} onClick={preview}>限量采样预览</Button>
        <Button onClick={() => { contextVersion.current++; previewRequest.current?.abort(); setSamples([]); setManual(''); setPreviewMeta(null); setExpires(0) }}>清除样本</Button></Space>
      <p className="muted">各分片轮转扫描，最多 1,000 个 Key / 256 KiB / 15 秒。非随机样本，不代表全量分布；五分钟自动清除，离开页面不保留。</p>
      <Input.TextArea aria-label="手动示例 Key" rows={3} maxLength={262144} placeholder="也可手动输入示例 Key，每行一个，仅当前浏览器计算，不发送后端" value={manual} onChange={e => { if (byteLength(e.target.value) > 256 * 1024) return message.warning('样本超过 256 KiB'); setManual(e.target.value); setExpires(Date.now() + 300000) }} />
      {previewMeta && <p>采样观测 {previewMeta.observed} 次，访问 {previewMeta.visitedShards}/{previewMeta.totalShards} 个分片；超长 {previewMeta.oversized}，二进制 {previewMeta.binary}。{previewMeta.limited ? '已达到采样预算' : '采样遍历结束'}</p>}
    </Card>
    <Card title="2. 归类规则（按顺序首次匹配）" extra={<Button disabled={rules.length >= 32} onClick={() => setRules([...rules, newRule()])}>添加规则</Button>}>
      {rules.map((rule, index) => <div className="distribution-rule" key={rule.id}>
        <Space wrap><span>规则 {index + 1}</span><Input aria-label={`规则 ${index + 1} 名称`} style={{ width: 150 }} value={rule.name} onChange={e => setRule(index, 'name', e.target.value)} maxLength={256} />
          <Input aria-label={`规则 ${index + 1} 匹配前缀`} placeholder="匹配前缀（空为全部）" style={{ width: 200 }} value={rule.prefix} maxLength={256} onChange={e => setRule(index, 'prefix', e.target.value)} />
          <Select aria-label={`规则 ${index + 1} 类型`} value={rule.kind} style={{ width: 140 }} onChange={v => setRule(index, 'kind', v)} options={[{ value: 'SEGMENTS', label: '取前 N 段' }, { value: 'FIXED', label: '固定业务分组' }]} />
          {rule.kind === 'SEGMENTS' && <><Input aria-label={`规则 ${index + 1} 分隔符`} value={rule.delimiter} style={{ width: 85 }} maxLength={8} onChange={e => setRule(index, 'delimiter', e.target.value)} /><span>前</span><InputNumber aria-label={`规则 ${index + 1} 段数`} min={1} max={8} value={rule.segments} onChange={v => setRule(index, 'segments', v ?? 1)} /><span>段</span></>}
          <Button disabled={index === 0} onClick={() => setRules(old => { const next = [...old]; [next[index-1], next[index]] = [next[index], next[index-1]]; return next })}>上移</Button>
          <Button disabled={rules.length === 1} onClick={() => setRules(rules.filter((_, i) => i !== index))}>移除</Button></Space>
      </div>)}
      <p>样本 {previewRows.length} 个，归类 {sampleGroups} 组；其他 {sampleOther}，异常格式 {sampleInvalid}。{sampleGroups > capacity ? '样本已超过分组容量！' : '样本不能保证全量基数；正式执行仍强制容量上限。'}</p>
      <Table size="small" rowKey="id" dataSource={previewRows} pagination={{ pageSize: 5, showSizeChanger: false }} scroll={{ x: 560 }} columns={[{ title: '示例 Key（仅内存）', dataIndex: 'key', ellipsis: true }, { title: '归类预览', render: (_, row) => groupLabel(row.group), ellipsis: true }]} />
    </Card>
    <Card title="3. 扫描预算与统计方式">
      <Space wrap><Select aria-label="统计方式" value={mode} style={{ width: 180 }} onChange={setMode} options={[{ value: 'FIXED', label: '固定容量，超限归桶' }, { value: 'TOP_K', label: '有界 Top-K 估算' }]} />
        <span>分组容量</span><InputNumber aria-label="分组容量" min={32} max={1000} value={capacity} onChange={v => setCapacity(v ?? 1000)} />
        <Checkbox checked={full} onChange={e => setFull(e.target.checked)}>完整遍历（仍受时限约束）</Checkbox>
        {!full && <><span>最多观测</span><InputNumber aria-label="最多观测" min={1} max={1e12} value={maxObservations} onChange={v => setMaxObservations(v ?? 1000000)} /></>}
        <span>最长秒数</span><InputNumber aria-label="最长秒数" min={1} max={21600} value={durationSeconds} onChange={v => setDuration(v ?? 1800)} />
        <span>目标 Key/s</span><InputNumber aria-label="扫描速率" min={1} max={1000} value={rate} onChange={v => setRate(v ?? 1000)} />
        <Button type="primary" loading={creating} onClick={create}>确认并开始分析</Button></Space>
    </Card>
    <Card title="分析任务">
      <Table rowKey="id" dataSource={tasks.items} pagination={{ current: page, pageSize: 20, showSizeChanger: false, total: tasks.total, onChange: setPage }} scroll={{ x: 850 }} columns={[
        { title: '任务', render: (_, t) => <Button type="link" onClick={() => { setSelected(t); setGroupPage(1); setGroups({ items: [], total: 0 }) }}>#{t.id}</Button> },
        { title: '集群 / DB', render: (_, t) => `${clusters.find(c => c.id === t.clusterId)?.name || t.clusterId} / ${t.spec.database}` },
        { title: '状态', render: (_, t) => <Tag>{statusNames[t.status] || t.status}</Tag> }, { title: '观测次数', dataIndex: 'observed' },
        { title: '已完成分片', render: (_, t) => `${t.completedShards}/${t.totalShards}` }, { title: '原因', dataIndex: 'reason' },
        { title: '操作', render: (_, t) => <Space>{['QUEUED', 'RUNNING'].includes(t.status) && <Button onClick={() => control(t, 'pause')}>暂停</Button>}{t.status === 'PAUSED' && <Button onClick={() => control(t, 'resume')}>恢复</Button>}{['QUEUED', 'RUNNING', 'PAUSED'].includes(t.status) && <Button onClick={() => control(t, 'cancel')}>取消</Button>}</Space> }
      ]} />
    </Card>
    <Drawer title={`分布结果 #${selected?.id || ''}`} open={!!selected} onClose={() => setSelected(null)} width="min(900px, 100vw)">
      {selected && <><Alert type={selected.status === 'COMPLETED' ? 'info' : 'warning'} message={`${statusNames[selected.status]} · ${selected.reason || 'SCAN 观测统计，非精确 Key 数'}`} />
        <p>观测 {selected.observed} 次；完成分片 {selected.completedShards}/{selected.totalShards}；有效执行 {Math.round(selected.elapsedMillis/1000)} 秒。</p>
        <p>分组容量：{selected.capacityReached ? '已占满；固定容量模式后续新增分组进入“分组超限”桶' : '未占满'}。</p>
        <p className="muted">{selected.spec.mode === 'TOP_K' ? '按估计次数排序，默认前 20 项。上下界仅对本次观测流有效；未展示项的合计不作精确余数推断。' : '固定容量分组，不是 Top-K；超限新分组计入独立系统桶。'}</p>
        <Table rowKey={r => JSON.stringify(r.group)} dataSource={groups.items} pagination={{ current: groupPage, pageSize: 20, showSizeChanger: false, total: groups.total, onChange: setGroupPage }} scroll={{ x: 650 }} columns={[
          { title: '分组', render: (_, r) => groupLabel(r.group), ellipsis: true }, { title: '观测次数 / 估计上界', dataIndex: 'count' },
          { title: '下界', render: (_, r) => r.count - r.error }, { title: '算法误差', dataIndex: 'error' },
          { title: '观测占比 / 区间', render: (_, r) => selected.observed ? `${((r.count-r.error)*100/selected.observed).toFixed(2)}% – ${(r.count*100/selected.observed).toFixed(2)}%` : '—' }
        ]} /></>}
    </Drawer>
  </div>
}
