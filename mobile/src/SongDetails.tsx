import { useEffect, useState } from 'react'
import { Artwork, Modal } from './components'
import { bytes, seconds } from './format'
import { t } from './i18n'
import type { MobileSong } from './native'

export function SongDetails({ song, playingId, onClose, onFavorite, onDownload, onRemove, onLookup, onFindAssets, onSaveLyrics, onAddToPlaylist }: {
  song: MobileSong; playingId: string | null; onClose: () => void; onFavorite: () => void; onDownload: () => void
  onRemove: () => void; onLookup: () => void; onSaveLyrics: (text: string) => Promise<void>
  onAddToPlaylist: () => void; onFindAssets: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [lyrics, setLyrics] = useState(song.customLyrics || '')
  const [saving, setSaving] = useState(false)
  useEffect(() => { if (!editing) setLyrics(song.customLyrics || '') }, [song.customLyrics, editing])
  const fields = [
    [t('album'), song.album || '—'], [t('format'), song.format.toUpperCase()], [t('duration'), song.duration ? seconds(song.duration) : t('unread')],
    [t('size'), song.remoteSize ? bytes(song.remoteSize) : '—'], [t('codec'), song.codec || t('unread')],
    [t('bitrate'), song.bitrate ? `${Math.round(song.bitrate / 1000)} kbps` : t('unread')], [t('sampleRate'), song.sampleRate ? `${song.sampleRate / 1000} kHz` : t('unread')],
    [t('channels'), song.channels ? String(song.channels) : t('unread')],
    [t('replayGain'), typeof song.replayGainDb === 'number' && Number.isFinite(song.replayGainDb) ? `${song.replayGainDb.toFixed(2)} dB` : t('tagUnavailable')],
    [t('replayPeak'), typeof song.replayPeak === 'number' && Number.isFinite(song.replayPeak) && song.replayPeak > 0 ? song.replayPeak.toPrecision(4) : t('tagUnavailable')],
  ]
  return <Modal title={t('detail')} onClose={onClose}><div className="detail-identity"><Artwork song={song}/><div><h3>{song.title}</h3><p>{song.artist || t('unknown')}</p></div></div>
    <dl className="detail-fields">{fields.map(([key, value]) => <div key={key}><dt>{key}</dt><dd>{value}</dd></div>)}</dl><p className="status-note">{t('qualityHint')}</p>
    <div className="detail-actions"><button className="outlined-button" onClick={onFavorite}>{song.isFavorite ? '♥' : '♡'} {t('favorites')}</button><button className="solid-button" disabled={song.offline || song.downloading} onClick={onDownload}>{t(song.offline ? 'downloaded' : song.downloading ? 'downloading' : 'download')}</button></div>
    <button className="outlined-button" onClick={onLookup}>{t('lookup')}</button>
    <button className="outlined-button" onClick={onFindAssets}>{t('assetFinder')}</button>
    <button className="outlined-button" onClick={onAddToPlaylist}>{t('addToPlaylist')}</button>
    <button className="text-button" onClick={() => setEditing(value => !value)}>{t('lyricEdit')}</button>
    {editing && <div className="lyric-editor"><textarea value={lyrics} maxLength={100000} aria-label={t('lyricEdit')} onChange={event => setLyrics(event.target.value)}/><button className="solid-button" disabled={saving} onClick={() => {
      setSaving(true); void onSaveLyrics(lyrics).then(() => setEditing(false)).catch(() => { /* Parent reports the failure; keep the draft open. */ }).finally(() => setSaving(false))
    }}>{saving ? t('loading') : t('save')}</button></div>}
    {song.offline && <button className="outlined-button danger" disabled={song.id === playingId} onClick={() => { if (confirm(t('removeConfirm'))) onRemove() }}>{t(song.id === playingId ? 'switchBeforeRemove' : 'remove')}</button>}
    {song.downloadError && <p role="status" className="warning">{song.downloadError}</p>}
  </Modal>
}
