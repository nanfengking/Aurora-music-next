import { useEffect, useMemo, useRef, useState } from 'react'
import { Artwork, Modal } from './components'
import { imageUrl, parseLyrics, seconds } from './format'
import { t } from './i18n'
import { mobileMusic, type MobileSong, type PlaybackSnapshot } from './native'
import { PlaybackOptionsDialog } from './PlaybackOptions'
import { playbackError } from './playbackError'

export function ArtworkBackdrop({ song, revision, enabled }: { song: MobileSong; revision: number; enabled: boolean }) {
  const image = song.customArtistImage || song.coverPath
  return image && enabled ? <div className="cover-background" style={{ backgroundImage: `url("${imageUrl(image, revision)}")` }} aria-hidden="true"/> : null
}
function TransportIcon({ kind }: { kind: 'previous' | 'next' | 'play' | 'pause' }) {
  const paths = { previous: 'M5 5h3v14H5zM18 5v14L8 12z', next: 'M16 5h3v14h-3zM6 5l10 7-10 7z', play: 'M7 4v16l14-8z', pause: 'M6 5h4v14H6zM14 5h4v14h-4z' }
  return <svg width="28" height="28" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d={paths[kind]}/></svg>
}

export function NowPlaying({ song, status, compact = false, blur, lyricsSize, onDetail, onLookup, onError }: {
  song: MobileSong; status: PlaybackSnapshot; compact?: boolean; blur: boolean; lyricsSize: number
  onDetail: () => void; onLookup: () => void; onError: (error: unknown) => void
}) {
  const [optionsOpen, setOptionsOpen] = useState(false)
  const [actionsOpen, setActionsOpen] = useState(false)
  const [seeking, setSeeking] = useState<number | null>(null)
  const lines = useMemo(() => parseLyrics(song.customLyrics), [song.customLyrics])
  const active = useMemo(() => { let low = 0, high = lines.length - 1, found = -1; while (low <= high) { const mid = (low + high) >>> 1; if (lines[mid].time <= status.position) { found = mid; low = mid + 1 } else high = mid - 1 } return found }, [lines, status.position])
  const lyricPanel = useRef<HTMLDivElement>(null)
  const mounted = useRef(true)
  const pendingAction = useRef<(() => void) | null>(null)
  const manualScroll = useRef(0)
  const scrollToActive = () => {
    const panel = lyricPanel.current
    const element = panel?.querySelector('.lyric-active') as HTMLElement | null
    if (element && panel) panel.scrollTo({ top: Math.max(0, element.offsetTop - panel.clientHeight / 3),
      behavior: document.documentElement.dataset.motion === 'true' ? 'smooth' : 'auto' })
  }
  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      if (Date.now() - manualScroll.current < 6000) return
      scrollToActive()
    })
    return () => cancelAnimationFrame(frame)
  }, [active, song.id, lyricsSize])
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; pendingAction.current = null }
  }, [])
  useEffect(() => { setSeeking(null); manualScroll.current = 0 }, [song.id])
  const action = (task: Promise<unknown>) => { void task.catch(onError) }
  const seek = (position: number) => { setSeeking(null); action(mobileMusic.seek({ position })) }
  const duration = status.duration || song.duration
  const wantsPlay = status.playWhenReady && !status.error
  const secondary = (task: () => void) => { pendingAction.current = task; setActionsOpen(false) }
  const afterActions = () => {
    const task = pendingAction.current; pendingAction.current = null
    if (mounted.current) task?.()
  }
  return <section className={'now-playing ' + (compact ? 'docked-player' : '')} aria-label={t('nowPlaying')}>
    {compact && <ArtworkBackdrop song={song} revision={status.revision} enabled={blur}/>}
    <div className="player-art-section"><Artwork song={song} className="player-art" revision={status.revision}/>
      <div className="player-title"><div><h2>{song.title}</h2><p>{song.artist || t('unknown')}</p></div></div>
    </div>
    <div className="player-body">
      <div className="player-tabs"><button type="button" onClick={() => { manualScroll.current = 0; scrollToActive() }}>{t('lyrics')}</button>
        <button type="button" className="more-actions" aria-label={t('moreActions')} aria-haspopup="dialog" aria-expanded={actionsOpen} onClick={() => setActionsOpen(true)}>⋯</button>
      </div>
      <div ref={lyricPanel} className="lyric-panel expanded" onTouchMove={() => { manualScroll.current = Date.now() }} onWheel={() => { manualScroll.current = Date.now() }}>
        {lines.length ? lines.map((line, index) => <button key={index} className={index === active ? 'lyric-active' : ''}
          aria-current={index === active ? 'true' : undefined} onClick={() => seek(line.time)}>{line.text || '♪'}</button>)
          : <p className="plain-lyrics">{song.customLyrics || t('noLyrics')}</p>}
      </div>
      <div className="player-controls"><input type="range" min={0} max={Math.max(1, duration)} step={.1} value={Math.min(duration || 1, seeking ?? status.position)}
        aria-label={t('progress')} onChange={event => setSeeking(Number(event.target.value))} onPointerUp={event => seek(Number(event.currentTarget.value))}
        onKeyUp={event => { if (['ArrowLeft', 'ArrowRight', 'Home', 'End', 'PageUp', 'PageDown'].includes(event.key)) seek(Number(event.currentTarget.value)) }}/>
        <div className="player-time"><span>{seconds(seeking ?? status.position)}</span><span>{status.buffering ? t('buffering') : seconds(duration)}</span></div>
        <div className="transport"><button className="icon-button" aria-label={t('previous')} onClick={() => action(mobileMusic.previous())}><TransportIcon kind="previous"/></button>
          <button className="play-button" aria-label={wantsPlay ? t('pause') : t('play')} onClick={() => action(mobileMusic.toggle())}><TransportIcon kind={wantsPlay ? 'pause' : 'play'}/></button>
          <button className="icon-button" aria-label={t('next')} onClick={() => action(mobileMusic.next())}><TransportIcon kind="next"/></button></div>
        {status.error && <p className="status-note" role="status">{playbackError(status.error, status.errorCode)}</p>}
      </div>
    </div>
    {actionsOpen && <Modal title={t('moreActions')} className="player-action-sheet" onClose={() => setActionsOpen(false)} onAfterClose={afterActions}>
      <div className="player-action-list">
        <button type="button" onClick={() => secondary(onDetail)}>{t('detail')}</button>
        <button type="button" onClick={() => secondary(onLookup)}>{t('lookup')}</button>
        <button type="button" onClick={() => secondary(() => setOptionsOpen(true))}>{t('playbackOptions')}</button>
      </div>
    </Modal>}
    {optionsOpen && <PlaybackOptionsDialog onClose={() => setOptionsOpen(false)} onError={onError}/>}
  </section>
}
