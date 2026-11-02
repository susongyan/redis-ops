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

// Registry-level liveness of a worker instance row from GET /api/v1/sync-workers.
export function workerRegistryStatus(worker, unavailable = false) {
  if (unavailable) return { label: '状态获取失败', color: 'default' }
  if (!worker) return { label: '未知实例', color: 'default' }
  if (!worker.online) return { label: '离线', color: 'error' }
  return { label: '在线', color: 'success' }
}

// Display text for the registry address: ip:port when both reported, ip alone, or a placeholder.
export function workerAddress(worker) {
  if (!worker) return ''
  if (!worker.workerIp) return worker.workerPort ? `端口 ${worker.workerPort}` : ''
  return worker.workerPort ? `${worker.workerIp}:${worker.workerPort}` : worker.workerIp
}

// Health link target for a registry row; only meaningful when the IP is reported too
// (linkable is display-only — the platform does not proxy actuator traffic).
export function workerHealthUrl(worker) {
  if (!worker?.workerIp || !worker.workerPort) return null
  return `http://${worker.workerIp}:${worker.workerPort}/actuator/health`
}

// Options for the worker assignment Select; offline instances stay visible but disabled.
export function syncWorkerOptions(registry, unavailable = false) {
  if (unavailable) return []
  return (registry || []).map(worker => ({
    value: worker.instanceName,
    label: `${worker.instanceName}${workerAddress(worker) ? ` · ${workerAddress(worker)}` : ''}${worker.online ? '' : '（离线）'}`,
    disabled: !worker.online,
  }))
}
