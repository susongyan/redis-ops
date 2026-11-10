// 大 key 拆分跳过清单 —— 解析 worker 写入 sync_task_event 的固定格式消息
// （../CONTRACT.md 契约 8：字段只允许在行尾追加，因此 reason 后不再锚定行尾）。
const SKIP_MESSAGE_PATTERN = /^SPLIT_KEY_SKIPPED keyFingerprint=([0-9a-f]+) keyBytes=(\d+) rdbType=(-?\d+) payloadBytes=(\d+) reason=(\S+)/

export function parseSplitSkip(message) {
  if (typeof message !== 'string') return null
  const match = message.match(SKIP_MESSAGE_PATTERN)
  if (!match) return null
  return {
    fingerprint: match[1],
    keyBytes: Number(match[2]),
    rdbType: Number(match[3]),
    payloadBytes: Number(match[4]),
    reason: match[5],
  }
}

export const skipReasonMeta = {
  OVER_HARD_CAP: ['超硬上限', 'error'],
  UNSUPPORTED_TYPE: ['不支持类型', 'warning'],
  ELEMENT_OVER_BULK_LIMIT: ['单元素超限', 'error'],
}

export function skipReasonTag(reason) {
  return skipReasonMeta[reason] || [reason || '未知', 'default']
}

// RDB 类型码 → 展示族名（redis-ops-sync/docs/big-key-split-design.md §5.2 矩阵）
const RDB_TYPE_NAMES = {
  0: 'string', 1: 'list', 2: 'set', 3: 'zset', 4: 'hash', 5: 'zset',
  6: 'module', 7: 'module', 9: 'hash', 11: 'set', 12: 'zset', 13: 'hash',
  14: 'list', 15: 'stream', 16: 'hash', 17: 'zset', 18: 'list',
  19: 'stream', 21: 'stream',
  24: 'hash（字段级 TTL）', 25: 'hash（字段级 TTL）',
}

export function rdbTypeName(type) {
  return RDB_TYPE_NAMES[type] || `type ${type}`
}

export function skipBytes(value) {
  if (value == null || Number.isNaN(value)) return '-'
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(1)} GiB`
  if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(1)} MiB`
  if (value >= 1024) return `${(value / 1024).toFixed(1)} KiB`
  return `${value} B`
}
