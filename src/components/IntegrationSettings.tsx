import { t } from '../i18n'
import { useEffect, useState } from 'react';
import type { AiProtocol, AiProvider, AssetConfig } from '../types/providers';
import type { DeepSeekConfigStatus } from '../types/music';
import { useThemeStore, type Theme } from '../stores/themeStore';
export function IntegrationSettings({ notify, onAiChanged }: {
    notify: (text: string) => void;
    onAiChanged: (status: DeepSeekConfigStatus) => void;
}) {
    const { theme, setTheme } = useThemeStore();
    const [providers, setProviders] = useState<AiProvider[]>([]);
    const [assets, setAssets] = useState<AssetConfig>({ lyricsUrl: '', coverUrl: '', backgroundUrl: '', configured: false, audioDbConfigured: false });
    const [busy, setBusy] = useState(false);
    useEffect(() => { void window.electronAPI.getAiProviders().then(r => { if (r.data)
        setProviders(r.data); }); void window.electronAPI.getAssetConfig().then(r => { if (r.data)
        setAssets(r.data); }); }, []);
    const update = (id: string, patch: Partial<AiProvider>) => setProviders(items => items.map(p => p.id === id ? { ...p, ...patch } : p));
    const add = (protocol: AiProtocol, standard = false) => setProviders(items => [...items, { id: crypto.randomUUID(), name: standard ? ({ openai: 'OpenAI', anthropic: 'Anthropic', gemini: 'Gemini' }[protocol]) : 'DeepSeek', protocol, baseUrl: standard ? ({ openai: 'https://api.openai.com/v1', anthropic: 'https://api.anthropic.com', gemini: 'https://generativelanguage.googleapis.com/v1beta' }[protocol]) : 'https://api.deepseek.com', model: standard ? '' : 'deepseek-chat', priority: items.length + 1, enabled: true, configured: false, apiKey: '' }]);
    const move = (index: number, step: number) => setProviders(items => { const next = [...items]; [next[index], next[index + step]] = [next[index + step], next[index]]; return next; });
    const save = async () => {
        setBusy(true);
        try {
            const result = await window.electronAPI.saveAiProviders(providers);
            if (!result.data)
                throw new Error(result.error || t("保存失败"));
            setProviders(result.data);
            const status = await window.electronAPI.getDeepSeekConfig();
            if (status.data)
                onAiChanged(status.data);
            notify(t("API 与优先级已安全保存"));
        }
        catch (error) {
            notify(error instanceof Error ? error.message : t("保存失败"));
        }
        finally {
            setBusy(false);
        }
    };
    const saveAssets = async () => { setBusy(true); try {
        const result = await window.electronAPI.saveAssetConfig(assets);
        if (result.data) {
            setAssets(result.data);
            notify(t("歌词与图片接口已保存"));
        }
        else
            notify(result.error || t("保存失败"));
    }
    finally {
        setBusy(false);
    } };
    return <>
    <section className="settings-card wide"><div className="setting-title"><div><h2>{t("外观")}</h2><p>{t("明亮、深色，或随 Windows 自动切换")}</p></div></div><div className="theme-options">{([['light', t("明亮")], ['dark', t("深色")], ['system', t("跟随系统")]] as [
        Theme,
        string
    ][]).map(([value, label]) => <button key={value} className={theme === value ? 'active' : ''} aria-pressed={theme === value} onClick={() => setTheme(value)}><span className={`theme-swatch ${value}`}/>{label}</button>)}</div></section>
    <section className="settings-card wide"><div className="setting-title"><div><h2>{t("AI 服务与优先级")}</h2><p>{t("从上到下尝试已启用且填写密钥的服务；失败或结果无效时自动切换。")}</p></div></div>
      <div className="provider-list">{providers.map((provider, index) => <details className="provider-editor" key={provider.id} open={!provider.configured || undefined}><summary><span className="provider-order">{index + 1}</span><b>{provider.name}</b><small>{provider.enabled ? (provider.configured ? t("已配置") : t("待填写密钥")) : t("已停用")}</small></summary><div className="provider-body"><div className="provider-actions"><label><input type="checkbox" checked={provider.enabled} onChange={event => update(provider.id, { enabled: event.target.checked })}/>{t("启用")}</label><button className="secondary-button" disabled={!index} onClick={() => move(index, -1)} aria-label={t("提高 {0} 优先级", [provider.name])}>{t("↑ 上移")}</button><button className="secondary-button" disabled={index === providers.length - 1} onClick={() => move(index, 1)} aria-label={t("降低 {0} 优先级", [provider.name])}>{t("↓ 下移")}</button><button className="text-button" onClick={() => setProviders(items => items.filter(p => p.id !== provider.id))}>{t("移除配置")}</button></div><div className="settings-form">
      <label><span>{t("服务名称")}</span><input value={provider.name} onChange={event => update(provider.id, { name: event.target.value })}/></label><label><span>{t("请求协议")}</span><select value={provider.protocol} onChange={event => update(provider.id, { protocol: event.target.value as AiProtocol })}><option value="openai">{t("OpenAI 兼容 / DeepSeek")}</option><option value="anthropic">Anthropic Messages</option><option value="gemini">Google Gemini</option></select></label>
      <label className="wide"><span>{t("API 地址")}</span><input value={provider.baseUrl} onChange={event => update(provider.id, { baseUrl: event.target.value })}/></label><label><span>{t("模型 ID")}</span><input value={provider.model} onChange={event => update(provider.id, { model: event.target.value })} placeholder={t("填写服务提供的模型 ID")}/></label><label><span>API Key</span><input type="password" autoComplete="off" value={provider.apiKey || ''} onChange={event => update(provider.id, { apiKey: event.target.value })} placeholder={provider.configured ? t("已保存 · 留空不变") : t("只保存在本机加密存储")}/></label></div></div></details>)}</div>
      <div className="preset-actions"><button className="secondary-button" disabled={providers.length >= 10} onClick={() => add('openai')}>＋ DeepSeek</button><button className="secondary-button" disabled={providers.length >= 10} onClick={() => add('openai', true)}>{t("＋ OpenAI 兼容")}</button><button className="secondary-button" disabled={providers.length >= 10} onClick={() => add('anthropic', true)}>＋ Anthropic</button><button className="secondary-button" disabled={providers.length >= 10} onClick={() => add('gemini', true)}>＋ Gemini</button></div>
      <p className="inline-note">{t("自动切换可能产生多次计费。只发送候选歌曲信息与需求，不发送音频或 WebDAV 凭据；中转服务也能看到这些内容。移除、启停和排序需保存后生效。")}</p><div className="settings-footer"><p>{t("最多 10 个服务，单次生成总等待不超过约 90 秒。")}</p><button className="primary-button" disabled={busy} onClick={() => void save()}>{t("保存 API 与优先级")}</button></div>
    </section>
    <section className="settings-card wide"><div className="setting-title"><div><h2>{t("歌词、封面与背景")}</h2><p>{t("在正在播放页面点击“查找资源”，预览并确认后才替换。")}</p></div></div><p className="inline-note">{t("默认使用 LRCLIB 歌词和 TheAudioDB 图片。不会后台扫描曲库；搜索时会向所选服务发送你填写的歌名、艺术家与专辑。免费接口可能限流或找不到匹配结果。")}</p><div className="settings-form">
      <label><span>{t("TheAudioDB Key（选填）")}</span><input type="password" autoComplete="off" value={assets.audioDbKey || ''} onChange={event => setAssets({ ...assets, audioDbKey: event.target.value })} placeholder={assets.audioDbConfigured ? t("已保存 · 留空不变") : t("留空使用公共测试接口")}/></label><label><span>{t("自定义接口 Bearer Key（选填）")}</span><input type="password" autoComplete="off" value={assets.apiKey || ''} onChange={event => setAssets({ ...assets, apiKey: event.target.value })} placeholder={assets.configured ? t("已保存 · 留空不变") : t("仅用于下面填写的自定义地址")}/></label>
      {([['lyricsUrl', t("自定义歌词 API")], ['coverUrl', t("自定义封面 API")], ['backgroundUrl', t("自定义背景 API")]] as const).map(([field, label]) => <label className="wide" key={field}><span>{label}</span><input value={assets[field]} onChange={event => setAssets({ ...assets, [field]: event.target.value })} placeholder={t("留空使用内置服务；自定义格式见开发说明")}/></label>)}</div><div className="settings-footer"><p>{t("自定义 API 需返回约定的 JSON 格式，不是任意网页地址。")}</p><button className="primary-button" disabled={busy} onClick={() => void saveAssets()}>{t("保存资源接口")}</button></div></section>
  </>;
}
