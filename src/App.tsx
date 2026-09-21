import { t, tm, artistLabel } from './i18n'
import { useLanguageStore } from './stores/languageStore'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { AiPlaylistResult, DeepSeekConfigStatus, Playlist, Song, RecommendationFeed } from './types/music';
import type { WebDavConfig, WebDavFile } from './types/webdav';
import { audioEngine, type PlayState } from './services/audioEngine';
import { usePlayerStore } from './stores/playerStore';
import { Icon, type IconName } from './components/Icon';
import { Artwork } from './components/Artwork';
import { SongTable } from './components/SongTable';
import { PlayerBar } from './components/PlayerBar';
import { Modal } from './components/Modal';
import { PlaylistComposer } from './components/PlaylistComposer';
import { NowPlaying } from './components/NowPlaying';
import { DashboardPage } from './pages/DashboardPage';
import { WebDavPage } from './pages/WebDavPage';
import { CollectionPage } from './pages/CollectionPage';
import { PlaylistsPage } from './pages/PlaylistsPage';
import { PreferencesPage } from './pages/PreferencesPage';
import './styles/app.css';
import { useThemeStore, applyTheme } from './stores/themeStore';
import './styles/light.css';
type Page = 'home' | 'browse' | 'library' | 'albums' | 'artists' | 'recent' | 'playlists' | 'favorites' | 'settings';
const EMPTY_CONFIG: WebDavConfig = { url: '', username: '', password: '' };
const EMPTY_AI: DeepSeekConfigStatus = { configured: false, baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat' };
const IDLE: PlayState = { isPlaying: false, currentTime: 0, duration: 0, progress: 0, isLoading: false, error: null };
const PAGE_INFO: Record<Page, {
    title: string;
}> = {
    albums: { title: '专辑' },
    artists: { title: '艺术家' },
    recent: { title: '最近播放' },
    home: { title: '现在就听' },
    browse: { title: 'WebDAV' },
    library: { title: '歌曲' },
    playlists: { title: '歌单' },
    favorites: { title: '喜欢的音乐' },
    settings: { title: '设置' },
};
const NAV: Array<{
    page: Page;
    label: string;
    icon: IconName;
}> = [
    { page: 'library', label: '歌曲', icon: 'music' },
    { page: 'albums', label: '专辑', icon: 'album' },
    { page: 'artists', label: '艺术家', icon: 'artist' },
    { page: 'favorites', label: '喜欢的音乐', icon: 'heart' },
];
function cleanFiles(items: WebDavFile[]): WebDavFile[] {
    return items.filter((file) => file.isDirectory || file.isMusic)
        .sort((a, b) => Number(b.isDirectory) - Number(a.isDirectory) || a.name.localeCompare(b.name, 'zh-CN'));
}
function host(url: string): string {
    try {
        return new URL(url).host;
    }
    catch {
        return t("地址待验证");
    }
}
function createPlaylist(name: string, songIds: string[] = [], description: string | null = null): Playlist {
    const timestamp = new Date().toISOString();
    const random = typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    return { id: `pl-${random}`, name, description, songIds: JSON.stringify(songIds), createdAt: timestamp, updatedAt: timestamp };
}
export default function App() {
    const locale = useLanguageStore(store => store.locale);
    useEffect(() => { document.documentElement.lang = locale }, [locale]);
    const { theme, setTheme } = useThemeStore();
    useEffect(() => {
        const apply = () => { applyTheme(theme); void window.electronAPI.setTheme(theme); };
        apply();
        const media = matchMedia('(prefers-color-scheme: dark)');
        media.addEventListener('change', apply);
        return () => media.removeEventListener('change', apply);
    }, [theme]);
    const [page, setPage] = useState<Page>('home');
    const [config, setConfig] = useState<WebDavConfig>(EMPTY_CONFIG);
    const [aiStatus, setAiStatus] = useState<DeepSeekConfigStatus>(EMPTY_AI);
    const [ready, setReady] = useState(false);
    const [files, setFiles] = useState<WebDavFile[]>([]);
    const [remotePath, setRemotePath] = useState('');
    const [pathHistory, setPathHistory] = useState<string[]>([]);
    const [songs, setSongs] = useState<Song[]>([]);
    const [feed, setFeed] = useState<RecommendationFeed | null>(null);
    const recommendations = feed?.songs || [];
    const [playlists, setPlaylists] = useState<Playlist[]>([]);
    const [selectedPlaylistId, setSelectedPlaylistId] = useState<string | null>(null);
    const [query, setQuery] = useState('');
    const [loading, setLoading] = useState(false);
    const [syncing, setSyncing] = useState(false);
    const [composerOpen, setComposerOpen] = useState(false);
    const [composerSong, setComposerSong] = useState<Song | null>(null);
    const [deleteId, setDeleteId] = useState<string | null>(null);
    const [deleteBusy, setDeleteBusy] = useState(false);
    const [message, setMessage] = useState<string | null>(null);
    const [playerState, setPlayerState] = useState<PlayState>(IDLE);
    const [current, setCurrent] = useState<Song | null>(null);
    const [songToAdd, setSongToAdd] = useState<Song | null>(null);
    const [queueOpen, setQueueOpen] = useState(false);
    const [fullPlayerOpen, setFullPlayerOpen] = useState(false);
    const directoryCache = useRef(new Map<string, WebDavFile[]>());
    const notificationTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
    const mainRef = useRef<HTMLElement>(null);
    const searchRef = useRef<HTMLInputElement>(null);
    const queueIds = usePlayerStore((store) => store.queue);
    const queueIndex = usePlayerStore((store) => store.queueIndex);
    const navigate = useCallback((target: Page) => {
        setPage(target);
        setQuery('');
        mainRef.current?.scrollTo({ top: 0 });
    }, []);
    useEffect(() => () => { if (notificationTimer.current)
        clearTimeout(notificationTimer.current); }, []);
    const notify = useCallback((text: string) => {
        setMessage(text);
        if (notificationTimer.current)
            clearTimeout(notificationTimer.current);
        notificationTimer.current = setTimeout(() => setMessage(null), 4500);
    }, []);
    const loadLibrary = useCallback(async () => {
        const [songResponse, recommendationResponse] = await Promise.all([
            window.electronAPI.getAllSongs(),
            window.electronAPI.getRecommendationFeed(),
        ]);
        if (songResponse.data)
            setSongs(songResponse.data);
        if (recommendationResponse.data)
            setFeed(recommendationResponse.data);
        return songResponse.data || [];
    }, []);
    useEffect(() => {
        let timer: ReturnType<typeof setTimeout> | undefined;
        const refresh = () => { clearTimeout(timer); timer = setTimeout(() => void loadLibrary(), 200); };
        window.addEventListener('aurora:listening-change', refresh);
        return () => { clearTimeout(timer); window.removeEventListener('aurora:listening-change', refresh); };
    }, [loadLibrary]);
    const loadPlaylists = useCallback(async () => {
        const response = await window.electronAPI.getPlaylists();
        if (response.data) {
            setPlaylists(response.data);
        }
    }, []);
    const listRemote = useCallback(async (target: string, activeConfig: WebDavConfig, force = false) => {
        if (!activeConfig.url)
            return;
        const cached = directoryCache.current.get(target);
        if (cached && !force) {
            setFiles(cached);
            setRemotePath(target);
            return;
        }
        setLoading(true);
        const response = await window.electronAPI.webdav.listFiles(activeConfig, target);
        if (response.data) {
            const cleaned = cleanFiles(response.data);
            directoryCache.current.set(target, cleaned);
            setFiles(cleaned);
            setRemotePath(target);
        }
        else
            notify(response.error || t("WebDAV 目录读取失败"));
        setLoading(false);
    }, [notify]);
    useEffect(() => {
        let active = true;
        Promise.all([
            window.electronAPI.webdav.loadConfig(),
            window.electronAPI.getAllSongs(),
            window.electronAPI.getRecommendationFeed(),
            window.electronAPI.getPlaylists(),
            window.electronAPI.getDeepSeekConfig(),
        ]).then(([configResponse, songResponse, recommendationResponse, playlistResponse, aiResponse]) => {
            if (!active)
                return;
            if (configResponse.data) {
                setConfig(configResponse.data);
                if (configResponse.data.url)
                    void listRemote('', configResponse.data);
            }
            if (songResponse.data)
                setSongs(songResponse.data);
            if (recommendationResponse.data)
                setFeed(recommendationResponse.data);
            if (playlistResponse.data) {
                setPlaylists(playlistResponse.data);
            }
            if (aiResponse.data)
                setAiStatus(aiResponse.data);
            setReady(true);
        }).catch(() => { if (active) {
            setReady(true);
            notify(t("音乐库加载失败，请重新启动应用。"));
        } });
        return () => { active = false; };
    }, [listRemote, notify]);
    useEffect(() => audioEngine.onStateChange((state) => {
        setPlayerState(state);
        setCurrent(audioEngine.getCurrentSong());
    }), []);
    useEffect(() => {
        if (current?.id && !playerState.isLoading)
            void loadLibrary();
    }, [current?.id, playerState.isLoading, loadLibrary]);
    useEffect(() => {
        const keyboard = (event: KeyboardEvent) => {
            if (document.querySelector('dialog[open]'))
                return;
            if (event.code === 'Escape') {
                setQueueOpen(false);
                return;
            }
            if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'f') {
                event.preventDefault();
                searchRef.current?.focus();
                return;
            }
            if (event.target instanceof HTMLElement && event.target.closest('input, textarea, select, button, [contenteditable]'))
                return;
            if (event.code === 'Space' && audioEngine.getCurrentSong()) {
                event.preventDefault();
                audioEngine.togglePlay();
            }
            if (event.code === 'ArrowRight')
                audioEngine.seek(audioEngine.getState().currentTime + 5);
            if (event.code === 'ArrowLeft')
                audioEngine.seek(audioEngine.getState().currentTime - 5);
        };
        window.addEventListener('keydown', keyboard);
        return () => window.removeEventListener('keydown', keyboard);
    }, []);
    const play = useCallback(async (song: Song, queue = songs) => {
        if (audioEngine.getCurrentSong()?.id === song.id) {
            audioEngine.togglePlay();
            return;
        }
        setCurrent(song);
        await audioEngine.play(song, queue);
        await loadLibrary();
    }, [songs, loadLibrary]);
    const toggleFavorite = useCallback(async (song: Song) => {
        const favorite = !Boolean(song.isFavorite);
        const response = await window.electronAPI.setFavorite(song.id, favorite);
        if (!response.success)
            return notify(response.error || t("收藏状态更新失败"));
        const update = (item: Song) => item.id === song.id ? { ...item, isFavorite: favorite ? 1 : 0 } : item;
        setSongs((items) => items.map(update));
        setFeed(value => value ? { ...value, songs: value.songs.map(update) } : value);
        void loadLibrary();
        setCurrent((item) => item ? update(item) : item);
        notify(favorite ? t("已添加到喜欢的音乐") : t("已取消喜欢"));
    }, [notify, loadLibrary]);
    const openRemote = useCallback(async (file: WebDavFile) => {
        if (file.isDirectory) {
            setPathHistory((items) => [...items, remotePath]);
            await listRemote(file.href, config);
            return;
        }
        setLoading(true);
        const registered = await window.electronAPI.webdav.registerFile(file);
        if (!registered.data) {
            notify(registered.error || t("无法将歌曲加入音乐库"));
            setLoading(false);
            return;
        }
        const library = await loadLibrary();
        await play(registered.data, library.length ? library : [registered.data]);
        setLoading(false);
    }, [config, listRemote, loadLibrary, notify, play, remotePath]);
    const goBack = useCallback(() => {
        const previous = pathHistory[pathHistory.length - 1] ?? '';
        setPathHistory((items) => items.slice(0, -1));
        void listRemote(previous, config);
    }, [config, listRemote, pathHistory]);
    const syncLibrary = useCallback(async () => {
        if (!config.url || syncing)
            return;
        setSyncing(true);
        const response = await window.electronAPI.webdav.indexLibrary(config, '');
        if (response.data) {
            await loadLibrary();
            notify(t("曲库同步完成：发现 {0} 首歌曲，扫描 {1} 个目录{2}", [response.data.indexed, response.data.directories, response.data.errors.length ? t("，{0} 个目录失败", [response.data.errors.length]) : '']));
        }
        else
            notify(response.error || t("曲库同步失败"));
        setSyncing(false);
    }, [config, loadLibrary, notify, syncing]);
    const saveNewPlaylist = useCallback(async (name: string, songIds: string[] = [], description: string | null = null) => {
        const playlist = createPlaylist(name, songIds, description);
        const response = await window.electronAPI.savePlaylist(playlist);
        if (!response.success) {
            notify(response.error || t("歌单创建失败"));
            return null;
        }
        await loadPlaylists();
        setSelectedPlaylistId(playlist.id);
        return playlist;
    }, [loadPlaylists, notify]);
    const deletePlaylist = useCallback(async (id: string) => {
        setDeleteBusy(true);
        try {
            const response = await window.electronAPI.deletePlaylist(id);
            if (response.success) {
                setSelectedPlaylistId(null);
                setDeleteId(null);
                await loadPlaylists();
                notify(t("歌单已删除"));
            }
            else
                notify(response.error || t("歌单删除失败"));
        }
        catch {
            notify(t("删除失败，请重试。"));
        }
        finally {
            setDeleteBusy(false);
        }
    }, [loadPlaylists, notify]);
    const addToPlaylist = useCallback(async (playlistId: string, song: Song) => {
        const response = await window.electronAPI.addToPlaylist(playlistId, song.id);
        if (response.success) {
            await loadPlaylists();
            setSongToAdd(null);
            notify(t("已添加到歌单"));
        }
        else
            notify(response.error || t("添加失败"));
    }, [loadPlaylists, notify]);
    const removeFromPlaylist = useCallback(async (playlistId: string, song: Song) => {
        const response = await window.electronAPI.removeFromPlaylist(playlistId, song.id);
        if (response.success)
            await loadPlaylists();
        else
            notify(response.error || t("移除失败"));
    }, [loadPlaylists, notify]);
    const generateAiPlaylist = useCallback(async (prompt: string, useAi: boolean): Promise<AiPlaylistResult | null> => {
        const response = await window.electronAPI.generateAiPlaylist(prompt, useAi);
        if (!response.data)
            notify(response.error || t("智能歌单生成失败"));
        return response.data || null;
    }, [notify]);
    const normalizedQuery = query.trim().toLocaleLowerCase();
    const visibleSongs = useMemo(() => !normalizedQuery ? songs : songs.filter((song) => `${song.title} ${artistLabel(song.artist)} ${song.album} ${song.genre}`.toLocaleLowerCase().includes(normalizedQuery)), [normalizedQuery, songs]);
    const visibleFiles = useMemo(() => !normalizedQuery ? files : files.filter((file) => file.name.toLocaleLowerCase().includes(normalizedQuery)), [files, normalizedQuery]);
    const favorites = visibleSongs.filter((song) => song.isFavorite);
    const queueSongs = queueIds.map((id) => songs.find((song) => song.id === id)).filter(Boolean) as Song[];
    const pageInfo = PAGE_INFO[page];
    const playingSong = current ? songs.find(song => song.id === current.id) || current : null;
    const recentlyPlayed = useMemo(() => songs.filter(song => song.lastPlayedAt).sort((a, b) => Number(b.lastPlayedAt) - Number(a.lastPlayedAt)), [songs]);
    const openComposer = () => { setComposerSong(null); setComposerOpen(true); };
    const renderPage = () => {
        if (normalizedQuery && !['browse', 'settings'].includes(page)) {
            return <div className="search-results"><div className="result-summary"><Icon name="search"/><span><b>“{query.trim()}”</b><small>{t("找到 {0} 首歌曲", [visibleSongs.length])}</small></span></div><SongTable songs={visibleSongs} current={current} state={playerState} onPlay={play} onFavorite={toggleFavorite} onAdd={setSongToAdd} emptyText={t("没有匹配的歌曲")}/></div>;
        }
        switch (page) {
            case 'home': return <DashboardPage songs={songs} recommendations={recommendations} feed={feed} current={current} state={playerState} configured={Boolean(config.url)} onNavigate={navigate} onPlay={play} onFavorite={toggleFavorite} onAdd={setSongToAdd}/>;
            case 'browse': return <WebDavPage ready={ready} configured={Boolean(config.url)} files={visibleFiles} path={remotePath} canGoBack={Boolean(pathHistory.length)} loading={loading} syncing={syncing} onBack={goBack} onRefresh={() => void listRemote(remotePath, config, true)} onOpen={(file) => void openRemote(file)} onSettings={() => setPage('settings')} onSync={() => void syncLibrary()}/>;
            case 'library':
            case 'albums':
            case 'artists': return <CollectionPage songs={visibleSongs} current={current} state={playerState} tab={page === 'library' ? 'songs' : page} onPlay={play} onFavorite={toggleFavorite} onAdd={setSongToAdd}/>;
            case 'recent': return <SongTable songs={recentlyPlayed} current={current} state={playerState} onPlay={play} onFavorite={toggleFavorite} onAdd={setSongToAdd} emptyText={t("从第一首开始")}/>;
            case 'playlists': return <PlaylistsPage playlists={playlists} songs={songs} selectedId={selectedPlaylistId} current={current} state={playerState} onSelect={setSelectedPlaylistId} onCreate={openComposer} onDelete={setDeleteId} onPlay={play} onFavorite={toggleFavorite} onAdd={setSongToAdd} onRemove={(playlistId, song) => void removeFromPlaylist(playlistId, song)}/>;
            case 'favorites': return <div><div className="collection-toolbar"><span>{t("{0} 首歌曲", [favorites.length])}</span><button className="text-button" disabled={!favorites.length} onClick={() => favorites[0] && play(favorites[0], favorites)}><Icon name="play" size={15}/>{t("播放全部")}</button></div><SongTable songs={favorites} current={current} state={playerState} onPlay={play} onFavorite={toggleFavorite} onAdd={setSongToAdd} emptyText={t("把心动的音乐收藏在这里")}/></div>;
            case 'settings': return <PreferencesPage config={config} setConfig={setConfig} aiStatus={aiStatus} onAiChanged={setAiStatus} onConnected={(root) => { const cleaned = cleanFiles(root); directoryCache.current.clear(); directoryCache.current.set('', cleaned); setFiles(cleaned); setRemotePath(''); setPathHistory([]); setPage('browse'); }} notify={notify}/>;
        }
    };
    return (<div className="app-shell">
      <aside className="sidebar">
        <button className="brand" onClick={() => navigate('home')} aria-label={t("Aurora 首页")}><span className="brand-symbol"><Icon name="music" size={22}/></span><b>Aurora<span>Music</span></b></button>
        <div className="sidebar-navigation">
        <nav aria-label={t("聆听")}>
          <button className={page === 'home' ? 'active' : ''} onClick={() => navigate('home')}><Icon name="home"/><span>{t("现在就听")}</span></button>
          <button className={page === 'recent' ? 'active' : ''} onClick={() => navigate('recent')}><Icon name="clock"/><span>{t("最近播放")}</span></button>
        </nav>
        <p className="nav-heading">{t("资料库")}</p>
        <nav aria-label={t("资料库")}>{NAV.map(item => <button key={item.page} className={page === item.page ? 'active' : ''} onClick={() => navigate(item.page)}><Icon name={item.icon}/><span>{t(item.label)}</span></button>)}</nav>
        <div className="nav-heading playlist-heading"><button onClick={() => { setSelectedPlaylistId(null); navigate('playlists'); }}>{t("我的歌单")}</button><button onClick={openComposer} aria-label={t("新建歌单")}><Icon name="plus" size={16}/></button></div>
        <nav className="sidebar-playlists" aria-label={t("我的歌单")}>{playlists.map(playlist => <button key={playlist.id} className={page === 'playlists' && selectedPlaylistId === playlist.id ? 'active' : ''} onClick={() => { setSelectedPlaylistId(playlist.id); navigate('playlists'); }}><Icon name="playlist" size={18}/><span>{playlist.name}</span></button>)}
          {!playlists.length && <button className="create-playlist-link" onClick={openComposer}><Icon name="plus" size={17}/><span>{t("创建第一张歌单")}</span></button>}
        </nav>
        </div>
        <div className="sidebar-bottom"><button className={page === 'browse' ? 'active' : ''} onClick={() => navigate('browse')}><Icon name="cloud" size={19}/><span>WebDAV<small>{config.url ? host(config.url) : t("尚未连接")}</small></span></button>
          <button className={page === 'settings' ? 'active' : ''} onClick={() => navigate('settings')}><Icon name="settings" size={19}/><span>{t("设置")}</span></button></div>
      </aside>
      <div className="content-shell">
        <div className="topbar"><span className="topbar-caption">{t("你的音乐，始终在这里。")}</span><button className="theme-toggle" onClick={() => setTheme(document.documentElement.dataset.theme === 'light' ? 'dark' : 'light')} aria-label={t("切换明暗主题")}><span aria-hidden="true">◐</span>{t("外观")}</button><label className="global-search"><Icon name="search" size={16}/><input ref={searchRef} aria-label={t("搜索音乐")} value={query} onChange={event => { setQuery(event.target.value); if (page === 'settings')
        setPage('library'); }} placeholder={page === 'browse' ? t("搜索当前目录") : t("搜索你的音乐")}/>{query ? <button aria-label={t("清除搜索")} onClick={() => setQuery('')}><Icon name="close" size={14}/></button> : <kbd>Ctrl F</kbd>}</label></div>
        <main className="main-content" ref={mainRef}>
          <header className="page-header"><div>{page === 'home' && <p className="page-date">{new Intl.DateTimeFormat(locale, { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())}</p>}<h1>{normalizedQuery && !['browse', 'settings'].includes(page) ? t("搜索") : t(pageInfo.title)}</h1></div>
            {(page === 'home' || page === 'playlists') && <button className="secondary-button" onClick={openComposer}><Icon name="plus" size={16}/>{t("新建歌单")}</button>}
          </header>
          <div className="page-container" key={page}>{ready ? renderPage() : <div className="soft-empty" role="status"><i className="spinner"/><p>{t("正在读取音乐库…")}</p></div>}</div>
        </main>
      </div>
      <PlayerBar song={playingSong} state={playerState} songs={songs} onQueue={() => setQueueOpen(value => !value)} queueOpen={queueOpen} onExpand={() => setFullPlayerOpen(true)} onFavorite={toggleFavorite}/>
      {message && <div className="toast" role="status"><span>{tm(message)}</span><button className="icon-button" aria-label={t("关闭提示")} onClick={() => setMessage(null)}><Icon name="close" size={15}/></button></div>}
      {songToAdd && <Modal title={t("添加到歌单")} onClose={() => setSongToAdd(null)}>
        <div className="adding-track"><Artwork song={songToAdd} size="small"/><div><b>{songToAdd.title}</b><small>{artistLabel(songToAdd.artist)}</small></div></div>
        <div className="playlist-picker">{playlists.map(playlist => <button key={playlist.id} onClick={() => void addToPlaylist(playlist.id, songToAdd)}><Icon name="playlist" size={19}/><span>{playlist.name}</span><Icon name="plus" size={16}/></button>)}</div>
        <button className="new-playlist-action" onClick={() => { setComposerSong(songToAdd); setSongToAdd(null); setComposerOpen(true); }}><Icon name="plus" size={17}/>{t("新建歌单")}</button>
      </Modal>}
      {composerOpen && <PlaylistComposer songs={songs} aiConfigured={aiStatus.configured} initialSong={composerSong} onClose={() => setComposerOpen(false)} onGenerate={generateAiPlaylist} onSettings={() => { setComposerOpen(false); navigate('settings'); }} onSave={async (name, ids, description) => { const playlist = await saveNewPlaylist(name, ids, description); if (playlist) {
        navigate('playlists');
        notify(t("歌单已保存"));
    } ; return playlist; }}/>}
      {deleteId && <Modal title={t("删除这张歌单？")} onClose={() => !deleteBusy && setDeleteId(null)}><p className="dialog-description">{t("将删除歌单“{0}”，音乐库中的歌曲会保留。", [playlists.find(p => p.id === deleteId)?.name || ''])}</p><footer className="dialog-footer"><button className="secondary-button" disabled={deleteBusy} onClick={() => setDeleteId(null)}>{t("取消")}</button><button className="primary-button" disabled={deleteBusy} onClick={() => void deletePlaylist(deleteId)}>{deleteBusy ? t("正在删除…") : t("删除歌单")}</button></footer></Modal>}
      {queueOpen && <aside className="queue-drawer" aria-label={t("播放队列")}><header><div><h2>{t("接下来播放")}</h2><p>{t("{0} 首歌曲", [queueSongs.length])}</p></div><button className="icon-button" aria-label={t("关闭队列")} onClick={() => setQueueOpen(false)}><Icon name="close"/></button></header><div className="queue-tracks">{queueSongs.map((song, index) => <button className={index === queueIndex ? 'active' : ''} key={song.id + '-' + index} onClick={() => void play(song, queueSongs)}><Artwork song={song} size="small"/><span><b>{song.title}</b><small>{artistLabel(song.artist)}</small></span>{index === queueIndex && <span className={'playing-bars ' + (playerState.isPlaying ? '' : 'paused')}><i /><i /><i /></span>}</button>)}</div>{!queueSongs.length && <div className="soft-empty"><Icon name="queue" size={28}/><p>{t("播放一首音乐，从这里继续。")}</p></div>}</aside>}
      {fullPlayerOpen && playingSong && <NowPlaying song={playingSong} songs={songs} state={playerState} onClose={() => setFullPlayerOpen(false)} onFavorite={toggleFavorite} onResourcesChanged={() => void loadLibrary()}/>}
    </div>);
}
