/**
 * A Swiss or Liechtenstein number in the usual grouping: "+41 79 123 45 67", "+423 234 56 78". Anything else is
 * shown as it was stored, so an unusual number is never altered.
 */
export function formatPhone(value: string): string {
  const compact = value.replace(/[\s/.-]/g, '')
  const swiss = /^(?:\+41|0041)(\d{2})(\d{3})(\d{2})(\d{2})$/.exec(compact)
  if (swiss) return `+41 ${swiss[1]} ${swiss[2]} ${swiss[3]} ${swiss[4]}`
  const local = /^0(\d{2})(\d{3})(\d{2})(\d{2})$/.exec(compact)
  if (local) return `0${local[1]} ${local[2]} ${local[3]} ${local[4]}`
  const liechtenstein = /^(?:\+423|00423)(\d{3})(\d{2})(\d{2})$/.exec(compact)
  if (liechtenstein) return `+423 ${liechtenstein[1]} ${liechtenstein[2]} ${liechtenstein[3]}`
  return value
}
