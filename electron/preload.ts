/// <reference types="node" />

import { contextBridge, ipcRenderer, IpcRendererEvent } from 'electron'
import type { AiPlaylistResult, DeepSeekConfigInput, DeepSeekConfigStatus, IpcResponse, Song, Playlist, PlayHistory, RecommendationFeed } from '../src/types/music'
import type { WebDavConfig, WebDavFile, SyncProgress } from '../src/types/webdav'
import type { AiProvider, AssetConfig, AssetKind, AssetCandidate } from '../src/types/providers'

/**
 * Electron API —— 安全地暴露给渲染进程
 * 所有方法返回 Promise<T>
 */
const electronAPI = {
  platform: process.platform,
  setTheme: (theme: 'light'|'dark'|'system'): Promise<void> => ipcRenderer.invoke('appearance:setTheme', theme),
  getAiProviders: (): Promise<IpcResponse<AiProvider[]>> => ipcRenderer.invoke('ai:getProviders'),
  saveAiProviders: (providers: AiProvider[]): Promise<IpcResponse<AiProvider[]>> => ipcRenderer.invoke('ai:saveProviders', providers),
  prepareCompatibleAudio: (id: string): Promise<IpcResponse<string>> => ipcRenderer.invoke('file:prepareCompatibleAudio', id),
  getAssetConfig: (): Promise<IpcResponse<AssetConfig>> => ipcRenderer.invoke('assets:getConfig'),
  saveAssetConfig: (config: AssetConfig): Promise<IpcResponse<AssetConfig>> => ipcRenderer.invoke('assets:saveConfig', config),
  searchAssets: (songId: string, kind: AssetKind, query: {title:string;artist:string;album:string}): Promise<IpcResponse<AssetCandidate[]>> => ipcRenderer.invoke('assets:search', songId, kind, query),
  applyAsset: (songId: string, kind: AssetKind, candidateId: string): Promise<IpcResponse<Song>> => ipcRenderer.invoke('assets:apply', songId, kind, candidateId),

  // ===== 歌曲 =====
  getAllSongs: (): Promise<IpcResponse<Song[]>> =>
    ipcRenderer.invoke('music:getAllSongs'),
  getSongById: (id: string): Promise<IpcResponse<Song>> =>
    ipcRenderer.invoke('music:getSongById', id),
  getRecommendations: (limit = 24): Promise<IpcResponse<Song[]>> =>
    ipcRenderer.invoke('music:getRecommendations', limit),
  getRecommendationFeed: (): Promise<IpcResponse<RecommendationFeed>> =>
    ipcRenderer.invoke('music:getRecommendationFeed'),
  setFavorite: (songId: string, favorite: boolean): Promise<IpcResponse> =>
    ipcRenderer.invoke('music:setFavorite', songId, favorite),

  // ===== 歌单 =====
  getPlaylists: (): Promise<IpcResponse<Playlist[]>> =>
    ipcRenderer.invoke('music:getPlaylists'),
  savePlaylist: (playlist: Playlist): Promise<IpcResponse> =>
    ipcRenderer.invoke('music:savePlaylist', playlist),
  deletePlaylist: (id: string): Promise<IpcResponse> =>
    ipcRenderer.invoke('music:deletePlaylist', id),
  addToPlaylist: (playlistId: string, songId: string): Promise<IpcResponse> =>
    ipcRenderer.invoke('music:addToPlaylist', playlistId, songId),
  removeFromPlaylist: (playlistId: string, songId: string): Promise<IpcResponse> =>
    ipcRenderer.invoke('music:removeFromPlaylist', playlistId, songId),

  // ===== 播放历史 =====
  getPlayHistory: (limit?: number, offset?: number): Promise<IpcResponse<PlayHistory[]>> =>
    ipcRenderer.invoke('music:getPlayHistory', limit, offset),
  recordPlayAction: (record: Omit<PlayHistory, 'id'>): Promise<IpcResponse> =>
    ipcRenderer.invoke('music:recordPlayAction', record),

  // ===== 设置 =====
  getSettings: (): Promise<IpcResponse<Record<string, string>>> =>
    ipcRenderer.invoke('music:getSettings'),
  setSettings: (key: string, value: string): Promise<IpcResponse> =>
    ipcRenderer.invoke('music:setSettings', key, value),

  // ===== DeepSeek 智能歌单 =====
  getDeepSeekConfig: (): Promise<IpcResponse<DeepSeekConfigStatus>> =>
    ipcRenderer.invoke('ai:getConfig'),
  saveDeepSeekConfig: (config: DeepSeekConfigInput): Promise<IpcResponse<DeepSeekConfigStatus>> =>
    ipcRenderer.invoke('ai:saveConfig', config),
  generateAiPlaylist: (prompt: string, useAi = true): Promise<IpcResponse<AiPlaylistResult>> =>
    ipcRenderer.invoke('ai:generatePlaylist', prompt, useAi),

  // ===== 文件 =====
  getAudioUrl: (filePath: string): Promise<IpcResponse<string>> =>
    ipcRenderer.invoke('file:getAudioUrl', filePath),
  getWebDavAudioUrl: (songId: string): Promise<IpcResponse<string>> =>
    ipcRenderer.invoke('file:getWebDavAudioUrl', songId),

  // ===== WebDAV =====
  webdav: {
    testConnection: (config: WebDavConfig): Promise<IpcResponse<boolean>> =>
      ipcRenderer.invoke('webdav:testConnection', config),
    listFiles: (config: WebDavConfig, remotePath: string): Promise<IpcResponse<WebDavFile[]>> =>
      ipcRenderer.invoke('webdav:listFiles', config, remotePath),
    registerFile: (file: WebDavFile): Promise<IpcResponse<Song>> =>
      ipcRenderer.invoke('webdav:registerFile', file),
    startSync: (config: WebDavConfig, files: WebDavFile[]): Promise<IpcResponse> =>
      ipcRenderer.invoke('webdav:startSync', config, files),
    indexLibrary: (config: WebDavConfig, remotePath = ''): Promise<IpcResponse<{ indexed: number; directories: number; errors: string[] }>> =>
      ipcRenderer.invoke('webdav:indexLibrary', config, remotePath),
    cancelSync: (): Promise<IpcResponse> =>
      ipcRenderer.invoke('webdav:cancelSync'),
    getSyncProgress: (): Promise<IpcResponse<SyncProgress>> =>
      ipcRenderer.invoke('webdav:getSyncProgress'),
    getCacheInfo: (): Promise<IpcResponse<{ totalSize: number; maxSize: number; fileCount: number }>> =>
      ipcRenderer.invoke('webdav:getCacheInfo'),
    setCacheMaxSize: (bytes: number): Promise<IpcResponse> =>
      ipcRenderer.invoke('webdav:setCacheMaxSize', bytes),
    clearCache: (): Promise<IpcResponse<{ freed: number; count: number }>> =>
      ipcRenderer.invoke('webdav:clearCache'),
    downloadSingle: (config: WebDavConfig, song: Song): Promise<IpcResponse<string>> =>
      ipcRenderer.invoke('webdav:downloadSingle', config, song),
    prefetch: (config: WebDavConfig, song: Song): Promise<IpcResponse<string>> =>
      ipcRenderer.invoke('webdav:prefetch', config, song),
    saveConfig: (config: WebDavConfig): Promise<IpcResponse> =>
      ipcRenderer.invoke('webdav:saveConfig', config),
    loadConfig: (): Promise<IpcResponse<WebDavConfig | null>> =>
      ipcRenderer.invoke('webdav:loadConfig'),
  },

  // ===== 通用消息通道 =====
  send: (channel: string, data: unknown) => {
    const validChannels = ['toMain']
    if (validChannels.includes(channel)) {
      ipcRenderer.send(channel, data)
    }
  },
  receive: (channel: string, func: (...args: unknown[]) => void) => {
    const validChannels = ['fromMain']
    if (validChannels.includes(channel)) {
      ipcRenderer.on(channel, (_event: IpcRendererEvent, ...args: unknown[]) => func(...args))
    }
  },
}

contextBridge.exposeInMainWorld('electronAPI', electronAPI)

export type ElectronAPI = typeof electronAPI
