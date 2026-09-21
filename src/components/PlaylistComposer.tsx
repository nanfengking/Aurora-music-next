import { t, tm, artistLabel } from '../i18n'
import { useState } from 'react';
import type { AiPlaylistResult, Playlist, Song } from '../types/music';
import { Modal } from './Modal';
import { Artwork } from './Artwork';
import { Icon } from './Icon';
export function PlaylistComposer({ songs, aiConfigured, initialSong, onClose, onGenerate, onSave, onSettings }: {
    songs: Song[];
    aiConfigured: boolean;
    initialSong?: Song | null;
    onClose: () => void;
    onGenerate: (prompt: string, useAi: boolean) => Promise<AiPlaylistResult | null>;
    onSave: (name: string, ids: string[], description?: string | null) => Promise<Playlist | null>;
    onSettings: () => void;
}) {
    const [mode, setMode] = useState<'manual' | 'ai'>('manual');
    const [name, setName] = useState('');
    const [prompt, setPrompt] = useState('');
    const [description, setDescription] = useState('');
    const [ids, setIds] = useState<string[]>(initialSong ? [initialSong.id] : []);
    const [busy, setBusy] = useState(false);
    const [generated, setGenerated] = useState(false);
    const [error, setError] = useState('');
    const [provider, setProvider] = useState('');
    const [useAi, setUseAi] = useState(aiConfigured);
    const [diagnostics, setDiagnostics] = useState<AiPlaylistResult['diagnostics']>();
    const [attempts, setAttempts] = useState<string[]>([]);
    const generate = async () => {
        setBusy(true);
        setError('');
        try {
            const result = await onGenerate(prompt, useAi && aiConfigured);
            if (result) {
                setName(result.name);
                setDescription(result.source === 'local' ? tm(result.description) : result.description);
                setIds(result.songIds);
                setGenerated(true);
                setProvider(result.providerName || '');
                setDiagnostics(result.diagnostics);
                setAttempts(result.attempts || []);
            }
            else
                setError(t("未能生成歌单，请检查设置后重试。"));
        }
        catch {
            setError(t("请求失败，请稍后重试。"));
        }
        finally {
            setBusy(false);
        }
    };
    const save = async () => {
        if (!name.trim())
            return;
        setBusy(true);
        try {
            if (await onSave(name.trim(), ids, description || null))
                onClose();
        }
        catch {
            setError(t("保存失败，请重试。"));
        }
        finally {
            setBusy(false);
        }
    };
    const byId = new Map(songs.map(song => [song.id, song]));
    return <Modal title={t("新建歌单")} onClose={onClose} className="composer-dialog">
    <div className="dialog-tabs"><button disabled={busy} className={mode === 'manual' ? 'active' : ''} onClick={() => setMode('manual')}>{t("自己挑选")}</button><button disabled={busy} className={mode === 'ai' ? 'active' : ''} onClick={() => setMode('ai')}><Icon name="sparkles" size={15}/>{t("智能歌单")}</button></div>
    {mode === 'ai' && <div className="composer-ai"><label className="field"><span>{t("此刻想听什么？")}</span><textarea rows={3} maxLength={500} value={prompt} disabled={busy} onChange={event => setPrompt(event.target.value)} placeholder={t("雨夜，想听一些安静的华语歌曲，大约 45 分钟。")}/></label>
      {aiConfigured ? <label className="hybrid-toggle"><input type="checkbox" checked={useAi} disabled={busy} onChange={event => setUseAi(event.target.checked)}/>{t("使用 AI 理解场景并编排歌单")}</label> : <p className="inline-note">{t("当前可用本地推荐；接入 DeepSeek 等 API 可增强场景理解。")}<button className="text-button" onClick={onSettings}>{t("前往设置")}</button></p>}
      <div className="composer-generate"><small>{useAi && aiConfigured ? t("通常 2 次 API 请求，失败按优先级切换可能增加费用。仅发送需求、音乐偏好摘要及最多 240 首候选信息；不上传音频、路径和原始历史。") : t("全曲库本地筛选，不调用 API；支持基础场景、歌手、类型和时长。")}</small><button className="secondary-button" disabled={busy || prompt.trim().length < 2 || !songs.length} onClick={() => void generate()}>{busy ? <i className="spinner"/> : <Icon name="sparkles" size={16}/>}{t("生成预览")}</button></div>
      {!songs.length && <p className="inline-note">{t("请先同步音乐库，再生成智能歌单。")}</p>}
    </div>}
    {(mode === 'manual' || generated) && <>
      <label className="field"><span>{t("歌单名称")}</span><input autoFocus={mode === 'manual'} maxLength={80} value={name} onChange={event => setName(event.target.value)} placeholder={t("给这一刻取个名字")}/></label>
      <label className="field"><span>{t("描述")}<small>{t("选填")}</small></span><input maxLength={240} value={description} onChange={event => setDescription(event.target.value)} placeholder={t("关于这张歌单的一点想法")}/></label>
      {ids.length > 0 && <div className="draft-tracks"><p>{t("{0} 首歌曲 · 可移除不合适的歌曲", [ids.length])}</p>{ids.map(id => { const song = byId.get(id); return song && <div key={id}><Artwork song={song} size="small"/><span><b>{song.title}</b><small>{artistLabel(song.artist)}</small></span><button className="icon-button" aria-label={t("移除 {0}", [song.title])} onClick={() => setIds(ids.filter(value => value !== id))}><Icon name="close" size={17}/></button></div>; })}</div>}
    </>}
    {provider && <p className="inline-note">{t("由 {0} 生成 · 保存前可编辑", [provider === '本地行为模型' ? t(provider) : provider])}</p>}
    {diagnostics && <div className="playlist-diagnostics"><p>{t("已检索全部 {0} 首 · 筛选后 {1} 首 · 编排候选 {2} 首", [diagnostics.librarySize, diagnostics.eligibleSize, diagnostics.candidateSize])}</p><p>{diagnostics.estimatedTracks ? t("生成时估计时长 {0} 分钟", [Math.round(diagnostics.durationSeconds / 60)]) : t("生成时总时长 {0} 分钟", [Math.round(diagnostics.durationSeconds / 60)])}{diagnostics.targetSeconds ? t(" / 目标 {0} 分钟", [Math.round(diagnostics.targetSeconds / 60)]) : ''}{t("；手动移除歌曲后时长会变化。")}</p>{diagnostics.warnings.map(warning => <p key={warning}>{tm(warning)}</p>)}{attempts.length > 0 && <details><summary>{t("查看接口切换记录")}</summary>{attempts.map((attempt, index) => <p key={index}>{tm(attempt)}</p>)}</details>}</div>}
    {error && <p className="form-error" role="alert">{tm(error)}</p>}
    <footer className="dialog-footer"><button className="secondary-button" onClick={onClose}>{t("取消")}</button><button className="primary-button" onClick={() => void save()} disabled={busy || !name.trim() || (mode === 'ai' && (!generated || !ids.length))}>{busy ? t("处理中…") : t("保存歌单")}</button></footer>
  </Modal>;
}
