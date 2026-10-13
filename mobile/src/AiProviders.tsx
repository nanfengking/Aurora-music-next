import { useEffect, useState } from 'react'
import type { AiProtocol, AiProvider } from '../../src/types/providers'
import { native } from './hooks'
import { t } from './i18n'
import { mobileMusic } from './native'
import { Choice } from './Choice'

export function AiProviderSettings({ onError, notify }: { onError: (error: unknown) => void; notify: (text: string) => void }) {
  const [providers, setProviders] = useState<AiProvider[]>([])
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    if (!native) return
    let active = true
    void mobileMusic.aiProviders().then(value => { if (active) setProviders(value.providers) }).catch(onError)
    return () => { active = false }
  }, [onError])
  const update = (id: string, patch: Partial<AiProvider>) => setProviders(items => items.map(item => item.id === id ? { ...item, ...patch } : item))
  const add = (protocol: AiProtocol) => setProviders(items => [...items, {
    id: crypto.randomUUID(), name: protocol === 'openai' ? 'DeepSeek' : protocol === 'responses' ? 'OpenAI Responses' : protocol === 'anthropic' ? 'Anthropic' : 'Gemini',
    protocol, baseUrl: protocol === 'openai' ? 'https://api.deepseek.com' : protocol === 'responses' ? 'https://api.openai.com/v1' : protocol === 'anthropic' ? 'https://api.anthropic.com' : 'https://generativelanguage.googleapis.com/v1beta',
    model: protocol === 'openai' ? 'deepseek-chat' : '', priority: items.length + 1, enabled: true, configured: false,
  }])
  const move = (index: number, direction: number) => setProviders(items => {
    const next = [...items]; [next[index], next[index + direction]] = [next[index + direction], next[index]]; return next
  })
  const save = async (testId?: string) => {
    setBusy(true)
    try {
      const result = await mobileMusic.saveAiProviders({ providers })
      setProviders(result.providers)
      if (testId) { const tested = await mobileMusic.testAiProvider({ id: testId }); notify(t('apiTestOk') + ' · ' + tested.providerName) }
      else notify(t('apiSaved'))
    } catch (error) { onError(error) } finally { setBusy(false) }
  }
  return <section className="settings-group ai-settings"><h2>{t('aiServices')}</h2><p>{t('apiPriorityHint')}</p>
    {providers.map((provider, index) => <details className="api-provider" key={provider.id} open={!provider.configured || undefined}>
      <summary><span>{index + 1}</span><strong>{provider.name}</strong><small>{t(provider.enabled ? provider.configured ? 'apiConfigured' : 'apiUnconfigured' : 'apiDisabled')}</small></summary>
      <label className="switch-row">{t('apiEnabled')}<input type="checkbox" checked={provider.enabled} disabled={busy} onChange={event => update(provider.id, { enabled: event.target.checked })}/></label>
      <label>{t('name')}<input value={provider.name} maxLength={60} disabled={busy} onChange={event => update(provider.id, { name: event.target.value })}/></label>
      <Choice label={t('apiProtocol')} value={provider.protocol} disabled={busy} onChange={protocol => update(provider.id, { protocol: protocol as AiProtocol })}
        options={[{ value: 'openai', label: 'Chat Completions / DeepSeek' }, { value: 'responses', label: 'OpenAI Responses' }, { value: 'anthropic', label: 'Anthropic Messages' }, { value: 'gemini', label: 'Google Gemini' }]}/>
      <label>{t('apiUrl')}<input type="url" value={provider.baseUrl} maxLength={2000} disabled={busy} autoCapitalize="none" autoCorrect="off" onChange={event => update(provider.id, { baseUrl: event.target.value })}/></label>
      <label>{t('apiModel')}<input value={provider.model} maxLength={120} disabled={busy} autoCapitalize="none" autoCorrect="off" onChange={event => update(provider.id, { model: event.target.value })}/></label>
      <label>API Key<input type="password" autoComplete="off" value={provider.apiKey || ''} maxLength={4096} disabled={busy} placeholder={provider.configured ? t('apiKeepKey') : ''} onChange={event => update(provider.id, { apiKey: event.target.value })}/></label>
      <div className="api-actions"><button disabled={busy || !index} aria-label={t('apiUp')} onClick={() => move(index, -1)}>↑</button><button disabled={busy || index === providers.length - 1} aria-label={t('apiDown')} onClick={() => move(index, 1)}>↓</button>
        <button disabled={!native || busy || (!provider.configured && !provider.apiKey?.trim())} onClick={() => void save(provider.id)}>{t('apiTest')}</button><button className="danger" disabled={busy} onClick={() => setProviders(items => items.filter(item => item.id !== provider.id))}>{t('apiRemove')}</button></div>
    </details>)}
    <div className="api-actions">{(['openai', 'responses', 'anthropic', 'gemini'] as const).map(protocol => <button key={protocol} disabled={busy || providers.length >= 10} onClick={() => add(protocol)}>＋ {protocol === 'openai' ? 'DeepSeek' : protocol === 'responses' ? 'Responses' : protocol === 'anthropic' ? 'Anthropic' : 'Gemini'}</button>)}</div>
    <p>{t('apiPrivacyHint')}</p><button className="solid-button" disabled={!native || busy} onClick={() => void save()}>{busy ? t('loading') : t('apiSave')}</button>
  </section>
}
