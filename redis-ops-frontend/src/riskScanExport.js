import { csvCell, fileStamp, safeFilePart } from './distributionExport.js'

export const riskScanFileName = (detail, clusterName = '') => {
  const task = detail?.task || {}
  return ['risk-scan', safeFilePart(clusterName || `cluster${task.clusterId ?? ''}`),
    task.databaseNo ? `db${task.databaseNo}` : '', fileStamp(detail?.latestRun?.startedAt || task.createdAt)].filter(Boolean).join('-') + '.csv'
}

// 与分布分析导出同款守卫：终态才允许导出；但风险任务终态可被重启，收尾必须复核 version/status/latestRun.id
export const exportableRiskScan = detail => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(detail?.task?.status)

// 明细接口 size 无后端钳制，用大页减少请求；findings 受任务 maxFindings（≤10 万）约束
const PAGE_SIZE = 1000

export async function loadRiskScanExport(api, id) {
  const detail = await api.riskScanTask(id)
  if (!exportableRiskScan(detail)) throw new Error('请等待任务结束后导出，避免分页期间结果变化')
  const runId = detail.latestRun?.id ?? 0
  const findings = []
  let total
  const pageLimit = Math.ceil(Math.min(detail.task.maxFindings || 1, 100000) / PAGE_SIZE) + 2
  for (let page = 1; page <= pageLimit; page++) {
    const result = await api.riskFindings(id, page, PAGE_SIZE)
    if (!Number.isInteger(result.total) || result.total < 0 || result.total > 100000 || (total != null && total !== result.total)) throw new Error('明细数量异常或已变化，请重试')
    total = result.total
    if (!Array.isArray(result.items) || result.items.length > PAGE_SIZE) throw new Error('导出分页响应异常')
    findings.push(...result.items)
    if (findings.length >= total) break
    if (!result.items.length) throw new Error('导出分页不完整，请重试')
  }
  if (findings.length !== total || new Set(findings.map(row => row.id)).size !== total) throw new Error('导出分页不完整，请重试')
  // 新 run 的无 TTL 是模式聚合（不同模式 ≤1000，超出折叠 GROUP_LIMIT），两页内拉全
  const groups = []
  if (detail.latestRun?.noTtlTotal != null) {
    let groupTotal
    for (let page = 1; page <= 2; page++) {
      const result = await api.riskNoTtlGroups(id, page, PAGE_SIZE)
      if (!Number.isInteger(result.total) || result.total < 0 || result.total > 1100 || (groupTotal != null && groupTotal !== result.total)) throw new Error('无 TTL 聚合数量异常或已变化，请重试')
      groupTotal = result.total
      if (!Array.isArray(result.items) || result.items.length > PAGE_SIZE) throw new Error('导出分页响应异常')
      groups.push(...result.items)
      if (groups.length >= groupTotal) break
      if (!result.items.length) throw new Error('导出分页不完整，请重试')
    }
    if (groups.length !== groupTotal) throw new Error('导出分页不完整，请重试')
  }
  const latest = await api.riskScanTask(id)
  if (latest.task?.version !== detail.task?.version || latest.task?.status !== detail.task?.status
    || (latest.latestRun?.id ?? 0) !== runId) throw new Error('任务结果已变化，请重试')
  return { detail, findings, groups }
}

export function riskScanCsv(detail, findings, groups, clusterName = '') {
  const { task: t, latestRun: run, summary } = detail
  // 已遍历口径与页面一致：checkpoint 的 SCAN 遍历数合计；run.scannedKeys 只是 Pattern 命中数
  const traversed = (detail.checkpoints || []).reduce((sum, c) => sum + (c.scannedKeys || 0), 0)
  const rows = [
    ['风险扫描报告（聚合与明细导出）'],
    ['统计说明', '大 Key 明细最多保留任务的“最多明细数”；新任务的无 TTL 结果为模式聚合，不保留逐 Key 明细；内存与 TTL 为扫描时点值'],
    ['任务编号', t.taskNo, '任务 ID', t.id, '集群 ID', t.clusterId, '集群名称', clusterName, 'DB', t.databaseNo],
    ['状态', t.status, 'Pattern', t.includePattern, '大 Key 阈值（字节）', t.largeKeyThresholdBytes, '扫描速率（key/s）', t.scanRatePerSecond],
    ['最多明细数', t.maxFindings, '分组分隔符', t.groupDelimiter, '分组段数', t.groupSegments],
    ['总 Key（启动时估算）', run?.plannedKeys, '已遍历（checkpoint 合计）', traversed, 'Pattern 命中', run?.scannedKeys, '已存明细', run?.findingCount],
    ['无 TTL 数量', summary?.noTtlCount, '无 TTL 占比', summary?.noTtlRatio, '大 Key 数量', summary?.largeKeyCount],
    ['无 TTL 全库总数', run?.noTtlTotal ?? '', '大 Key 全库总数', run?.largeKeyTotal ?? ''],
    ['开始时间', run?.startedAt, '结束时间', run?.completedAt],
  ]
  if (groups.length) {
    rows.push([], ['无 TTL 模式聚合（按数量降序）'], ['runId', '风险类型', '模式', '数量', '样例 Key', '最大内存（字节）'],
      ...groups.map(g => [g.runId, g.riskType, g.groupText, g.hitCount, g.sampleKey, g.maxMemoryBytes]))
  }
  rows.push([], ['发现明细（按内存降序）'], ['id', 'runId', '风险类型', '等级', 'Key', 'Key 哈希', 'Redis 类型', '内存（字节）', '元素数', 'TTL（秒）', '分片', '时间'],
    ...findings.map(f => [f.id, f.runId, f.riskType, f.riskLevel, f.keyName || '历史记录仅保留摘要', f.keyHash, f.redisType, f.memoryBytes, f.elementCount, f.ttlSeconds, f.nodeId, f.createdAt]))
  return '﻿' + rows.map(row => row.map(csvCell).join(',')).join('\r\n') + '\r\n'
}
