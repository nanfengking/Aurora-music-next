import { t, getLocale, artistLabel } from '../i18n'
import { useEffect, useMemo, useState } from 'react';
import type { Song } from '../types/music';
import type { PlayState } from '../services/audioEngine';
import { Artwork } from '../components/Artwork';
import { Icon } from '../components/Icon';
import { SongTable } from '../components/SongTable';
export type CollectionTab = 'songs' | 'albums' | 'artists';
export function CollectionPage({ songs, current, state, tab, onPlay, onFavorite, onAdd }: {
    songs: Song[];
    current: Song | null;
    state: PlayState;
    tab: CollectionTab;
    onPlay: (song: Song, queue?: Song[]) => void;
    onFavorite: (song: Song) => void;
    onAdd: (song: Song) => void;
}) {
    const [detail, setDetail] = useState<string | null>(null);
    const [sort, setSort] = useState('title');
    useEffect(() => setDetail(null), [tab]);
    const sorted = useMemo(() => [...songs].sort((a, b) => sort === 'recent' ? Number(b.lastPlayedAt || 0) - Number(a.lastPlayedAt || 0) : (sort === 'artist' ? a.artist : a.title).localeCompare(sort === 'artist' ? b.artist : b.title, getLocale())), [songs, sort, getLocale()]);
    const groups = useMemo(() => {
        const result = new Map<string, {
            key: string;
            name: string;
            tracks: Song[];
        }>();
        for (const song of sorted) {
            const name = tab === 'artists' ? artistLabel(song.artist) : song.album || t("未分类专辑");
            const key = tab === 'artists' ? name : `${song.artist}\n${name}`;
            if (!result.has(key))
                result.set(key, { key, name, tracks: [] });
            result.get(key)!.tracks.push(song);
        }
        return [...result.values()];
    }, [sorted, tab, getLocale()]);
    const selected = groups.find(group => group.key === detail);
    const table = (tracks: Song[]) => <SongTable songs={tracks} current={current} state={state} onPlay={onPlay} onFavorite={onFavorite} onAdd={onAdd}/>;
    return <div className="collection-page">
    {selected ? <>
      <button className="text-button back-link" onClick={() => setDetail(null)}><Icon name="back" size={16}/>{tab === 'artists' ? t("所有艺术家") : t("所有专辑")}</button>
      <div className="collection-detail"><Artwork song={selected.tracks.find(s => s.coverPath) || selected.tracks[0]} size="large"/><div><span className="subtle-label">{tab === 'artists' ? t("艺术家") : t("专辑")}</span><h2>{selected.name}</h2><p>{tab === 'albums' && artistLabel(selected.tracks[0]?.artist)}{tab === 'albums' && ' · '}{t("{0} 首歌曲", [selected.tracks.length])}</p><button className="primary-button" onClick={() => onPlay(selected.tracks[0], selected.tracks)}><Icon name="play" size={17}/>{t("播放")}</button></div></div>
      {table(selected.tracks)}
    </> : <>
      <div className="collection-toolbar"><span>{tab === 'songs' ? t("{0} 首歌曲", [songs.length]) : tab === 'albums' ? t("{0} 张专辑", [groups.length]) : t("{0} 位艺术家", [groups.length])}</span><div>{tab === 'songs' && <button className="text-button" disabled={!sorted.length} onClick={() => sorted[0] && onPlay(sorted[0], sorted)}><Icon name="play" size={14}/>{t("播放全部")}</button>}<label className="sort-select"><span className="sr-only">{t("排序")}</span><select value={sort} onChange={event => setSort(event.target.value)}><option value="title">{t("按名称")}</option><option value="artist">{t("按艺术家")}</option><option value="recent">{t("最近播放")}</option></select></label></div></div>
      {tab === 'songs' ? table(sorted) : <div className={`collection-grid ${tab === 'artists' ? 'artist-grid' : ''}`}>{groups.map(group => <article className="music-tile" key={group.key}><button className="cover-button" onClick={() => setDetail(group.key)} aria-label={t("查看 {0}", [group.name])}><Artwork song={group.tracks.find(s => s.coverPath) || group.tracks[0]} size="large"/><span className="cover-play"><Icon name="chevron"/></span></button><button className="tile-title" onClick={() => setDetail(group.key)}>{group.name}</button><p>{tab === 'albums' ? artistLabel(group.tracks[0]?.artist) : t("{0} 首歌曲", [group.tracks.length])}</p></article>)}</div>}
      {tab !== 'songs' && !groups.length && <div className="soft-empty"><Icon name={tab === 'albums' ? 'album' : 'artist'} size={30}/><h3>{t("曲库还是空的")}</h3><p>{t("同步音乐后，它们会整理在这里。")}</p></div>}
    </>}
  </div>;
}
