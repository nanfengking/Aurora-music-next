import { create } from 'zustand'

export type StoredPlayMode = 'sequential' | 'smart-shuffle' | 'repeat-one'

interface PlayerState {
  playMode: StoredPlayMode
  volume: number
  queue: string[]
  queueIndex: number
  setPlayMode: (mode: StoredPlayMode) => void
  setVolume: (volume: number) => void
  setQueue: (songIds: string[], startIndex?: number) => void
  setQueueIndex: (index: number) => void
}

export const usePlayerStore = create<PlayerState>((set) => ({
  playMode: 'sequential',
  volume: 0.8,
  queue: [],
  queueIndex: -1,
  setPlayMode: (playMode) => set({ playMode }),
  setVolume: (volume) => set({ volume }),
  setQueue: (queue, queueIndex = 0) => set({ queue, queueIndex }),
  setQueueIndex: (queueIndex) => set({ queueIndex }),
}))
