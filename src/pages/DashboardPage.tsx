import { t, tm, artistLabel } from '../i18n'
import { useMemo } from 'react';
import type { RecommendationFeed, Song } from '../types/music';
import type { PlayState } from '../services/audioEngine';
import { Artwork } from '../components/Artwork';
import { Icon } from '../components/Icon';
import { SongTable } from '../components/SongTable';
export function DashboardPage({ songs, recommendations, feed, current, state, configured, onNavigate, onPlay, onFavorite, onAdd }: {
    songs: Song[];
    recommendations: Song[];
    feed: RecommendationFeed | null;
    current: Song | null;
    state: PlayState;
    configured: boolean;
    onNavigate: (page: 'browse' | 'library' | 'settings' | 'recent') => void;
    onPlay: (song: Song, queue?: Song[]) => void;
    onFavorite: (song: Song) => void;
    onAdd: (song: Song) => void;
}) {
    const picks = (recommendations.length ? recommendations : songs).slice(0, 5);
    const recent = useMemo(() => songs.filter((song) => song.lastPlayedAt).sort((a, b) => Number(b.lastPlayedAt) - Number(a.lastPlayedAt)).slice(0, 6), [songs]);
    const albums = useMemo(() => {
        const groups = new Map<string, Song[]>();
        for (const song of songs) {
            if (!song.album)
                continue;
            const key = `${song.artist}\n${song.album}`;
            const group = groups.get(key) || [];
            group.push(song);
            groups.set(key, group);
        }
        return [...groups.values()].sort((a, b) => b.reduce((sum, s) => sum + (s.playCount || 0), 0) - a.reduce((sum, s) => sum + (s.playCount || 0), 0)).slice(0, 5);
    }, [songs]);
    if (!songs.length)
        return <section className="library-onboarding">
    <div className="onboarding-record"><div /><Icon name="music" size={42}/></div>
    <h2>{t("把你的音乐，带到这里。")}</h2><p>{configured ? t("音乐源已就绪。同步曲库，开始第一首。") : t("连接你的 WebDAV 音乐库，收藏与聆听从这里开始。")}</p>
    <button className="primary-button" onClick={() => onNavigate(configured ? 'browse' : 'settings')}><Icon name={configured ? 'refresh' : 'plus'} size={17}/>{configured ? t("浏览并同步") : t("连接音乐库")}</button>
    <span className="onboarding-note">{t("你的收藏，你的音乐空间。")}</span>
  </section>;
    return <div className="listen-page">
    <section><div className="section-heading"><div><h2>{t("为你精选")}</h2><p>{t("熟悉的旋律，也有久违的惊喜。")}</p></div><button className="text-button" onClick={() => picks[0] && onPlay(picks[0], recommendations.length ? recommendations : songs)}><Icon name="play" size={14}/>{t("播放全部")}</button></div>
      <div className="cover-shelf">{picks.map((song) => <article className="music-tile" key={song.id}>
        <button className="cover-button" aria-label={t("播放 {0}", [song.title])} onClick={() => onPlay(song, recommendations.length ? recommendations : songs)}><Artwork song={song} size="large"/><span className="cover-play"><Icon name={current?.id === song.id && state.isPlaying ? 'pause' : 'play'}/></span></button>
        <button className="tile-title" onClick={() => onPlay(song, recommendations.length ? recommendations : songs)}>{song.title}</button><p>{artistLabel(song.artist)}</p>
        {feed?.reasons[song.id] && <p className="recommendation-reason" title={tm(feed.reasons[song.id])}>{tm(feed.reasons[song.id])}</p>}
      </article>)}</div>
    </section>
    {feed && <section className="listening-taste" aria-label={t("聆听偏好")}>
      <div className="section-heading"><div><h2>{t("你的聆听偏好")}</h2><p>{tm(feed.profile.summary)}</p></div><span className="local-label">{t("只在本机学习")}</span></div>
      {feed.profile.artists.length > 0 && <p className="taste-artists">{t("最近更靠近 {0}", [feed.profile.artists.slice(0, 3).map(a => a.name).join(' · ')])}</p>}
      <div className="taste-types">{feed.profile.suggestedGenres.map(genre => <button key={t(genre.name)} title={tm(genre.reason)} onClick={() => { const byId = new Map(songs.map(s => [s.id, s])); const queue = genre.songIds.map(id => byId.get(id)).filter((s): s is Song => Boolean(s)); if (queue[0])
            onPlay(queue[0], queue); }}><Icon name="play" size={13}/><span>{t(genre.name)}</span><small>{genre.reason.startsWith('换个') ? t("探索") : t("偏好")}</small></button>)}</div>
      <details className="taste-details"><summary>{t("依据与数据完整度")}</summary><p>{t("近 180 天：{0} 次有效聆听，其中完整播放 {1} 次、切歌 {2} 次。收藏加分，早早跳过减分，旧行为逐渐降低权重。失败的播放不计入喜好。", [feed.profile.historySessions, feed.profile.completedSessions, feed.profile.skippedSessions])}</p><p>{t("曲库 {0} 首中，{1} 首有类型标签。没有标签时不臆测风格；“探索”仅表示可尝试的曲库类型。", [feed.profile.librarySize, feed.profile.genreCoverage])}</p>{feed.profile.genres.map(g => <p key={g.name}>{t("{0}：{1} 首歌曲提供正向依据 · 曲库有 {2} 首", [t(g.name), g.evidenceTracks, g.availableTracks])}</p>)}</details>
    </section>}
    {recent.length > 0 && <section><div className="section-heading"><h2>{t("再听一次")}</h2><button className="text-button" onClick={() => onNavigate('recent')}>{t("最近播放")}<Icon name="chevron" size={14}/></button></div>
      <SongTable songs={recent} current={current} state={state} onPlay={onPlay} onFavorite={onFavorite} onAdd={onAdd} compact/>
    </section>}
    {albums.length > 0 && <section><div className="section-heading"><h2>{t("留一点时间，听张专辑")}</h2><button className="text-button" onClick={() => onNavigate('library')}>{t("音乐库")}<Icon name="chevron" size={14}/></button></div>
      <div className="cover-shelf">{albums.map((tracks) => <article className="music-tile" key={`${tracks[0].artist}-${tracks[0].album}`}><button className="cover-button" aria-label={t("播放专辑 {0}", [tracks[0].album])} onClick={() => onPlay(tracks[0], tracks)}><Artwork song={tracks.find(s => s.coverPath) || tracks[0]} size="large"/><span className="cover-play"><Icon name="play"/></span></button><h3>{tracks[0].album}</h3><p>{artistLabel(tracks[0].artist)}</p></article>)}</div>
    </section>}
  </div>;
}
