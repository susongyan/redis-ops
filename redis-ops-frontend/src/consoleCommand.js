export function parseConsoleCommand(line) {
  const tokens = []
  let token = '', quote = null, active = false, escaped = false
  for (const char of line) {
    if (escaped) { token += char; escaped = false; active = true; continue }
    if (char === '\\') { escaped = true; active = true; continue }
    if (quote) { if (char === quote) quote = null; else token += char; continue }
    if (char === '\n' || char === '\r') throw new Error('一次只能执行一条命令，请移除未引用的换行')
    if (char === '"' || char === "'") { quote = char; active = true; continue }
    if (/\s/.test(char)) { if (active) { tokens.push(token); token = ''; active = false } }
    else { token += char; active = true }
  }
  if (quote || escaped) throw new Error('引号或转义未完成')
  if (active) tokens.push(token)
  return { commandName: (tokens.shift() || '').toUpperCase(), arguments: tokens }
}
