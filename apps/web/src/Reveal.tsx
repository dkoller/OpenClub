import { useState, type ReactNode } from 'react'

/**
 * A form for an occasional action stays behind one button until it is needed, so the page shows its content first
 * (design rule, 2026-11-26). Once opened it stays open on this page.
 */
export function Reveal({ label, open: initiallyOpen = false, children }: { label: string; open?: boolean; children: ReactNode }) {
  const [open, setOpen] = useState(initiallyOpen)
  if (open) return <>{children}</>
  return <p><button type="button" className="btn" aria-expanded="false" onClick={() => setOpen(true)}>{label}</button></p>
}
