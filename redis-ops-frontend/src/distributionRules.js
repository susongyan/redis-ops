export const byteLength = value => new TextEncoder().encode(value).length
export function classifySample(text, rules) {
  if (byteLength(text) > 4096) return { ruleId: '', text: 'KEY_TOO_LONG', system: true }
  for (const rule of rules) {
    if (!text.startsWith(rule.prefix || '')) continue
    if (rule.kind === 'FIXED') return { ruleId: rule.id, text: rule.name, system: false }
    if (!rule.delimiter || byteLength(rule.delimiter) > 8 || rule.segments < 1 || rule.segments > 8)
      return { ruleId: '', text: 'INVALID_RULE', system: true }
    let end = -1, start = 0
    for (let i = 0; i < rule.segments; i++) {
      end = text.indexOf(rule.delimiter, start)
      if (end < 0) {
        if (i < rule.segments - 1) return { ruleId: '', text: 'STRUCTURE_MISMATCH', system: true }
        end = text.length; break
      }
      start = end + rule.delimiter.length
    }
    const group = text.slice(0, end)
    return byteLength(group) > 256 ? { ruleId: '', text: 'GROUP_TOO_LONG', system: true } : { ruleId: rule.id, text: group, system: false }
  }
  return { ruleId: '', text: 'OTHER', system: true }
}
