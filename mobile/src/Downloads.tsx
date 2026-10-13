import { useCallback, useEffect, useRef, useState } from 'react'
import { Modal } from './components'
import { bytes } from './format'
import { native } from './hooks'
import { t } from './i18n'
import { mobileMusic, type DownloadPage, type DownloadTask } from './native'
import { playbackError } from './playbackError'

const PAGE_SIZE = 60
const EMPTY: DownloadPage = { items: [], total: 0, active: 0, offset: 0, hasMore: false }
function errorText(error: string) {
  if (error.startsWith('AUDIO_')) return playbackError(error)
  if (error === 'Reconnect to the original WebDAV server before retrying') return t('audioScope')
  if (error === 'Connection interrupted; waiting to retry') return t('downloadRetrying')
  if (error === 'Not enough device storage') return t('downloadNoSpace')
  if (error === 'Cannot schedule download; retry') return t('downloadScheduleFailed')
  const http = /^Download HTTP (\d{3})$/.exec(error)
  return http ? `${t('downloadHttpError')} ${http[1]}` : t('downloadCheckHint')
}

/** A bounded secondary screen. No WebView progress polling while hidden or in the background. */
export function DownloadsDialog({ onClose, onChanged, onError }: { onClose: () => void; onChanged: () => void; onError: (error: unknown) => void }) {
  const [page, setPage] = useState(EMPTY)
  const [offset, setOffset] = useState(0)
  const [visible, setVisible] = useState(!document.hidden)
  const [busy, setBusy] = useState(false)
  const alive = useRef(true), sequence = useRef(0), operation = useRef(false), fetching = useRef(false)
  const visibleNow = useRef(visible), latest = useRef<() => Promise<void>>(async () => {})
  visibleNow.current = visible
  const refresh = useCallback(async () => {
    if (!native || !visibleNow.current || fetching.current) return
    const stamp = sequence.current; fetching.current = true
    try {
      const result = await mobileMusic.downloadTasks({ offset, limit: PAGE_SIZE })
      if (alive.current && stamp === sequence.current) {
        if (!result.items.length && offset > 0) setOffset(Math.max(0, offset - PAGE_SIZE))
        else setPage(result)
      }
    } catch (error) { if (alive.current && stamp === sequence.current) onError(error) }
    finally { fetching.current = false; if (alive.current && stamp !== sequence.current) void latest.current() }
  }, [offset, onError])
  latest.current = refresh
  useEffect(() => {
    alive.current = true
    let nativeActive = true
    const update = () => setVisible(nativeActive && !document.hidden)
    document.addEventListener('visibilitychange', update)
    const listener = native ? mobileMusic.addListener('appStateChanged', state => { nativeActive = state.active; update() }) : null
    return () => { alive.current = false; sequence.current++; document.removeEventListener('visibilitychange', update); void listener?.then(handle => handle.remove()).catch(() => {}) }
  }, [])
  useEffect(() => { sequence.current++; void refresh() }, [refresh])
  useEffect(() => {
    if (!visible || !native) return
    void refresh()
    if (page.active <= 0) return
    const timer = window.setInterval(() => { if (!operation.current) void refresh() }, 4000)
    return () => clearInterval(timer)
  }, [visible, page.active, refresh])
  const act = async (task: DownloadTask, action: 'pause' | 'resume' | 'cancel') => {
    if (operation.current || !native || action === 'cancel' && !confirm(t('cancelDownloadConfirm'))) return
    operation.current = true; sequence.current++; setBusy(true)
    try {
      if (action === 'pause') await mobileMusic.pauseDownload({ songId: task.songId })
      else if (action === 'cancel') await mobileMusic.cancelDownload({ songId: task.songId })
      else await mobileMusic.download({ songId: task.songId })
      onChanged()
    } catch (error) { if (alive.current) onError(error) }
    finally { operation.current = false; if (alive.current) { setBusy(false); await refresh() } }
  }
  return <Modal title={t('downloadTasks')} onClose={onClose} className="downloads-sheet">
    <p className="status-note">{page.active} {t('activeDownloads')} · {page.total} {t('tracks')}</p>
    <p className="status-note">{t('resumeHint')}</p>
    <button className="outlined-button" disabled={busy || !visible} onClick={() => void refresh()}>{t('refreshDownloads')}</button>
    <div className="download-pagination"><button className="outlined-button" disabled={busy || !visible || offset === 0} onClick={() => setOffset(value => Math.max(0, value - PAGE_SIZE))}>{t('previousDownloads')}</button>
      <span>{page.total ? offset + 1 : 0}–{offset + page.items.length} / {page.total}</span><button className="outlined-button" disabled={busy || !visible || !page.hasMore} onClick={() => setOffset(value => value + PAGE_SIZE)}>{t('nextDownloads')}</button></div>
    <div className="download-tasks" aria-busy={busy}>{page.items.map(task => {
      const active = task.state === 'pending' || task.state === 'running'
      const ratio = task.totalBytes > 0 ? Math.min(100, Math.floor(Math.max(0, task.receivedBytes) * 100 / task.totalBytes)) : null
      return <article className="download-task" key={task.songId}>
        <h3>{task.title}</h3><p>{task.artist || t('unknown')}</p>
        <div className="download-status"><span>{t(task.state === 'pending' ? 'downloadWaiting' : task.state === 'running' ? 'downloading' : task.state === 'paused' ? 'downloadPaused' : task.state === 'failed' ? 'downloadFailed' : 'downloadCancelled')}</span><span>{ratio === null ? bytes(task.receivedBytes) : `${ratio}% · ${bytes(task.receivedBytes)} / ${bytes(task.totalBytes)}`}</span></div>
        {ratio !== null && <progress value={ratio} max={100} aria-label={task.title}/>}
        {task.error && <p className="warning">{errorText(task.error)}</p>}
        <div className="download-actions"><button className="outlined-button" disabled={busy || !visible} onClick={() => void act(task, active ? 'pause' : 'resume')}>{t(active ? 'pauseDownload' : task.state === 'failed' ? 'retryDownload' : 'resumeDownload')}</button>
          <button className="text-button danger" disabled={busy || !visible} onClick={() => void act(task, 'cancel')}>{t('cancelDownload')}</button></div>
      </article>
    })}</div>
    {!page.total && <p className="status-note">{t('noDownloads')}</p>}
  </Modal>
}
