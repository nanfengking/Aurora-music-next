import { t, artistLabel } from '../i18n'
import { useEffect, useState } from 'react';
import type { Song } from '../types/music';
import type { PlayState } from '../services/audioEngine';
import { Artwork } from './Artwork';
import { Icon } from './Icon';
import { formatTime } from './PlayerBar';
interface SongTableProps {
    songs: Song[];
    current: Song | null;
    state: PlayState;
    onPlay: (song: Song, queue?: Song[]) => void;
    onFavorite: (song: Song) => void;
    onAdd: (song: Song) => void;
    onRemove?: (song: Song) => void;
    emptyText?: string;
    compact?: boolean;
}
export function SongTable({ songs, current, state, onPlay, onFavorite, onAdd, onRemove, emptyText = t("这里还没有歌曲"), compact = false }: SongTableProps) {
    const [page, setPage] = useState(0);
    const pageSize = 100;
    useEffect(() => setPage(0), [songs]);
    if (!songs.length)
        return <div className="soft-empty"><Icon name="music" size={30}/><h3>{emptyText}</h3><p>{t("在 WebDAV 中同步音乐，或尝试其他搜索词。")}</p></div>;
    const tracks = compact ? songs : songs.slice(page * pageSize, (page + 1) * pageSize);
    return <div className={`song-table ${compact ? 'compact' : ''}`} role="table" aria-label={t("歌曲列表")}>
    {!compact && <div className="song-table-head" role="row"><span role="columnheader">#</span><span role="columnheader">{t("歌曲")}</span><span role="columnheader">{t("专辑")}</span><span role="columnheader">{t("时长")}</span><span role="columnheader" className="sr-only">{t("操作")}</span></div>}
    <div className="song-rows">{tracks.map((song, index) => {
            const active = current?.id === song.id;
            return <div className={`song-row ${active ? 'active' : ''}`} role="row" key={song.id}>
        <button className="row-index" onClick={() => onPlay(song, songs)} aria-label={`${active && state.isPlaying ? t("暂停") : t("播放")} ${song.title}`}>
          <span>{page * pageSize + index + 1}</span><Icon name={active && state.isPlaying ? 'pause' : 'play'} size={14}/></button>
        <button className="song-identity" onClick={() => onPlay(song, songs)}><Artwork song={song} size="small"/><span><b>{song.title}</b><small>{artistLabel(song.artist)}</small></span></button>
        {!compact && <span className="song-album" role="cell">{song.album || '—'}</span>}
        {!compact && <span className="song-duration" role="cell">{song.duration > 0 ? formatTime(song.duration) : '—'}</span>}
        <div className="song-actions"><button className={song.isFavorite ? 'favorite' : ''} onClick={() => onFavorite(song)} aria-label={`${song.isFavorite ? t("取消喜欢") : t("喜欢")} ${song.title}`} title={song.isFavorite ? t("取消喜欢") : t("喜欢")}><Icon name="heart" size={16} filled={Boolean(song.isFavorite)}/></button>
          <button onClick={() => onAdd(song)} aria-label={t("添加 {0} 到歌单", [song.title])} title={t("添加到歌单")}><Icon name="plus" size={17}/></button>
          {onRemove && <button onClick={() => onRemove(song)} aria-label={t("从歌单移除 {0}", [song.title])} title={t("从歌单移除")}><Icon name="close" size={16}/></button>}</div>
      </div>;
        })}</div>
    {!compact && songs.length > pageSize && <div className="pagination"><span>{t("{0}–{1} / {2} 首", [page * pageSize + 1, Math.min((page + 1) * pageSize, songs.length), songs.length])}</span><button className="icon-button" disabled={page === 0} aria-label={t("上一页")} onClick={() => setPage(page - 1)}><Icon name="back"/></button><button className="icon-button" disabled={(page + 1) * pageSize >= songs.length} aria-label={t("下一页")} onClick={() => setPage(page + 1)}><Icon name="chevron"/></button></div>}
  </div>;
}
