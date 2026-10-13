import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { usePlayerPreferences } from './playerPreferences'

export type StoredPlayMode = 'sequential' | 'smart-shuffle' | 'repeat-one'

interface PlayerState {
  playMode: StoredPlayMode
  volume: number
  queue: string[]
  queueIndex: number
  currentId: string | null
  position: number
  setSession: (currentId: string | null, position: number) => void
  setPlayMode: (mode: StoredPlayMode) => void
  setVolume: (volume: number) => void
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
  setSession: (currentId, position) => set({ currentId, position }),
  setPlayMode: (playMode) => set({ playMode }),
  setVolume: (volume) => set({ volume }),
  setQueue: (queue, queueIndex = 0) => set({ queue, queueIndex }),
  setQueueIndex: (queueIndex) => set({ queueIndex }),
}), {
  name: 'aurora-playback',
  partialize: ({ playMode, volume, queue, currentId, position }) => ({
    playMode, volume,
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
      volume: Number.isFinite(data.volume) ? Math.max(0, Math.min(1, data.volume!)) : current.volume,
      playMode: ['sequential', 'smart-shuffle', 'repeat-one'].includes(data.playMode || '') ? data.playMode! : current.playMode,
    }
  },
}))
