// Expiry is also checked locally so a stale snapshot cannot remain green after its lease deadline.
export function syncWorkerStatus(worker, unavailable = false, now = Date.now()) {
  if (unavailable) return { label: '状态获取失败', color: 'default' }
  if (!worker) return { label: '状态未获取', color: 'default' }
  if (!worker.leaseOwner) return { label: '未分配 / 已释放', color: 'default' }
  const expires = Date.parse(worker.leaseUntil)
  if (worker.leaseStatus !== 'VALID' || !Number.isFinite(expires) || expires <= now)
    return { label: '租约已过期', color: 'warning' }
  return { label: '租约有效', color: 'success' }
}
