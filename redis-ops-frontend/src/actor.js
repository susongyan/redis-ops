// Names are action-time snapshots, never reconstructed from today's user directory.
export function actorLabel(subject, snapshot) {
  let actor
  try { actor = typeof snapshot === 'string' ? JSON.parse(snapshot) : snapshot } catch { /* legacy fallback */ }
  if (!actor || !actor.login) return subject || '—'
  return actor.displayName && actor.displayName !== actor.login
    ? `${actor.displayName}（${actor.login}）` : actor.login
}
