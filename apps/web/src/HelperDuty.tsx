import { useEffect, useState, type FormEvent } from 'react'
import { Reveal } from './Reveal'
import type { Language } from './language'
import { readHelperDuty, readMyHelperDuty, saveHelperDuty, type HelperDutyOverview, type MyHelperDuty } from './helperDuty'

const words = {
  de: { change: 'Helferpflicht ändern', title: 'Helfereinsätze', mine: '{done} von {quota} Helfereinsätzen in dieser Saison ({from} bis {to}).', doneAll: 'Erledigt – danke!', counts: 'Gezählt werden Einsätze, die schon stattgefunden haben, auch die Ihrer Kinder.', admin: 'Helferpflicht', quota: 'Einsätze pro Saison (0 = aus)', month: 'Saisonbeginn (Monat)', save: 'Speichern', saved: 'Gespeichert.', failed: 'Nicht gespeichert.', member: 'Mitglied', done: 'Geleistet', owed: 'Offen', off: 'Die Helferpflicht ist ausgeschaltet.', none: 'Niemand ist betroffen.', hint: 'Betrifft Erwachsene in einem Team und Eltern von Kindern in einem Team. Ein allfälliger Ersatzbeitrag wird als normaler Beitrag erfasst.' },
  en: { change: 'Change helper duty', title: 'Helper shifts', mine: '{done} of {quota} helper shifts this season ({from} to {to}).', doneAll: 'Done – thank you!', counts: 'Shifts count once they have taken place, your children’s too.', admin: 'Helper duty', quota: 'Shifts per season (0 = off)', month: 'Season starts (month)', save: 'Save', saved: 'Saved.', failed: 'Not saved.', member: 'Member', done: 'Done', owed: 'Still owed', off: 'Helper duty is off.', none: 'Nobody is affected.', hint: 'Applies to adults in a team and parents of children in a team. Any substitute fee is entered as a normal fee.' },
  fr: { change: 'Modifier l’obligation de bénévolat', title: 'Interventions de bénévolat', mine: '{done} interventions sur {quota} cette saison (du {from} au {to}).', doneAll: 'C’est fait – merci !', counts: 'Les interventions comptent une fois effectuées, celles de vos enfants aussi.', admin: 'Obligation de bénévolat', quota: 'Interventions par saison (0 = désactivé)', month: 'Début de saison (mois)', save: 'Enregistrer', saved: 'Enregistré.', failed: 'Non enregistré.', member: 'Membre', done: 'Effectuées', owed: 'Restantes', off: 'L’obligation de bénévolat est désactivée.', none: 'Personne n’est concerné.', hint: 'Concerne les adultes d’une équipe et les parents d’enfants dans une équipe. Une éventuelle contribution de remplacement est saisie comme cotisation normale.' },
  it: { change: 'Modifica l’obbligo di aiuto', title: 'Turni di volontariato', mine: '{done} turni su {quota} in questa stagione (dal {from} al {to}).', doneAll: 'Fatto – grazie!', counts: 'I turni contano quando si sono svolti, anche quelli dei vostri figli.', admin: 'Obbligo di volontariato', quota: 'Turni per stagione (0 = disattivato)', month: 'Inizio stagione (mese)', save: 'Salva', saved: 'Salvato.', failed: 'Non salvato.', member: 'Membro', done: 'Svolti', owed: 'Mancanti', off: 'L’obbligo di volontariato è disattivato.', none: 'Nessuno è interessato.', hint: 'Riguarda gli adulti in una squadra e i genitori di figli in una squadra. Un eventuale contributo sostitutivo si registra come quota normale.' },
} satisfies Record<Language, Record<string, string>>

const fill = (text: string, values: Record<string, string | number>) =>
  text.replace(/\{(\w+)\}/g, (_, key: string) => String(values[key] ?? ''))
const swissDate = (date: string) => date.slice(8, 10) + '.' + date.slice(5, 7) + '.' + date.slice(0, 4)

/** Family → fees: the member's own count; shown only when the club asks them for shifts. */
export function MyHelperDutyCard({ language }: { language: Language }) {
  const t = words[language]
  const [duty, setDuty] = useState<MyHelperDuty | null>(null)
  useEffect(() => {
    let active = true
    readMyHelperDuty().then(result => { if (active && result !== 'denied') setDuty(result) }).catch(() => {})
    return () => { active = false }
  }, [])
  if (!duty || duty.quota === 0) return null
  return <section aria-labelledby="helper-duty" className="card pad stack">
    <h2 id="helper-duty">{t.title}</h2>
    <p>{fill(t.mine, { done: duty.done, quota: duty.quota, from: swissDate(duty.seasonFrom), to: swissDate(duty.seasonTo) })}
      {duty.done >= duty.quota && <> <b>{t.doneAll}</b></>}</p>
    <p className="muted small">{t.counts}</p>
  </section>
}

/** Administration → fees: the quota, the season and who still owes shifts. */
export function HelperDutyAdmin({ language, csrfToken }: { language: Language; csrfToken: string }) {
  const t = words[language]
  const [overview, setOverview] = useState<HelperDutyOverview | 'denied' | 'error' | null>(null)
  const [quota, setQuota] = useState(0)
  const [month, setMonth] = useState(8)
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    readHelperDuty().then(result => {
      if (!active) return
      setOverview(result)
      if (typeof result === 'object') { setQuota(result.quota); setMonth(result.seasonStartMonth) }
    }).catch(() => { if (active) setOverview('error') })
    return () => { active = false }
  }, [revision])
  if (overview === null || overview === 'denied') return null
  if (overview === 'error') return <p role="alert">{t.failed}</p>
  async function save(event: FormEvent) {
    event.preventDefault(); setNotice(null)
    try {
      const outcome = await saveHelperDuty(quota, month, csrfToken)
      setNotice(outcome === 'ok' ? { ok: true, text: t.saved } : { ok: false, text: t.failed })
      if (outcome === 'ok') setRevision(value => value + 1)
    } catch { setNotice({ ok: false, text: t.failed }) }
  }
  return <section aria-labelledby="helper-duty-admin" className="stack">
    <h2 id="helper-duty-admin">{t.admin}</h2>
    <p className="muted small">{t.hint}</p>
    <Reveal label={t.change}><form className="actions" onSubmit={event => { void save(event) }}>
      <label>{t.quota}<input type="number" min={0} max={50} value={quota} onChange={e => setQuota(Number(e.target.value))} /></label>
      <label>{t.month}<input type="number" min={1} max={12} value={month} onChange={e => setMonth(Number(e.target.value))} /></label>
      <button type="submit" className="btn primary">{t.save}</button>
    </form></Reveal>
    {notice && <p role={notice.ok ? 'status' : 'alert'}>{notice.text}</p>}
    {overview.quota === 0 ? <p className="muted">{t.off}</p> : overview.items.length === 0 ? <p>{t.none}</p> : <table className="cards">
      <caption>{t.admin}: {swissDate(overview.seasonFrom)}–{swissDate(overview.seasonTo)}</caption>
      <thead><tr><th scope="col">{t.member}</th><th scope="col">{t.done}</th><th scope="col">{t.owed}</th></tr></thead>
      <tbody>{overview.items.map(item => <tr key={item.memberId}>
        <th scope="row">{item.displayName}</th><td data-label={t.done}>{item.done}</td><td data-label={t.owed}>{item.owed}</td></tr>)}</tbody>
    </table>}
  </section>
}
