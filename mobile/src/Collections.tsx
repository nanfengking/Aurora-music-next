import { useEffect, useRef, useState } from 'react'
import { Empty, Track } from './components'
import { imageUrl } from './format'
import { native, useLibrary } from './hooks'
import { t } from './i18n'
import { mobileMusic, type Collection, type MobileSong } from './native'

export function CollectionsPage({ kind, offlineOnly, playingId, onDetail, onDownload, onError }: {
  kind: 'albums' | 'artists'; offlineOnly: boolean; playingId: string | null
  onDetail: (song: MobileSong) => void; onDownload: (song: MobileSong) => void; onError: (error: unknown) => void
}) {
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<Collection | null>(null)
  const [items, setItems] = useState<Collection[]>([])
  const [offset, setOffset] = useState(0)
  const [total, setTotal] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [busy, setBusy] = useState(false)
  const generation = useRef(0)
  const filter = { artist: selected?.artist, ...(kind === 'albums' && selected ? { album: selected.name } : {}) }
  const library = useLibrary('', offlineOnly, onError, filter, Boolean(selected))
  useEffect(() => {
    const ticket = ++generation.current
    setItems([]); setOffset(0); setTotal(0); setHasMore(false); setBusy(native && !selected)
    if (!native || selected) return
    const timer = window.setTimeout(() => {
      void mobileMusic.collections({ kind, query, offlineOnly, limit: 60 }).then(result => {
        if (ticket === generation.current) { setItems(result.items); setTotal(result.total); setHasMore(result.hasMore) }
      }).catch(error => { if (ticket === generation.current) onError(error) }).finally(() => { if (ticket === generation.current) setBusy(false) })
    }, query ? 250 : 0)
    return () => { clearTimeout(timer); generation.current++ }
  }, [kind, query, offlineOnly, selected, onError])
  const more = async () => {
    if (busy || !hasMore) return
    const ticket = generation.current; setBusy(true)
    try {
      const result = await mobileMusic.collections({ kind, query, offlineOnly, offset: offset + items.length, limit: 60 })
      if (ticket !== generation.current) return
      const merged = [...items, ...result.items], drop = Math.max(0, merged.length - 600)
      setItems(merged.slice(drop)); setOffset(offset + drop); setTotal(result.total); setHasMore(result.hasMore)
    } catch (error) { if (ticket === generation.current) onError(error) } finally { if (ticket === generation.current) setBusy(false) }
  }
  const name = (item: Collection) => item.name || t(kind === 'albums' ? 'unknownAlbum' : 'unknown')
  return <section className="collections-page">
    {selected ? <><button className="text-button" onClick={() => setSelected(null)}>‹ {t(kind)}</button><div className="section-heading"><h2>{name(selected)}</h2><span>{library.total} {t('tracks')}</span></div>
      {library.songs.length ? <div className="track-list">{library.songs.map(song => <Track key={song.id} song={song} playing={song.id === playingId}
        onDetail={() => onDetail(song)} onDownload={() => onDownload(song)} onPlay={() => {
          void mobileMusic.play({ libraryQuery: { ...filter, offlineOnly }, startId: song.id, offlineOnly }).catch(onError)
        }}/>)}</div> : library.busy ? <p>{t('loading')}</p> : <Empty/>}
      {library.hasMore && <button className="outlined-button" disabled={library.busy} onClick={() => void library.more()}>{t('more')}</button>}
    </> : <><label className="search"><span aria-hidden="true">⌕</span><input type="search" value={query} aria-label={t(kind)} placeholder={t(kind)} onChange={event => setQuery(event.target.value)}/></label><p className="list-caption">{total} {t(kind)}</p>
      {items.length ? <div className="collection-grid">{items.map(item => <button className={'collection-card ' + kind} key={JSON.stringify([item.name, item.artist])} onClick={() => setSelected(item)}>
        <span className="collection-art" aria-hidden="true">{item.coverPath ? <img src={imageUrl(item.coverPath)} alt="" loading="lazy" decoding="async"/> : '♫'}</span>
        <strong>{name(item)}</strong><small>{kind === 'albums' && item.artist ? item.artist + ' · ' : ''}{item.count} {t('tracks')}</small>
      </button>)}</div> : busy ? <p>{t('loading')}</p> : <Empty/>}
      {hasMore && <button className="outlined-button" disabled={busy} onClick={() => void more()}>{t('more')}</button>}
    </>}
  </section>
}
