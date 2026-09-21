import type { ReactNode } from 'react'

export type IconName =
  | 'home' | 'browse' | 'library' | 'playlist' | 'heart' | 'settings' | 'search'
  | 'refresh' | 'back' | 'play' | 'pause' | 'prev' | 'next' | 'music' | 'trash'
  | 'eye' | 'eyeoff' | 'shuffle' | 'repeat' | 'sequence' | 'plus' | 'queue'
  | 'sparkles' | 'album' | 'artist' | 'clock' | 'cloud' | 'check' | 'close'
  | 'volume' | 'chevron' | 'grid' | 'list' | 'lyrics' | 'download'

const PATHS: Record<IconName, ReactNode> = {
  home: <><path d="M3 11.5 12 4l9 7.5"/><path d="M5.5 10v10h13V10M9 20v-6h6v6"/></>,
  browse: <><path d="M3 8h7l2 2h9v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M3 8V6a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v2"/></>,
  library: <><path d="M4 4v16M8 4v16"/><path d="m12 5 7-2 2 16-7 2z"/></>,
  playlist: <><path d="M4 6h11M4 11h11M4 16h7"/><path d="M18 14v7M15 18h6"/></>,
  heart: <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8z"/>,
  settings: <><circle cx="12" cy="12" r="3"/><path d="M19 15a2 2 0 0 0 .4 2l-2.4 2.4a2 2 0 0 0-2-.4 2 2 0 0 0-1 2h-4a2 2 0 0 0-1-2 2 2 0 0 0-2 .4L4.6 17A2 2 0 0 0 5 15a2 2 0 0 0-2-1v-4a2 2 0 0 0 2-1 2 2 0 0 0-.4-2L7 4.6A2 2 0 0 0 9 5a2 2 0 0 0 1-2h4a2 2 0 0 0 1 2 2 2 0 0 0 2-.4L19.4 7A2 2 0 0 0 19 9a2 2 0 0 0 2 1v4a2 2 0 0 0-2 1z"/></>,
  search: <><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></>,
  refresh: <><path d="M20 6v5h-5"/><path d="M19 11a7 7 0 1 0-1 6"/></>,
  back: <path d="m15 18-6-6 6-6"/>,
  play: <path d="m8 5 11 7-11 7z" fill="currentColor" stroke="none"/>,
  pause: <><path d="M9 5v14M15 5v14"/></>,
  prev: <><path d="M6 5v14"/><path d="m18 6-9 6 9 6z" fill="currentColor" stroke="none"/></>,
  next: <><path d="M18 5v14"/><path d="m6 6 9 6-9 6z" fill="currentColor" stroke="none"/></>,
  music: <><path d="M9 18V5l11-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/></>,
  trash: <><path d="M4 7h16M9 7V4h6v3"/><path d="m6 7 1 14h10l1-14"/></>,
  eye: <><path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12"/><circle cx="12" cy="12" r="2.5"/></>,
  eyeoff: <><path d="m3 3 18 18"/><path d="M10.6 6.2A10 10 0 0 1 12 6c6.5 0 10 6 10 6a15 15 0 0 1-3 3.7M6.2 6.2C3.5 8 2 12 2 12s3.5 6 10 6a10 10 0 0 0 3-.4"/></>,
  shuffle: <><path d="M4 7h3c5 0 5 10 10 10h3"/><path d="m17 14 3 3-3 3M17 4l3 3-3 3"/><path d="M4 17h3c1.2 0 2.1-.6 2.9-1.5M14.2 8.5C15 7.6 15.8 7 17 7h3"/></>,
  repeat: <><path d="m17 2 4 4-4 4"/><path d="M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4"/><path d="M21 13v2a3 3 0 0 1-3 3H3"/></>,
  sequence: <><path d="M5 6h14M5 12h14M5 18h9"/><path d="m17 16 3 2-3 2"/></>,
  plus: <path d="M12 5v14M5 12h14"/>,
  queue: <><path d="M4 6h16M4 12h12M4 18h8"/><path d="m17 16 3 2-3 2"/></>,
  sparkles: <><path d="m12 3 1.5 4.5L18 9l-4.5 1.5L12 15l-1.5-4.5L6 9l4.5-1.5z"/><path d="m19 15 .8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z"/></>,
  album: <><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2"/><path d="M12 3v3"/></>,
  artist: <><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></>,
  clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></>,
  cloud: <path d="M17.5 19H6a4 4 0 0 1-.7-7.9A7 7 0 0 1 19 9.5 4.8 4.8 0 0 1 17.5 19z"/>,
  check: <path d="m5 12 4 4L19 6"/>,
  close: <path d="m6 6 12 12M18 6 6 18"/>,
  volume: <><path d="M11 5 6 9H3v6h3l5 4z"/><path d="M15 9a4 4 0 0 1 0 6M18 6a8 8 0 0 1 0 12"/></>,
  chevron: <path d="m9 18 6-6-6-6"/>,
  grid: <><rect x="4" y="4" width="6" height="6" rx="1"/><rect x="14" y="4" width="6" height="6" rx="1"/><rect x="4" y="14" width="6" height="6" rx="1"/><rect x="14" y="14" width="6" height="6" rx="1"/></>,
  list: <><path d="M8 6h12M8 12h12M8 18h12"/><circle cx="4" cy="6" r="1" fill="currentColor"/><circle cx="4" cy="12" r="1" fill="currentColor"/><circle cx="4" cy="18" r="1" fill="currentColor"/></>,
  lyrics: <><path d="M5 5h14M5 9h10M5 13h8"/><path d="M18 12v7M15 18h6"/></>,
  download: <><path d="M12 3v12M7 10l5 5 5-5"/><path d="M5 21h14"/></>,
}

export function Icon({ name, size = 20, filled = false }: { name: IconName; size?: number; filled?: boolean }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {PATHS[name]}
    </svg>
  )
}
