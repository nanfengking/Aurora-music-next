import { useEffect, useState } from 'react'
import { Modal } from './components'
import { Choice } from './Choice'
import { native } from './hooks'
import { t } from './i18n'
import { mobileMusic, type AudioOutputs, type PlaybackOptions } from './native'

export function PlaybackOptionsDialog({ onClose, onError }: { onClose: () => void; onError: (error: unknown) => void }) {
  const [value, setValue] = useState<PlaybackOptions | null>(null)
  const [outputs, setOutputs] = useState<AudioOutputs | null>(null)
  const [sleep, setSleep] = useState('')
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState(false)
  useEffect(() => {
    let active = true
    if (native) {
      void mobileMusic.playbackOptions().then(result => { if (active) setValue(result) }).catch(error => { if (active) onError(error) })
      void mobileMusic.audioOutputs().then(result => { if (active) setOutputs(result) }).catch(error => { if (active) onError(error) })
    }
    return () => { active = false }
  }, [onError])
  const save = async () => {
    if (!value || busy) return
    setBusy(true)
    try {
      const { sleepRemainingSeconds: _remaining, ...options } = value
      const result = await mobileMusic.setPlaybackOptions({ ...options, ...(sleep === '' ? {} : { sleepMinutes: Number(sleep) }) })
      setValue(result); setSleep(''); setSaved(true)
    } catch (error) { onError(error) } finally { setBusy(false) }
  }
  const patch = (update: Partial<PlaybackOptions>) => { setSaved(false); setValue(previous => previous && { ...previous, ...update }) }
  return <Modal title={t('playbackOptions')} onClose={onClose} className="playback-options-sheet">
    {!value ? <p role="status">{native ? t('loading') : t('preview')}</p> : <form className="playback-options-form" onSubmit={event => { event.preventDefault(); void save() }}>
      <fieldset disabled={busy}>
        <Choice label={t('repeatMode')} value={value.repeatMode} onChange={mode => patch({ repeatMode: mode as PlaybackOptions['repeatMode'] })}
          options={(['off', 'one', 'all'] as const).map(mode => ({ value: mode, label: t(mode === 'off' ? 'repeatOff' : mode === 'one' ? 'repeatOne' : 'repeatAll') }))}/>
        <label className="switch-row">{t('shuffle')}<input type="checkbox" checked={value.shuffle} onChange={event => patch({ shuffle: event.target.checked })}/></label>
        <label className="switch-row">{t('rememberPlayback')}<input type="checkbox" checked={value.rememberPlayback} onChange={event => patch({ rememberPlayback: event.target.checked })}/></label><p className="status-note">{t('rememberHint')}</p>
        <Choice label={t('speed')} value={value.speed} onChange={speed => patch({ speed: Number(speed) })}
          options={[.5, .75, 1, 1.25, 1.5, 1.75, 2].map(speed => ({ value: String(speed), label: speed + '×' }))}/>
        <label className="volume-control">{t('appVolume')} · {Math.round(value.volume * 100)}%<input type="range" min="0" max="1" step=".05" value={value.volume} onChange={event => patch({ volume: Number(event.target.value) })}/></label>
        <label className="switch-row">{t('normalizeVolume')}<input type="checkbox" checked={value.normalizeVolume} onChange={event => patch({ normalizeVolume: event.target.checked })}/></label>
        <p className="status-note">{t('replayGainHint')}</p>
        <Choice label={t('crossfade')} value={value.crossfadeSeconds} onChange={seconds => patch({ crossfadeSeconds: Number(seconds) })}
          options={[{ value: '0', label: t('crossfadeOff') }, ...[1, 2, 3].map(seconds => ({ value: String(seconds), label: seconds + ' ' + t('seconds') }))]}/>
        <p className="status-note">{t('crossfadeHint')}</p>
        <label className="switch-row">{t('prefetchNext')}<input type="checkbox" checked={value.prefetch} onChange={event => patch({ prefetch: event.target.checked })}/></label>
        <p className="status-note">{t('prefetchHint')}</p>
        <Choice label={t('sleepTimer')} value={sleep} onChange={minutes => { setSleep(minutes); setSaved(false) }}
          options={[{ value: '', label: t('unchanged') }, { value: '0', label: t('cancelTimer') }, ...[15, 30, 45, 60, 90, 120, 180].map(minutes => ({ value: String(minutes), label: minutes + ' ' + t('minutes') }))]}/>
        {value.sleepRemainingSeconds > 0 && <p>{t('timerActive')} · {Math.ceil(value.sleepRemainingSeconds / 60)} {t('minutes')}</p>}
        <p className="status-note">{t('nativePlaybackHint')}</p>
        <button className="solid-button" disabled={!native} type="submit">{busy ? t('loading') : t('save')}</button>
        {saved && <p role="status">{t('playbackSaved')}</p>}
      </fieldset>
    </form>}
    <section className="audio-output-info"><h3>{t('outputDevices')}</h3><p>{t('outputDeviceHint')}</p>
      {outputs && <><ul>{outputs.devices.map((device, index) => <li key={index}><strong>{device.name}</strong><small>{t(device.kind)}</small></li>)}</ul>
        <p>{t('systemVolume')} · {outputs.systemVolume} / {outputs.systemVolumeMax}</p></>}
      <p>{t('noiseHint')}</p>
    </section>
  </Modal>
}
