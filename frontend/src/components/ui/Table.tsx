import type { MouseEvent, ReactNode } from 'react'
import { cx } from '../../lib/util'
import { Skeleton } from './Feedback'

export interface Column<T> {
  key: string
  header: ReactNode
  cell: (row: T, index: number) => ReactNode
  align?: 'left' | 'right' | 'center'
  width?: string
  /** Hide below this breakpoint to keep tables readable on tablets. */
  hideBelow?: 'md' | 'lg'
  className?: string
}

export interface DataTableProps<T> {
  columns: ReadonlyArray<Column<T>>
  rows: ReadonlyArray<T> | undefined
  rowKey: (row: T) => string
  loading?: boolean
  /** Rendered when rows is an empty array. */
  empty?: ReactNode
  /**
   * Mouse convenience: clicking anywhere on a row. Keyboard users reach the same destination via a
   * link inside the row, so rows are not made focusable.
   */
  onRowClick?: (row: T) => void
  rowClassName?: (row: T) => string | undefined
  caption?: string
  skeletonRows?: number
  dense?: boolean
  className?: string
  footer?: ReactNode
}

function isInteractive(target: EventTarget | null): boolean {
  return target instanceof Element && Boolean(target.closest('a, button, input, select, textarea, label, [role="switch"]'))
}

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  loading,
  empty,
  onRowClick,
  rowClassName,
  caption,
  skeletonRows = 5,
  dense,
  className,
  footer,
}: DataTableProps<T>) {
  const showSkeleton = loading && !rows
  const colClass = (col: Column<T>) =>
    cx(col.align && col.align !== 'left' && `is-${col.align}`, col.hideBelow && `hide-below-${col.hideBelow}`, col.className)

  return (
    <div className={cx('table-wrap', className)}>
      <table className={cx('table', dense && 'table--dense', onRowClick && 'table--clickable')}>
        {caption ? <caption className="sr-only">{caption}</caption> : null}
        <thead>
          <tr>
            {columns.map((col) => (
              <th key={col.key} scope="col" className={colClass(col)} style={col.width ? { width: col.width } : undefined}>
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {showSkeleton
            ? Array.from({ length: skeletonRows }, (_, i) => (
                <tr key={`sk-${i}`} aria-hidden>
                  {columns.map((col, c) => (
                    <td key={col.key} className={colClass(col)}>
                      <Skeleton width={c === 0 ? '70%' : '50%'} />
                    </td>
                  ))}
                </tr>
              ))
            : rows?.map((row, index) => (
                <tr
                  key={rowKey(row)}
                  className={rowClassName?.(row)}
                  onClick={
                    onRowClick
                      ? (e: MouseEvent<HTMLTableRowElement>) => {
                          if (!isInteractive(e.target) && !window.getSelection()?.toString()) onRowClick(row)
                        }
                      : undefined
                  }
                >
                  {columns.map((col) => (
                    <td key={col.key} className={colClass(col)}>
                      {col.cell(row, index)}
                    </td>
                  ))}
                </tr>
              ))}
        </tbody>
      </table>
      {!showSkeleton && rows && rows.length === 0 && empty ? <div className="table-empty">{empty}</div> : null}
      {footer ? <div className="table-footer">{footer}</div> : null}
    </div>
  )
}
