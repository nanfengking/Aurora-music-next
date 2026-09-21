import { t } from '../i18n'
import type { WebDavFile } from '../types/webdav';
import { Icon } from '../components/Icon';
const bytes = (value: number) => {
    if (!value)
        return '—';
    const unit = Math.min(4, Math.floor(Math.log(value) / Math.log(1024)));
    return `${(value / 1024 ** unit).toFixed(unit ? 1 : 0)} ${['B', 'KB', 'MB', 'GB', 'TB'][unit]}`;
};
export function WebDavPage({ ready, configured, files, path, canGoBack, loading, syncing, onBack, onRefresh, onOpen, onSettings, onSync }: {
    ready: boolean;
    configured: boolean;
    files: WebDavFile[];
    path: string;
    canGoBack: boolean;
    loading: boolean;
    syncing: boolean;
    onBack: () => void;
    onRefresh: () => void;
    onOpen: (file: WebDavFile) => void;
    onSettings: () => void;
    onSync: () => void;
}) {
    if (ready && !configured) {
        return <div className="soft-empty full"><div className="welcome-orb small"><Icon name="cloud" size={32}/></div><h2>{t("连接你的 WebDAV")}</h2><p>{t("支持 Nextcloud、坚果云、群晖以及其他标准 WebDAV 服务。")}</p><button className="primary-button" onClick={onSettings}>{t("配置服务器")}</button></div>;
    }
    let displayPath = path;
    try {
        displayPath = decodeURIComponent(path);
    }
    catch { /* Some servers return literal percent signs. */ }
    const parts = displayPath.split('/').filter(Boolean);
    return (<section className="browser-panel">
      <div className="browser-toolbar">
        <button onClick={onBack} disabled={!canGoBack} aria-label={t("返回上级目录")}><Icon name="back"/></button>
        <div><b>{path ? parts[parts.length - 1] : t("音乐根目录")}</b></div>
        <button className="sync-library" onClick={onSync} disabled={syncing || loading}>{syncing ? <i className="spinner"/> : <Icon name="download" size={17}/>}<span>{syncing ? t("正在扫描…") : t("同步曲库")}</span></button>
        <button className={loading ? 'rotating' : ''} onClick={onRefresh} disabled={loading} aria-label={t("刷新目录")}><Icon name="refresh"/></button>
      </div>
      {(loading || syncing) && <div className="activity-line"/>}
      <div className="file-table-head"><span>{t("名称")}</span><span>{t("类型")}</span><span>{t("大小")}</span><span /></div>
      <div className="file-list">
        {files.map((file, index) => (<button className="file-item" key={file.href} onClick={() => onOpen(file)} style={{ animationDelay: `${Math.min(index, 12) * 18}ms` }}>
            <span className={`file-icon ${file.isDirectory ? 'folder' : ''}`}><Icon name={file.isDirectory ? 'browse' : 'music'} size={19}/></span>
            <span className="file-title"><b>{file.isDirectory ? file.name : file.name.replace(/\.[^.]+$/, '')}</b><small>{file.isDirectory ? t("文件夹") : file.name.split('.').pop()?.toUpperCase()}</small></span>
            <span>{file.isDirectory ? t("目录") : t("音频")}</span>
            <span>{file.isDirectory ? '—' : bytes(file.size)}</span>
            <i>{file.isDirectory ? <Icon name="chevron" size={16}/> : <Icon name="play" size={15}/>}</i>
          </button>))}
      </div>
      {!loading && !files.length && <div className="soft-empty"><Icon name="browse" size={30}/><h3>{t("当前目录没有音乐")}</h3><p>{t("Aurora 只展示文件夹和支持的音频文件。")}</p></div>}
    </section>);
}
