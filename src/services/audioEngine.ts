import { t } from '../i18n'
import { Howl } from 'howler';
import type { PlayHistory, Song } from '../types/music';
import { usePlayerStore } from '../stores/playerStore';
type PlayStateListener = (state: PlayState) => void;
export type PlayMode = 'sequential' | 'smart-shuffle' | 'repeat-one';
export interface PlayState {
    isPlaying: boolean;
    currentTime: number;
    duration: number;
    progress: number;
    isLoading: boolean;
    error: string | null;
    loadingMessage?: string;
}
/**
 * 音频播放引擎 —— 封装 Howler.js
 *
 * 关键设计：
 * - 所有本地文件通过 local-protocol:// 自定义协议加载，规避 file:// 安全限制
 * - Howler 实例为单例，切换歌曲时先 unload() 旧实例
 * - 通过 onStateChange 回调通知 UI 更新（250ms 推送）
 */
class AudioEngine {
    private howl: Howl | null = null;
    private currentSong: Song | null = null;
    private listeners: Set<PlayStateListener> = new Set();
    private updateInterval: ReturnType<typeof setInterval> | null = null;
    private lastError: string | null = null;
    private loading: boolean = false;
    private requestId = 0;
    private didStart = false;
    private terminalRecorded = false;
    private listeningSeconds = 0;
    private lastListeningTick = 0;
    private loadingMessage = '';
    private loadTimeout: ReturnType<typeof setTimeout> | null = null;
    private queue: Song[] = [];
    private queueIndex = -1;
    private recentKeys: string[] = [];
    private prefetchedNext: Song | null = null;
    private prefetchTimer: ReturnType<typeof setTimeout> | null = null;
    getState(): PlayState {
        return {
            isPlaying: this.howl?.playing() ?? false,
            currentTime: (this.howl?.seek() as number) ?? 0,
            duration: this.howl?.duration() ?? (this.currentSong?.duration ?? 0),
            progress: this.getProgress(),
            isLoading: this.loading,
            error: this.lastError,
            loadingMessage: this.loading ? this.loadingMessage : '',
        };
    }
    /** 获取当前播放的歌曲（供 UI 直接查询，避免 store 查找失败） */
    getCurrentSong(): Song | null {
        return this.currentSong;
    }
    getPlayMode(): PlayMode {
        return usePlayerStore.getState().playMode;
    }
    setPlayMode(mode: PlayMode): void {
        usePlayerStore.getState().setPlayMode(mode);
        this.prefetchedNext = null;
        this.schedulePrefetch();
    }
    async play(song: Song, allSongs?: Song[], compatiblePath?: string): Promise<void> {
        const sameSession = Boolean(compatiblePath && this.currentSong?.id === song.id);
        if (!sameSession)
            this.recordSkipIfNeeded();
        const requestId = ++this.requestId;
        this.destroy();
        if (!sameSession) {
            this.didStart = false;
            this.terminalRecorded = false;
            this.listeningSeconds = 0;
        }
        this.currentSong = song;
        this.lastError = null;
        this.loading = true;
        this.loadingMessage = t("正在加载音频…");
        this.notifyListeners();
        if (allSongs && allSongs.length > 0) {
            const ids = allSongs.map((s) => s.id);
            usePlayerStore.getState().setQueue(ids, ids.indexOf(song.id));
            this.queue = [...allSongs];
            this.queueIndex = allSongs.findIndex((item) => item.id === song.id || item.remotePath === song.remotePath);
            this.prefetchedNext = null;
        }
        try {
            let filePath = song.filePath;
            let src = '';
            const containerNeedsDecoder = /\.(m4a|mp4|m4b|alac)(?:[?#]|$)/i.test(song.remotePath || filePath) || /M4A|MP42|ISOM|ALAC/i.test(song.format);
            if (!compatiblePath && containerNeedsDecoder) {
                this.loadingMessage = t("正在本地兼容解码，首次播放需要下载原文件…");
                this.notifyListeners();
                const decoded = await window.electronAPI.prepareCompatibleAudio(song.id);
                if (requestId !== this.requestId)
                    return;
                if (!decoded.data) {
                    this.notifyError(decoded.error || t("兼容解码失败"));
                    return;
                }
                compatiblePath = decoded.data;
            }
            if (compatiblePath)
                filePath = compatiblePath;
            else if (song.sourceType === 'webdav' && song.remotePath) {
                if (!song.localCachePath || song.localCachePath.length === 0) {
                    const streamResult = await window.electronAPI.getWebDavAudioUrl(song.id);
                    if (requestId !== this.requestId)
                        return;
                    if (!streamResult.success || !streamResult.data) {
                        this.notifyError(streamResult.error || t("无法创建 WebDAV 播放流"));
                        return;
                    }
                    src = streamResult.data;
                    filePath = song.remotePath;
                }
                else {
                    filePath = song.localCachePath;
                }
            }
            if (!filePath) {
                this.notifyError(t("无有效文件路径"));
                return;
            }
            if (!src) {
                const result = await window.electronAPI.getAudioUrl(filePath);
                if (requestId !== this.requestId)
                    return;
                if (!result.success || !result.data) {
                    this.notifyError(t("无法获取音频 URL"));
                    return;
                }
                src = result.data;
            }
            const ext = (filePath.split('.').pop() || 'mp3').toLowerCase();
            // Howler html5 模式下格式名：mp3/flac/ogg/wav/aac/m4a/webm 等
            const knownFormats = ['mp3', 'flac', 'ogg', 'wav', 'aac', 'm4a', 'mp4', 'webm', 'wma', 'opus', 'aiff', 'ape'];
            const howlFormat = knownFormats.includes(ext) ? (ext === 'mp4' ? 'm4a' : ext) : 'mp3';
            this.howl = new Howl({
                src: [src],
                html5: true,
                format: [howlFormat],
                volume: usePlayerStore.getState().volume,
                // 缓存文件走本地 Range，未缓存文件走带鉴权的 WebDAV Range 协议。
                preload: true,
                onload: () => {
                    if (requestId !== this.requestId)
                        return;
                    this.clearLoadTimeout();
                    this.lastError = null;
                    this.loading = false;
                    this.notifyListeners();
                },
                onplay: () => {
                    if (requestId !== this.requestId)
                        return;
                    this.clearLoadTimeout();
                    this.loading = false;
                    this.notifyListeners();
                    if (!this.didStart) {
                        this.didStart = true;
                        this.recordAction('start', 0);
                        this.remember(song);
                        this.schedulePrefetch();
                    }
                    this.startProgressUpdates();
                    if ('mediaSession' in navigator)
                        navigator.mediaSession.playbackState = 'playing';
                },
                onpause: () => {
                    if (requestId !== this.requestId)
                        return;
                    this.notifyListeners();
                    this.stopProgressUpdates();
                    if ('mediaSession' in navigator)
                        navigator.mediaSession.playbackState = 'paused';
                },
                onstop: () => {
                    this.notifyListeners();
                    this.stopProgressUpdates();
                },
                onend: () => {
                    if (requestId !== this.requestId)
                        return;
                    this.notifyListeners();
                    this.stopProgressUpdates();
                    if ('mediaSession' in navigator)
                        navigator.mediaSession.playbackState = 'none';
                    this.terminalRecorded = true;
                    const heard = this.getHeardProgress();
                    this.recordAction(heard >= 0.85 ? 'complete' : 'pause', heard);
                    setTimeout(() => { if (requestId === this.requestId)
                        this.next(this.queue); }, 0);
                },
                onloaderror: (_id: number, err: unknown) => {
                    if (requestId !== this.requestId)
                        return;
                    const msg = err instanceof Error ? err.message : String(err);
                    console.error('[AudioEngine] Load error (id=' + _id + '):', msg);
                    if (!compatiblePath && song.remotePath) {
                        this.clearLoadTimeout();
                        this.howl?.unload();
                        this.howl = null;
                        void this.decodeAndRetry(song, requestId);
                        return;
                    }
                    this.notifyError(t("无法解码 {0} 音频（{1}）", [song.format || ext.toUpperCase(), msg]));
                },
                onplayerror: (_id: number, err: unknown) => {
                    if (requestId !== this.requestId)
                        return;
                    if (!compatiblePath && song.remotePath) {
                        this.clearLoadTimeout();
                        this.howl?.unload();
                        this.howl = null;
                        void this.decodeAndRetry(song, requestId);
                        return;
                    }
                    const msg = err instanceof Error ? err.message : String(err);
                    console.error('[AudioEngine] Play error (id=' + _id + '):', msg);
                    this.notifyError(t("播放失败: ") + msg);
                },
            });
            this.loadTimeout = setTimeout(() => {
                if (!this.loading || requestId !== this.requestId)
                    return;
                this.howl?.unload();
                this.howl = null;
                if (!compatiblePath && song.remotePath)
                    void this.decodeAndRetry(song, requestId);
                else
                    this.notifyError(t("兼容音频加载超时，请重新播放或检查缓存空间"));
            }, 30000);
            this.howl.play();
            this.configureMediaSession(song);
        }
        catch (err) {
            if (requestId !== this.requestId)
                return;
            const msg = err instanceof Error ? err.message : String(err);
            console.error('[AudioEngine] Unexpected error:', msg);
            this.notifyError(t("播放异常: {0}", [msg]));
        }
    }
    /** 跳转到指定秒数。 */
    seek(seconds: number): void {
        if (!this.howl)
            return;
        const duration = this.howl.duration() || 0;
        this.howl.seek(Math.max(0, Math.min(seconds, duration)));
        this.notifyListeners();
    }
    /** 暂停 */
    pause(): void {
        if (!this.howl)
            return;
        this.howl.pause();
        this.stopProgressUpdates();
        this.notifyListeners();
        if (this.didStart)
            this.recordAction('pause', this.getHeardProgress());
    }
    /** 恢复播放 */
    resume(): void {
        if (!this.howl)
            return;
        this.howl.play();
        this.startProgressUpdates();
        this.notifyListeners();
    }
    /** 设置音量 */
    setVolume(vol: number): void {
        const value = Math.max(0, Math.min(1, vol));
        usePlayerStore.getState().setVolume(value);
        this.howl?.volume(value);
    }
    /** 下一曲 */
    next(songs: Song[]): void {
        const queue = this.queue.length ? this.queue : songs;
        if (!queue.length)
            return;
        const next = this.pickNext(queue);
        if (next)
            void this.play(next);
    }
    /** 上一曲 */
    prev(songs: Song[]): void {
        const queue = this.queue.length ? this.queue : songs;
        if (!queue.length)
            return;
        this.queueIndex = (this.queueIndex - 1 + queue.length) % queue.length;
        usePlayerStore.getState().setQueueIndex(this.queueIndex);
        void this.play(queue[this.queueIndex]);
    }
    /** 切换播放/暂停 */
    togglePlay(song?: Song): void {
        // 情况1：切换为不同歌曲 → 直接播放
        if (song && song.id !== this.currentSong?.id) {
            this.play(song);
            return;
        }
        // 情况2：同一首歌，正在播放 → 暂停
        if (this.howl?.playing()) {
            this.pause();
            return;
        }
        // 情况3：同一首歌，已暂停且有实例 → 恢复
        if (this.howl) {
            this.resume();
            return;
        }
        // 情况4：同一首歌，实例已被销毁（播放完毕/出错）→ 重新播放
        if (song) {
            this.play(song);
        }
    }
    /** 注册状态变化监听器，返回取消订阅函数 */
    onStateChange(listener: PlayStateListener): () => void {
        this.listeners.add(listener);
        return () => {
            this.listeners.delete(listener);
        };
    }
    // ===== 私有方法 =====
    /** 销毁当前 Howl 实例 */
    private destroy(): void {
        this.clearLoadTimeout();
        this.stopProgressUpdates();
        if (this.howl) {
            this.howl.unload();
            this.howl = null;
        }
    }
    /** 获取播放进度 0~1 */
    private getProgress(): number {
        if (!this.howl)
            return 0;
        const duration = this.howl.duration();
        if (!duration || duration <= 0)
            return 0;
        const seek = this.howl.seek() as number;
        return Math.min(seek / duration, 1);
    }
    /** 通知所有监听器 */
    private notifyListeners(): void {
        const state = this.getState();
        this.listeners.forEach((fn) => {
            try {
                fn(state);
            }
            catch { /* ignore listener errors */ }
        });
    }
    /** 通知错误 */
    private notifyError(error: string): void {
        this.clearLoadTimeout();
        this.lastError = error;
        this.loading = false;
        this.stopProgressUpdates();
        console.error('[AudioEngine] Error:', error);
        const state = this.getState();
        this.listeners.forEach((fn) => {
            try {
                fn(state);
            }
            catch { /* ignore */ }
        });
    }
    /** 开始定时推送进度更新 */
    private startProgressUpdates(): void {
        this.stopProgressUpdates();
        this.lastListeningTick = Date.now();
        this.updateInterval = setInterval(() => {
            if (this.howl?.playing()) {
                this.updateListeningTime();
                this.notifyListeners();
            }
        }, 250);
    }
    /** 停止定时推送 */
    private stopProgressUpdates(): void {
        if (this.updateInterval)
            this.updateListeningTime();
        this.lastListeningTick = 0;
        if (this.updateInterval) {
            clearInterval(this.updateInterval);
            this.updateInterval = null;
        }
    }
    private songKey(song: Song): string {
        return song.remotePath || song.id;
    }
    private remember(song: Song): void {
        const key = this.songKey(song);
        this.recentKeys = [key, ...this.recentKeys.filter(item => item !== key)].slice(0, 8);
    }
    /** Lightweight on-device recommendation model: diversity + recency + cache readiness. */
    private recommend(queue: Song[]): Song | null {
        const current = this.currentSong;
        const candidates = queue.filter(song => this.songKey(song) !== (current ? this.songKey(current) : ''));
        if (!candidates.length)
            return current;
        const scored = candidates.map(song => {
            const recentIndex = this.recentKeys.indexOf(this.songKey(song));
            const recency = recentIndex < 0 ? 3 : recentIndex * 0.25;
            const artistDiversity = current?.artist && song.artist === current.artist ? -2.5 : 1;
            const albumDiversity = current?.album && song.album === current.album ? -1.25 : 0.5;
            const readyBonus = song.localCachePath ? 0.75 : 0;
            return { song, score: recency + artistDiversity + albumDiversity + readyBonus + Math.random() };
        });
        scored.sort((a, b) => b.score - a.score);
        return scored[0]?.song || null;
    }
    private pickNext(queue: Song[]): Song | null {
        const mode = this.getPlayMode();
        if (mode === 'repeat-one')
            return this.currentSong;
        let next: Song | null;
        if (mode === 'smart-shuffle') {
            next = this.prefetchedNext && this.songKey(this.prefetchedNext) !== (this.currentSong ? this.songKey(this.currentSong) : '')
                ? this.prefetchedNext
                : this.recommend(queue);
            this.queueIndex = next ? queue.findIndex(song => this.songKey(song) === this.songKey(next!)) : this.queueIndex;
        }
        else {
            this.queueIndex = (this.queueIndex + 1 + queue.length) % queue.length;
            next = queue[this.queueIndex];
        }
        usePlayerStore.getState().setQueueIndex(this.queueIndex);
        this.prefetchedNext = null;
        return next;
    }
    private peekNext(): Song | null {
        if (!this.queue.length || !this.currentSong)
            return null;
        const mode = this.getPlayMode();
        if (mode === 'repeat-one')
            return null;
        if (mode === 'smart-shuffle')
            return this.recommend(this.queue);
        return this.queue[(this.queueIndex + 1 + this.queue.length) % this.queue.length] || null;
    }
    private schedulePrefetch(): void {
        if (this.prefetchTimer)
            clearTimeout(this.prefetchTimer);
        const candidate = this.peekNext();
        this.prefetchedNext = candidate;
        if (!candidate || candidate.localCachePath || !candidate.remotePath)
            return;
        this.prefetchTimer = setTimeout(async () => {
            const config = await window.electronAPI.webdav.loadConfig();
            if (!config.success || !config.data)
                return;
            const result = await window.electronAPI.webdav.prefetch(config.data, candidate);
            if (result.success && result.data) {
                candidate.localCachePath = result.data;
                candidate.filePath = result.data;
            }
        }, 500);
    }
    private updateListeningTime(): void {
        const now = Date.now();
        if (this.lastListeningTick)
            this.listeningSeconds += Math.min(2, Math.max(0, (now - this.lastListeningTick) / 1000));
        this.lastListeningTick = now;
    }
    private getHeardProgress(): number {
        const duration = this.howl?.duration() || this.currentSong?.duration || 0;
        return duration > 0 ? Math.min(1, this.listeningSeconds / duration) : 0;
    }
    private recordAction(action: PlayHistory['action'], progress: number): void {
        if (!this.currentSong)
            return;
        void window.electronAPI.recordPlayAction({
            songId: this.currentSong.id, action, timestamp: Date.now(), progress,
        }).then(result => { if (result.success)
            window.dispatchEvent(new Event('aurora:listening-change')); }).catch(() => { });
    }
    private recordSkipIfNeeded(): void {
        if (!this.didStart || this.terminalRecorded || this.lastError)
            return;
        if (this.howl?.playing())
            this.updateListeningTime();
        this.terminalRecorded = true;
        const heard = this.getHeardProgress();
        this.recordAction(heard >= 0.85 ? 'complete' : 'skip', heard);
    }
    private async decodeAndRetry(song: Song, requestId: number): Promise<void> {
        this.loading = true;
        this.lastError = null;
        this.notifyListeners();
        this.loadingMessage = t("浏览器解码失败，正在本地兼容解码…");
        this.notifyListeners();
        try {
            const decoded = await window.electronAPI.prepareCompatibleAudio(song.id);
            if (requestId !== this.requestId)
                return;
            if (!decoded.data) {
                this.notifyError(decoded.error || t("兼容解码失败"));
                return;
            }
            await this.play(song, undefined, decoded.data);
        }
        catch {
            if (requestId === this.requestId)
                this.notifyError(t("兼容解码失败，请重试"));
        }
    }
    private configureMediaSession(song: Song): void {
        if (!('mediaSession' in navigator))
            return;
        navigator.mediaSession.metadata = new MediaMetadata({
            title: song.title,
            artist: song.artist || t("未知艺术家"),
            album: song.album || 'Aurora Music',
        });
        const safeSet = (action: MediaSessionAction, handler: MediaSessionActionHandler | null) => {
            try {
                navigator.mediaSession.setActionHandler(action, handler);
            }
            catch { /* unsupported action */ }
        };
        safeSet('play', () => this.resume());
        safeSet('pause', () => this.pause());
        safeSet('previoustrack', () => this.prev(this.queue));
        safeSet('nexttrack', () => this.next(this.queue));
        safeSet('seekbackward', (details) => this.seek(this.getState().currentTime - (details.seekOffset || 10)));
        safeSet('seekforward', (details) => this.seek(this.getState().currentTime + (details.seekOffset || 10)));
        safeSet('seekto', (details) => {
            if (typeof details.seekTime === 'number')
                this.seek(details.seekTime);
        });
    }
    private clearLoadTimeout(): void {
        if (this.loadTimeout) {
            clearTimeout(this.loadTimeout);
            this.loadTimeout = null;
        }
    }
}
/** 全局单例 */
export const audioEngine = new AudioEngine();
