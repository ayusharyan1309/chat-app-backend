import { ChevronLeft } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useDocumentTitle } from '../../hooks/useDocumentTitle'

export function PageHeader({
  title,
  description,
  actions,
  back,
  meta,
  documentTitle,
}: {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  back?: { to: string; label: string }
  /** Inline badges next to the title. */
  meta?: ReactNode
  documentTitle?: string
}) {
  useDocumentTitle(documentTitle ?? (typeof title === 'string' ? title : ''))
  return (
    <header className="page-header">
      {back ? (
        <Link to={back.to} className="page-header__back">
          <ChevronLeft size={14} aria-hidden />
          {back.label}
        </Link>
      ) : null}
      <div className="page-header__row">
        <div className="page-header__text">
          <div className="page-header__title-row">
            <h1 className="page-header__title">{title}</h1>
            {meta ? <div className="page-header__meta">{meta}</div> : null}
          </div>
          {description ? <p className="page-header__desc">{description}</p> : null}
        </div>
        {actions ? <div className="page-header__actions">{actions}</div> : null}
      </div>
    </header>
  )
}
