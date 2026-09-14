import { useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Card, Checkbox, Drawer, Input, InputNumber, Modal, Select, Space, Steps, Table, Tag, message } from 'antd'
import { api } from '../api.js'
import { byteLength, classifySample } from '../distributionRules.js'
import { distributionCsv, exportableDistribution, loadDistributionExport } from '../distributionExport.js'

const newRule = () => ({ id: crypto.randomUUID().replaceAll('-', ''), name: '业务分组', prefix: '', kind: 'SEGMENTS', delimiter: ':', segments: 1 })
const statusNames = { QUEUED: '排队', RUNNING: '运行中', PAUSED: '已暂停', COMPLETED: '遍历完成', INCOMPLETE: '不完整', FAILED: '失败', CANCELLED: '已取消' }
const bucketNames = { OTHER: '其他', GROUP_LIMIT: '分组超限', KEY_TOO_LONG: 'Key 过长', GROUP_TOO_LONG: '分组过长', BINARY_KEY: '二进制 Key', STRUCTURE_MISMATCH: '结构不匹配', INVALID_RULE: '规则不合法' }
const groupLabel = (group, snapshot) => {
  if (group.system) return bucketNames[group.text] || group.text
  const index = snapshot.findIndex(rule => rule.id === group.ruleId)
  return `${index < 0 ? `未知规则 ${group.ruleId}` : `规则 ${index + 1} · ${snapshot[index].name}`} / ${group.text || '(空段)'}`
}
const groupCell = (group, snapshot) => {
  const index = snapshot.findIndex(rule => rule.id === group.ruleId)
  return <div className="distribution-group-cell">
    <div className="distribution-group-text">{group.system ? (bucketNames[group.text] || group.text) : (group.text || '(空段)')}</div>
    <div className="muted distribution-group-rule">{group.system ? '系统保留分组' : index < 0 ? `未知规则 ${group.ruleId}` : `规则 ${index + 1} · ${snapshot[index].name}`}</div>
  </div>
}

export default function KeyDistributionsPage() {
  const [step, setStep] = useState(0)
  const [clusters, setClusters] = useState([]), [clusterId, setClusterId] = useState(), [database, setDatabase] = useState(0)
  const [rules, setRules] = useState([newRule()]), [mode, setMode] = useState('FIXED'), [capacity, setCapacity] = useState(1000)
  const [full, setFull] = useState(false), [maxObservations, setMaxObservations] = useState(1000000), [durationSeconds, setDuration] = useState(1800)
  const [scanCountInput, setScanCountInput] = useState('200'), [scanIntervalInput, setScanIntervalInput] = useState('200')
  const [samples, setSamples] = useState([]), [manual, setManual] = useState(''), [expires, setExpires] = useState(0), [previewMeta, setPreviewMeta] = useState(null)
  const [previewBusy, setPreviewBusy] = useState(false), [creating, setCreating] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [tasks, setTasks] = useState({ items: [], total: 0 }), [page, setPage] = useState(1), [selected, setSelected] = useState(null), [groups, setGroups] = useState({ items: [], total: 0 }), [groupPage, setGroupPage] = useState(1)
  const previewRequest = useRef(null), alive = useRef(true), contextVersion = useRef(0)
  const isCluster = clusters.find(c => c.id === clusterId)?.mode === 'CLUSTER'
  const exportCsv = async () => {
    const id = selected.id
    setExporting(true)
    try {
      const { task, groups: allGroups } = await loadDistributionExport(api, id)
      if (!alive.current) return
      const csv = distributionCsv(task, allGroups, clusters.find(c => c.id === task.spec.clusterId)?.name || '')
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
      const link = document.createElement('a')
      link.href = url; link.download = `key-distribution-${id}.csv`
      document.body.appendChild(link); link.click(); link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      message.success(`已导出 ${allGroups.length} 个保留分组`)
    } catch (e) { if (alive.current) message.error(e.message) }
    finally { if (alive.current) setExporting(false) }
  }
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
  const rulesValid = () => !rules.some(r => !r.name.trim() || byteLength(r.name) > 256 || byteLength(r.prefix) > 256 || r.kind === 'SEGMENTS' && (!r.delimiter || byteLength(r.delimiter) > 8 || !Number.isInteger(r.segments) || r.segments < 1 || r.segments > 8))
  const goToStep = next => {
    if (next > 0 && !clusterId) return message.warning('请先选择分析集群；采样预览可以跳过')
    if (next > 1 && !rulesValid()) return message.error('请检查规则名称、前缀、分隔符和取段数')
    setStep(next)
  }
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
    if (!rulesValid()) return message.error('请检查规则名称、前缀、分隔符和取段数')
    const scanCount = Number(scanCountInput), scanIntervalMillis = Number(scanIntervalInput)
    if (!/^\d+$/.test(scanCountInput) || scanCount < 1 || scanCount > 5000) return message.error('SCAN COUNT 必须是 1–5000 的整数')
    if (!/^\d+$/.test(scanIntervalInput) || scanIntervalMillis < 10 || scanIntervalMillis > 60000) return message.error('SCAN 最小间隔必须是 10–60000 毫秒的整数')
    const request = { clusterId, database, rules, mode, capacity, maxObservations: full ? 0 : maxObservations, durationSeconds, scanCount, scanIntervalMillis }
    Modal.confirm({ title: '确认开始只读 Key 分布分析', content: `不读取 value。范围：${full ? '完整遍历' : `最多 ${maxObservations} 次观测`}，最长 ${durationSeconds} 秒。每批 SCAN COUNT ${scanCount}，两次 SCAN 最小间隔 ${scanIntervalMillis} ms（整个任务共享）。COUNT 是提示值；前缀过滤不等于减少遍历工作量；分组数上限 ${capacity}。`, onOk: async () => {
      setCreating(true)
      try { const task = await api.createDistribution(request); setSelected(task); setGroupPage(1); setGroups({ items: [], total: 0 }); message.success('任务已创建') }
      catch (e) { message.error(e.message); throw e } finally { setCreating(false) }
    } })
  }
  const control = async (task, action) => {
    try { const updated = await api.controlDistribution(task.id, task.version, action); if (selected?.id === task.id) setSelected(updated); setTasks(await api.distributionTasks(page)) }
    catch (e) { message.error(e.message) }
  }
  return <div className="distribution-page">
    <Alert type="info" showIcon message="Key 前缀／业务归属分布" description="只统计 SCAN 观测次数，不读取 value、不作精确去重，不是实时一致性快照。规则和容量决定分组；Top-K 的区间只表示算法误差，不包含 SCAN 重复或数据变化误差。" />
    <Card title="新建分布分析">
      <Steps current={step} onChange={goToStep} size="small" items={[{ title: '选择范围', description: '预览可跳过' }, { title: '配置规则', description: '本地预览归类' }, { title: '确认执行', description: '设置扫描预算' }]} />
    </Card>
    {step === 0 && <Card title="1. 选择范围与预览">
      <Space wrap><Select aria-label="分析集群" placeholder="选择集群" style={{ width: 280 }} value={clusterId} onChange={setClusterId} options={clusters.map(c => ({ value: c.id, label: `${c.name} (${c.mode})` }))} />
        <span>DB</span><InputNumber aria-label="数据库" min={0} max={15} disabled={isCluster} value={database} onChange={v => setDatabase(v ?? 0)} />
        <Button loading={previewBusy} onClick={preview}>限量采样预览</Button>
        <Button onClick={() => { contextVersion.current++; previewRequest.current?.abort(); setSamples([]); setManual(''); setPreviewMeta(null); setExpires(0) }}>清除样本</Button></Space>
      <p className="muted">各分片轮转扫描，最多 1,000 个 Key / 256 KiB / 15 秒。非随机样本，不代表全量分布；五分钟自动清除，离开页面不保留。</p>
      <Input.TextArea aria-label="手动示例 Key" rows={3} maxLength={262144} placeholder="也可手动输入示例 Key，每行一个，仅当前浏览器计算，不发送后端" value={manual} onChange={e => { if (byteLength(e.target.value) > 256 * 1024) return message.warning('样本超过 256 KiB'); setManual(e.target.value); setExpires(Date.now() + 300000) }} />
      {previewMeta && <p>采样观测 {previewMeta.observed} 次，访问 {previewMeta.visitedShards}/{previewMeta.totalShards} 个分片；超长 {previewMeta.oversized}，二进制 {previewMeta.binary}。{previewMeta.limited ? '已达到采样预算' : '采样遍历结束'}</p>}
    </Card>}
    {step === 1 && <Card title="2. 归类规则（按顺序首次匹配）" extra={<Button disabled={rules.length >= 32} onClick={() => setRules([...rules, newRule()])}>添加规则</Button>}>
      {!previewRows.length && <Alert type="info" showIcon message="当前没有样本，可直接配置规则继续，也可返回上一步采样或输入示例。" />}
      {rules.map((rule, index) => <div className="distribution-rule" key={rule.id}>
        <Space wrap><span>规则 {index + 1}</span><Input aria-label={`规则 ${index + 1} 名称`} style={{ width: 150 }} value={rule.name} onChange={e => setRule(index, 'name', e.target.value)} maxLength={256} />
          <Input aria-label={`规则 ${index + 1} 匹配前缀`} placeholder="匹配前缀（空为全部）" style={{ width: 200 }} value={rule.prefix} maxLength={256} onChange={e => setRule(index, 'prefix', e.target.value)} />
          <Select aria-label={`规则 ${index + 1} 类型`} value={rule.kind} style={{ width: 140 }} onChange={v => setRule(index, 'kind', v)} options={[{ value: 'SEGMENTS', label: '取前 N 段' }, { value: 'FIXED', label: '固定业务分组' }]} />
          {rule.kind === 'SEGMENTS' && <><Input aria-label={`规则 ${index + 1} 分隔符`} value={rule.delimiter} style={{ width: 85 }} maxLength={8} onChange={e => setRule(index, 'delimiter', e.target.value)} /><span>前</span><InputNumber aria-label={`规则 ${index + 1} 段数`} min={1} max={8} value={rule.segments} onChange={v => setRule(index, 'segments', v ?? 1)} /><span>段</span></>}
          <Button disabled={index === 0} onClick={() => setRules(old => { const next = [...old]; [next[index-1], next[index]] = [next[index], next[index-1]]; return next })}>上移</Button>
          <Button disabled={rules.length === 1} onClick={() => setRules(rules.filter((_, i) => i !== index))}>移除</Button></Space>
      </div>)}
      <p>样本 {previewRows.length} 个，归类 {sampleGroups} 组；其他 {sampleOther}，异常格式 {sampleInvalid}。{sampleGroups > capacity ? '样本已超过分组容量！' : '样本不能保证全量基数；正式执行仍强制容量上限。'}</p>
      <Table size="small" rowKey="id" dataSource={previewRows} pagination={{ pageSize: 5, showSizeChanger: false }} scroll={{ x: 560 }} columns={[{ title: '示例 Key（仅内存）', dataIndex: 'key', ellipsis: true }, { title: '归类预览', render: (_, row) => groupLabel(row.group, rules), ellipsis: true }]} />
    </Card>}
    {step === 2 && <Card title="3. 扫描预算与统计方式">
      <p>分析集群：{clusters.find(c => c.id === clusterId)?.name || clusterId} · DB {database} · {rules.length} 条规则（按顺序首次匹配）</p>
      <p className="muted">正式分析从头扫描，不累计预览计数。返回修改不会丢失当前设置。</p>
      <Space wrap><Select aria-label="统计方式" value={mode} style={{ width: 180 }} onChange={setMode} options={[{ value: 'FIXED', label: '固定容量，超限归桶' }, { value: 'TOP_K', label: '有界 Top-K 估算' }]} />
        <span>{mode === 'TOP_K' ? '候选分组数上限' : '最多统计分组数'}</span><InputNumber aria-label={mode === 'TOP_K' ? '候选分组数上限' : '最多统计分组数'} min={32} max={1000} value={capacity} onChange={v => setCapacity(v ?? 1000)} />
      </Space>
      <p className="muted">限制整个任务保留的分组数，不是 Key 数，也不是每次 SCAN 的返回数量。</p>
      <div className="distribution-budget-row">
      <h4>扫描停止条件</h4>
      <Space wrap>
        <Checkbox checked={full} onChange={e => setFull(e.target.checked)}>完整遍历（仍受时限约束）</Checkbox>
        {!full && <><span>最多扫描观测次数</span><InputNumber aria-label="最多扫描观测次数" min={1} max={1e12} value={maxObservations} onChange={v => setMaxObservations(v ?? 1000000)} /></>}
        <span>最长执行时间（秒）</span><InputNumber aria-label="最长执行时间（秒）" min={1} max={21600} value={durationSeconds} onChange={v => setDuration(v ?? 1800)} />
      </Space>
      <p className="muted">达到数量或时间限制即停止，保留不完整结果；完整遍历也受时间限制。观测次数包含 SCAN 可能重复返回的 Key。</p>
      </div>
      <div className="distribution-budget-row">
      <h4>执行速率限制</h4>
      <Space wrap>
        <label>每批扫描数量（COUNT） <Input aria-label="每批扫描数量（COUNT）" inputMode="numeric" style={{ width: 110 }} maxLength={8} value={scanCountInput} onChange={e => setScanCountInput(e.target.value)} /></label>
        <label>两次 SCAN 最小间隔（毫秒） <Input aria-label="两次 SCAN 最小间隔（毫秒）" inputMode="numeric" style={{ width: 110 }} maxLength={8} value={scanIntervalInput} onChange={e => setScanIntervalInput(e.target.value)} /></label>
      </Space>
      <p className="muted">COUNT：1–5000，默认 200，仅为工作量提示，实际返回数量可能不同。最小间隔：10–60000 ms，默认 200 ms，按整个任务相邻 SCAN 开始时间计算，所有 Master 共用。部署配置可能进一步收紧范围。</p>
      <p className="muted">不再按 Key/s 限速。单次响应仍受 1 MiB / 5000 个 Key 等安全限制；调大 COUNT 不会放大这些上限，超限页将丢弃并结束任务。持续慢响应会增大间隔或暂停。</p>
      </div>
    </Card>}
    <div className="distribution-step-actions">
      <span className="muted">第 {step + 1} / 3 步</span>
      <Space wrap>
        {step > 0 && <Button disabled={creating} onClick={() => goToStep(step - 1)}>上一步</Button>}
        {step < 2 ? <Button type="primary" disabled={previewBusy} onClick={() => goToStep(step + 1)}>{step === 0 ? '下一步：配置规则' : '下一步：确认预算'}</Button> : <Button type="primary" loading={creating} onClick={create}>确认并开始分析</Button>}
      </Space>
    </div>
    <Card title="分析任务">
      <Table rowKey="id" dataSource={tasks.items} pagination={{ current: page, pageSize: 20, showSizeChanger: false, total: tasks.total, onChange: setPage }} scroll={{ x: 850 }} columns={[
        { title: '任务', render: (_, t) => <Button type="link" onClick={() => { setSelected(t); setGroupPage(1); setGroups({ items: [], total: 0 }) }}>#{t.id}</Button> },
        { title: '集群 / DB', render: (_, t) => `${clusters.find(c => c.id === t.clusterId)?.name || t.clusterId} / ${t.spec.database}` },
        { title: '状态', render: (_, t) => <Tag>{statusNames[t.status] || t.status}</Tag> }, { title: '观测次数', dataIndex: 'observed' },
        { title: '已完成分片', render: (_, t) => `${t.completedShards}/${t.totalShards}` }, { title: '原因', dataIndex: 'reason' },
        { title: '操作', render: (_, t) => <Space>{['QUEUED', 'RUNNING'].includes(t.status) && <Button onClick={() => control(t, 'pause')}>暂停</Button>}{t.status === 'PAUSED' && <Button onClick={() => control(t, 'resume')}>恢复</Button>}{['QUEUED', 'RUNNING', 'PAUSED'].includes(t.status) && <Button onClick={() => control(t, 'cancel')}>取消</Button>}</Space> }
      ]} />
    </Card>
    <Drawer title={`分布结果 #${selected?.id || ''}`} extra={<Button loading={exporting} disabled={!exportableDistribution(selected)} onClick={exportCsv}>导出 CSV</Button>} open={!!selected} onClose={() => setSelected(null)} width="min(900px, 100vw)">
      {selected && <><Alert type={selected.status === 'COMPLETED' ? 'info' : 'warning'} message={`${statusNames[selected.status]} · ${selected.reason || 'SCAN 观测统计，非精确 Key 数'}`} />
        <p className="muted">任务结束后可导出全部已保留分组（不限当前页）；文件包含分组与规则信息，请仅分享给授权研发人员。</p>
        <p>观测 {selected.observed} 次；完成分片 {selected.completedShards}/{selected.totalShards}；有效执行 {Math.round(selected.elapsedMillis/1000)} 秒。</p>
        <p>{selected.spec.scanCount == null ? `旧版参数：${selected.spec.keysPerSecond} Key/s（仅供历史查看，请新建任务使用批次参数）` : `任务参数：SCAN COUNT ${selected.spec.scanCount}；最小间隔 ${selected.spec.scanIntervalMillis} ms（慢响应保护可自动增大间隔）`}</p>
        <p>{selected.spec.mode === 'TOP_K' ? '候选分组数上限' : '最多统计分组数'}：{selected.spec.capacity}；{selected.capacityReached ? '已占满；固定容量模式后续新增分组进入“分组超限”桶' : '未占满'}。</p>
        <p className="muted">{selected.spec.mode === 'TOP_K' ? '按估计次数排序，默认前 20 项。上下界仅对本次观测流有效；未展示项的合计不作精确余数推断。' : '固定容量分组，不是 Top-K；超限新分组计入独立系统桶。'}</p>
        <Card size="small" title="本次任务的规则快照" style={{ marginBottom: 16 }}>
          <p className="muted">按下列顺序首次匹配。创建任务时保存，不受当前新建表单修改影响。</p>
          {selected.spec.rules.map((rule, index) => <div key={rule.id} className="distribution-result-rule">
            <div><strong>规则 {index + 1} · {rule.name}</strong></div>
            <div>匹配前缀：{rule.prefix ? <code>{JSON.stringify(rule.prefix)}</code> : '不限（匹配所有 Key）'}</div>
            <div>提取方式：{rule.kind === 'FIXED' ? <>固定业务分组，计入“{rule.name}”</> : <>前 {rule.segments} 段；字面分隔符 <code>{JSON.stringify(rule.delimiter)}</code>（保留空段）</>}</div>
            <div className="muted">完整规则 ID：<code>{rule.id}</code></div>
          </div>)}
        </Card>
        <Table rowKey={r => JSON.stringify(r.group)} dataSource={groups.items} pagination={{ current: groupPage, pageSize: 20, showSizeChanger: false, total: groups.total, onChange: setGroupPage }} tableLayout="fixed" scroll={{ x: 760 }} columns={[
          { title: 'Key 分组 / 所属规则', width: 280, render: (_, r) => groupCell(r.group, selected.spec.rules) }, { title: '观测次数 / 估计上界', dataIndex: 'count' },
          { title: '下界', render: (_, r) => r.count - r.error }, { title: '算法误差', dataIndex: 'error' },
          { title: '观测占比 / 区间', render: (_, r) => selected.observed ? `${((r.count-r.error)*100/selected.observed).toFixed(2)}% – ${(r.count*100/selected.observed).toFixed(2)}%` : '—' }
        ]} /></>}
    </Drawer>
  </div>
}
