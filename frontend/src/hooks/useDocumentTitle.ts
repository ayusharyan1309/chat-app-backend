import { useEffect } from 'react'

/** Set document.title while mounted. */
export function useDocumentTitle(title: string): void {
  useEffect(() => {
    const previous = document.title
    document.title = title ? `${title} – Chat Platform Console` : 'Chat Platform Console'
    return () => {
      document.title = previous
    }
  }, [title])
}
