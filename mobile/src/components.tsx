import { useEffect, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { imageUrl } from './format'
import { t } from './i18n'
import type { MobileSong } from './native'

export function Artwork({ song, className = '', revision = 0 }: { song: MobileSong | null; className?: string; revision?: number }) {
  return <span className={'artwork ' + className} aria-hidden="true">
    {song?.coverPath ? <img src={imageUrl(song.coverPath, revision)} alt="" loading="lazy" decoding="async"/> : <span>♫</span>}
  </span>
}
export function Track({ song, playing, onPlay, onDetail, onDownload }: {
  song: MobileSong; playing: boolean; onPlay: () => void; onDetail: () => void; onDownload: () => void
}) {
  return <div className={'track ' + (playing ? 'current-track' : '')}>
    <button className="track-main" onClick={onPlay} aria-label={t('play') + ' · ' + song.title}>
      <Artwork song={song}/><span className="track-copy"><strong>{song.title}</strong><small>{song.artist || t('unknown')}{song.album && ' · ' + song.album}</small></span>
    </button>
    <button className={'icon-button download-button ' + (song.offline ? 'ready' : '')} onClick={onDownload}
      disabled={song.offline || song.downloading} aria-label={song.offline ? t('downloaded') : song.downloading ? t('downloading') : t('download')}>
      {song.offline ? '✓' : song.downloading ? '◷' : '↓'}
    </button>
    <button className="icon-button" onClick={onDetail} aria-label={t('detail') + ' · ' + song.title}>⋯</button>
  </div>
}
let modalId = 0
const modalStack: number[] = []
/** Native dialog focus handling plus a history entry for Android's back gesture. */
export function Modal({ title, onClose, children, className = '', decoration, closeIcon = false, onAfterClose }: {
  title: string; onClose: () => void; children: ReactNode; className?: string; decoration?: ReactNode; closeIcon?: boolean; onAfterClose?: () => void
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const close = useRef(onClose); close.current = onClose
  const afterClose = useRef(onAfterClose); afterClose.current = onAfterClose
  useEffect(() => {
    const dialog = ref.current!
    const id = ++modalId
    modalStack.push(id)
    dialog.showModal()
    history.pushState({ auroraModal: id }, '')
    const pop = () => { if (modalStack[modalStack.length - 1] === id && history.state?.auroraModal !== id) close.current() }
    const cancel = (event: Event) => { event.preventDefault(); close.current() }
    window.addEventListener('popstate', pop); dialog.addEventListener('cancel', cancel)
    return () => {
      window.removeEventListener('popstate', pop); dialog.removeEventListener('cancel', cancel); dialog.close()
      const index = modalStack.indexOf(id); if (index >= 0) modalStack.splice(index, 1)
      if (history.state?.auroraModal === id) {
        // Complete the old history traversal before an action opens another
        // dialog. Otherwise its pushState can race this asynchronous back().
        if (afterClose.current) window.addEventListener('popstate', () => afterClose.current?.(), { once: true })
        history.back()
      } else afterClose.current?.()
    }
  }, [])
  // Keep nested dialogs out of an animated/scrolling ancestor's containing block.
  return createPortal(<dialog ref={ref} className={'sheet ' + className} aria-label={title} onClick={event => { if (event.target === ref.current) onClose() }}>
    {decoration}<div className="sheet-inner"><header className="sheet-header"><h2>{title}</h2><button type="button" className={closeIcon ? 'icon-button' : 'text-button'} aria-label={t('close')} onClick={onClose}>
      {closeIcon ? <svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/></svg> : t('close')}
    </button></header>{children}</div>
  </dialog>, document.body)
}
export function Empty() { return <div className="empty-state"><span aria-hidden="true">♫</span><p>{t('empty')}</p></div> }
