import { useCallback, useEffect, useRef, useState } from 'react'
import { DEFAULT_SMART_RULES, parseSmartRules } from '../../src/services/smartPlaylists'
import { Empty, Modal, Track } from './components'
import { native } from './hooks'
import { t } from './i18n'
import { mobileMusic, type MobilePlaylist, type MobileSong } from './native'

function PlaylistEditor({ playlist, onClose, onSaved, onError }: { playlist?: MobilePlaylist; onClose: () => void; onSaved: () => void; onError: (error: unknown) => void }) {
  const [name, setName] = useState(playlist?.name || '')
  const [description, setDescription] = useState(playlist?.description || '')
  const [smart, setSmart] = useState(Boolean(playlist?.smartRules))
  const [rules, setRules] = useState(() => { try { return parseSmartRules(playlist?.smartRules) || DEFAULT_SMART_RULES } catch { return DEFAULT_SMART_RULES } })
  const [busy, setBusy] = useState(false)
  const save = async () => {
    setBusy(true)
    try { await mobileMusic.savePlaylist({ id: playlist?.id, name, description, ...(smart ? { rules } : {}) }); onSaved(); onClose() }
    catch (error) { onError(error) } finally { setBusy(false) }
  }
  return <Modal title={t(playlist ? 'editPlaylist' : 'createPlaylist')} onClose={onClose}><div className="settings-group playlist-editor">
    <label>{t('name')}<input value={name} maxLength={100} onChange={event => setName(event.target.value)}/></label>
    <label>{t('description')}<input value={description} maxLength={500} onChange={event => setDescription(event.target.value)}/></label>
    <label className="switch-row">{t('smart')}<input type="checkbox" checked={smart} onChange={event => setSmart(event.target.checked)}/></label>
    {smart && <><label className="switch-row">{t('favorites')}<input type="checkbox" checked={rules.favoritesOnly} onChange={event => setRules({ ...rules, favoritesOnly: event.target.checked })}/></label>
      <label>{t('artist')}<input value={rules.artist} maxLength={100} onChange={event => setRules({ ...rules, artist: event.target.value })}/></label>
      <label>{t('genre')}<input value={rules.genre} maxLength={100} onChange={event => setRules({ ...rules, genre: event.target.value })}/></label>
      <label>{t('recentDays')}<input type="number" min={0} max={365} value={rules.recentDays} onChange={event => setRules({ ...rules, recentDays: Number(event.target.value) })}/></label>
      <label>{t('limit')}<input type="number" min={1} max={5000} value={rules.limit} onChange={event => setRules({ ...rules, limit: Number(event.target.value) })}/></label>
      <label>{t('durationMinutes')}<input type="number" min={0} max={1440} value={rules.durationMinutes} onChange={event => setRules({ ...rules, durationMinutes: Number(event.target.value) })}/></label>
      <label className="select-row">{t('smart')}<select value={rules.sort} onChange={event => setRules({ ...rules, sort: event.target.value as typeof rules.sort })}>
        <option value="recent">{t('recent')}</option><option value="most-played">{t('mostPlayed')}</option><option value="newest">{t('newest')}</option><option value="title">{t('titleSort')}</option>
      </select></label><p>{t('smartHint')}</p></>}
    <button className="solid-button" disabled={!native || busy || !name.trim()} onClick={() => void save()}>{busy ? t('loading') : t('save')}</button>
  </div></Modal>
}

export function PlaylistsPage({ offlineOnly, playingId, onDetail, onDownload, onError }: {
  offlineOnly: boolean; playingId: string | null; onDetail: (song: MobileSong) => void; onDownload: (song: MobileSong) => void; onError: (error: unknown) => void
}) {
  const [playlists, setPlaylists] = useState<MobilePlaylist[]>([])
  const [selected, setSelected] = useState<MobilePlaylist | null>(null)
  const [editor, setEditor] = useState<MobilePlaylist | 'new' | null>(null)
  const [songs, setSongs] = useState<MobileSong[]>([])
  const [total, setTotal] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [offset, setOffset] = useState(0)
  const [busy, setBusy] = useState(false)
  const generation = useRef(0)
  const refresh = useCallback(async () => {
    if (!native) return
    try { setPlaylists((await mobileMusic.playlists()).playlists) } catch (error) { onError(error) }
  }, [onError])
  const [revision, setRevision] = useState(0)
  useEffect(() => { void refresh() }, [refresh, revision])
  useEffect(() => {
    const ticket = ++generation.current
    setSongs([]); setHasMore(false); setOffset(0); setBusy(false); setTotal(0)
    if (!native || !selected) return
    let current = true; setBusy(true)
    void mobileMusic.playlistSongs({ id: selected.id, offlineOnly, limit: 60 }).then(result => {
      if (current) { setSongs(result.songs); setTotal(result.total); setHasMore(result.hasMore) }
    }).catch(onError).finally(() => { if (current) setBusy(false) })
    return () => { current = false; if (generation.current === ticket) generation.current++ }
  }, [selected?.id, offlineOnly, revision, onError])
  const more = async () => {
    if (!selected || busy) return
    const ticket = generation.current
    setBusy(true)
    try {
      const result = await mobileMusic.playlistSongs({ id: selected.id, offlineOnly, offset: offset + songs.length, limit: 60 })
      if (ticket !== generation.current) return
      const merged = [...songs, ...result.songs], drop = Math.max(0, merged.length - 600)
      setSongs(merged.slice(drop)); setOffset(offset + drop); setTotal(result.total); setHasMore(result.hasMore)
    } catch (error) { if (ticket === generation.current) onError(error) } finally { if (ticket === generation.current) setBusy(false) }
  }
  const action = (task: Promise<unknown>) => { void task.catch(onError) }
  const changed = () => setRevision(value => value + 1)
  return <section className="playlists-page">
    {selected ? <><button className="text-button" onClick={() => setSelected(null)}>‹ {t('playlists')}</button><div className="section-heading"><h2>{selected.name}</h2><span>{total} {t('tracks')}</span></div>
      <div className="browse-actions"><button onClick={() => setEditor(selected)}>{t('editPlaylist')}</button><button className="danger" onClick={() => {
        if (confirm(t('deletePlaylistConfirm'))) action(mobileMusic.deletePlaylist({ id: selected.id }).then(() => { setSelected(null); changed() }))
      }}>{t('deletePlaylist')}</button></div>
      {songs.length ? <div className="track-list">{songs.map(song => <div className="playlist-track" key={song.id}><Track song={song} playing={song.id === playingId}
        onPlay={() => action(mobileMusic.play({ playlistId: selected.id, startId: song.id, offlineOnly }))} onDetail={() => onDetail(song)} onDownload={() => onDownload(song)}/>
        {!selected.smartRules && <button className="text-button remove-member" aria-label={t('removeFromPlaylist') + ' · ' + song.title} onClick={() => action(mobileMusic.playlistSong({ id: selected.id, songId: song.id, remove: true }).then(changed))}>−</button>}</div>)}</div> : busy ? <p>{t('loading')}</p> : <Empty/>}
      {hasMore && <button className="outlined-button" disabled={busy} onClick={() => void more()}>{t('more')}</button>}
    </> : <><button className="outlined-button" onClick={() => setEditor('new')}>{t('createPlaylist')}</button>
      {playlists.length ? <div className="playlist-grid">{playlists.map(playlist => <button className="playlist-card" key={playlist.id} onClick={() => setSelected(playlist)}><span aria-hidden="true">{playlist.smartRules ? '✧' : '♫'}</span><strong>{playlist.name}</strong><small>{playlist.smartRules ? t('smart') : playlist.count + ' ' + t('tracks')}</small></button>)}</div> : <p className="status-note">{t('emptyPlaylists')}</p>}
    </>}
    {editor && <PlaylistEditor playlist={editor === 'new' ? undefined : editor} onClose={() => setEditor(null)} onSaved={() => { changed(); setSelected(null) }} onError={onError}/>}
  </section>
}

export function PlaylistPicker({ song, onClose, onAdded, onError }: { song: MobileSong; onClose: () => void; onAdded: () => void; onError: (error: unknown) => void }) {
  const [playlists, setPlaylists] = useState<MobilePlaylist[]>([])
  useEffect(() => { if (native) void mobileMusic.playlists().then(value => setPlaylists(value.playlists.filter(item => !item.smartRules))).catch(onError) }, [onError])
  return <Modal title={t('addToPlaylist')} onClose={onClose}><p>{song.title}</p>{playlists.map(item => <button className="outlined-button" key={item.id} onClick={() => {
    void mobileMusic.playlistSong({ id: item.id, songId: song.id }).then(() => { onAdded(); onClose() }).catch(onError)
  }}>{item.name}</button>)}{!playlists.length && <p className="status-note">{t('emptyPlaylists')}</p>}</Modal>
}
