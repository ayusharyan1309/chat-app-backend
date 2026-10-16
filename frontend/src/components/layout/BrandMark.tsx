/** Product mark: a speech bubble on a stand, i.e. a message plugged into a socket. */
export function BrandMark({ size = 28 }: { size?: number }) {
  return (
    <svg className="brand-mark" width={size} height={size} viewBox="0 0 32 32" fill="none" aria-hidden>
      <rect width="32" height="32" rx="8" fill="var(--accent)" />
      <path d="M9 11.5A3.5 3.5 0 0 1 12.5 8h7A3.5 3.5 0 0 1 23 11.5v3a3.5 3.5 0 0 1-3.5 3.5H15l-4 3.5V18A3.5 3.5 0 0 1 9 14.5z" fill="var(--on-accent)" />
      <circle cx="13" cy="13" r="1.4" fill="var(--accent)" />
      <circle cx="19" cy="13" r="1.4" fill="var(--accent)" />
      <path d="M16 21.5v2.5M12.5 26h7" stroke="var(--on-accent)" strokeWidth="2" strokeLinecap="round" />
    </svg>
  )
}
