export type UsageMetric = 'messages' | 'apiCalls' | 'mediaBytes'

export const USAGE_METRICS: ReadonlyArray<{ value: UsageMetric; label: string }> = [
  { value: 'messages', label: 'Messages' },
  { value: 'apiCalls', label: 'API calls' },
  { value: 'mediaBytes', label: 'Media' },
]
