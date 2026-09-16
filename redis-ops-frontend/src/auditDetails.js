export function auditDetails(value) {
  if (!value) return null
  try {
    const parsed=typeof value==='string'?JSON.parse(value):value
    if (!parsed || typeof parsed.summary!=='string' || !Array.isArray(parsed.changes)) return null
    return {...parsed,changes:parsed.changes.filter(x=>x&&typeof x.field==='string')}
  } catch { return null }
}
export function auditValue(value) {
  if (value==null) return '—'
  if (typeof value==='boolean') return value?'是':'否'
  return String(value)
}
