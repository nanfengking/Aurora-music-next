import { t } from '../i18n'
import type { Playlist, Song } from '../types/music';
import type { PlayState } from '../services/audioEngine';
import { Artwork } from '../components/Artwork';
import { Icon } from '../components/Icon';
import { SongTable } from '../components/SongTable';
export function playlistIds(playlist?: Playlist): string[] {
    try {
        const value: unknown = JSON.parse(playlist?.songIds || '[]');
        return Array.isArray(value) ? value.filter((id): id is string => typeof id === 'string') : [];
    }
    catch {
        return [];
    }
}
export function PlaylistsPage({ playlists, songs, selectedId, current, state, onSelect, onCreate, onDelete, onPlay, onFavorite, onAdd, onRemove }: {
    playlists: Playlist[];
    songs: Song[];
    selectedId: string | null;
    current: Song | null;
    state: PlayState;
    onSelect: (id: string | null) => void;
    onCreate: () => void;
    onDelete: (id: string) => void;
    onPlay: (song: Song, queue?: Song[]) => void;
    onFavorite: (song: Song) => void;
    onAdd: (song: Song) => void;
    onRemove: (playlistId: string, song: Song) => void;
}) {
    const byId = new Map(songs.map(song => [song.id, song]));
    const selected = playlists.find(playlist => playlist.id === selectedId);
    const tracks = playlistIds(selected).map(id => byId.get(id)).filter((song): song is Song => Boolean(song));
    return <div className="playlists-page">{selected ? <>
    <button className="text-button back-link" onClick={() => onSelect(null)}><Icon name="back" size={16}/>{t("所有歌单")}</button>
    <div className="collection-detail"><Artwork song={tracks.find(s => s.coverPath) || tracks[0]} size="large"/><div><span className="subtle-label">{t("我的歌单")}</span><h2>{selected.name}</h2><p>{selected.description || t("{0} 首歌曲", [tracks.length])}</p><div className="button-row"><button className="primary-button" disabled={!tracks.length} onClick={() => onPlay(tracks[0], tracks)}><Icon name="play" size={17}/>{t("播放")}</button><button className="icon-button danger" onClick={() => onDelete(selected.id)} aria-label={t("删除歌单")} title={t("删除歌单")}><Icon name="trash" size={18}/></button></div></div></div>
    <SongTable songs={tracks} current={current} state={state} onPlay={onPlay} onFavorite={onFavorite} onAdd={onAdd} onRemove={song => onRemove(selected.id, song)} emptyText={t("为这个歌单添加一些音乐")}/>
  </> : <>
    <div className="collection-toolbar"><span>{t("{0} 个歌单", [playlists.length])}</span><button className="text-button" onClick={onCreate}><Icon name="plus" size={16}/>{t("新建歌单")}</button></div>
    <div className="collection-grid">{playlists.map(playlist => <article className="music-tile" key={playlist.id}><button className="cover-button" onClick={() => onSelect(playlist.id)} aria-label={t("打开歌单 {0}", [playlist.name])}><Artwork song={playlistIds(playlist).map(id => byId.get(id)).find(song => song?.coverPath) || byId.get(playlistIds(playlist)[0])} size="large"/><span className="cover-play"><Icon name="chevron"/></span></button><button className="tile-title" onClick={() => onSelect(playlist.id)}>{playlist.name}</button><p>{t("{0} 首歌曲", [playlistIds(playlist).length])}</p></article>)}</div>
    {!playlists.length && <div className="soft-empty full"><Icon name="playlist" size={36}/><h2>{t("每一种心情，都可以是一张歌单。")}</h2><p>{t("亲手挑选，或让 DeepSeek 帮你寻找适合此刻的音乐。")}</p><button className="primary-button" onClick={onCreate}><Icon name="plus" size={16}/>{t("创建歌单")}</button></div>}
  </>}</div>;
}
