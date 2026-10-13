import { useEffect, useState } from 'react'
import type { AssetKind, AssetProvider } from '../../src/types/providers'
import { native } from './hooks'
import { t, type Message } from './i18n'
import { mobileMusic } from './native'
import { Choice } from './Choice'

export function assetError(error: unknown): Error {
  const code = error instanceof Error ? error.message : String(error)
  const keys: Record<string, Message> = { ASSET_BUSY: 'assetBusy', ASSET_CANCELLED: 'assetCancelled', ASSET_EXPIRED: 'assetExpired', ASSET_STORAGE_FULL: 'assetStorageFull', ASSET_CONFIG_INVALID: 'assetConfigInvalid', ASSET_FAILED: 'assetFailed', WEBDAV_XML_INVALID: 'webdavXmlInvalid', WEBDAV_XML_UNSAFE: 'webdavXmlUnsafe', WEBDAV_XML_LIMIT: 'webdavXmlLimit' }
  return new Error(keys[code] ? t(keys[code]) : code)
}

export function AssetProviderSettings({ onError, notify }: { onError: (error: unknown) => void; notify: (text: string) => void }) {
  const [providers, setProviders] = useState<AssetProvider[]>([])
  const [itunesEnabled, setItunesEnabled] = useState(false)
  const [ready, setReady] = useState(false)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    if (!native) return
    let active = true
    void mobileMusic.assetProviders().then(value => { if (active) { setProviders(value.providers); setItunesEnabled(value.itunesEnabled); setReady(true) } }).catch(error => { if (active) onError(assetError(error)) })
    return () => { active = false }
  }, [onError])
  const update = (id: string, patch: Partial<AssetProvider>) => setProviders(items => items.map(item => item.id === id ? { ...item, ...patch } : item))
  const move = (index: number, step: number) => setProviders(items => {
    const next = [...items]; [next[index], next[index + step]] = [next[index + step], next[index]]; return next
  })
  const save = async () => {
    setBusy(true)
    try {
      await mobileMusic.cancelAssets()
      const result = await mobileMusic.saveAssetProviders({ providers, itunesEnabled })
      setProviders(result.providers); setItunesEnabled(result.itunesEnabled); notify(t('apiSaved'))
    } catch (error) { onError(assetError(error)) } finally { setBusy(false) }
  }
  return <section className="settings-group asset-settings"><h2>{t('assetServices')}</h2><p>{t('assetPriorityHint')}</p>
    {providers.map((provider, index) => <details className="api-provider" key={provider.id} open={!provider.configured || undefined}>
      <summary><span>{index + 1}</span><strong>{provider.name}</strong><small>{t(provider.kind === 'cover' ? 'assetCover' : provider.kind === 'background' ? 'assetBackground' : 'lyrics')}</small></summary>
      <label className="switch-row">{t('apiEnabled')}<input type="checkbox" checked={provider.enabled} disabled={busy} onChange={event => update(provider.id, { enabled: event.target.checked })}/></label>
      <label>{t('name')}<input maxLength={60} value={provider.name} disabled={busy} onChange={event => update(provider.id, { name: event.target.value })}/></label>
      <Choice label={t('assetKind')} value={provider.kind} disabled={busy} onChange={kind => update(provider.id, { kind: kind as AssetKind })}
        options={[{ value: 'lyrics', label: t('lyrics') }, { value: 'cover', label: t('assetCover') }, { value: 'background', label: t('assetBackground') }]}/>
      <label>{t('assetEndpoint')}<input type="url" value={provider.baseUrl} maxLength={2000} disabled={busy} autoCapitalize="none" autoCorrect="off" placeholder="https://example.com/api/lyrics" onChange={event => update(provider.id, { baseUrl: event.target.value })}/></label>
      <label>{t('assetOptionalKey')}<input type="password" autoComplete="off" value={provider.apiKey || ''} maxLength={4096} disabled={busy} placeholder={provider.configured ? t('apiKeepKey') : ''} onChange={event => update(provider.id, { apiKey: event.target.value })}/></label>
      <div className="api-actions"><button disabled={busy || !index} aria-label={t('apiUp')} onClick={() => move(index, -1)}>↑</button><button disabled={busy || index === providers.length - 1} aria-label={t('apiDown')} onClick={() => move(index, 1)}>↓</button>
        <button className="danger" disabled={busy} onClick={() => setProviders(items => items.filter(item => item.id !== provider.id))}>{t('apiRemove')}</button></div>
    </details>)}
    <button className="outlined-button" disabled={busy || (native && !ready) || providers.length >= 10} onClick={() => setProviders(items => [...items, { id: crypto.randomUUID(), name: 'API', kind: 'lyrics', baseUrl: '', enabled: true, configured: false, priority: items.length + 1 }])}>{t('assetAdd')}</button>
    <p>{t('assetContractHint')}</p><p>{t('assetPrivacyHint')}</p>
    <label className="switch-row">{t('assetItunes')}<input type="checkbox" checked={itunesEnabled} disabled={busy || (native && !ready)} onChange={event => { if (!event.target.checked || confirm(t('assetItunesConsent'))) setItunesEnabled(event.target.checked) }}/></label>
    <p>{t('assetItunesHint')} <a href="https://performance-partners.apple.com/search-api" target="_blank" rel="noreferrer">{t('assetTerms')}</a></p>
    <button className="solid-button" disabled={!native || !ready || busy} onClick={() => void save()}>{busy ? t('loading') : t('apiSave')}</button>
  </section>
}
