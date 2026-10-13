import { t, tm, artistLabel } from '../i18n'
import { useState } from 'react';
import type { Song } from '../types/music';
import { usePlayback } from '../services/usePlayback';
import { usePlayerPreferences } from '../stores/playerPreferences';
import { Artwork, localAssetUrl } from './Artwork';
import { AssetFinder } from './AssetFinder';
import { Modal } from './Modal';
import { Timeline, Transport } from './PlayerBar';
import { Icon } from './Icon';
import { Lyrics } from './Lyrics';
import { SongDetails } from './SongDetails';
export function NowPlaying({ song, songs, onClose, onFavorite, onResourcesChanged }: {
    song: Song;
    songs: Song[];
    onClose: () => void;
    onFavorite: (song: Song) => void;
    onResourcesChanged: () => void;
}) {
    const [finding, setFinding] = useState(false);
    const [details, setDetails] = useState(false);
    const state = usePlayback();
    const backdrop = usePlayerPreferences(store => store.preferences.showBackdrop);
    return <Modal title={t("正在播放")} onClose={onClose} className={`now-playing-dialog ${song.customLyrics ? 'with-lyrics' : ''}`}>
    {backdrop && song.coverPath && <div className="full-backdrop"><img src={localAssetUrl(song.coverPath)} alt=""/></div>}
    <div className="listening-stage"><section className="listening-record"><Artwork song={song} size="large"/>
      <div className="listening-title"><div><h1>{song.title}</h1><p>{artistLabel(song.artist)}{song.album && ` · ${song.album}`}</p></div><button className={`icon-button ${song.isFavorite ? 'favorite' : ''}`} onClick={() => onFavorite(song)} aria-label={song.isFavorite ? t("取消喜欢") : t("喜欢")}><Icon name="heart" filled={Boolean(song.isFavorite)}/></button></div>
      <Timeline song={song} state={state}/><Transport song={song} songs={songs} state={state}/>
      <div className="listening-actions"><button className="text-button" onClick={() => setFinding(true)}>{t("查找歌词与封面")}</button><button className="text-button" onClick={() => setDetails(true)}><Icon name="info" size={15}/>{t("歌曲详情")}</button></div>
      {state.error && <p className="form-error" role="alert">{tm(state.error)}</p>}
    </section>
    {song.customLyrics && <Lyrics text={song.customLyrics} currentTime={state.currentTime}/>}
    </div>
    {finding && <AssetFinder song={song} onClose={() => setFinding(false)} onApplied={onResourcesChanged}/>}
    {details && <SongDetails song={song} onClose={() => setDetails(false)}/>}
  </Modal>;
}
