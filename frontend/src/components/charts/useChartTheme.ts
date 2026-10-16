import { useMemo } from 'react'
import { useTheme } from '../../state/theme'

export interface ChartTheme {
  series1: string
  series2: string
  grid: string
  axis: string
  surface: string
  ink: string
  ink3: string
}

function read(styles: CSSStyleDeclaration, name: string, fallback: string): string {
  return styles.getPropertyValue(name).trim() || fallback
}

/** Resolve chart colours from CSS tokens for the active theme (SVG attributes need literal values). */
export function useChartTheme(): ChartTheme {
  const { theme } = useTheme()
  return useMemo(() => {
    const styles = getComputedStyle(document.documentElement)
    return {
      series1: read(styles, '--chart-1', '#2a78d6'),
      series2: read(styles, '--chart-2', '#eb6834'),
      grid: read(styles, '--chart-grid', '#e7eaf0'),
      axis: read(styles, '--chart-axis', '#8a93a5'),
      surface: read(styles, '--chart-surface', '#ffffff'),
      ink: read(styles, '--ink', '#18202f'),
      ink3: read(styles, '--ink-3', '#6f798d'),
    }
    // Recompute when the theme attribute changes.
    // eslint-disable-next-line react/exhaustive-deps
  }, [theme])
}
