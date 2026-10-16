/** Pragmatic address check for forms: something@domain.tld, no whitespace. The backend validates for real. */
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function isEmail(value: string): boolean {
  return EMAIL_PATTERN.test(value.trim())
}
