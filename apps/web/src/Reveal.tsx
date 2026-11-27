import { useState, type ReactNode } from 'react'

/**
 * A form for an occasional action stays behind one button until it is needed, so the page shows its content first
 * (design rule, 2026-11-26). Once opened it stays open on this page.
 */
export function Reveal({ label, open: initiallyOpen = false, quiet = false, children }: {
  label: string; open?: boolean; quiet?: boolean; children: ReactNode
}) {
  const [open, setOpen] = useState(initiallyOpen)
  if (open) return <>{children}</>
  return <p><button type="button" className={quiet ? 'quiet' : 'btn'} aria-expanded="false" onClick={() => setOpen(true)}>{label}</button></p>
}

/**
 * A part of a page most visits do not need (squad, message, changing the event): a card whose title opens it.
 * Native <details>, so it works with the keyboard and screen readers without script.
 */
export function Fold({ title, children }: { title: ReactNode; children: ReactNode }) {
  return <details className="card pad fold">
    <summary>{title}</summary>
    <div className="stack">{children}</div>
  </details>
}
