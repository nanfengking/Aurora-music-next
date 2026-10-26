import { getLocale, isLocale } from '../../src/i18n'
import { mobileMusic, type Appearance, type AppearancePatch } from './native'

const colors = ['background', 'surface', 'text', 'muted', 'border', 'accent', 'container', 'onAccent'] as const
const booleans = ['dynamic', 'dynamicAvailable', 'motion', 'lowPowerVisuals', 'autoLookup', 'dark', 'systemReducedMotion', 'powerSave', 'batteryOptimized', 'backgroundRestricted', 'showBackdrop'] as const
const patchKeys = ['mode', 'accent', 'dynamic', 'motion', 'lowPowerVisuals', 'autoLookup', 'lyricsSize', 'showBackdrop'] as const
const hex = (value: unknown): value is string => typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value)

/** A malformed bridge response must never enable network consent or become CSS. */
export function checkedAppearance(input: unknown): Appearance {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('Invalid native appearance')
  const value = input as Appearance
  if (!['system', 'light', 'dark'].includes(value.mode) || !hex(value.accent)
    || ![18, 24, 30, 36].includes(value.lyricsSize) || !isLocale(value.effectiveLanguage)
    || (value.language !== '' && (!isLocale(value.language) || value.language !== value.effectiveLanguage))
    || booleans.some(key => typeof value[key] !== 'boolean')
    || !value.palette || typeof value.palette !== 'object' || colors.some(key => !hex(value.palette[key])))
    throw new Error('Invalid native appearance')
  return {
    mode: value.mode, accent: value.accent, lyricsSize: value.lyricsSize, language: value.language,
    effectiveLanguage: value.effectiveLanguage,
    ...Object.fromEntries(booleans.map(key => [key, value[key]])) as Pick<Appearance, typeof booleans[number]>,
    palette: Object.fromEntries(colors.map(key => [key, value.palette[key]])),
  }
}

export async function readNativeAppearance(): Promise<Appearance> { return checkedAppearance(await mobileMusic.appearance()) }

/** Browser preview only. Native startup never imports this optional browser storage. */
export function previewAppearance(): Appearance {
  const locale = getLocale()
  const value: Appearance = {
    mode: 'system', accent: '#b94b5c', dynamic: true, dynamicAvailable: false, motion: true, lowPowerVisuals: true,
    autoLookup: false, lyricsSize: 18, showBackdrop: true, language: locale, effectiveLanguage: locale,
    dark: false, systemReducedMotion: false, powerSave: false, batteryOptimized: false, backgroundRestricted: false, palette: {},
  }
  try {
    const saved = JSON.parse(localStorage.getItem('aurora-mobile-appearance') || '{}')
    if (!saved || typeof saved !== 'object' || Array.isArray(saved)) return value
    if (['system', 'light', 'dark'].includes(saved.mode)) value.mode = saved.mode
    if (hex(saved.accent)) value.accent = saved.accent
    if ([18, 24, 30, 36].includes(saved.lyricsSize)) value.lyricsSize = saved.lyricsSize
    for (const key of ['dynamic', 'motion', 'lowPowerVisuals', 'autoLookup', 'showBackdrop'] as const)
      if (typeof saved[key] === 'boolean') value[key] = saved[key]
  } catch { /* preview storage is optional */ }
  return value
}

export function appearancePatch(change: AppearancePatch): AppearancePatch {
  const patch: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(change)) {
    if (!patchKeys.includes(key as typeof patchKeys[number])) throw new Error('Invalid appearance patch')
    if (key === 'mode' ? !['system', 'light', 'dark'].includes(value as string)
      : key === 'accent' ? !hex(value) : key === 'lyricsSize' ? ![18, 24, 30, 36].includes(value as number) : typeof value !== 'boolean')
      throw new Error('Invalid appearance patch')
    patch[key] = value
  }
  return patch as AppearancePatch
}
