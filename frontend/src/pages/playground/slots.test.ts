import { describe, expect, it } from 'vitest'
import { MAX_PANES, parseSlots, slotKeys } from './slots'

describe('playground slots', () => {
  it('defaults to two pickers and keeps empty slots', () => {
    expect(parseSlots(null)).toEqual(['', ''])
    expect(parseSlots('')).toEqual([''])
    expect(parseSlots('alice,,bob')).toEqual(['alice', '', 'bob'])
  })

  it('drops duplicate users and caps the pane count', () => {
    expect(parseSlots('alice,bob,alice')).toEqual(['alice', 'bob'])
    expect(parseSlots('a,b,c,d,e,f')).toHaveLength(MAX_PANES)
  })

  it('keys signed-in panes by tenant and user so closing one does not remount the others', () => {
    const before = slotKeys('acme', ['alice', 'bob', 'carol'])
    const after = slotKeys('acme', ['bob', 'carol'])
    expect(after).toEqual(before.slice(1))
    expect(new Set(before).size).toBe(before.length)
  })

  it('keys pickers by their order among pickers', () => {
    expect(slotKeys('acme', ['', 'alice', ''])).toEqual(['picker:acme:0', 'pane:acme:alice', 'picker:acme:1'])
  })
})
