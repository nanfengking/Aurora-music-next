import { setLocale } from '../i18n'
import { DEFAULT_PLAYER_PREFERENCES, type PlayerPreferences } from '../types/player'
import { pickDesktopPreferences, type DesktopPreferenceSnapshot } from '../types/preferences'
import { connectDesktopPreferences } from './preferencePersistence'
import { usePlayerPreferences } from './playerPreferences'
import { usePlayerStore } from './playerStore'
import { useThemeStore, applyTheme } from './themeStore'
import { useLanguageStore } from './languageStore'

let revision = 0
function readLegacy() {
  const read = (key: string): string | null => { try { return localStorage.getItem(key) } catch { return null } }
  const json = (key: string): Record<string, unknown> => {
    try { return JSON.parse(read(key) || '{}')?.state || {} } catch { return {} }
  }
  const playback = json('aurora-playback')
  return pickDesktopPreferences({ ...pickDesktopPreferences(json('aurora-player-preferences').preferences),
    theme: read('aurora-theme'), locale: read('aurora-language'), volume: playback.volume, playMode: playback.playMode })
}

function apply(snapshot: DesktopPreferenceSnapshot): void {
  if (snapshot.revision <= revision) return
  const values = snapshot.values
  const preferences = Object.fromEntries(Object.keys(DEFAULT_PLAYER_PREFERENCES)
    .map(key => [key, values[key as keyof PlayerPreferences]])) as unknown as PlayerPreferences
  usePlayerPreferences.setState({ preferences })
  useThemeStore.setState({ theme: values.theme })
  applyTheme(values.theme)
  setLocale(values.locale, false)
  useLanguageStore.setState({ locale: values.locale })
  const playback = usePlayerStore.getState()
  usePlayerStore.setState({ playMode: values.playMode, volume: values.volume, restoreRevision: snapshot.restoreRevision,
    ...(!values.rememberPlayback || playback.restoreRevision !== snapshot.restoreRevision ? { currentId: null, position: 0 } : {}) })
  revision = snapshot.revision
}

export function initializeDesktopPreferences(): Promise<void> {
  return connectDesktopPreferences(readLegacy(), apply)
}
