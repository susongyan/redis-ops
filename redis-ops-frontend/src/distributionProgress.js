// 分布分析进度口径，与校验/风扫/治理弹窗一致：
// 分母 = 启动时逐分片 DBSIZE 估算（后端存 checkpoint，0=未知），并与观测上限取小——
// 观测上限先于全库遍历触发时进度也能走到 100%（GovernanceTaskDrawer 同款语义）；
// 分子 = observed（SCAN 观测数，含 SCAN 重复返回，达到或超过分母属正常）；
// 速率 = observed / 有效执行时长（elapsedMillis 跨暂停累计、不含暂停间隙，>3s 才可信）；
// ETA = 剩余 ÷ 速率。COMPLETED 与观测上限触顶时 observed ≥ 分母，百分比自然封顶 100%，
// 时间预算中断 / 失败 / 取消则停在真实覆盖处，不做虚假的 100%。
export function distributionProgress(task) {
  if (!task) return { denominator: 0, percent: null, rate: null, remaining: null, budgetCapped: false, running: false }
  const estimated = task.estimatedTotal || 0
  const cap = task.spec?.maxObservations > 0 ? task.spec.maxObservations : 0
  const denominator = estimated > 0 ? cap > 0 ? Math.min(estimated, cap) : estimated : 0
  const observed = task.observed || 0
  const running = ['QUEUED', 'RUNNING'].includes(task.status)
  const percent = denominator > 0 ? Math.min(100, observed / denominator * 100) : null
  const elapsedSeconds = (task.elapsedMillis || 0) / 1000
  const rate = running && elapsedSeconds > 3 && observed > 0 ? observed / elapsedSeconds : null
  const remaining = denominator > 0 && rate > 0 && observed < denominator ? (denominator - observed) / rate : null
  const budgetCapped = task.status === 'INCOMPLETE' && task.reason === 'BUDGET_REACHED'
  return { denominator, percent, rate, remaining, budgetCapped, running }
}

export const formatDuration = seconds => seconds == null ? '—'
  : seconds >= 3600 ? `~${Math.round(seconds / 3600)} 小时`
  : seconds >= 60 ? `~${Math.ceil(seconds / 60)} 分钟`
  : `${Math.ceil(seconds)} 秒`

// 创建即执行、满负荷不排队：这两个错误码要在创建/预览失败时给出可行动的中文说明
const distributionErrors = {
  DISTRIBUTION_BUSY: '本实例的分布分析并发槽已满，请等待现有任务结束后重试（创建不排队，失败立即可知）',
  DISTRIBUTION_CLUSTER_BUSY: '该集群已有分布分析任务或采样预览在运行，请先等待其结束或取消后再试',
  REQUEST_IN_PROGRESS: '扫描通道正忙（本实例容量被占或集群租约被持有），请稍后重试',
  DISTRIBUTION_PREVIEW_FAILED: '采样预览失败，未保存任何样本，请检查集群连接后重试',
}
export const distributionErrorText = (error, fallback = '请求失败') =>
  distributionErrors[error?.code] || error?.message || fallback
