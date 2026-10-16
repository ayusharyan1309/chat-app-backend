const numberFmt = new Intl.NumberFormat(undefined)
const compactFmt = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 })
const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })
const dateTimeFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })
const dateFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })
const timeFmt = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' })
const dayLabelFmt = new Intl.DateTimeFormat(undefined, { weekday: 'long', day: 'numeric', month: 'long' })
const shortDayFmt = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' })

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return numberFmt.format(value)
}

/** Compact for large values (12.3K), exact below 10,000. */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return Math.abs(value) >= 10_000 ? compactFmt.format(value) : numberFmt.format(value)
}

export function formatLimit(value: number | null | undefined, unit?: string): string {
  if (value === null || value === undefined) return 'Unlimited'
  return unit ? `${formatCount(value)} ${unit}` : formatCount(value)
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 100 ? value.toFixed(0) : value.toFixed(1)} ${units[unit]}`
}

export function formatLatency(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—'
  if (ms < 1) return '<1 ms'
  if (ms >= 1000) return `${(ms / 1000).toFixed(1)} s`
  return `${Math.round(ms)} ms`
}

export function formatMoney(amount: number | null | undefined, currency: string): string {
  if (amount === null || amount === undefined) return '—'
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency, maximumFractionDigits: amount % 1 === 0 ? 0 : 2 }).format(amount)
  } catch {
    return `${amount} ${currency}`
  }
}

function toDate(value: string | number | Date): Date {
  return value instanceof Date ? value : new Date(value)
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const d = toDate(value)
  return Number.isNaN(d.getTime()) ? value : dateTimeFmt.format(d)
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  const d = toDate(value)
  return Number.isNaN(d.getTime()) ? value : dateFmt.format(d)
}

export function formatTime(value: string | number | Date): string {
  return timeFmt.format(toDate(value))
}

export function formatShortDay(value: string): string {
  // "2026-10-16" is a calendar day; parse as local midnight to avoid TZ drift.
  const d = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T00:00:00`) : new Date(value)
  return Number.isNaN(d.getTime()) ? value : shortDayFmt.format(d)
}

const RELATIVE_STEPS: Array<[Intl.RelativeTimeFormatUnit, number]> = [
  ['second', 60],
  ['minute', 60],
  ['hour', 24],
  ['day', 7],
  ['week', 4.345],
  ['month', 12],
  ['year', Number.POSITIVE_INFINITY],
]

export function formatRelative(value: string | null | undefined, now: number = Date.now()): string {
  if (!value) return '—'
  const d = toDate(value)
  if (Number.isNaN(d.getTime())) return value
  let delta = (d.getTime() - now) / 1000
  if (Math.abs(delta) < 45) return 'just now'
  for (const [unit, size] of RELATIVE_STEPS) {
    if (Math.abs(delta) < size) return rtf.format(Math.round(delta), unit)
    delta /= size
  }
  return dateFmt.format(d)
}

export function formatDuration(totalSeconds: number | null | undefined): string {
  if (totalSeconds === null || totalSeconds === undefined) return '—'
  const s = Math.max(0, Math.floor(totalSeconds))
  const days = Math.floor(s / 86400)
  const hours = Math.floor((s % 86400) / 3600)
  const minutes = Math.floor((s % 3600) / 60)
  if (days > 0) return `${days}d ${hours}h`
  if (hours > 0) return `${hours}h ${minutes}m`
  if (minutes > 0) return `${minutes}m`
  return `${s}s`
}

export function isSameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

/** "Today", "Yesterday" or "Monday, 14 October" for chat day separators. */
export function formatDaySeparator(value: string): string {
  const d = new Date(value)
  const today = new Date()
  const yesterday = new Date()
  yesterday.setDate(today.getDate() - 1)
  if (isSameDay(d, today)) return 'Today'
  if (isSameDay(d, yesterday)) return 'Yesterday'
  return dayLabelFmt.format(d)
}

/** Inbox timestamp: time today, weekday this week, otherwise short date. */
export function formatInboxTime(value: string | null | undefined): string {
  if (!value) return ''
  const d = new Date(value)
  const now = new Date()
  if (isSameDay(d, now)) return timeFmt.format(d)
  const diffDays = (now.getTime() - d.getTime()) / 86_400_000
  if (diffDays < 6) return new Intl.DateTimeFormat(undefined, { weekday: 'short' }).format(d)
  return shortDayFmt.format(d)
}

export function pluralize(count: number, singular: string, plural = `${singular}s`): string {
  return `${formatNumber(count)} ${count === 1 ? singular : plural}`
}

export function initials(name: string | null | undefined): string {
  const clean = (name ?? '').trim()
  if (!clean) return '?'
  const parts = clean.split(/[\s._-]+/).filter(Boolean)
  const first = parts[0]?.[0] ?? ''
  const second = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? '') : (parts[0]?.[1] ?? '')
  return `${first}${second}`.toUpperCase()
}

/** Stable hue (0-359) for avatars. */
export function hueFor(seed: string): number {
  let hash = 0
  for (let i = 0; i < seed.length; i += 1) hash = (hash * 31 + seed.charCodeAt(i)) | 0
  return Math.abs(hash) % 360
}

/** Lowercase, hyphenated slug suggestion from a display name. */
export function slugify(value: string): string {
  return value
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
}

export function titleCase(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase()
}
