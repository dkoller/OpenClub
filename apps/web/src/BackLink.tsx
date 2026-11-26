/** The one way back to the page above: "← Family", "← Schedule", "← Juniors U12". */
export function BackLink({ href, label }: { href: string; label: string }) {
  return <p className="back"><a href={href}>{'← ' + label}</a></p>
}
