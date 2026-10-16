import { describe, expect, it } from 'vitest'
import { safeMediaUrl, type UrlEnvironment } from './mediaUrl'

const sameOrigin: UrlEnvironment = { pageUrl: 'https://console.example.com/playground', apiBase: '' }
const separateApi: UrlEnvironment = { pageUrl: 'https://console.example.com/playground', apiBase: 'https://api.example.com/chat' }

describe('safeMediaUrl: images (avatars, logos)', () => {
  it('allows http(s) URLs anywhere', () => {
    expect(safeMediaUrl('https://cdn.acme.io/logo.png', 'image', sameOrigin)).toBe('https://cdn.acme.io/logo.png')
    expect(safeMediaUrl('http://cdn.acme.io/a.png', 'image', sameOrigin)).toBe('http://cdn.acme.io/a.png')
  })

  it('resolves relative paths against the backend', () => {
    expect(safeMediaUrl('/avatars/a.png', 'image', sameOrigin)).toBe('https://console.example.com/avatars/a.png')
    expect(safeMediaUrl('/avatars/a.png', 'image', separateApi)).toBe('https://api.example.com/chat/avatars/a.png')
  })

  it.each([
    'javascript:alert(1)',
    'JavaScript:alert(1)',
    ' javascript:alert(1)',
    'data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=',
    'vbscript:msgbox(1)',
    'file:///etc/passwd',
    'blob:https://evil.example/uuid',
    'https://user:secret@cdn.acme.io/a.png',
  ])('rejects %s', (raw) => {
    expect(safeMediaUrl(raw, 'image', sameOrigin)).toBeNull()
  })

  it('rejects empty and missing values', () => {
    expect(safeMediaUrl('', 'image', sameOrigin)).toBeNull()
    expect(safeMediaUrl('   ', 'image', sameOrigin)).toBeNull()
    expect(safeMediaUrl(null, 'image', sameOrigin)).toBeNull()
    expect(safeMediaUrl(undefined, 'image', sameOrigin)).toBeNull()
  })
})

describe('safeMediaUrl: message attachments', () => {
  it('allows the media endpoint on the page origin', () => {
    expect(safeMediaUrl('/api/v1/media/acme/abc?sig=1', 'attachment', sameOrigin)).toBe('https://console.example.com/api/v1/media/acme/abc?sig=1')
    expect(safeMediaUrl('https://console.example.com/api/v1/media/acme/abc', 'attachment', sameOrigin)).toBe('https://console.example.com/api/v1/media/acme/abc')
  })

  it('allows the media endpoint under the API base', () => {
    expect(safeMediaUrl('/api/v1/media/acme/abc?sig=1', 'attachment', separateApi)).toBe('https://api.example.com/chat/api/v1/media/acme/abc?sig=1')
    expect(safeMediaUrl('https://api.example.com/chat/api/v1/media/acme/abc', 'attachment', separateApi)).toBe('https://api.example.com/chat/api/v1/media/acme/abc')
  })

  it.each([
    ['another host', 'https://evil.example/api/v1/media/acme/abc'],
    ['API origin outside its base path', 'https://api.example.com/api/v1/media/acme/abc'],
    ['protocol-relative host', '//evil.example/api/v1/media/acme/abc'],
    ['backslash host', '\\\\evil.example/api/v1/media/x'],
    ['another endpoint on the page origin', '/api/admin/tenants'],
    ['path traversal', '/api/v1/media/../admin/tenants'],
    ['encoded path traversal', '/api/v1/media/%2e%2e/admin/tenants'],
    ['javascript scheme', 'javascript:alert(1)'],
    ['data URL', 'data:text/html,<script>alert(1)</script>'],
    ['prefix without the trailing slash', '/api/v1/mediaX/abc'],
  ])('rejects %s', (_label, raw) => {
    expect(safeMediaUrl(raw, 'attachment', separateApi)).toBeNull()
  })
})
