import { useEffect, useState } from 'react'
import { fetchHealth } from './health'

export function App() {
  const [status, setStatus] = useState('Checking API…')

  useEffect(() => {
    let active = true
    fetchHealth()
      .then((ok) => { if (active) setStatus(ok ? 'API connected' : 'API unavailable') })
      .catch(() => { if (active) setStatus('API unavailable') })
    return () => { active = false }
  }, [])

  return (
    <main>
      <h1>OpenClub</h1>
      <p>Open-source club management, starting small.</p>
      <p role="status">{status}</p>
    </main>
  )
}
