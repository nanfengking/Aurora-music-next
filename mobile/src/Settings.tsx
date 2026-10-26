import { useEffect, useState } from 'react'
import { bytes } from './format'
import { Choice } from './Choice'
import { native } from './hooks'
import { languages, t } from './i18n'
import { mobileMusic, type Appearance, type AppearancePatch, type NativeLanguage } from './native'
import { AiProviderSettings } from './AiProviders'
import { PlaybackOptionsDialog } from './PlaybackOptions'
import { DownloadsDialog } from './Downloads'
import { AssetProviderSettings } from './AssetProviders'

export function SettingsPage({ appearance, saveAppearance, locale, setLocale, offlineOnly, setOfflineOnly, offlineTotal, connected, setConnected, onChanged, onError, notify }: {
  appearance: Appearance; saveAppearance: (value: AppearancePatch) => Promise<void>; locale: NativeLanguage; setLocale: (value: NativeLanguage) => Promise<void>
  offlineOnly: boolean; setOfflineOnly: (value: boolean) => void; offlineTotal: number
  connected: { url: string; username: string }; setConnected: (value: { url: string; username: string }) => void
  onChanged: () => void; onError: (error: unknown) => void; notify: (message: string) => void
}) {
  const [config, setConfig] = useState({ ...connected, password: '' })
  const [wifiOnly, setWifiOnly] = useState(true)
  const [storageBytes, setStorageBytes] = useState(0)
  const [partialBytes, setPartialBytes] = useState(0)
  const [freeBytes, setFreeBytes] = useState(0)
  const [stream, setStream] = useState({ bytes: 0, limit: 128 * 1024 * 1024, artwork: 0 })
  const [clearing, setClearing] = useState(false)
  const [storageRevision, setStorageRevision] = useState(0)
  const [busy, setBusy] = useState(false)
  const [optionsOpen, setOptionsOpen] = useState(false)
  const [downloadsOpen, setDownloadsOpen] = useState(false)
  useEffect(() => {
    if (!native) return
    let active = true
    void mobileMusic.settings().then(settings => { if (active) {
      setWifiOnly(settings.wifiOnly); setStorageBytes(settings.storageBytes); setPartialBytes(settings.partialBytes); setFreeBytes(settings.freeBytes)
      setStream({ bytes: settings.streamBytes, limit: settings.streamLimitBytes, artwork: settings.artworkBytes })
    } }).catch(error => { if (active) onError(error) })
    return () => { active = false }
  }, [offlineTotal, storageRevision, onError])
  useEffect(() => setConfig(value => ({ ...value, ...connected })), [connected])
  const connect = async () => {
    setBusy(true)
    try {
      const result = await mobileMusic.connect(config)
      setConnected({ url: result.url, username: config.username }); setConfig(value => ({ ...value, password: '' })); onChanged(); notify(t('connected'))
    } catch (error) { onError(error) } finally { setBusy(false) }
  }
  const act = (task: Promise<unknown>) => { void task.catch(onError) }
  const lookupSwitch = (enabled: boolean) => { if (!enabled || confirm(t('lookupConsent'))) act(saveAppearance({ autoLookup: enabled })) }
  const clearStream = async () => {
    if (!native || clearing || !confirm(t('clearStreamConfirm'))) return
    setClearing(true)
    try {
      const result = await mobileMusic.clearStreamCache(); setStorageRevision(value => value + 1)
      notify(`${t('cacheFreed')} ${bytes(result.freedBytes)}${result.retainedBytes > 0 ? ' · ' + t('cacheInUse') : ''}`)
    } catch (error) { onError(error) } finally { setClearing(false) }
  }
  return <div className="settings-layout">
    <section className="settings-group"><h2>{t('appearance')}</h2>
      <div className="segmented">{(['system', 'light', 'dark'] as const).map(mode => <button key={mode} aria-pressed={appearance.mode === mode} onClick={() => act(saveAppearance({ mode }))}>{t(mode)}</button>)}</div>
      <label className="switch-row">{t('dynamic')}<input type="checkbox" checked={appearance.dynamic} disabled={native && !appearance.dynamicAvailable} onChange={event => act(saveAppearance({ dynamic: event.target.checked }))}/></label>
      <label className="color-row">{t('accent')}<input type="color" value={appearance.accent} disabled={appearance.dynamic && appearance.dynamicAvailable} onChange={event => act(saveAppearance({ accent: event.target.value, dynamic: false }))}/></label>
      <label className="switch-row">{t('showBackdrop')}<input type="checkbox" checked={appearance.showBackdrop} onChange={event => act(saveAppearance({ showBackdrop: event.target.checked }))}/></label><p>{t('showBackdropHint')}</p>
      <label className="switch-row">{t('motion')}<input type="checkbox" checked={appearance.motion} onChange={event => act(saveAppearance({ motion: event.target.checked }))}/></label>
      <label className="switch-row">{t('lowPower')}<input type="checkbox" checked={appearance.lowPowerVisuals} onChange={event => act(saveAppearance({ lowPowerVisuals: event.target.checked }))}/></label><p>{t('lowPowerHint')}</p>
      <Choice label={t('lyricsSize')} value={appearance.lyricsSize} onChange={size => act(saveAppearance({ lyricsSize: Number(size) }))}
        options={[18, 24, 30, 36].map(size => ({ value: String(size), label: size + ' px' }))}/><p>{t('lyricsSizeHint')}</p>
      <Choice label={t('language')} setting="language" value={locale} onChange={language => act(setLocale(language as NativeLanguage))}
        options={native ? [{ value: '', label: t('system') }, ...languages] : languages}/>
    </section>
    <section className="settings-group"><h2>{t('playbackOptions')}</h2><p>{t('nativePlaybackHint')}</p>
      <button className="outlined-button" onClick={() => setOptionsOpen(true)}>{t('playbackAndDevices')}</button>
    </section>
    <section className="settings-group"><h2>{t('connection')}</h2>
      <label>{t('server')}<input type="url" value={config.url} onChange={event => setConfig({ ...config, url: event.target.value })} placeholder="https://example.com/dav/music/" autoCapitalize="none" autoCorrect="off"/></label>
      <label>{t('username')}<input value={config.username} onChange={event => setConfig({ ...config, username: event.target.value })} autoCapitalize="none" autoCorrect="off"/></label>
      <label>{t('password')}<input type="password" autoComplete="current-password" value={config.password} onChange={event => setConfig({ ...config, password: event.target.value })}/></label>
      <button className="solid-button" onClick={() => void connect()} disabled={!native || busy || !config.url.trim()}>{busy ? t('loading') : t('connect')}</button>
      <p>{t('passwordHint')}</p>
    </section>
    <section className="settings-group"><h2>{t('storage')}</h2><div className="storage-usage"><strong>{bytes(storageBytes)}</strong><span>{offlineTotal} {t('tracks')}</span></div>
      <p>{t('partialStorage')}: {bytes(partialBytes)} · {t('freeStorage')}: {bytes(freeBytes)}</p>
      <p>{t('streamCache')}: {bytes(stream.bytes)} / {bytes(stream.limit)} · {t('artworkStorage')}: {bytes(stream.artwork)}</p>
      <button className="outlined-button" disabled={!native || clearing || !stream.bytes} onClick={() => void clearStream()}>{clearing ? t('loading') : t('clearStreamCache')}</button><p>{t('streamCacheHint')}</p>
      <button className="outlined-button" disabled={!native} onClick={() => setDownloadsOpen(true)}>{t('downloadTasks')}</button>
      <label className="switch-row">{t('wifiOnly')}<input type="checkbox" checked={wifiOnly} onChange={event => {
        const enabled = event.target.checked
        if (!native) { setWifiOnly(enabled); return }
        act(mobileMusic.setWifiOnly({ enabled }).then(() => setWifiOnly(enabled)))
      }}/></label>
      <label className="switch-row">{t('offline')}<input type="checkbox" checked={offlineOnly} onChange={event => setOfflineOnly(event.target.checked)}/></label><p>{t('downloadHint')}</p>
      <label className="switch-row">{t('autoLookup')}<input type="checkbox" checked={appearance.autoLookup} onChange={event => lookupSwitch(event.target.checked)}/></label><p>{t('lookupHint')}</p>
    </section>
    <section className="settings-group"><h2>{t('background')}</h2><p>{t('backgroundHint')}</p>
      {(appearance.batteryOptimized || appearance.backgroundRestricted) && <p className="warning">{t('restricted')}</p>}
      <button className="outlined-button" disabled={!native} onClick={() => act(mobileMusic.openSystemSettings({ section: 'app' }))}>{t('appSettings')}</button>
      <button className="text-button" disabled={!native} onClick={() => act(mobileMusic.openSystemSettings({ section: 'battery' }))}>{t('batterySettings')}</button>
    </section>
    <section className="settings-group"><h2>{t('widgets')}</h2><div className="widget-preview"><div className="widget-preview-track"><small>AURORA</small><strong>Morning light</strong><span>♫ &nbsp; ⏮　Ⅱ　⏭</span></div><div className="widget-preview-lyrics"><small>AURORA · LYRICS</small><strong>And the music<br/>carries us home.</strong><span>⏮　Ⅱ　⏭</span></div></div>
      {(['music', 'lyrics'] as const).map(kind => <button key={kind} className="outlined-button" disabled={!native} onClick={() => act(mobileMusic.pinWidget({ kind }).then(result => notify(t(result.requested ? 'widgetRequested' : 'widgetUnsupported'))))}>{t(kind === 'music' ? 'musicWidget' : 'lyricWidget')}</button>)}<p>{t('widgetHint')}</p>
    </section><AssetProviderSettings onError={onError} notify={notify}/><AiProviderSettings onError={onError} notify={notify}/><footer className="byline">by jinlaoshi</footer>
    {optionsOpen && <PlaybackOptionsDialog onClose={() => setOptionsOpen(false)} onError={onError}/>}
    {downloadsOpen && <DownloadsDialog onClose={() => { setDownloadsOpen(false); setStorageRevision(value => value + 1) }} onChanged={() => { setStorageRevision(value => value + 1); onChanged() }} onError={onError}/>}
  </div>
}
