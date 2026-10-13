import { Capacitor } from '@capacitor/core'
import { useCallback, useEffect, useRef, useState } from 'react'
import { mobileMusic, type Appearance, type LibraryPage, type LibraryQuery, type MobileSong, type PlaybackSnapshot } from './native'
import type { PlayHistory } from '../../src/types/music'

export const native = Capacitor.isNativePlatform()
export const IDLE: PlaybackSnapshot = { songId: null, playing: false, playWhenReady: false, buffering: false, position: 0, duration: 0, error: null, speed: 1, revision: 0 }
const PAGE_SIZE = 60
const EMPTY: LibraryPage = { songs: [], total: 0, libraryTotal: 0, offlineTotal: 0, hasMore: false, offset: 0 }

/** Search the complete SQLite library, never just the currently rendered page. */
export function useLibrary(query: string, offlineOnly: boolean, onError: (error: unknown) => void, filter: Pick<LibraryQuery, 'artist' | 'album' | 'favoritesOnly' | 'genreTerms'> = {}, enabled = true) {
  const [page, setPage] = useState(EMPTY)
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  const generation = useRef(0)
  const genresKey = JSON.stringify(filter.genreTerms || [])
  const current = useRef(page); current.current = page
  useEffect(() => {
    const request = ++generation.current
    setPage(EMPTY); setBusy(native && enabled)
    if (!native || !enabled) return
    const timer = window.setTimeout(() => {
      void mobileMusic.library({ query, offlineOnly, ...filter, limit: PAGE_SIZE }).then(result => {
        if (request === generation.current) setPage(result)
      }).catch(error => { if (request === generation.current) onError(error) })
        .finally(() => { if (request === generation.current) setBusy(false) })
    }, query ? 250 : 0)
    return () => { clearTimeout(timer); generation.current++ }
  }, [query, offlineOnly, filter.artist, filter.album, filter.favoritesOnly, genresKey, revision, onError, enabled])
  const more = useCallback(async () => {
    if (!native || busy || !current.current.hasMore) return
    const request = generation.current, old = current.current
    setBusy(true)
    try {
      const result = await mobileMusic.library({ query, offlineOnly, ...filter, offset: old.offset + old.songs.length, limit: PAGE_SIZE })
      if (request !== generation.current) return
      // Ten pages are a sliding window, not an unbounded DOM / artwork cache.
      const merged = [...old.songs, ...result.songs]
      const drop = Math.max(0, merged.length - 600)
      setPage({ ...result, offset: old.offset + drop, songs: merged.slice(drop) })
    } catch (error) { onError(error) }
    finally { if (request === generation.current) setBusy(false) }
  }, [query, offlineOnly, filter.artist, filter.album, filter.favoritesOnly, genresKey, busy, onError])
  return { ...page, busy, more, refresh: useCallback(() => setRevision(value => value + 1), []) }
}

export function useOverview(onError: (error: unknown) => void) {
  const [songs, setSongs] = useState<MobileSong[]>([])
  const [history, setHistory] = useState<PlayHistory[]>([])
  const [total, setTotal] = useState(0)
  const [offlineTotal, setOfflineTotal] = useState(0)
  const refresh = useCallback(async () => {
    if (!native) return
    try {
      const result = await mobileMusic.overview()
      setSongs(result.songs); setHistory(result.history); setTotal(result.libraryTotal); setOfflineTotal(result.offlineTotal)
    } catch (error) { onError(error) }
  }, [onError])
  useEffect(() => { void refresh() }, [refresh])
  return { songs, history, total, offlineTotal, refresh }
}

/** No timer when paused/failed/hidden. Playback changes arrive as native events. */
export function usePlayback(onError: (error: unknown) => void) {
  const [status, setStatus] = useState(IDLE)
  const [song, setSong] = useState<MobileSong | null>(null)
  const [visible, setVisible] = useState(document.visibilityState === 'visible')
  const refresh = useCallback(async () => {
    if (!native || document.visibilityState !== 'visible') return
    try { setStatus(await mobileMusic.playback()) } catch (error) { onError(error) }
  }, [onError])
  useEffect(() => {
    const change = () => { setVisible(document.visibilityState === 'visible'); void refresh() }
    document.addEventListener('visibilitychange', change)
    if (!native) return () => document.removeEventListener('visibilitychange', change)
    let removed = false
    const subscriptions: { remove: () => Promise<void> }[] = []
    void refresh()
    void mobileMusic.addListener('playbackChanged', next => { if (!removed) setStatus(next) })
      .then(value => { if (removed) void value.remove(); else subscriptions.push(value) }).catch(onError)
    void mobileMusic.addListener('appStateChanged', state => { if (!removed) { setVisible(state.active); if (state.active) void refresh() } })
      .then(value => { if (removed) void value.remove(); else subscriptions.push(value) }).catch(onError)
    return () => { removed = true; document.removeEventListener('visibilitychange', change); subscriptions.forEach(value => void value.remove()) }
  }, [refresh, onError])
  useEffect(() => {
    if (!native || !visible || status.error || (!status.playWhenReady && !status.buffering)) return
    const timer = window.setInterval(() => void refresh(), 1000)
    return () => clearInterval(timer)
  }, [visible, status.error, status.playWhenReady, status.buffering, refresh])
  useEffect(() => {
    let current = true
    if (!native || !status.songId) { setSong(null); return }
    void mobileMusic.song({ songId: status.songId }).then(result => { if (current) setSong(result.song) }).catch(onError)
    return () => { current = false }
  }, [status.songId, status.revision, onError])
  return { status, song, setSong, refresh, visible }
}

const DEFAULT_APPEARANCE: Appearance = {
  mode: 'system', accent: '#b94b5c', dynamic: true, dynamicAvailable: false, motion: true, lowPowerVisuals: true,
  autoLookup: false, lyricsSize: 18, showBackdrop: true, dark: false, systemReducedMotion: false, powerSave: false, batteryOptimized: false,
  backgroundRestricted: false, palette: {},
}
export function useAppearance(onError: (error: unknown) => void) {
  const [value, setValue] = useState<Appearance>(() => {
    try {
      const saved = JSON.parse(localStorage.getItem('aurora-mobile-appearance') || '{}')
      return { ...DEFAULT_APPEARANCE, ...saved, lyricsSize: [18, 24, 30, 36].includes(saved.lyricsSize) ? saved.lyricsSize : 18,
        showBackdrop: typeof saved.showBackdrop === 'boolean' ? saved.showBackdrop : true }
    }
    catch { return DEFAULT_APPEARANCE }
  })
  const current = useRef(value)
  const confirmed = useRef(value)
  const revision = useRef(0)
  const mounted = useRef(true)
  const pending = useRef(Promise.resolve())
  const commit = useCallback((next: Appearance) => { current.current = next; if (mounted.current) setValue(next) }, [])
  // Serialize full native snapshots: fast changes must not undo another preference or an older refresh.
  const enqueue = useCallback((task: () => Promise<void>) => {
    pending.current = pending.current.then(task).catch(error => { if (mounted.current) onError(error) })
    return pending.current
  }, [onError])
  const refresh = useCallback(() => {
    const request = revision.current
    return enqueue(async () => {
      if (!native) return
      confirmed.current = await mobileMusic.appearance()
      if (request === revision.current) commit(confirmed.current)
    })
  }, [enqueue, commit])
  const save = useCallback((change: Partial<Appearance>) => {
    const request = ++revision.current
    commit({ ...current.current, ...change })
    return enqueue(async () => {
      try {
        const next = { ...confirmed.current, ...change }
        if (native) confirmed.current = await mobileMusic.setAppearance(next)
        else { localStorage.setItem('aurora-mobile-appearance', JSON.stringify(next)); confirmed.current = next }
        if (request === revision.current) commit(confirmed.current)
      } catch (error) { if (request === revision.current) commit(confirmed.current); throw error }
    })
  }, [enqueue, commit])
  useEffect(() => {
    mounted.current = true
    void refresh()
    const onVisible = () => { if (document.visibilityState === 'visible') void refresh() }
    const media = matchMedia('(prefers-color-scheme: dark)')
    document.addEventListener('visibilitychange', onVisible); media.addEventListener('change', onVisible)
    return () => { mounted.current = false; document.removeEventListener('visibilitychange', onVisible); media.removeEventListener('change', onVisible) }
  }, [refresh])
  useEffect(() => {
    const root = document.documentElement
    root.dataset.mode = value.mode; root.dataset.motion = String(value.motion && !value.systemReducedMotion)
    root.dataset.lowPower = String(value.lowPowerVisuals || value.powerSave)
    root.style.setProperty('--lyrics-size', `${[18, 24, 30, 36].includes(value.lyricsSize) ? value.lyricsSize : 18}px`)
    if (native) { root.dataset.dark = String(value.dark); for (const [key, color] of Object.entries(value.palette)) root.style.setProperty('--' + key, color) }
    else { root.style.setProperty('--custom-accent', value.accent); root.dataset.dark = String(value.mode === 'dark' || value.mode === 'system' && matchMedia('(prefers-color-scheme: dark)').matches) }
  }, [value])
  return { value, save, refresh }
}
