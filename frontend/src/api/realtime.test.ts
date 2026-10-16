import type { IStompSocket } from '@stomp/stompjs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RealtimeClient, type ConnectionState } from './realtime'

/** A socket that never finishes connecting; closing it reports the close like a browser would. */
class FakeSocket implements IStompSocket {
  url = 'ws://test/ws'
  binaryType = 'arraybuffer'
  readyState = 0
  onclose: ((ev?: unknown) => void) | null = null
  onerror: ((ev: unknown) => void) | null = null
  onmessage: ((ev: unknown) => void) | null = null
  onopen: ((ev?: unknown) => void) | null = null
  close(): void {
    if (this.readyState === 3) return
    this.readyState = 3
    this.onclose?.({ code: 1000, reason: '', wasClean: true })
  }
  send(): void {}
}

function setup(getToken: () => Promise<string>) {
  const sockets: FakeSocket[] = []
  const tokenErrors: number[] = []
  const states: ConnectionState[] = []
  const client = new RealtimeClient({
    getToken,
    onEvent: () => undefined,
    onStateChange: (state) => states.push(state),
    onTokenError: (_err, retryInMs) => tokenErrors.push(retryInMs),
    webSocketFactory: () => {
      const socket = new FakeSocket()
      sockets.push(socket)
      return socket
    },
  })
  return { client, sockets, tokenErrors, states }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('RealtimeClient token failures', () => {
  it('backs off and opens a socket once a token can be obtained again', async () => {
    let calls = 0
    const { client, sockets, tokenErrors } = setup(() => {
      calls += 1
      return calls <= 2 ? Promise.reject(new Error('backend down')) : Promise.resolve('token')
    })

    client.connect()
    await vi.advanceTimersByTimeAsync(0)
    // No CONNECT without credentials, and (crucially) no rejection escaping into stompjs.
    expect(sockets).toHaveLength(0)
    expect(tokenErrors).toEqual([1_000])

    await vi.advanceTimersByTimeAsync(1_000)
    expect(calls).toBe(2)
    expect(tokenErrors).toEqual([1_000, 2_000])
    expect(sockets).toHaveLength(0)

    await vi.advanceTimersByTimeAsync(2_000)
    expect(calls).toBe(3)
    expect(sockets).toHaveLength(1)

    await client.disconnect()
  })

  it('stops retrying after disconnect', async () => {
    let calls = 0
    const { client, sockets, states } = setup(() => {
      calls += 1
      return Promise.reject(new Error('backend down'))
    })
    client.connect()
    await vi.advanceTimersByTimeAsync(0)
    await client.disconnect()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(calls).toBe(1)
    expect(sockets).toHaveLength(0)
    expect(states[states.length - 1]).toBe('closed')
  })

  it('a manual reconnect retries immediately', async () => {
    let calls = 0
    const { client, sockets } = setup(() => {
      calls += 1
      return calls === 1 ? Promise.reject(new Error('backend down')) : Promise.resolve('token')
    })
    client.connect()
    await vi.advanceTimersByTimeAsync(0)
    await client.reconnect()
    await vi.advanceTimersByTimeAsync(0)
    expect(calls).toBe(2)
    expect(sockets).toHaveLength(1)
    await client.disconnect()
  })
})
