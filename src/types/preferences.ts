import { DEFAULT_PLAYER_PREFERENCES, type PlayerPreferences } from './player'

export type DesktopTheme = 'light' | 'dark' | 'system'
export type DesktopLocale = 'zh-CN' | 'zh-TW' | 'en' | 'fr'
export type DesktopPlayMode = 'sequential' | 'smart-shuffle' | 'repeat-one'

export interface DesktopPreferences extends PlayerPreferences {
  theme: DesktopTheme
  locale: DesktopLocale
  playMode: DesktopPlayMode
  volume: number
}

export interface DesktopPreferenceSnapshot {
  version: 1
  revision: number
  restoreRevision: number
  values: DesktopPreferences
}

export const DEFAULT_DESKTOP_PREFERENCES: Readonly<DesktopPreferences> = Object.freeze({
  ...DEFAULT_PLAYER_PREFERENCES, theme: 'light', locale: 'zh-CN', playMode: 'sequential', volume: 0.8,
})

const validators: { [K in keyof DesktopPreferences]: (value: unknown) => boolean } = {
  theme: value => ['light', 'dark', 'system'].includes(value as string),
  locale: value => ['zh-CN', 'zh-TW', 'en', 'fr'].includes(value as string),
  playMode: value => ['sequential', 'smart-shuffle', 'repeat-one'].includes(value as string),
  volume: value => typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 1,
  rememberPlayback: value => typeof value === 'boolean',
  alwaysOnTop: value => typeof value === 'boolean',
  prefetch: value => typeof value === 'boolean',
  normalizeVolume: value => typeof value === 'boolean',
  crossfadeSeconds: value => [0, 1, 2, 3].includes(value as number),
  lyricsSize: value => [18, 24, 30, 36].includes(value as number),
  showBackdrop: value => typeof value === 'boolean',
  accent: value => ['rose', 'blue', 'green', 'violet'].includes(value as string),
}
const keys = Object.keys(validators) as Array<keyof DesktopPreferences>
const record = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)

/** Legacy migration is deliberately tolerant, but only copies known, valid scalars. */
export function pickDesktopPreferences(input: unknown): Partial<DesktopPreferences> {
  const result: Record<string, unknown> = {}
  if (record(input)) for (const key of keys) {
    if (Object.prototype.hasOwnProperty.call(input, key) && validators[key](input[key])) result[key] = input[key]
  }
  return result as Partial<DesktopPreferences>
}

/** Normal writes are strict: an invalid/unknown field rejects the whole patch. */
export function validateDesktopPreferencePatch(input: unknown): Partial<DesktopPreferences> {
  if (!record(input) || Object.keys(input).length > keys.length) throw new Error('Invalid preference patch')
  for (const key of Object.keys(input)) {
    if (!Object.prototype.hasOwnProperty.call(validators, key) || !validators[key as keyof DesktopPreferences](input[key])) {
      throw new Error('Invalid preference field')
    }
  }
  return pickDesktopPreferences(input)
}

export function validateDesktopPreferenceSnapshot(input: unknown): DesktopPreferenceSnapshot {
  if (!record(input) || input.version !== 1 || Object.keys(input).length !== 4
    || !Number.isSafeInteger(input.revision) || (input.revision as number) < 1
    || !Number.isSafeInteger(input.restoreRevision) || (input.restoreRevision as number) < 0) {
    throw new Error('Unsupported or invalid preference snapshot')
  }
  const values = validateDesktopPreferencePatch(input.values)
  if (Object.keys(values).length !== keys.length) throw new Error('Incomplete preference snapshot')
  return Object.freeze({ version: 1, revision: input.revision as number,
    restoreRevision: input.restoreRevision as number, values: Object.freeze(values) as DesktopPreferences })
}
