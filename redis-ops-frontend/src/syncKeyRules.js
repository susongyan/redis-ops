const encoder = new TextEncoder()

// Worker BinaryGlob byte semantics: *, ?, and backslash escape only; [] is literal.
export function syncKeyMatches(pattern, key) {
  const bytes = encoder.encode(pattern)
  const value = key instanceof Uint8Array ? key : encoder.encode(key)
  const tokens = []
  for (let i = 0; i < bytes.length; i++) {
    if (bytes[i] === 92 && i + 1 < bytes.length) tokens.push(bytes[++i])
    else tokens.push(bytes[i] === 42 ? -1 : bytes[i] === 63 ? -2 : bytes[i])
  }
  let p = 0, v = 0, star = -1, retry = 0
  while (v < value.length) {
    if (p < tokens.length && (tokens[p] === -2 || tokens[p] === value[v])) { p++; v++ }
    else if (p < tokens.length && tokens[p] === -1) { star = p++; retry = v }
    else if (star >= 0) { p = star + 1; v = ++retry }
    else return false
  }
  while (p < tokens.length && tokens[p] === -1) p++
  return p === tokens.length
}

export function syncKeyDecision(key, includes = [], excludes = []) {
  if (excludes.some(pattern => syncKeyMatches(pattern, key))) return '排除规则命中'
  return !includes.length || includes.some(pattern => syncKeyMatches(pattern, key)) ? '包含' : '未命中包含规则'
}

export function previewSyncKeys(text, includes = [], excludes = []) {
  const keys = text.slice(0, 16384).split('\n').filter(key => key.length > 0).slice(0, 20)
  const ruleBytes = [...includes, ...excludes].reduce((sum, rule) => sum + encoder.encode(rule).length, 0)
  const sampleBytes = keys.reduce((sum, key) => sum + encoder.encode(key).length, 0)
  const overBudget = ruleBytes * sampleBytes > 2_000_000
  return keys.map((key, index) => ({ id: index, key,
    decision: overBudget ? '预览预算超限，请减少示例或规则' : syncKeyDecision(key, includes, excludes) }))
}
