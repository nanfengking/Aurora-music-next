import { create } from 'zustand'
import { DEFAULT_PLAYER_PREFERENCES, type PlayerPreferences } from '../types/player'
import { persistDesktopPreferences } from './preferencePersistence'

interface PreferencesState {
  preferences: PlayerPreferences
  update: (patch: Partial<PlayerPreferences>) => void
  reset: () => void
}

export const usePlayerPreferences = create<PreferencesState>()(() => ({
  preferences: { ...DEFAULT_PLAYER_PREFERENCES },
  update: patch => { void persistDesktopPreferences(patch) },
  reset: () => { void persistDesktopPreferences({ ...DEFAULT_PLAYER_PREFERENCES }) },
}))
