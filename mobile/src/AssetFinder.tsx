import { useEffect, useRef, useState } from 'react'
import type { AssetCandidate, AssetKind } from '../../src/types/providers'
import { Modal } from './components'
import { imageUrl } from './format'
import { native } from './hooks'
import { t } from './i18n'
import { mobileMusic, type MobileSong } from './native'
import { assetError } from './AssetProviders'

export function AssetFinder({ song, onClose, onApplied, onError }: { song: MobileSong; onClose: () => void; onApplied: (song: MobileSong) => void; onError: (error: unknown) => void }) {
  const [kind, setKind] = useState<AssetKind>('lyrics')
  const [query, setQuery] = useState({ title: song.title.slice(0, 200), artist: song.artist.slice(0, 200), album: song.album.slice(0, 200) })
  const [candidates, setCandidates] = useState<AssetCandidate[]>([])
  const [selected, setSelected] = useState<AssetCandidate | null>(null)
  const [imageReady, setImageReady] = useState(false)
  const [busy, setBusy] = useState(true)
  const [searched, setSearched] = useState(false)
  const [active, setActive] = useState(true)
  const alive = useRef(true), generation = useRef(0)
  useEffect(() => {
    alive.current = true
    let remove: (() => Promise<void>) | undefined
    const ticket = ++generation.current
    if (native) {
      void mobileMusic.cancelAssets().then(() => { if (alive.current && ticket === generation.current) setBusy(false) }).catch(error => { if (alive.current) { setBusy(false); onError(assetError(error)) } })
      void mobileMusic.addListener('appStateChanged', state => {
        if (!alive.current) return
        setActive(state.active)
        if (!state.active) { generation.current++; setBusy(false); setSelected(null); setCandidates([]); setSearched(false); void mobileMusic.cancelAssets().catch(() => {}) }
      }).then(listener => { if (alive.current) remove = listener.remove; else void listener.remove() })
    } else setBusy(false)
    return () => { alive.current = false; generation.current++; void remove?.(); if (native) void mobileMusic.cancelAssets().catch(() => {}) }
  }, [onError])
  const run = async <T,>(task: () => Promise<T>, done: (value: T) => void) => {
    const ticket = ++generation.current; setBusy(true)
    try { const value = await task(); if (alive.current && ticket === generation.current) done(value) }
    catch (error) { if (alive.current && ticket === generation.current) onError(assetError(error)) }
    finally { if (alive.current && ticket === generation.current) setBusy(false) }
  }
  const search = () => {
    if (!confirm(t('lookupConsent'))) return
    setSelected(null); setCandidates([]); setImageReady(false); setSearched(false)
    void run(async () => { await mobileMusic.cancelAssets(); return mobileMusic.searchAssets({ songId: song.id, kind, query }) }, value => { setCandidates(value.candidates); setSearched(true) })
  }
  const select = (candidate: AssetCandidate) => {
    setSelected(null); setImageReady(false)
    void run(() => mobileMusic.previewAsset({ songId: song.id, kind, candidateId: candidate.id }), value => setSelected(value.candidate))
  }
  const applied = (value: { song: MobileSong }) => { onApplied(value.song); onClose() }
  const canAct = native && active && !busy
  return <Modal title={t('assetFinder')} onClose={onClose} className="asset-finder">
    <div className="segmented">{(['lyrics', 'cover', 'background'] as const).map(value => <button key={value} disabled={busy} aria-pressed={kind === value} onClick={() => {
      setKind(value); setSelected(null); setCandidates([]); setSearched(false); setImageReady(false)
      void run(() => mobileMusic.cancelAssets(), () => {})
    }}>{t(value === 'cover' ? 'assetCover' : value === 'background' ? 'assetBackground' : 'lyrics')}</button>)}</div>
    <form className="asset-query" onSubmit={event => { event.preventDefault(); if (canAct) search() }}>
      {(['title', 'artist', 'album'] as const).map(field => <label key={field}>{t(field === 'title' ? 'assetTitle' : field)}<input value={query[field]} maxLength={200} disabled={busy} onChange={event => setQuery({ ...query, [field]: event.target.value })}/></label>)}
      <button className="solid-button" disabled={!canAct || !Object.values(query).some(value => value.trim())}>{busy ? t('loading') : t('assetSearch')}</button>
    </form><p className="status-note">{t(kind === 'background' ? 'assetBackgroundHint' : 'assetSearchHint')}</p>
    {searched && !candidates.length && <p role="status">{t('assetNoResults')}</p>}
    <div className="asset-results">{candidates.map(candidate => <button key={candidate.id} className="asset-result" disabled={!canAct} aria-pressed={selected?.id === candidate.id} onClick={() => select(candidate)}>
      <strong>{candidate.title || t('unread')}</strong><span>{candidate.artist} {candidate.album && ' · ' + candidate.album}</span><small>{candidate.source}{candidate.synced && ' · ' + t('assetSynced')}</small>
    </button>)}</div>
    {selected && <section className="asset-preview"><h3>{t('assetPreview')}</h3><p>{selected.source}</p>
      {kind === 'lyrics' ? <pre>{selected.text}</pre> : selected.imagePath && <img src={imageUrl(selected.imagePath)} alt={selected.title || t('assetPreview')} decoding="async" onLoad={() => setImageReady(true)} onError={() => { setImageReady(false); onError(new Error(t('assetFailed'))) }}/>}
      <button className="solid-button" disabled={!canAct || (kind !== 'lyrics' && !imageReady)} onClick={() => {
        if (confirm(t('assetApplyConfirm'))) void run(() => mobileMusic.applyAsset({ songId: song.id, kind, candidateId: selected.id }), applied)
      }}>{t('assetApply')}</button>
    </section>}
    {kind !== 'lyrics' && <button className="text-button" disabled={!canAct} onClick={() => {
      if (confirm(t('assetResetConfirm'))) void run(() => mobileMusic.clearAsset({ songId: song.id, kind }), applied)
    }}>{t('assetReset')}</button>}
  </Modal>
}
