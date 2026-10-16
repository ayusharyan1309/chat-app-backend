import { toApiError } from '../api/http'

export type ToastTone = 'success' | 'error' | 'info'

export type PushToast = (tone: ToastTone, title: string, description?: string) => void

export interface ToastApi {
  success: (title: string, description?: string) => void
  info: (title: string, description?: string) => void
  /** An error you describe yourself: `title` is the headline, `description` the detail. */
  error: (title: string, description?: string) => void
  /**
   * A thrown value. The backend's message (or a client-side description) becomes the detail under
   * `title`, or the headline when no title is given. Cancelled requests are not reported.
   */
  fromError: (err: unknown, title?: string) => void
}

export function createToastApi(push: PushToast): ToastApi {
  return {
    success: (title, description) => push('success', title, description),
    info: (title, description) => push('info', title, description),
    error: (title, description) => push('error', title, description),
    fromError: (err, title) => {
      const apiError = toApiError(err)
      if (apiError.isCancelled) return
      if (title) push('error', title, apiError.message)
      else push('error', apiError.message)
    },
  }
}
