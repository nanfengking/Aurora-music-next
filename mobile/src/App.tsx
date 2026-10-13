import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { genreSearchTerms, recommendationFeed } from '../../src/services/recommendation'
import { t as desktopText } from '../../src/i18n'
import { Artwork, Empty, Modal, Track } from './components'
import { bytes } from './format'
import { native, useAppearance, useLibrary, useOverview, usePlayback } from './hooks'
import { getLocale, setLocale, t, type Locale } from './i18n'
import { mobileMusic, type LibraryQuery, type MobileSong, type RemoteEntry } from './native'
import { ArtworkBackdrop, NowPlaying } from './NowPlaying'
import { SettingsPage } from './Settings'
import { SongDetails } from './SongDetails'
import { PlaylistPicker, PlaylistsPage } from './Playlists'
import { AiPlaylist } from './AiPlaylist'
import { CollectionsPage } from './Collections'
import { AssetFinder } from './AssetFinder'
import { assetError } from './AssetProviders'

type Page = 'listen' | 'library' | 'cloud' | 'settings'
function savedOffline() { try { return localStorage.getItem('aurora-android-offline') === 'true' } catch { return false } }

export function App() {
  const [page, setPage] = useState<Page>('listen')
  const [locale, updateLocale] = useState(getLocale)
  const [offlineOnly, setOfflineOnly] = useState(savedOffline)
  const [query, setQuery] = useState('')
  const [libraryTab, setLibraryTab] = useState<'songs' | 'albums' | 'artists' | 'playlists'>('songs')
  const [libraryFilter, setLibraryFilter] = useState<Pick<LibraryQuery, 'artist' | 'album' | 'favoritesOnly' | 'genreTerms'>>({})
  const [addToPlaylist, setAddToPlaylist] = useState<MobileSong | null>(null)
  const [aiOpen, setAiOpen] = useState(false)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [playerOpen, setPlayerOpen] = useState(false)
  const [widePlayer, setWidePlayer] = useState(() => matchMedia('(min-width: 1000px)').matches)
  const [details, setDetails] = useState<MobileSong | null>(null)
  const [assetSong, setAssetSong] = useState<MobileSong | null>(null)
  const [connected, setConnected] = useState({ url: '', username: '' })
  const [entries, setEntries] = useState<RemoteEntry[]>([])
  const [path, setPath] = useState('')
  const [lookupBusy, setLookupBusy] = useState(false)
  const onError = useCallback((error: unknown) => setMessage(assetError(error).message), [])
  const library = useLibrary(query, offlineOnly, onError, libraryFilter)
  const overview = useOverview(onError)
  const playback = usePlayback(onError)
  const appearance = useAppearance(onError)
  // Keep the native decoder shared, and do not mount a second hidden lyric/artwork tree on phones.
  useEffect(() => {
    const media = matchMedia('(min-width: 1000px)')
    const changed = () => setWidePlayer(media.matches)
    changed(); media.addEventListener('change', changed)
    return () => media.removeEventListener('change', changed)
  }, [])
  const songRef = useRef(playback.song); songRef.current = playback.song
  const detailsRef = useRef(details); detailsRef.current = details
  const refreshing = useRef(false)
  const refreshAll = useCallback(() => {
    if (refreshing.current) return
    refreshing.current = true
    library.refresh()
    void Promise.all([overview.refresh(), playback.refresh()]).finally(() => { refreshing.current = false })
  }, [library.refresh, overview.refresh, playback.refresh])
  useEffect(() => {
    if (!native) return
    let active = true
    const subscriptions: { remove: () => Promise<void> }[] = []
    void mobileMusic.connection().then(value => { if (active) setConnected(value) }).catch(onError)
    void mobileMusic.addListener('libraryChanged', refreshAll).then(value => { if (active) subscriptions.push(value); else void value.remove() }).catch(onError)
    void mobileMusic.addListener('openPlayer', () => setPlayerOpen(true)).then(value => { if (active) subscriptions.push(value); else void value.remove() }).catch(onError)
    const checkLaunch = () => { if (document.visibilityState === 'visible') void mobileMusic.launchState().then(value => { if (active && value.openPlayer) setPlayerOpen(true) }).catch(onError) }
    checkLaunch(); document.addEventListener('visibilitychange', checkLaunch)
    return () => { active = false; subscriptions.forEach(value => void value.remove()); document.removeEventListener('visibilitychange', checkLaunch) }
  }, [refreshAll, onError])
  useEffect(() => {
    try { localStorage.setItem('aurora-android-offline', String(offlineOnly)) } catch { /* optional preference storage */ }
  }, [offlineOnly])
  useEffect(() => {
    document.documentElement.lang = locale
    if (native) void mobileMusic.setLanguage({ language: locale }).catch(onError)
  }, [locale, onError])
  useEffect(() => {
    if (!message) return
    const timer = window.setTimeout(() => setMessage(''), 6000)
    return () => clearTimeout(timer)
  }, [message])
  useEffect(() => {
    if (!native || !playback.visible || !library.songs.some(song => song.downloading)) return
    let timer: number | undefined
    const schedule = () => {
      clearInterval(timer)
      if (document.visibilityState === 'visible') timer = window.setInterval(() => library.refresh(), 8000)
    }
    schedule(); document.addEventListener('visibilitychange', schedule)
    return () => { clearInterval(timer); document.removeEventListener('visibilitychange', schedule) }
  }, [playback.visible, library.songs.some(song => song.downloading), library.refresh])

  const available = useMemo(() => offlineOnly ? overview.songs.filter(song => song.offline) : overview.songs, [overview.songs, offlineOnly])
  const feed = useMemo(() => recommendationFeed(available, overview.history, 12), [available, overview.history])
  const favorites = useMemo(() => available.filter(song => song.isFavorite).slice(0, 12), [available])
  const blur = appearance.value.showBackdrop && !appearance.value.lowPowerVisuals && !appearance.value.powerSave
  const act = (task: Promise<unknown>) => { void task.catch(onError) }
  const play = async (song: MobileSong, queue?: MobileSong[]) => {
    if (!native) { setMessage(t('preview')); return }
    try {
      if (song.id === playback.status.songId) await mobileMusic.toggle()
      else await mobileMusic.play({ ...(queue ? { songIds: queue.map(item => item.id) } : { libraryQuery: { query, offlineOnly, ...libraryFilter } }), startId: song.id, offlineOnly })
      await playback.refresh()
    } catch (error) { onError(error) }
  }
  const download = async (song: MobileSong) => {
    if (!native || song.offline || song.downloading) return
    try { await mobileMusic.download({ songId: song.id }); refreshAll() } catch (error) { onError(error) }
  }
  const openDetails = async (song: MobileSong) => {
    setDetails(song)
    if (!native) return
    try {
      const result = await mobileMusic.song({ songId: song.id })
      if (result.song) setDetails(current => current?.id === song.id ? result.song : current)
    } catch (error) { onError(error) }
  }
  const lookup = useCallback(async (song: MobileSong, automatic = false) => {
    if (!native || (!automatic && !appearance.value.autoLookup && !confirm(t('lookupConsent')))) return
    if (!automatic) setLookupBusy(true)
    try {
      const result = await mobileMusic.lookupAssets({ songId: song.id, force: !automatic })
      if (songRef.current?.id === song.id) playback.setSong(result.song)
      if (detailsRef.current?.id === song.id) setDetails(result.song)
      if (!automatic) setMessage(t('lookupResult'))
      refreshAll()
    } catch (error) { if (!automatic) onError(error) }
    finally { if (!automatic) setLookupBusy(false) }
  }, [appearance.value.autoLookup, playback.setSong, refreshAll, onError])
  useEffect(() => {
    const song = playback.song
    if (!native || !playback.visible || !song || assetSong || page === 'settings' || !appearance.value.autoLookup || !playback.status.duration || (song.coverPath && song.customLyrics)) return
    const timer = window.setTimeout(() => void lookup(song, true), 1500)
    return () => clearTimeout(timer)
  }, [playback.visible, playback.song?.id, playback.song?.title, playback.song?.artist, playback.song?.album, playback.status.duration, appearance.value.autoLookup, lookup, assetSong, page])
  const browse = async (folder: string) => {
    if (!native) return
    setBusy(true)
    try { const result = await mobileMusic.browse({ path: folder }); setEntries(result.entries); setPath(folder) } catch (error) { onError(error) } finally { setBusy(false) }
  }
  const scan = async () => {
    setBusy(true)
    try { const result = await mobileMusic.scan(); setMessage(result.added + ' ' + t('tracks')); refreshAll(); await browse(path) } catch (error) { onError(error) } finally { setBusy(false) }
  }
  const row = (song: MobileSong, queue?: MobileSong[]) => <Track key={song.id} song={song} playing={song.id === playback.status.songId}
    onPlay={() => void play(song, queue)} onDetail={() => void openDetails(song)} onDownload={() => void download(song)}/>
  const changePage = (next: Page) => { setPage(next); if (next === 'cloud' && connected.url) void browse(path); window.scrollTo(0, 0) }
  const changeLocale = (value: Locale) => { setLocale(value); updateLocale(value); document.documentElement.lang = value }

  return <div className={'mobile-app ' + (playback.song ? 'has-player' : '')}>
    <header className="app-header"><span className="wordmark">AURORA<span>music</span></span><button className={'offline-chip ' + (offlineOnly ? 'active' : '')} aria-pressed={offlineOnly} onClick={() => setOfflineOnly(value => !value)}>{offlineOnly ? t('offline') : t('online')}</button></header>
    <nav className="main-nav" aria-label="Main navigation">{(['listen', 'library', 'cloud', 'settings'] as const).map((item, index) => <button key={item} data-page={item} aria-current={page === item ? 'page' : undefined} onClick={() => changePage(item)}><span aria-hidden="true">{['◌', '♫', '☁', '⚙'][index]}</span>{t(item)}</button>)}</nav>
    <main className="screen" key={page}><div className="eyebrow">YOUR MUSIC, YOUR WAY</div><h1>{t(page)}</h1>
      {!native && <p className="status-note">{t('preview')}</p>}
      {page === 'listen' && <>
        <section className="journey-card"><div><small>AURORA / OFFLINE</small><h2>{t('journey')}</h2><p>{t('journeyHint')}</p><button onClick={() => { setOfflineOnly(true); setPage('library') }}>{t('playOffline')} <span aria-hidden="true">↗</span></button></div><strong>{overview.offlineTotal}<small>{t('tracks')}</small></strong></section>
        <button className="outlined-button ai-open" onClick={() => setAiOpen(true)}>✧ {t('aiPlaylist')}</button>
        <div className="section-heading"><h2>{t('recommended')}</h2></div>
        {feed.songs.length ? <div className="track-list">{(feed.songs as MobileSong[]).map(song => row(song, feed.songs as MobileSong[]))}</div> : <Empty/>}
        {favorites.length > 0 && <><div className="section-heading"><h2>{t('favorites')}</h2><button className="text-button" onClick={() => { setLibraryFilter({ favoritesOnly: true }); setQuery(''); setLibraryTab('songs'); setPage('library') }}>{t('viewAll')}</button></div><div className="track-list">{favorites.map(song => row(song, favorites))}</div></>}
        {(feed.profile.artists.length > 0 || feed.profile.genres.length > 0) && <section className="taste-card"><h2>{t('preference')}</h2><div className="filter-chips">
          {feed.profile.artists.slice(0, 3).map(item => <button key={'artist:' + item.name} onClick={() => { setLibraryFilter({ artist: item.name }); setQuery(''); setLibraryTab('songs'); setPage('library') }}>{item.name}</button>)}
          {feed.profile.genres.slice(0, 3).map(item => <button key={'genre:' + item.name} onClick={() => { setLibraryFilter({ genreTerms: genreSearchTerms(item.name) }); setQuery(''); setLibraryTab('songs'); setPage('library') }}>{desktopText(item.name)}</button>)}
        </div></section>}
      </>}
      {page === 'library' && <><div className="segmented library-tabs">{(['songs', 'albums', 'artists', 'playlists'] as const).map(tab => <button key={tab} aria-pressed={libraryTab === tab} onClick={() => setLibraryTab(tab)}>{t(tab)}</button>)}</div>
        {libraryTab === 'playlists' ? <PlaylistsPage offlineOnly={offlineOnly} playingId={playback.status.songId} onDetail={song => void openDetails(song)} onDownload={song => void download(song)} onError={onError}/> : libraryTab === 'albums' || libraryTab === 'artists' ? <CollectionsPage key={libraryTab} kind={libraryTab} offlineOnly={offlineOnly} playingId={playback.status.songId} onDetail={song => void openDetails(song)} onDownload={song => void download(song)} onError={onError}/> : <>
        {(libraryFilter.favoritesOnly || libraryFilter.artist || libraryFilter.genreTerms) && <div className="section-heading"><h2>{libraryFilter.favoritesOnly ? t('favorites') : libraryFilter.artist || desktopText(libraryFilter.genreTerms?.[0] || '')}</h2><button className="text-button" onClick={() => setLibraryFilter({})}>{t('clearFilter')}</button></div>}
        <label className="search"><span aria-hidden="true">⌕</span><input type="search" aria-label={t('search')} value={query} onChange={event => setQuery(event.target.value)} placeholder={t('search')}/></label>
        <div className="list-caption">{library.total} {t('tracks')} {offlineOnly && '· ' + t('offline')}</div>
        {library.songs.length ? <div className="track-list">{library.songs.map(song => row(song))}</div> : library.busy ? <p role="status">{t('loading')}</p> : <Empty/>}
        {library.hasMore && <button className="outlined-button load-more" disabled={library.busy} onClick={() => act(library.more())}>{library.busy ? t('loading') : t('more')}</button>}
      </>}</>}
      {page === 'cloud' && <>{connected.url ? <><div className="browse-actions"><button onClick={() => void browse('')} disabled={busy}>{t('root')}</button><button onClick={() => void browse(path.replace(/[^/]+\/?$/, ''))} disabled={!path || busy}>{t('back')}</button><button onClick={() => void scan()} disabled={busy}>{busy ? t('loading') : t('scan')}</button></div><p className="folder-path">{path || '/'}</p><div className="file-list">{entries.map(entry => <button key={entry.href} onClick={() => {
        if (entry.directory) void browse(entry.href)
        else if (entry.songId) act(mobileMusic.play({ songIds: [entry.songId], startId: entry.songId, offlineOnly }).then(playback.refresh))
      }}><span aria-hidden="true">{entry.directory ? '▤' : '♫'}</span><strong>{entry.name}</strong><small>{entry.directory ? '›' : bytes(entry.size)}</small></button>)}</div></> : <Empty/>}</>}
      {page === 'settings' && <SettingsPage appearance={appearance.value} saveAppearance={appearance.save} locale={locale} setLocale={changeLocale} offlineOnly={offlineOnly} setOfflineOnly={setOfflineOnly}
        offlineTotal={overview.offlineTotal} connected={connected} setConnected={setConnected} onChanged={refreshAll} onError={onError} notify={setMessage}/>}
    </main>
    {playback.song && !widePlayer && <aside className="mini-player"><button className="mini-track" onClick={() => setPlayerOpen(true)}><Artwork song={playback.song} revision={playback.status.revision}/><span><strong>{playback.song.title}</strong><small>{playback.status.buffering ? t('buffering') : playback.song.artist || t('unknown')}</small></span></button><button className="icon-button" aria-label={playback.status.playWhenReady && !playback.status.error ? t('pause') : t('play')} onClick={() => act(mobileMusic.toggle())}>{playback.status.playWhenReady && !playback.status.error ? 'Ⅱ' : '▶'}</button><div className="mini-progress" style={{ width: Math.min(100, 100 * playback.status.position / (playback.status.duration || 1)) + '%' }}/></aside>}
    {playback.song && widePlayer && <aside className="tablet-player"><div className="eyebrow">{t('nowPlaying')}</div><NowPlaying song={playback.song} status={playback.status} compact blur={blur} lyricsSize={appearance.value.lyricsSize} onDetail={() => void openDetails(playback.song!)} onLookup={() => { if (!lookupBusy) void lookup(playback.song!) }} onError={onError}/></aside>}
    {playerOpen && playback.song && <Modal title={t('nowPlaying')} onClose={() => setPlayerOpen(false)} className="player-sheet" closeIcon
      decoration={<ArtworkBackdrop song={playback.song} revision={playback.status.revision} enabled={blur}/>}><NowPlaying song={playback.song} status={playback.status} blur={blur} lyricsSize={appearance.value.lyricsSize} onDetail={() => void openDetails(playback.song!)} onLookup={() => { if (!lookupBusy) void lookup(playback.song!) }} onError={onError}/></Modal>}
    {details && <SongDetails key={details.id} song={details} playingId={playback.status.songId} onClose={() => setDetails(null)}
      onAddToPlaylist={() => setAddToPlaylist(details)}
      onFindAssets={() => setAssetSong(details)}
      onFavorite={() => act(mobileMusic.favorite({ songId: details.id, favorite: !details.isFavorite }).then(async () => { const result = await mobileMusic.song({ songId: details.id }); if (result.song) setDetails(result.song); refreshAll() }))}
      onDownload={() => void download(details)} onLookup={() => { if (!lookupBusy) void lookup(details) }}
      onRemove={() => act(mobileMusic.removeDownload({ songId: details.id }).then(() => { setDetails(null); refreshAll() }))}
      onSaveLyrics={async text => { try {
        const id = details.id; await mobileMusic.saveLyrics({ songId: id, text })
        setDetails(current => current?.id === id ? { ...current, customLyrics: text } : current); refreshAll()
      } catch (error) { onError(error); throw error } }}/>}
    {aiOpen && <AiPlaylist offlineOnly={offlineOnly} onClose={() => setAiOpen(false)} onSaved={() => { refreshAll(); setMessage(t('aiSaved')) }} onError={onError}/>}
    {assetSong && <AssetFinder song={assetSong} onClose={() => setAssetSong(null)} onError={onError} onApplied={song => {
      if (detailsRef.current?.id === song.id) setDetails(song)
      if (songRef.current?.id === song.id) playback.setSong(song)
      refreshAll(); setMessage(t('assetApplied'))
    }}/>}
    {addToPlaylist && <PlaylistPicker song={addToPlaylist} onClose={() => setAddToPlaylist(null)} onAdded={refreshAll} onError={onError}/>}
    {(message || lookupBusy) && <div className="toast" role="status" onClick={() => setMessage('')}>{lookupBusy ? t('loading') : message}</div>}
  </div>
}
