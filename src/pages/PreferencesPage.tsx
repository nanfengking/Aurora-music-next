import { t, languages, isLocale } from '../i18n'
import { useLanguageStore } from '../stores/languageStore'
import { useEffect, useState } from 'react';
import type { DeepSeekConfigStatus } from '../types/music';
import type { WebDavConfig, WebDavFile } from '../types/webdav';
import { Icon } from '../components/Icon';
import { Modal } from '../components/Modal';
import { IntegrationSettings } from '../components/IntegrationSettings';
const bytes = (value: number) => {
    if (!value)
        return '0 B';
    const unit = Math.min(4, Math.floor(Math.log(value) / Math.log(1024)));
    return `${(value / 1024 ** unit).toFixed(unit ? 1 : 0)} ${['B', 'KB', 'MB', 'GB', 'TB'][unit]}`;
};
function SettingTitle({ icon, title, subtitle }: {
    icon: 'cloud' | 'sparkles' | 'download' | 'settings';
    title: string;
    subtitle: string;
}) {
    return <div className="setting-title"><span><Icon name={icon}/></span><div><h2>{title}</h2><p>{subtitle}</p></div></div>;
}
export function PreferencesPage({ config, setConfig, aiStatus, onConnected, onAiChanged, notify }: {
    config: WebDavConfig;
    setConfig: (config: WebDavConfig) => void;
    aiStatus: DeepSeekConfigStatus;
    onConnected: (files: WebDavFile[]) => void;
    onAiChanged: (status: DeepSeekConfigStatus) => void;
    notify: (message: string) => void;
}) {
    const { locale, changeLocale } = useLanguageStore();
    const [showWebDavPassword, setShowWebDavPassword] = useState(false);
    const [working, setWorking] = useState(false);
    const [confirmClear, setConfirmClear] = useState(false);
    const [clearing, setClearing] = useState(false);
    const [cache, setCache] = useState({ totalSize: 0, maxSize: 2 * 1024 ** 3, fileCount: 0 });
    const [cacheGb, setCacheGb] = useState(2);
    const refreshCache = async () => {
        const response = await window.electronAPI.webdav.getCacheInfo();
        if (response.data) {
            setCache(response.data);
            setCacheGb(Number((response.data.maxSize / 1024 ** 3).toFixed(1)));
        }
    };
    useEffect(() => { void refreshCache(); }, []);
    const saveWebDav = async () => {
        if (!config.url.trim())
            return notify(t("请填写 WebDAV 地址"));
        setWorking(true);
        const normalized = { ...config, url: config.url.trim() };
        const test = await window.electronAPI.webdav.testConnection(normalized);
        if (!test.success || !test.data) {
            notify(test.error || t("WebDAV 连接失败"));
            setWorking(false);
            return;
        }
        const [saved, root] = await Promise.all([
            window.electronAPI.webdav.saveConfig(normalized),
            window.electronAPI.webdav.listFiles(normalized, ''),
        ]);
        if (saved.success && root.data) {
            setConfig(normalized);
            onConnected(root.data);
            notify(t("WebDAV 已连接并安全保存"));
        }
        else
            notify(saved.error || root.error || t("保存失败"));
        setWorking(false);
    };
    const applyCache = async () => {
        const response = await window.electronAPI.webdav.setCacheMaxSize(cacheGb * 1024 ** 3);
        notify(response.success ? t("缓存上限已设为 {0} GB", [cacheGb]) : response.error || t("设置失败"));
        await refreshCache();
    };
    const clearCache = async () => {
        setClearing(true);
        try {
            const response = await window.electronAPI.webdav.clearCache();
            if (response.data) {
                notify(t("已清理 {0} 个文件，释放 {1}", [response.data.count, bytes(response.data.freed)]));
                setConfirmClear(false);
            }
            else
                notify(response.error || t("缓存清理失败"));
            await refreshCache();
        }
        catch {
            notify(t("缓存清理失败，请重试。"));
        }
        finally {
            setClearing(false);
        }
    };
    return (<div className="preferences-grid">
      <section className="settings-card wide language-card"><SettingTitle icon="settings" title={t("语言")} subtitle={t("立即生效，不更改歌曲标题、歌词和歌单名称。")}/><label className="language-select"><span>{t("界面语言")}</span><select data-testid="language-select" value={locale} onChange={event => { if (isLocale(event.target.value)) changeLocale(event.target.value) }}>{languages.map(language => <option key={language.value} value={language.value}>{language.label}</option>)}</select></label></section>
      <section className="settings-card wide">
        <SettingTitle icon="cloud" title={t("WebDAV 音乐源")} subtitle={t("支持标准 WebDAV 服务，凭据由 Windows 加密")}/>
        <div className="settings-form">
          <label className="wide"><span>{t("服务器地址")}</span><input value={config.url} onChange={(event) => setConfig({ ...config, url: event.target.value })} placeholder="https://dav.example.com/music/"/></label>
          <label><span>{t("用户名")}</span><input value={config.username} onChange={(event) => setConfig({ ...config, username: event.target.value })}/></label>
          <label><span>{t("密码")}</span><div className="secret-input"><input type={showWebDavPassword ? 'text' : 'password'} value={config.password} onChange={(event) => setConfig({ ...config, password: event.target.value })}/><button aria-label={showWebDavPassword ? t("隐藏 WebDAV 密码") : t("显示 WebDAV 密码")} onClick={() => setShowWebDavPassword((value) => !value)}><Icon name={showWebDavPassword ? 'eyeoff' : 'eye'} size={17}/></button></div></label>
        </div>
        <div className="settings-footer"><p>{t("密码仅保存在当前 Windows 用户的加密存储中。")}</p><button className="primary-button" onClick={saveWebDav} disabled={working}>{working ? <i className="spinner"/> : <Icon name="check" size={16}/>}{t("测试并保存")}</button></div>
      </section>

      <IntegrationSettings notify={notify} onAiChanged={onAiChanged}/>
      <section className="settings-card wide"><SettingTitle icon="settings" title={t("音频兼容解码")} subtitle={t("M4A / MP4 / ALAC 使用内置 FFmpeg；其他格式在浏览器解码失败时自动回退")}/><p className="inline-note">{t("音频只在本机处理，原文件不会修改。首次播放需下载并转换，之后复用无损 FLAC 缓存。独立解码缓存上限 512 MB，清空缓存时一并清理；损坏或受 DRM 保护的文件无法保证播放。")}</p></section>

      <section className="settings-card">
        <SettingTitle icon="download" title={t("智能缓存")} subtitle={t("按最近使用自动清理")}/>
        <div className="cache-number"><b>{bytes(cache.totalSize)}</b><span>{t("已使用 · {0} 首已缓存", [cache.fileCount])}</span></div>
        <div className="cache-meter"><i style={{ width: `${Math.min(100, cache.totalSize / Math.max(cache.maxSize, 1) * 100)}%` }}/></div>
        <label className="range-label"><span>{t("缓存上限")}</span><b>{cacheGb} GB</b></label>
        <input aria-label={t("缓存上限")} className="range-input" type="range" min="0.5" max="100" step="0.5" value={cacheGb} onChange={(event) => setCacheGb(Number(event.target.value))}/>
        <div className="button-row"><button onClick={applyCache}>{t("应用上限")}</button><button className="danger" onClick={() => setConfirmClear(true)}><Icon name="trash" size={15}/>{t("清空缓存")}</button></div>
      </section>

      <section className="settings-card">
        <SettingTitle icon="settings" title={t("键盘快捷键")} subtitle={t("双手留在键盘上，也能轻松聆听")}/>
        <div className="shortcut-list"><div><span>{t("播放 / 暂停")}</span><kbd>Space</kbd></div><div><span>{t("快退 / 快进 5 秒")}</span><kbd>← / →</kbd></div><div><span>{t("搜索音乐")}</span><kbd>Ctrl F</kbd></div><div><span>{t("关闭浮层")}</span><kbd>Esc</kbd></div></div>
      </section>
      <footer className="settings-signature" aria-label="Author">by jinlaoshi</footer>
      {confirmClear && <Modal title={t("清空本地缓存？")} onClose={() => !clearing && setConfirmClear(false)}><p className="dialog-description">{t("已下载的音频将被移除，下次播放时需要重新联网。曲库、歌单与收藏会保留。")}</p><footer className="dialog-footer"><button className="secondary-button" disabled={clearing} onClick={() => setConfirmClear(false)}>{t("取消")}</button><button className="primary-button" disabled={clearing} onClick={() => void clearCache()}>{clearing ? t("清理中…") : t("清空缓存")}</button></footer></Modal>}
    </div>);
}
