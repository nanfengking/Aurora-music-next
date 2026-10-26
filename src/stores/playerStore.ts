import { create } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import { usePlayerPreferences } from './playerPreferences'
import { persistDesktopPreferences } from './preferencePersistence'

export type StoredPlayMode = 'sequential' | 'smart-shuffle' | 'repeat-one'
export const useSessionStorageState = create<{ error: boolean }>(() => ({ error: false }))

interface PlayerState {
  playMode: StoredPlayMode
  volume: number
  queue: string[]
  queueIndex: number
  currentId: string | null
  position: number
  restoreRevision: number
  setSession: (currentId: string | null, position: number) => void
  setPlayMode: (mode: StoredPlayMode) => Promise<boolean>
  setVolume: (volume: number) => Promise<boolean>
  setQueue: (songIds: string[], startIndex?: number) => void
  setQueueIndex: (index: number) => void
}

export const usePlayerStore = create<PlayerState>()(persist((set) => ({
  playMode: 'sequential',
  volume: 0.8,
  queue: [],
  queueIndex: -1,
  currentId: null,
  position: 0,
  restoreRevision: 0,
  setSession: (currentId, position) => set({ currentId, position }),
  setPlayMode: playMode => persistDesktopPreferences({ playMode }),
  setVolume: volume => persistDesktopPreferences({ volume }),
  setQueue: (queue, queueIndex = 0) => set({ queue, queueIndex }),
  setQueueIndex: (queueIndex) => set({ queueIndex }),
}), {
  name: 'aurora-playback',
  // This optional recovery cache is not the authority for preferences. A full
  // browser store must not turn an already committed SQLite write into failure.
  storage: createJSONStorage(() => ({
    getItem: name => { try { return localStorage.getItem(name) } catch { return null } },
    setItem: (name, value) => {
      try { localStorage.setItem(name, value); useSessionStorageState.setState({ error: false }) }
      catch { useSessionStorageState.setState({ error: true }) }
    },
    removeItem: name => {
      try { localStorage.removeItem(name); useSessionStorageState.setState({ error: false }) }
      catch { useSessionStorageState.setState({ error: true }) }
    },
  })),
  partialize: ({ queue, currentId, position, restoreRevision }) => ({
    restoreRevision,
    ...(usePlayerPreferences.getState().preferences.rememberPlayback ? { queue, currentId, position } : { queue: [], currentId: null, position: 0 }),
  }),
  merge: (saved, current) => {
    const data = saved as Partial<PlayerState> | undefined
    if (!data || typeof data !== 'object') return current
    return {
      ...current,
      queue: Array.isArray(data.queue) ? data.queue.filter((id): id is string => typeof id === 'string').slice(0, 50000) : [],
      currentId: typeof data.currentId === 'string' ? data.currentId : null,
      position: Number.isFinite(data.position) ? Math.max(0, data.position!) : 0,
      restoreRevision: Number.isSafeInteger(data.restoreRevision) && data.restoreRevision! >= 0 ? data.restoreRevision! : 0,
      volume: Number.isFinite(data.volume) ? Math.max(0, Math.min(1, data.volume!)) : current.volume,
      playMode: ['sequential', 'smart-shuffle', 'repeat-one'].includes(data.playMode || '') ? data.playMode! : current.playMode,
    }
  },
}))
