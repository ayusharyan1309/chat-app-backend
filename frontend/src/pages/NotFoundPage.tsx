import { Compass } from 'lucide-react'
import { Link } from 'react-router-dom'
import { buttonClass } from '../components/ui/classes'
import { EmptyState } from '../components/ui/Feedback'
import { useDocumentTitle } from '../hooks/misc'

export function NotFoundPage() {
  useDocumentTitle('Not found')
  return (
    <EmptyState
      icon={<Compass size={20} aria-hidden />}
      title="This page does not exist"
      description="The link may be outdated, or the tenant or plan it pointed to was removed."
      action={
        <Link to="/" className={buttonClass('primary')}>
          Go to overview
        </Link>
      }
    />
  )
}
