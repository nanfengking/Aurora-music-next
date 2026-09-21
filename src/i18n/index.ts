import data from './messages.json'

export type Locale = 'zh-CN' | 'zh-TW' | 'en' | 'fr'
export const languages: Array<{ value: Locale; label: string }> = [
  { value: 'zh-CN', label: '简体中文' }, { value: 'zh-TW', label: '繁體中文' },
  { value: 'en', label: 'English' }, { value: 'fr', label: 'Français' },
]
export const isLocale = (value: unknown): value is Locale => languages.some(language => language.value === value)
let currentLocale: Locale = 'zh-CN'
try { const saved = localStorage.getItem('aurora-language'); if (isLocale(saved)) currentLocale = saved } catch { /* storage unavailable */ }
export const getLocale = (): Locale => currentLocale
export function setLocale(locale: Locale): void {
  currentLocale = locale
  try { localStorage.setItem('aurora-language', locale) } catch { /* private/blocked storage */ }
}
const messages = data as Record<string, Record<string, string>>
export function t(source: string, values: unknown[] = [], locale = currentLocale): string {
  const template = locale === 'zh-CN' ? source : messages[source]?.[locale] || source
  return template.replace(/\{(\d+)\}/g, (_match, index: string) => String(values[Number(index)] ?? `{${index}}`))
}

// Main-process status messages are translated only at explicit UI message boundaries.
// Never run this over songs, lyrics, user playlist names or DOM contents.
const exact = new Map<string, string>()
const patterns: Array<{ source: string; pattern: RegExp; indexes: number[] }> = []
const escape = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
for (const [source, translations] of Object.entries(messages)) {
  for (const text of [source, ...Object.values(translations)]) {
    if (!/\{\d+\}/.test(text)) { exact.set(text, source); continue }
    const indexes: number[] = []
    const pieces = text.split(/(\{\d+\})/).map(piece => {
      if (/^\{\d+\}$/.test(piece)) { indexes.push(Number(piece.slice(1, -1))); return '([\\s\\S]*?)' }
      return escape(piece)
    })
    patterns.push({ source, pattern: new RegExp(`^${pieces.join('')}$`), indexes })
  }
}
patterns.sort((a, b) => b.source.length - a.source.length)
export function tm(message: string, depth = 0): string {
  const known = exact.get(message)
  if (known) return t(known)
  if (depth > 3) return message
  // Provider names are user data; only our bounded failure suffix is translated.
  const failures = message.split('；')
  const providerFailure = failures.every(part => /^.+：(HTTP \d+|超时、网络失败或返回格式无效)$/.test(part))
  for (const item of patterns) {
    if (providerFailure && item.source === '{0}无效') continue
    const match = item.pattern.exec(message)
    if (!match) continue
    const values: string[] = []
    item.indexes.forEach((index, position) => { values[index] = match[position + 1] })
    // Only app-authored nested status fields, never artist/title placeholders.
    if (['探索推荐 · {0}', '场景理解 · {0}', '歌单编排 · {0}', '按优先级尝试后仍未得到有效响应。{0}'].includes(item.source)) values[0] = tm(values[0], depth + 1)
    if (item.source === '与你近期的 {0} 偏好相近') values[0] = t(values[0])
    return t(item.source, values)
  }
  if (providerFailure) return failures.map(part => {
    const at = part.lastIndexOf('：')
    return `${part.slice(0, at)}: ${t(part.slice(at + 1))}`
  }).join('; ')
  return message
}
export function artistLabel(value?: string | null): string { return !value || value === '未知艺术家' ? t('未知艺术家') : value }
