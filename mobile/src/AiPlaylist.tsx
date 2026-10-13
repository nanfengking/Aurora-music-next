import { useEffect, useRef, useState } from 'react'
import type { AiPlaylistResult } from '../../src/types/music'
import { tm as desktopMessage } from '../../src/i18n'
import { Modal } from './components'
import { seconds } from './format'
import { native } from './hooks'
import { generateMobilePlaylist } from './hybridPlaylist'
import { t } from './i18n'
import { mobileMusic, type MobileSong } from './native'

export function AiPlaylist({ offlineOnly, onClose, onSaved, onError }: { offlineOnly: boolean; onClose: () => void; onSaved: () => void; onError: (error: unknown) => void }) {
  const [prompt, setPrompt] = useState('')
  const [useAi, setUseAi] = useState(false)
  const [busy, setBusy] = useState(false)
  const [saving, setSaving] = useState(false)
  const [progress, setProgress] = useState({ count: 0, total: 0 })
  const [result, setResult] = useState<AiPlaylistResult | null>(null)
  const [songs, setSongs] = useState<MobileSong[]>([])
  const controller = useRef<AbortController | null>(null)
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    let active = true
    const cancel = () => { controller.current?.abort(); if (native && controller.current) void mobileMusic.cancelAi().catch(() => {}) }
    let listener: { remove: () => Promise<void> } | undefined
    if (native) void mobileMusic.addListener('appStateChanged', state => { if (!state.active) cancel() })
      .then(value => { if (active) listener = value; else void value.remove() }).catch(onError)
    return () => { active = false; mounted.current = false; cancel(); if (listener) void listener.remove() }
  }, [])
  const generate = async () => {
    if (useAi && !confirm(t('aiConsent'))) return
    const task = new AbortController(); controller.current = task
    setBusy(true); setResult(null); setSongs([]); setProgress({ count: 0, total: 0 })
    try {
      const next = await generateMobilePlaylist(prompt.trim(), useAi, offlineOnly, task.signal, (count, total) => { if (!task.signal.aborted && mounted.current) setProgress({ count, total }) })
      // Only the at-most-80 selected records, not the complete library, need rich UI metadata.
      const selected: MobileSong[] = []
      for (const id of next.songIds) {
        if (task.signal.aborted || !mounted.current) return
        const value = await mobileMusic.song({ songId: id }); if (value.song) selected.push(value.song)
      }
      if (!task.signal.aborted && mounted.current) { setResult(next.source === 'local' ? { ...next, description: t('aiLocalDescription') } : next); setSongs(selected) }
    } catch (error) { if (!task.signal.aborted && mounted.current) onError(error) }
    finally { if (controller.current === task) controller.current = null; if (mounted.current) setBusy(false) }
  }
  const save = async () => {
    if (!result || !songs.length) return
    setSaving(true)
    try { await mobileMusic.savePlaylist({ name: result.name, description: result.description, songIds: songs.map(song => song.id) }); onSaved(); onClose() }
    catch (error) { onError(error) } finally { if (mounted.current) setSaving(false) }
  }
  return <Modal title={t('aiPlaylist')} onClose={onClose}><div className="settings-group playlist-editor ai-playlist">
    <label>{t('aiRequest')}<textarea value={prompt} maxLength={2000} rows={3} disabled={busy || saving} placeholder={t('aiExample')} onChange={event => setPrompt(event.target.value)}/></label>
    <label className="switch-row">{t('aiUseApi')}<input type="checkbox" checked={useAi} disabled={busy || saving} onChange={event => setUseAi(event.target.checked)}/></label><p>{t('aiHybridHint')}</p>
    <button className="solid-button" disabled={!native || busy || saving || !prompt.trim()} onClick={() => void generate()}>{busy ? t('aiGenerating') : t('aiGenerate')}</button>
    {busy && <p role="status">{progress.total ? `${t('aiChecking')} ${progress.count} / ${progress.total}` : t('aiGenerating')}</p>}
    {result && <section className="ai-result"><h3>{result.name}</h3><p>{result.description}</p><p>{songs.length} {t('tracks')} · {seconds(result.diagnostics?.durationSeconds || 0)} · {t(result.source === 'hybrid' ? 'aiHybrid' : 'aiLocal')}</p>
      <p>{t('aiChecked')} {result.diagnostics?.librarySize} · {t('aiCandidates')} {result.diagnostics?.candidateSize}</p>
      {result.diagnostics?.estimatedTracks ? <p className="warning">{t('aiEstimated')}</p> : null}
      {!!result.diagnostics?.warnings.length && <details><summary>{t('aiDiagnostics')}</summary>{result.diagnostics.warnings.map((warning, index) => <p key={index}>{desktopMessage(warning)}</p>)}</details>}
      {!!result.attempts?.length && <details><summary>{t('apiAttempts')}</summary>{result.attempts.map((attempt, index) => <p key={index}>{attempt}</p>)}</details>}
      <ol className="ai-track-list">{songs.map(song => <li key={song.id}><strong>{song.title}</strong><span>{song.artist}</span></li>)}</ol>
      <button className="solid-button" disabled={saving || !songs.length} onClick={() => void save()}>{t('aiSavePlaylist')}</button>
    </section>}
  </div></Modal>
}
