import { csvCell, fileStamp, safeFilePart } from './distributionExport.js'

export const validationFileName = (detail, sourceName = '', targetName = '') => {
  const task = detail?.task || {}
  return ['validation', safeFilePart(sourceName || `cluster${task.sourceClusterId ?? ''}`), 'to',
    safeFilePart(targetName || `cluster${task.targetClusterId ?? ''}`), fileStamp(detail?.latestRun?.startedAt || task.createdAt)].filter(Boolean).join('-') + '.csv'
}

// 与分布分析导出同款守卫：终态才允许导出；但校验任务 FAILED/INCONCLUSIVE 可被重启，收尾必须复核 version/status/latestRun.id
export const exportableValidation = detail => ['PASSED', 'FAILED', 'INCONCLUSIVE', 'CANCELLED'].includes(detail?.task?.status)

// 差异接口后端每页硬顶 100（MyBatisValidationRepository 钳制），只能按 100 分页；
// 差异行数无上限，导出截断在 1 万行（100 个请求），尾注说明总数
const PAGE_SIZE = 100
export const EXPORT_DIFF_LIMIT = 10000

export async function loadValidationExport(api, id) {
  const detail = await api.validationTask(id)
  if (!exportableValidation(detail)) throw new Error('请等待任务结束后导出，避免分页期间结果变化')
  const runId = detail.latestRun?.id ?? 0
  const differences = []
  let total, truncated = false
  const pageLimit = Math.ceil(EXPORT_DIFF_LIMIT / PAGE_SIZE)
  for (let page = 1; page <= pageLimit; page++) {
    const result = await api.validationDifferences(id, page, PAGE_SIZE)
    if (!Number.isInteger(result.total) || result.total < 0 || (total != null && total !== result.total)) throw new Error('差异数量异常或已变化，请重试')
    total = result.total
    if (!Array.isArray(result.items) || result.items.length > PAGE_SIZE) throw new Error('导出分页响应异常')
    differences.push(...result.items)
    if (differences.length >= total) break
    if (!result.items.length) throw new Error('导出分页不完整，请重试')
    if (differences.length >= EXPORT_DIFF_LIMIT) { truncated = true; break }
  }
  const exported = truncated ? differences.slice(0, EXPORT_DIFF_LIMIT) : differences
  if (!truncated && (exported.length !== total || new Set(exported.map(row => row.id)).size !== total)) throw new Error('导出分页不完整，请重试')
  const latest = await api.validationTask(id)
  if (latest.task?.version !== detail.task?.version || latest.task?.status !== detail.task?.status
    || (latest.latestRun?.id ?? 0) !== runId) throw new Error('任务结果已变化，请重试')
  return { detail, differences: exported, truncated, total }
}

const samplingText = task => task.samplingMode === 'FULL' ? '全量校验'
  : task.samplingMode === 'PERCENTAGE' ? `${task.samplePercentage}% 抽样` : `${task.sampleLimit} Key 抽样`

export function validationCsv(detail, differences, truncated, total, sourceName = '', targetName = '') {
  const { task: t, latestRun: run } = detail
  const rows = [
    ['数据校验报告（差异明细导出）'],
    ['统计说明', '差异为扫描时点对比结果；差异明细按时间倒序；大 Key 受安全阈值保护只报告不深比'],
    ['任务编号', t.taskNo, '任务 ID', t.id, '关联同步任务', t.syncTaskId ?? '独立校验'],
    ['源集群', `${sourceName}#${t.sourceClusterId}`, '源 DB', t.sourceDb, '目标集群', `${targetName}#${t.targetClusterId}`, '目标 DB', t.targetDb],
    ['状态', t.status, '覆盖范围', samplingText(t), '结论模式', t.strictness === 'STRICT' ? '严格门禁' : '报告'],
    ['待比对 Key', run?.plannedKeys, '已扫描', run?.scannedKeys, '已比较', run?.comparedKeys],
    ['差异', run?.differenceCount, '降级大 Key', run?.degradedCount, '未验证', run?.unverifiableCount, '未确定', run?.inconclusiveCount],
    ['开始时间', run?.startedAt, '结束时间', run?.finishedAt],
    [], ['差异明细（按时间倒序）'], ['id', 'runId', '差异类型', 'Key', 'Key 哈希', 'Redis 类型', '源大小（字节）', '目标大小（字节）', '源 TTL（秒）', '目标 TTL（秒）', '比较级别', '降级原因', '时间'],
    ...differences.map(d => [d.id, d.runId, d.differenceType, d.keyName || '历史记录仅保留摘要', d.keyHash, d.redisType,
      d.sourceSize, d.targetSize, d.sourceTtlSeconds, d.targetTtlSeconds, d.comparisonLevel, d.degradedReason, d.createdAt]),
  ]
  if (truncated) rows.push([], [`差异共 ${total} 条，仅导出前 ${EXPORT_DIFF_LIMIT} 条（按时间倒序）`])
  return '﻿' + rows.map(row => row.map(csvCell).join(',')).join('\r\n') + '\r\n'
}
