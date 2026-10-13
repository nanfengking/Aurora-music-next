import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { DEFAULT_PLAYER_PREFERENCES, type PlayerPreferences } from '../types/player'

interface PreferencesState {
  preferences: PlayerPreferences
  update: (patch: Partial<PlayerPreferences>) => void
  reset: () => void
}

export const usePlayerPreferences = create<PreferencesState>()(persist((set) => ({
  preferences: { ...DEFAULT_PLAYER_PREFERENCES },
  update: (patch) => set(({ preferences }) => ({ preferences: { ...preferences, ...patch } })),
  reset: () => set({ preferences: { ...DEFAULT_PLAYER_PREFERENCES } }),
}), {
  name: 'aurora-player-preferences',
  merge: (saved, current) => {
    const input = (saved as Partial<PreferencesState> | undefined)?.preferences
    const preferences = { ...DEFAULT_PLAYER_PREFERENCES }
    if (input && typeof input === 'object') {
      for (const key of ['rememberPlayback', 'alwaysOnTop', 'prefetch', 'normalizeVolume', 'showBackdrop'] as const) {
        if (typeof input[key] === 'boolean') preferences[key] = input[key]
      }
      if ([0, 1, 2, 3].includes(input.crossfadeSeconds)) preferences.crossfadeSeconds = input.crossfadeSeconds
      if ([18, 24, 30, 36].includes(input.lyricsSize)) preferences.lyricsSize = input.lyricsSize
      if (['rose', 'blue', 'green', 'violet'].includes(input.accent)) preferences.accent = input.accent
    }
    return { ...current, preferences }
  },
}))
