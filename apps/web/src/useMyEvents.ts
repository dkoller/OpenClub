import { useEffect, useState } from 'react'
import { answerAttendance, clearAttendance, readMyEvents, type AttendanceResponse, type MyEvent } from './events'

export type Notice = 'closed' | 'denied' | 'failed'

/** The member's next eight weeks (GET /v1/me/events) and answering for themselves or a child. */
export function useMyEvents(csrfToken: string) {
  const [events, setEvents] = useState<MyEvent[] | null>(null)
  const [truncated, setTruncated] = useState(false)
  const [state, setState] = useState<'loading' | 'ready' | 'denied' | 'error'>('loading')
  const [revision, setRevision] = useState(0)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  useEffect(() => {
    let active = true
    readMyEvents().then(result => {
      if (!active) return
      if (result === 'denied') { setState('denied'); return }
      setEvents(result.items); setTruncated(result.truncated); setState('ready')
    }).catch(() => { if (active) setState('error') })
    return () => { active = false }
  }, [revision])

  /** Choosing the current answer again clears it. */
  async function respond(event: MyEvent, memberId: string, current: AttendanceResponse | null, next: AttendanceResponse) {
    setBusy(true); setNotice(null)
    try {
      const outcome = current === next ? await clearAttendance(event.id, memberId, csrfToken)
        : await answerAttendance(event.id, memberId, next, csrfToken)
      if (outcome !== 'ok') setNotice(outcome)
    } catch { setNotice('failed') } finally { setBusy(false); setRevision(value => value + 1) }
  }

  return { events, truncated, state, busy, notice, respond, reload: () => setRevision(value => value + 1) }
}
