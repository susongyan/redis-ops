export const exportableDistribution = task => ['COMPLETED', 'INCOMPLETE', 'FAILED', 'CANCELLED'].includes(task?.status)

// Quote every cell and neutralize spreadsheet formulas, including leading whitespace.
export function csvCell(value) {
  let text = String(value ?? '')
  if (/^[\s\u0000-\u001f]*[=+@-]/u.test(text) || /^[\t\r\n]/u.test(text)) text = `'${text}`
  return `"${text.replaceAll('"', '""')}"`
}

export async function loadDistributionExport(api, id) {
  const task = await api.distributionTask(id)
  if (!exportableDistribution(task)) throw new Error('请等待任务结束后导出，避免分页期间结果变化')
  const groups = []
  let total
  for (let page = 1; page <= 55; page++) {
    const result = await api.distributionGroups(id, page)
    if (!Number.isInteger(result.total) || result.total < 0 || result.total > 1100 || (total != null && total !== result.total)) throw new Error('分组数量异常或已变化，请重试')
    total = result.total
    if (!Array.isArray(result.items) || result.items.length > 20) throw new Error('导出分页响应异常')
    groups.push(...result.items)
    if (groups.length >= total) break
    if (!result.items.length) throw new Error('导出分页不完整，请重试')
  }
  if (groups.length !== total || new Set(groups.map(row => JSON.stringify(row.group))).size !== total) throw new Error('导出分页不完整，请重试')
  const latest = await api.distributionTask(id)
  if (latest.version !== task.version || latest.status !== task.status || latest.observed !== task.observed) throw new Error('任务结果已变化，请重试')
  return { task, groups }
}

export function distributionCsv(task, groups, clusterName = '') {
  const spec = task.spec
  const rows = [
    ['Key 分布分析报告（仅聚合结果，不含原始 Key 样本）'],
    ['统计说明', 'SCAN 观测次数，非精确独立 Key 数；不保证一致性快照；上下界仅表示算法误差'],
    ['覆盖说明', '仅导出任务已保留分组；Top-K 和容量超限不代表全部实际分组'],
    ['任务 ID', task.id, '集群 ID', spec.clusterId, '集群名称', clusterName, 'DB', spec.database],
    ['状态', task.status, '停止原因', task.reason, '观测次数', task.observed],
    ['完成分片', task.completedShards, '总分片', task.totalShards, '执行毫秒', task.elapsedMillis],
    ['统计模式', spec.mode, '分组容量', spec.capacity, '容量已占满', task.capacityReached],
    ['COUNT', spec.scanCount, '最小间隔毫秒', spec.scanIntervalMillis, '旧版 Key/s', spec.keysPerSecond || ''],
    ['最多观测（0表示完整遍历）', spec.maxObservations, '最长秒数', spec.durationSeconds],
    [], ['规则顺序', '规则 ID', '名称', '匹配前缀', '提取方式', '字面分隔符', '前 N 段'],
    ...spec.rules.map((rule, i) => [i + 1, rule.id, rule.name, rule.prefix, rule.kind, rule.delimiter, rule.segments]),
    [], ['Key 分组', '系统桶', '规则 ID', '规则名称', '匹配前缀', '提取方式', '字面分隔符', '前 N 段', '观测次数 / 估计上界', '下界', '算法误差', '观测占比下界(%)', '观测占比上界(%)'],
    ...groups.map(({ group, count, error }) => {
      const rule = spec.rules.find(r => r.id === group.ruleId)
      return [group.text, group.system ? '是' : '否', group.ruleId, rule?.name, rule?.prefix, rule?.kind, rule?.delimiter, rule?.segments, count, count - error, error,
        task.observed ? ((count - error) * 100 / task.observed).toFixed(4) : '', task.observed ? (count * 100 / task.observed).toFixed(4) : '']
    }),
  ]
  return '\ufeff' + rows.map(row => row.map(csvCell).join(',')).join('\r\n') + '\r\n'
}
