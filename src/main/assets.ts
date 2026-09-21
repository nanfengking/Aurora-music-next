import { app, ipcMain, nativeImage } from 'electron'
import type Database from 'better-sqlite3'
import { mkdirSync, writeFileSync, existsSync, unlinkSync } from 'node:fs'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { lookup } from 'node:dns/promises'
import { isIP } from 'node:net'
import { encryptSecret, decryptSecret } from './secrets'
import { validateApiUrl } from './aiProviders'
import type { AssetConfig, AssetCandidate, AssetKind } from '../types/providers'
import type { Song } from '../types/music'

type StoredConfig = AssetConfig & { secret?: string; audioDbSecret?: string }
const defaults: AssetConfig = {lyricsUrl:'', coverUrl:'', backgroundUrl:'', configured:false, audioDbConfigured:false}
const candidates = new Map<string, {songId:string;kind:AssetKind;candidate:AssetCandidate;expires:number}>()
function stored(db: Database.Database): StoredConfig { const row=db.prepare("SELECT value FROM settings WHERE key = 'asset_config'").get() as {value:string}|undefined; return row ? {...defaults,...JSON.parse(row.value)} : {...defaults} }
function status(db: Database.Database): AssetConfig { const {secret,audioDbSecret,apiKey,audioDbKey,...config}=stored(db); return {...config, configured:Boolean(decryptSecret(secret||'')), audioDbConfigured:Boolean(decryptSecret(audioDbSecret||''))} }
async function boundedBody(response: Response, limit: number): Promise<Buffer> {
  if (!response.ok) { await response.body?.cancel(); throw new Error(`服务响应 HTTP ${response.status}`) }
  if (Number(response.headers.get('content-length')) > limit) { await response.body?.cancel(); throw new Error('返回内容过大') }
  const reader=response.body?.getReader(); if(!reader) throw new Error('返回内容为空')
  const chunks:Uint8Array[]=[]; let size=0
  try { while(true) { const part=await reader.read(); if(part.done) break; size+=part.value.length; if(size>limit) throw new Error('返回内容过大'); chunks.push(part.value) } }
  finally { await reader.cancel().catch(()=>{}) }
  return Buffer.concat(chunks)
}
function publicAddress(address: string) {
  const ip=address.toLowerCase()
  if (ip.includes(':')) return !/^(::|fc|fd|fe[89ab]|ff)/.test(ip) && !ip.includes('ffff:')
  const p=ip.split('.').map(Number)
  return !(p[0]===0||p[0]===10||p[0]===127||p[0]>=224||p[0]===169&&p[1]===254||p[0]===172&&p[1]>=16&&p[1]<=31||p[0]===192&&p[1]===168||p[0]===100&&p[1]>=64&&p[1]<=127)
}
async function downloadImage(raw: string): Promise<string> {
  const url=new URL(raw)
  if(url.protocol!=='https:'||url.username||url.password) throw new Error('图片必须使用公开 HTTPS 地址')
  const hostname=url.hostname.replace(/^\[|\]$/g,'')
  const addresses=isIP(hostname) ? [{address:hostname}] : await lookup(hostname,{all:true})
  if(!addresses.length||addresses.some(item=>!publicAddress(item.address))) throw new Error('拒绝访问本地或内网图片地址')
  const response=await fetch(url,{redirect:'error',signal:AbortSignal.timeout(12_000)})
  const data=await boundedBody(response,8*1024*1024)
  const image=nativeImage.createFromBuffer(data)
  if(image.isEmpty()) throw new Error('不是有效图片')
  const dimensions=image.getSize()
  if(dimensions.width>8000||dimensions.height>8000) throw new Error('图片尺寸过大')
  const scaled=dimensions.width>1600 ? image.resize({width:1600}) : image
  const dir=join(app.getPath('userData'),'online-assets'); mkdirSync(dir,{recursive:true})
  const path=join(dir,`${randomUUID()}.jpg`); writeFileSync(path,scaled.toJPEG(90)); return path
}
function expire() { for(const [id,item] of candidates) if(item.expires<Date.now()) { if(item.candidate.imagePath) { try{unlinkSync(item.candidate.imagePath)}catch{} }; candidates.delete(id) } }
export function registerAssetHandlers(db: Database.Database): void {
  ipcMain.handle('assets:getConfig',()=>({success:true,data:status(db)}))
  ipcMain.handle('assets:saveConfig',(_event,input:AssetConfig)=>{
    try {
      const old=stored(db)
      const next={...defaults} as StoredConfig
      for(const field of ['lyricsUrl','coverUrl','backgroundUrl'] as const) { const value=String(input[field]||'').trim(); if(value) validateApiUrl(value); next[field]=value }
      if(old.secret&&!input.apiKey&&(['lyricsUrl','coverUrl','backgroundUrl'] as const).some(field=>next[field]!==old[field])) throw new Error('修改自定义地址时请重新填写密钥')
      next.secret=input.apiKey?.trim()?encryptSecret(input.apiKey.trim()):old.secret||''
      next.audioDbSecret=input.audioDbKey?.trim()?encryptSecret(input.audioDbKey.trim()):old.audioDbSecret||''
      db.prepare('INSERT OR REPLACE INTO settings VALUES (?, ?)').run('asset_config',JSON.stringify(next))
      return {success:true,data:status(db)}
    } catch(error) { return {success:false,error:error instanceof Error?error.message:'保存失败'} }
  })
  ipcMain.handle('assets:search',async (_event,songId:string,kind:AssetKind,query:{title:string;artist:string;album:string})=>{
    try {
      expire()
      if(!['lyrics','cover','background'].includes(kind)) throw new Error('不支持的资源类型')
      if(!db.prepare('SELECT 1 FROM songs WHERE id = ?').get(songId)) throw new Error('歌曲不存在')
      const title=String(query?.title||'').trim().slice(0,200), artist=String(query?.artist||'').trim().slice(0,200), album=String(query?.album||'').trim().slice(0,200)
      if(!title&&!artist&&!album) throw new Error('请填写检索信息')
      const config=stored(db)
      const custom=config[kind==='lyrics'?'lyricsUrl':kind==='cover'?'coverUrl':'backgroundUrl']
      let url:URL; let source:string
      const headers:Record<string,string>={'User-Agent':'AuroraMusic/1.2 (personal desktop music player)'}
      if(custom) {
        url=validateApiUrl(custom); url.search=new URLSearchParams({title,artist,album,kind}).toString(); source='自定义 API'
        const key=decryptSecret(config.secret||''); if(key) headers.Authorization=`Bearer ${key}`
      } else if(kind==='lyrics') {
        url=new URL('https://lrclib.net/api/search'); url.search=new URLSearchParams({track_name:title,artist_name:artist,album_name:album}).toString(); source='LRCLIB'
      } else {
        const key=decryptSecret(config.audioDbSecret||'')||'123'
        url=new URL(`https://www.theaudiodb.com/api/v1/json/${encodeURIComponent(key)}/${kind==='cover'?'searchalbum.php':'search.php'}`)
        url.search=new URLSearchParams(kind==='cover'?{s:artist,a:album}:{s:artist}).toString(); source='TheAudioDB'
      }
      const payload=JSON.parse((await boundedBody(await fetch(url,{headers,redirect:'error',signal:AbortSignal.timeout(15_000)}),2*1024*1024)).toString('utf8'))
      const rows=custom ? (Array.isArray(payload)?payload:payload.results) : kind==='lyrics'?payload:kind==='cover'?payload.album:payload.artists
      if(!Array.isArray(rows)) return {success:true,data:[]}
      const results:AssetCandidate[]=[]
      for(const row of rows.slice(0,8)) {
        const text=String(row.syncedLyrics||row.plainLyrics||row.lyrics||'').slice(0,100_000)
        const imageUrl=kind==='cover'?(row.imageUrl||row.strAlbumThumb):(row.imageUrl||row.strArtistFanart||row.strArtistFanart2)
        if(kind==='lyrics'&&!text || kind!=='lyrics'&&!imageUrl) continue
        const candidate:AssetCandidate={id:randomUUID(),title:String(row.trackName||row.title||row.strAlbum||title).slice(0,200),artist:String(row.artistName||row.artist||row.strArtist||artist).slice(0,200),album:String(row.albumName||row.album||album).slice(0,200),source,sourceUrl:custom?new URL(custom).origin:source==='LRCLIB'?'https://lrclib.net':'https://www.theaudiodb.com'}
        if(kind==='lyrics') {candidate.text=text;candidate.synced=/\[\d+:\d+/.test(text)}
        else {try{candidate.imagePath=await downloadImage(String(imageUrl))}catch{continue}}
        candidates.set(candidate.id,{songId,kind,candidate,expires:Date.now()+10*60_000});results.push(candidate)
      }
      return {success:true,data:results}
    } catch(error) { return {success:false,error:error instanceof Error&&/HTTP \d+|过大|检索|不支持/.test(error.message)?error.message:'检索失败：请检查接口地址、网络和密钥；已有资源未改动。'} }
  })
  ipcMain.handle('assets:apply',(_event,songId:string,kind:AssetKind,candidateId:string)=>{
    try {
      expire();const item=candidates.get(candidateId)
      if(!item||item.songId!==songId||item.kind!==kind) throw new Error('预览已过期，请重新搜索')
      const column=kind==='lyrics'?'customLyrics':kind==='cover'?'coverPath':'customArtistImage'
      const value=kind==='lyrics'?item.candidate.text:item.candidate.imagePath
      if(!value||kind!=='lyrics'&&!existsSync(value)) throw new Error('预览资源不可用')
      db.prepare(`UPDATE songs SET ${column} = ? WHERE id = ?`).run(value,songId)
      candidates.delete(candidateId)
      return {success:true,data:db.prepare('SELECT * FROM songs WHERE id = ?').get(songId) as Song}
    } catch(error) { return {success:false,error:error instanceof Error?error.message:'应用失败'} }
  })
}
