import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/http'
import { createToastApi, type ToastTone } from './toastApi'

function setup() {
  const pushed: Array<{ tone: ToastTone; title: string; description: string | undefined }> = []
  const api = createToastApi((tone, title, description) => pushed.push({ tone, title, description }))
  return { api, pushed }
}

describe('toast API', () => {
  it('error(title, description) shows exactly what it is given', () => {
    const { api, pushed } = setup()
    api.error('Test event was not delivered', 'HTTP 500 from https://hooks.example')
    expect(pushed).toEqual([{ tone: 'error', title: 'Test event was not delivered', description: 'HTTP 500 from https://hooks.example' }])
  })

  it('error(title) never turns a plain string into a generic message', () => {
    const { api, pushed } = setup()
    api.error('Could not save')
    expect(pushed).toEqual([{ tone: 'error', title: 'Could not save', description: undefined }])
  })

  it('fromError(err, title) puts the backend message under the title', () => {
    const { api, pushed } = setup()
    api.fromError(new ApiError('Plan is in use', 409, 'conflict'), 'Could not delete the plan')
    expect(pushed).toEqual([{ tone: 'error', title: 'Could not delete the plan', description: 'Plan is in use' }])
  })

  it('fromError(err) uses the message as the title', () => {
    const { api, pushed } = setup()
    api.fromError(new Error('Socket closed'))
    api.fromError('weird thrown value')
    expect(pushed.map((p) => p.title)).toEqual(['Socket closed', 'Something went wrong.'])
  })

  it('fromError ignores cancelled requests', () => {
    const { api, pushed } = setup()
    api.fromError(new ApiError('Request cancelled.', 0, 'cancelled'), 'Could not load')
    expect(pushed).toEqual([])
  })

  it('success and info pass title and description through', () => {
    const { api, pushed } = setup()
    api.success('Saved', 'All good')
    api.info('Heads up')
    expect(pushed).toEqual([
      { tone: 'success', title: 'Saved', description: 'All good' },
      { tone: 'info', title: 'Heads up', description: undefined },
    ])
  })
})
