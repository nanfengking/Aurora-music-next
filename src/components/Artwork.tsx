import { useState, type CSSProperties } from 'react'
import type { Song } from '../types/music'
import { Icon } from './Icon'

export function localAssetUrl(filePath: string): string {
  const encoded = filePath.replace(/\\/g, '/').split('/').map((part, index) =>
    index === 0 && /^[a-zA-Z]:$/.test(part) ? part : encodeURIComponent(part)).join('/').replace(/^\/+/, '')
  return `local-protocol://art/${encoded}`
}

export function Artwork({ song, size = 'medium', className = '' }: {
  song?: Song | null; size?: 'small' | 'medium' | 'large'; className?: string
}) {
  const [failedPath, setFailedPath] = useState<string | null>(null)
  const seed = song?.album || song?.artist || song?.title || 'Aurora'
  const hash = Array.from(seed).reduce((sum, char) => sum + char.charCodeAt(0), 0)
  const hasCover = Boolean(song?.coverPath && failedPath !== song.coverPath)
  return <div className={`artwork artwork-${size} ${hasCover ? 'has-cover' : 'no-cover'} ${className}`}
    style={{ '--cover-tone': [24, 155, 210, 290, 355][hash % 5] } as CSSProperties}>
    {hasCover ? <img src={localAssetUrl(song!.coverPath!)} alt="" draggable={false} loading="lazy" onError={() => setFailedPath(song!.coverPath)}/> : <>
      <div className="record-grooves"/><Icon name="music" size={size === 'large' ? 34 : 19}/>
      {size === 'large' && <span className="cover-caption">{song?.album || song?.artist || 'Aurora Music'}</span>}
    </>}
  </div>
}
