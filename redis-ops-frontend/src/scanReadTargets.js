export const scanTargetLabel = value => {
  const match = /^(replica|master)@([^#]+)#/.exec(value || '')
  return match ? `${match[1] === 'replica' ? 'Slave' : 'Master'} · ${match[2]}` : value
}

export const scanReadTargets = (nodes = [], checkpoints = []) => [...new Set(nodes.length
  ? nodes.filter(n => n.endpoint).map(n => `${n.role === 'replica' || n.role === 'slave' ? 'Slave' : 'Master'} · ${n.endpoint}`)
  : checkpoints.filter(c => /^(replica|master)@/.test(c.shardId)).map(c => scanTargetLabel(c.shardId)))]
