import { useEffect, useState } from 'react'
import { fetchArchivedPage } from './members'

/**
 * Whether the signed-in member holds EDIT_MEMBERS, so editor-only panels render nothing for anyone else. It asks
 * the editor-only archived-member list (OpenAPI 1.5.0); 'unknown' until it answers. The server still enforces
 * every rule on each request: this only avoids showing forms that would be refused.
 */
export function useEditorAccess(): 'unknown' | 'editor' | 'denied' {
  const [state, setState] = useState<'unknown' | 'editor' | 'denied'>('unknown')
  useEffect(() => {
    const controller = new AbortController()
    fetchArchivedPage('', undefined, controller.signal)
      .then(page => { if (!controller.signal.aborted) setState(page === 'denied' ? 'denied' : 'editor') })
      .catch(() => { if (!controller.signal.aborted) setState('denied') })
    return () => controller.abort()
  }, [])
  return state
}
