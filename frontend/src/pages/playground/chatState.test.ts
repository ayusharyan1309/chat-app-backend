import { describe, expect, it } from 'vitest'
import {
  chatReducer,
  initialChatState,
  MAX_CACHED_THREADS,
  mergeMessages,
  sortedConversations,
  staleConversationIds,
  tickFor,
  type ChatAction,
  type ChatState,
} from './chatState'
import { makeConversation, makeMessage, makeParticipant, makePending } from './testFixtures'

function reduce(state: ChatState, ...actions: ChatAction[]): ChatState {
  return actions.reduce(chatReducer, state)
}

function withLoadedThread(id = 'c1', messages = [makeMessage(1), makeMessage(2)]): ChatState {
  const last = messages[messages.length - 1]
  const conversation = makeConversation(id, {
    lastSeq: last?.seq ?? 0,
    lastMessage: last ? { id: last.id, preview: last.content, senderId: last.senderId, at: last.createdAt } : null,
  })
  return reduce(initialChatState, { type: 'inbox/loaded', conversations: [conversation], hasMore: false }, { type: 'thread/loaded', id, messages, hasMoreBefore: false })
}

describe('mergeMessages', () => {
  it('appends an ascending batch after the newest message (fast path)', () => {
    const existing = [makeMessage(1), makeMessage(2)]
    const incoming = [makeMessage(3), makeMessage(4)]
    const merged = mergeMessages(existing, incoming)
    expect(merged.map((m) => m.seq)).toEqual([1, 2, 3, 4])
    // Existing objects are reused, the input arrays are not mutated.
    expect(merged[0]).toBe(existing[0])
    expect(existing).toHaveLength(2)
  })

  it('returns the same array when the batch is empty or adds nothing new', () => {
    const existing = [makeMessage(1), makeMessage(2)]
    expect(mergeMessages(existing, [])).toBe(existing)
    // Fresh objects with identical content (a re-fetched page) change nothing.
    expect(mergeMessages(existing, [makeMessage(1), makeMessage(2)])).toBe(existing)
  })

  it('dedupes by id', () => {
    const existing = [makeMessage(1), makeMessage(2)]
    const merged = mergeMessages(existing, [makeMessage(2), makeMessage(3), makeMessage(3)])
    expect(merged.map((m) => m.id)).toEqual(['m1', 'm2', 'm3'])
  })

  it('inserts out-of-order messages by seq', () => {
    const existing = [makeMessage(1), makeMessage(4)]
    const merged = mergeMessages(existing, [makeMessage(3), makeMessage(2)])
    expect(merged.map((m) => m.seq)).toEqual([1, 2, 3, 4])
  })

  it('prepends an older page', () => {
    const existing = [makeMessage(5), makeMessage(6)]
    const merged = mergeMessages(existing, [makeMessage(3), makeMessage(4)])
    expect(merged.map((m) => m.seq)).toEqual([3, 4, 5, 6])
  })

  it('replaces an updated message in place and keeps the untouched objects', () => {
    const existing = [makeMessage(1), makeMessage(2), makeMessage(3)]
    const edited = makeMessage(2, { content: 'edited', edited: true })
    const merged = mergeMessages(existing, [edited])
    expect(merged).not.toBe(existing)
    expect(merged[1]).toBe(edited)
    expect(merged[0]).toBe(existing[0])
    expect(merged[2]).toBe(existing[2])
  })

  it('treats reaction changes as updates', () => {
    const existing = [makeMessage(1, { reactions: { '👍': ['bob'] } })]
    expect(mergeMessages(existing, [makeMessage(1, { reactions: { '👍': ['bob'] } })])).toBe(existing)
    const merged = mergeMessages(existing, [makeMessage(1, { reactions: { '👍': ['bob', 'alice'] } })])
    expect(merged[0]?.reactions).toEqual({ '👍': ['bob', 'alice'] })
  })
})

describe('chatReducer', () => {
  it('reconciles an optimistic message by clientMsgId when the server confirms it', () => {
    let state = withLoadedThread()
    state = reduce(state, { type: 'pending/add', pending: makePending('cm-1') })
    expect(Object.keys(state.pending)).toEqual(['cm-1'])

    const stored = makeMessage(3, { senderId: 'alice', clientMsgId: 'cm-1', content: 'hello' })
    state = reduce(state, { type: 'message/confirmed', message: stored })
    expect(state.pending).toEqual({})
    expect(state.threads.c1?.messages.map((m) => m.id)).toEqual(['m1', 'm2', 'm3'])
    expect(state.conversations.c1?.lastSeq).toBe(3)

    // The echo of the same message on another channel (message.new) is deduplicated.
    const again = reduce(state, { type: 'message/confirmed', message: { ...stored } })
    expect(again.threads.c1?.messages).toHaveLength(3)
  })

  it('returns the same state for a duplicate incoming message', () => {
    const state = withLoadedThread()
    const next = reduce(state, { type: 'message/incoming', message: makeMessage(2), countUnread: true })
    expect(next).toBe(state)
  })

  it('counts unread only for new messages', () => {
    let state = withLoadedThread()
    state = reduce(state, { type: 'message/incoming', message: makeMessage(3), countUnread: true })
    expect(state.conversations.c1?.unreadCount).toBe(1)
    state = reduce(state, { type: 'message/incoming', message: makeMessage(3), countUnread: true })
    expect(state.conversations.c1?.unreadCount).toBe(1)
  })

  it('removes a conversation with its thread, typing state, optimistic sends and active selection', () => {
    let state = withLoadedThread()
    state = reduce(
      state,
      { type: 'active/set', id: 'c1' },
      { type: 'pending/add', pending: makePending('cm-1') },
      { type: 'pending/add', pending: makePending('cm-2', { conversationId: 'other' }) },
      { type: 'typing', id: 'c1', userId: 'bob', typing: true, now: 0 },
      { type: 'conv/remove', id: 'c1' },
    )
    expect(state.conversations.c1).toBeUndefined()
    expect(state.threads.c1).toBeUndefined()
    expect(state.typing.c1).toBeUndefined()
    expect(Object.keys(state.pending)).toEqual(['cm-2'])
    expect(state.activeId).toBeNull()
    expect(state.threadLru).not.toContain('c1')
  })

  it('keeps a failed open as not loaded with an error, and clears it on retry', () => {
    let state = reduce(initialChatState, { type: 'thread/loading', id: 'c1' }, { type: 'thread/failed', id: 'c1', error: 'Backend down' })
    expect(state.threads.c1).toMatchObject({ loaded: false, error: 'Backend down' })
    state = reduce(state, { type: 'thread/loading', id: 'c1' })
    expect(state.threads.c1).toMatchObject({ loaded: false, error: null })
    state = reduce(state, { type: 'thread/loaded', id: 'c1', messages: [makeMessage(1)], hasMoreBefore: false })
    expect(state.threads.c1).toMatchObject({ loaded: true, error: null })
  })

  it(`keeps message state for at most ${MAX_CACHED_THREADS} threads, evicting the least recently opened`, () => {
    let state = initialChatState
    const ids = Array.from({ length: MAX_CACHED_THREADS + 2 }, (_, i) => `c${i + 1}`)
    for (const id of ids) {
      state = reduce(state, { type: 'active/set', id }, { type: 'thread/loading', id }, { type: 'thread/loaded', id, messages: [makeMessage(1, { conversationId: id })], hasMoreBefore: false })
    }
    expect(Object.keys(state.threads)).toHaveLength(MAX_CACHED_THREADS)
    expect(state.threads.c1).toBeUndefined()
    expect(state.threads.c2).toBeUndefined()
    expect(state.threadLru[0]).toBe(ids[ids.length - 1])

    // Re-opening c3 makes it most recent; the next new thread evicts c4 instead.
    state = reduce(state, { type: 'active/set', id: 'c3' }, { type: 'active/set', id: 'c9' }, { type: 'thread/loading', id: 'c9' })
    expect(state.threads.c3).toBeDefined()
    expect(state.threads.c4).toBeUndefined()
  })

  it('never evicts the active thread', () => {
    let state = reduce(initialChatState, { type: 'active/set', id: 'keep' }, { type: 'thread/loading', id: 'keep' })
    for (let i = 0; i < MAX_CACHED_THREADS + 3; i += 1) state = reduce(state, { type: 'thread/loading', id: `x${i}` })
    expect(state.activeId).toBe('keep')
    expect(state.threads.keep).toBeDefined()
    expect(Object.keys(state.threads)).toHaveLength(MAX_CACHED_THREADS)
  })

  it('drops conversations listed as removed by an inbox refresh', () => {
    let state = reduce(initialChatState, { type: 'inbox/loaded', conversations: [makeConversation('a'), makeConversation('b')], hasMore: false })
    state = reduce(state, { type: 'inbox/loaded', conversations: [makeConversation('a')], hasMore: false, removed: ['b'] })
    expect(Object.keys(state.conversations)).toEqual(['a'])
  })

  it('does not let an older snapshot move the watermark backwards', () => {
    let state = withLoadedThread()
    state = reduce(state, { type: 'message/incoming', message: makeMessage(3), countUnread: false })
    state = reduce(state, { type: 'conv/upsert', conversation: makeConversation('c1', { lastSeq: 2 }) })
    expect(state.conversations.c1?.lastSeq).toBe(3)
  })

  it('keeps unchanged typing entries and state identity when nothing expires', () => {
    const state = reduce(initialChatState, { type: 'typing', id: 'c1', userId: 'bob', typing: true, now: 1_000 })
    expect(reduce(state, { type: 'typing/sweep', now: 2_000 })).toBe(state)
    const swept = reduce(state, { type: 'typing/sweep', now: 60_000 })
    expect(swept.typing).toEqual({})
  })

  it('ignores receipts that do not advance a watermark', () => {
    let state = withLoadedThread()
    state = reduce(state, { type: 'thread/participants', id: 'c1', participants: [makeParticipant('alice', 2, 2), makeParticipant('bob', 2, 1)] })
    const same = reduce(state, { type: 'receipt', id: 'c1', userId: 'bob', deliveredSeq: 1, readSeq: 1, me: 'alice' })
    expect(same).toBe(state)
    const advanced = reduce(state, { type: 'receipt', id: 'c1', userId: 'bob', deliveredSeq: 2, readSeq: 2, me: 'alice' })
    expect(advanced.threads.c1?.participants?.find((p) => p.userId === 'bob')?.readSeq).toBe(2)
  })
})

describe('staleConversationIds', () => {
  const at = (minute: number) => ({ id: `x${minute}`, preview: null, senderId: 'bob', at: new Date(Date.UTC(2026, 0, 1, 12, minute)).toISOString() })

  it('flags conversations newer than the oldest entry of a full first page that are missing from it', () => {
    const known = {
      a: makeConversation('a', { lastMessage: at(50) }),
      gone: makeConversation('gone', { lastMessage: at(40) }),
      older: makeConversation('older', { lastMessage: at(5) }),
    }
    const page = [makeConversation('a', { lastMessage: at(50) }), makeConversation('b', { lastMessage: at(30) })]
    // Page is "full" (pageSize 2): `older` may live on the next page, `gone` cannot.
    expect(staleConversationIds(known, page, 2)).toEqual(['gone'])
  })

  it('flags everything missing when the page is the whole inbox', () => {
    const known = { a: makeConversation('a'), old: makeConversation('old', { lastMessage: at(1) }) }
    expect(staleConversationIds(known, [makeConversation('a')], 50)).toEqual(['old'])
  })
})

describe('tickFor', () => {
  const me = 'alice'

  it('direct chat: sent, delivered, read', () => {
    expect(tickFor(5, [makeParticipant(me, 5, 5), makeParticipant('bob', 4, 3)], me)).toBe('sent')
    expect(tickFor(5, [makeParticipant(me, 5, 5), makeParticipant('bob', 5, 3)], me)).toBe('delivered')
    expect(tickFor(5, [makeParticipant(me, 5, 5), makeParticipant('bob', 5, 5)], me)).toBe('read')
  })

  it('a read watermark implies delivery', () => {
    expect(tickFor(5, [makeParticipant('bob', 0, 6)], me)).toBe('read')
  })

  it('group: needs every other participant', () => {
    const participants = [makeParticipant(me, 9, 9), makeParticipant('bob', 9, 9), makeParticipant('carol', 9, 2)]
    expect(tickFor(9, participants, me)).toBe('delivered')
    expect(tickFor(2, participants, me)).toBe('read')
    expect(tickFor(10, participants, me)).toBe('sent')
  })

  it('no other participants known: sent', () => {
    expect(tickFor(1, null, me)).toBe('sent')
    expect(tickFor(1, [makeParticipant(me, 1, 1)], me)).toBe('sent')
  })
})

describe('sortedConversations', () => {
  it('puts pinned first, then newest activity, and hides archived', () => {
    const at = (minute: number) => ({ id: 'x', preview: null, senderId: 'bob', at: new Date(Date.UTC(2026, 0, 1, 12, minute)).toISOString() })
    const list = sortedConversations({
      old: makeConversation('old', { lastMessage: at(1) }),
      pinned: makeConversation('pinned', { pinned: true, lastMessage: at(0) }),
      new: makeConversation('new', { lastMessage: at(9) }),
      archived: makeConversation('archived', { archived: true, lastMessage: at(30) }),
    })
    expect(list.map((c) => c.id)).toEqual(['pinned', 'new', 'old'])
  })
})
