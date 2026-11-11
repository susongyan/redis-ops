// 大 key 拆分跳过清单 —— 解析 worker 写入 sync_task_event 的固定格式消息
// （../CONTRACT.md 契约 8）。v2 起原 key 名直接随事件携带（放行尾，容忍 key 内空格）、
// 集合类带 RDB 声明的总元素个数（elements=0 表示不可知）；指纹格式仅存量旧事件还有。
const SKIP_V2_PATTERN = /^SPLIT_KEY_SKIPPED rdbType=(-?\d+) keyBytes=(\d+) elements=(\d+) payloadBytes=(\d+) reason=(\S+) key=(.*)$/
const SKIP_V1_PATTERN = /^SPLIT_KEY_SKIPPED keyFingerprint=([0-9a-f]+) keyBytes=(\d+) rdbType=(-?\d+) payloadBytes=(\d+) reason=(\S+)/

export function parseSplitSkip(message) {
  if (typeof message !== 'string') return null
  const v2 = message.match(SKIP_V2_PATTERN)
  if (v2)
    return {
      key: v2[6],
      fingerprint: null,
      rdbType: Number(v2[1]),
      keyBytes: Number(v2[2]),
      elements: Number(v2[3]),
      payloadBytes: Number(v2[4]),
      reason: v2[5],
    }
  const v1 = message.match(SKIP_V1_PATTERN)
  if (!v1) return null
  return {
    key: null,
    fingerprint: v1[1],
    keyBytes: Number(v1[2]),
    rdbType: Number(v1[3]),
    elements: 0,
    payloadBytes: Number(v1[4]),
    reason: v1[5],
  }
}

/** 展示用 key 标识：新事件给原 key 名，存量旧事件回退指纹。 */
export function skipKeyLabel(entry) {
  return entry?.key ?? entry?.fingerprint ?? '-'
}

/** 集合类总元素个数（声明值）；0 = 不可知（如 string/编码前 blob）。 */
export function skipElementsLabel(entry) {
  const n = entry?.elements
  return n > 0 ? n.toLocaleString() : '—'
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
