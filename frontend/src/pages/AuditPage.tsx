import { ChevronDown, ChevronRight, RefreshCw, ScrollText } from 'lucide-react'
import { Fragment, useState } from 'react'
import { Link } from 'react-router-dom'
import { auditApi } from '../api/admin'
import { tenantsApi } from '../api/tenants'
import { PageHeader } from '../components/layout/PageHeader'
import { Button, IconButton } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Badge, EmptyState, LoadError, Skeleton, type Tone } from '../components/ui/Feedback'
import { Select } from '../components/ui/Form'
import { useApi } from '../hooks/useApi'
import { usePagedApi } from '../hooks/usePagedApi'
import { formatDateTime, formatRelative } from '../lib/format'
import { stringifyDetails } from '../lib/util'
import type { AuditActorType, AuditEntry } from '../types'

const PAGE = 100

const ACTOR_TONE: Record<AuditActorType, Tone> = { ADMIN: 'accent', API_KEY: 'info', SYSTEM: 'neutral' }
const ACTOR_LABEL: Record<AuditActorType, string> = { ADMIN: 'Admin', API_KEY: 'API key', SYSTEM: 'System' }

function hasDetails(details: unknown): boolean {
  if (details === null || details === undefined || details === '') return false
  if (typeof details === 'object') return Object.keys(details as object).length > 0
  return true
}

const AUDIT_PAGING = {
  pageSize: PAGE,
  cursorOf: (entry: AuditEntry) => entry.id,
  idOf: (entry: AuditEntry) => String(entry.id),
}

export function AuditPage() {
  const [tenant, setTenant] = useState('')
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set())
  const tenants = useApi((signal) => tenantsApi.list({}, { signal }), 'tenants:all')
  // The key carries the filter, so a response or "load more" page for a previous filter is dropped.
  const audit = usePagedApi(
    (before, signal) => auditApi.list({ tenant: tenant || undefined, limit: PAGE, before }, { signal }),
    `audit:${tenant}`,
    AUDIT_PAGING,
  )
  const { items: entries, loading, loadingMore, hasMore, error } = audit

  const changeTenant = (next: string) => {
    setTenant(next)
    setExpanded(new Set())
  }

  const toggle = (id: string) =>
    setExpanded((set) => {
      const next = new Set(set)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  return (
    <>
      <PageHeader title="Audit log" description="Every change made by admins, API keys and the system, newest first." />
      <div className="toolbar">
        <Select aria-label="Filter by tenant" value={tenant} onChange={(e) => changeTenant(e.target.value)}>
          <option value="">All tenants and platform</option>
          {(tenants.data ?? []).map((t) => (
            <option key={t.slug} value={t.slug}>
              {t.name} ({t.slug})
            </option>
          ))}
        </Select>
        <div className="spacer" />
        <IconButton label="Refresh" variant="secondary" icon={<RefreshCw size={15} aria-hidden />} onClick={() => void audit.reload()} />
      </div>
      {error ? <LoadError error={error} onRetry={() => void audit.reload()} /> : null}
      <Card flush>
        <div className="table-wrap">
          <table className="table audit-table">
            <caption className="sr-only">Audit entries</caption>
            <thead>
              <tr>
                <th scope="col" style={{ width: 28 }}>
                  <span className="sr-only">Details</span>
                </th>
                <th scope="col">When</th>
                <th scope="col">Actor</th>
                <th scope="col">Action</th>
                <th scope="col">Target</th>
                <th scope="col" className="hide-below-md">
                  Tenant
                </th>
                <th scope="col" className="hide-below-lg">
                  IP
                </th>
              </tr>
            </thead>
            <tbody>
              {loading && entries.length === 0
                ? Array.from({ length: 8 }, (_, i) => (
                    <tr key={i} aria-hidden>
                      {Array.from({ length: 7 }, (__, c) => (
                        <td key={c} className={c === 5 ? 'hide-below-md' : c === 6 ? 'hide-below-lg' : undefined}>
                          {c === 0 ? null : <Skeleton width="70%" />}
                        </td>
                      ))}
                    </tr>
                  ))
                : entries.map((entry) => {
                    const id = String(entry.id)
                    const open = expanded.has(id)
                    const detailed = hasDetails(entry.details)
                    return (
                      <Fragment key={id}>
                        <tr className={open ? 'is-expanded' : undefined}>
                          <td>
                            {detailed ? (
                              <IconButton
                                size="sm"
                                label={open ? 'Hide details' : 'Show details'}
                                icon={open ? <ChevronDown size={14} aria-hidden /> : <ChevronRight size={14} aria-hidden />}
                                aria-expanded={open}
                                onClick={() => toggle(id)}
                              />
                            ) : null}
                          </td>
                          <td className="nowrap" title={formatDateTime(entry.at)}>
                            {formatRelative(entry.at)}
                          </td>
                          <td>
                            <div className="row">
                              <Badge tone={ACTOR_TONE[entry.actorType] ?? 'neutral'}>{ACTOR_LABEL[entry.actorType] ?? entry.actorType}</Badge>
                              <span className="truncate audit-table__actor">{entry.actor ?? '—'}</span>
                            </div>
                          </td>
                          <td>
                            <span className="mono">{entry.action}</span>
                          </td>
                          <td>
                            {entry.targetType ? (
                              <span className="audit-table__target">
                                <span className="muted">{entry.targetType}</span> <span className="mono">{entry.targetId ?? ''}</span>
                              </span>
                            ) : (
                              <span className="muted">—</span>
                            )}
                          </td>
                          <td className="hide-below-md">
                            {entry.tenant ? (
                              <Link to={`/tenants/${encodeURIComponent(entry.tenant)}`} className="mono">
                                {entry.tenant}
                              </Link>
                            ) : (
                              <span className="muted">Platform</span>
                            )}
                          </td>
                          <td className="hide-below-lg mono secondary">{entry.ip ?? ''}</td>
                        </tr>
                        {open ? (
                          <tr className="audit-table__details">
                            <td />
                            <td colSpan={6}>
                              <pre className="code-block">{stringifyDetails(entry.details)}</pre>
                            </td>
                          </tr>
                        ) : null}
                      </Fragment>
                    )
                  })}
            </tbody>
          </table>
        </div>
        {!loading && entries.length === 0 && !error ? (
          <EmptyState compact icon={<ScrollText size={18} aria-hidden />} title="No audit entries" description={tenant ? 'Nothing has been recorded for this tenant yet.' : 'Nothing has been recorded yet.'} />
        ) : null}
        {entries.length > 0 ? (
          <div className="table-footer row row--between">
            <span className="muted">
              Showing {entries.length} {entries.length === 1 ? 'entry' : 'entries'}
            </span>
            {hasMore ? (
              <Button size="sm" onClick={() => void audit.loadMore()} loading={loadingMore}>
                Load older entries
              </Button>
            ) : (
              <span className="muted">End of log</span>
            )}
          </div>
        ) : null}
      </Card>
    </>
  )
}
