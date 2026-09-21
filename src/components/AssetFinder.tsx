import { t, tm } from '../i18n'
import { useState } from 'react';
import type { Song } from '../types/music';
import type { AssetKind, AssetCandidate } from '../types/providers';
import { Modal } from './Modal';
import { localAssetUrl } from './Artwork';
export function AssetFinder({ song, onClose, onApplied }: {
    song: Song;
    onClose: () => void;
    onApplied: () => void;
}) {
    const [kind, setKind] = useState<AssetKind>('lyrics');
    const [query, setQuery] = useState({ title: song.title, artist: song.artist === '未知艺术家' ? '' : song.artist, album: song.album });
    const [results, setResults] = useState<AssetCandidate[]>([]);
    const [selected, setSelected] = useState<string>('');
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState('');
    const search = async () => { setBusy(true); setMessage(''); setResults([]); setSelected(''); try {
        const result = await window.electronAPI.searchAssets(song.id, kind, query);
        if (result.data) {
            setResults(result.data);
            if (!result.data.length)
                setMessage(t("没有找到匹配结果，可调整歌名、艺术家或专辑后重试。"));
        }
        else
            setMessage(result.error || t("检索失败"));
    }
    catch {
        setMessage(t("检索失败，请稍后重试。"));
    }
    finally {
        setBusy(false);
    } };
    const apply = async () => { setBusy(true); try {
        const result = await window.electronAPI.applyAsset(song.id, kind, selected);
        if (result.success) {
            onApplied();
            onClose();
        }
        else
            setMessage(result.error || t("保存失败"));
    }
    catch {
        setMessage(t("保存失败"));
    }
    finally {
        setBusy(false);
    } };
    return <Modal title={t("查找音乐资源")} className="asset-finder" onClose={onClose}><div className="dialog-tabs">{([['lyrics', t("歌词")], ['cover', t("封面")], ['background', t("背景")]] as const).map(([value, label]) => <button key={value} className={kind === value ? 'active' : ''} disabled={busy} onClick={() => { setKind(value); setResults([]); setSelected(''); setMessage(''); }}>{label}</button>)}</div><div className="settings-form">{([['title', t("歌名")], ['artist', t("艺术家")], ['album', t("专辑")]] as const).map(([field, label]) => <label key={field}><span>{label}</span><input value={query[field]} onChange={event => setQuery({ ...query, [field]: event.target.value })}/></label>)}</div><div className="settings-footer"><p>{t("只发送上述信息。请确认匹配版本和资源使用权限。")}</p><button className="secondary-button" disabled={busy} onClick={() => void search()}>{busy ? t("正在处理…") : t("搜索资源")}</button></div>
  <div className="asset-results">{results.map(item => <button key={item.id} className={selected === item.id ? 'selected' : ''} onClick={() => setSelected(item.id)} aria-pressed={selected === item.id}>{item.imagePath && <img src={localAssetUrl(item.imagePath)} alt={t("{0} 预览", [item.title])}/>}<b>{item.title}</b><small>{item.artist} · {t(item.source)}{item.synced ? t(" · 同步歌词") : ''}</small>{item.text && <pre>{item.text}</pre>}</button>)}</div>{message && <p className="inline-note" role="status">{tm(message)}</p>}<footer className="dialog-footer"><p className="inline-note">{t("应用后会替换该歌曲已有的{0}。", [kind === 'lyrics' ? t("歌词") : kind === 'cover' ? t("封面") : t("背景")])}</p><button className="primary-button" disabled={busy || !selected} onClick={() => void apply()}>{t("应用选中资源")}</button></footer></Modal>;
}
