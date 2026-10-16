import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react'
import type { ConversationSettingsPatch, Message, TenantSettings, User } from '../../types'
import { ChatEngine, type SessionStatus } from './chatEngine'
import { chatReducer, initialChatState, type ChatAction, type ChatState } from './chatState'

export interface Notice {
  id: number
  message: string
}

export interface ChatActions {
  open: (id: string | null) => void
  loadOlder: (id: string) => void
  loadMoreInbox: () => void
  send: (conversationId: string, content: string, replyToId: string | null) => void
  sendFile: (conversationId: string, file: File, caption: string, replyToId: string | null) => void
  retry: (clientMsgId: string) => void
  discard: (clientMsgId: string) => void
  edit: (message: Message, content: string) => void
  remove: (message: Message) => void
  react: (message: Message, emoji: string) => void
  notifyTyping: (conversationId: string) => void
  stopTyping: (conversationId: string) => void
  searchUsers: (q: string) => Promise<User[]>
  startDirect: (userId: string) => Promise<unknown>
  createGroup: (title: string, members: string[]) => Promise<unknown>
  updateSettings: (id: string, patch: ConversationSettingsPatch) => void
  reconnect: () => void
  /** Show a transient message in this pane. */
  notify: (message: string) => void
  dismissNotice: (id: number) => void
}

export interface ChatSession {
  state: ChatState
  me: User | null
  status: SessionStatus
  fatal: string | null
  notices: Notice[]
  actions: ChatActions
}

/**
 * Runs one impersonated end-user session (REST + STOMP) for a playground pane. The pane must be
 * keyed by tenant + user so a different identity always gets a fresh session.
 */
export function useChatSession(tenant: string, userId: string, features: readonly string[], settings: TenantSettings | null): ChatSession {
  const [state, dispatch] = useReducer(chatReducer, initialChatState)
  const [me, setMe] = useState<User | null>(null)
  const [status, setStatus] = useState<SessionStatus>('authenticating')
  const [fatal, setFatal] = useState<string | null>(null)
  const [notices, setNotices] = useState<Notice[]>([])

  // Synchronous mirror of the reducer state so the engine never reads a stale snapshot between
  // a dispatch and the next render. Same pure reducer, same action sequence, same result.
  const shadowRef = useRef<ChatState>(initialChatState)
  const featuresRef = useRef<ReadonlySet<string>>(new Set(features))
  const settingsRef = useRef(settings)
  const engineRef = useRef<ChatEngine | null>(null)
  const noticeId = useRef(1)

  const pushNotice = useCallback((message: string) => {
    const id = noticeId.current++
    setNotices((list) => [...list.filter((n) => n.message !== message).slice(-2), { id, message }])
    window.setTimeout(() => setNotices((list) => list.filter((n) => n.id !== id)), 7_000)
  }, [])

  useEffect(() => {
    featuresRef.current = new Set(features)
    settingsRef.current = settings
  })

  useEffect(() => {
    let alive = true
    const guard = (action: ChatAction) => {
      if (!alive) return
      shadowRef.current = chatReducer(shadowRef.current, action)
      dispatch(action)
    }
    const engine = new ChatEngine({
      tenant,
      userId,
      dispatch: guard,
      getState: () => shadowRef.current,
      getFeatures: () => featuresRef.current,
      getSettings: () => settingsRef.current,
      onMe: (user) => alive && setMe(user),
      onStatus: (s) => alive && setStatus(s),
      onFatal: (message) => alive && setFatal(message),
      onNotice: (message) => {
        if (alive) pushNotice(message)
      },
    })
    engineRef.current = engine
    void engine.start()
    return () => {
      alive = false
      engine.stop()
      if (engineRef.current === engine) engineRef.current = null
    }
  }, [tenant, userId, pushNotice])

  useEffect(() => {
    const onVisibility = () => {
      if (document.visibilityState === 'visible') engineRef.current?.onVisible()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => document.removeEventListener('visibilitychange', onVisibility)
  }, [])

  const actions = useMemo<ChatActions>(() => {
    const e = () => engineRef.current
    return {
      open: (id) => void e()?.open(id),
      loadOlder: (id) => void e()?.loadOlder(id),
      loadMoreInbox: () => void e()?.loadInbox(true),
      send: (conversationId, content, replyToId) => e()?.send(conversationId, content, replyToId),
      sendFile: (conversationId, file, caption, replyToId) => void e()?.sendFile(conversationId, file, caption, replyToId),
      retry: (id) => e()?.retry(id),
      discard: (id) => e()?.discard(id),
      edit: (message, content) => void e()?.edit(message, content),
      remove: (message) => void e()?.remove(message),
      react: (message, emoji) => void e()?.react(message, emoji),
      notifyTyping: (id) => e()?.notifyTyping(id),
      stopTyping: (id) => e()?.stopTyping(id),
      searchUsers: async (q) => (await e()?.searchUsers(q)) ?? [],
      startDirect: async (id) => e()?.startDirect(id),
      createGroup: async (title, members) => e()?.createGroup(title, members),
      updateSettings: (id, patch) => void e()?.updateSettings(id, patch),
      reconnect: () => e()?.reconnect(),
      notify: pushNotice,
      dismissNotice: (id) => setNotices((list) => list.filter((n) => n.id !== id)),
    }
  }, [pushNotice])

  return { state, me, status, fatal, notices, actions }
}
