import { useEffect, useState } from 'react'
import { audioEngine } from './audioEngine'

// Only the visible playback controls subscribe to the 250 ms progress updates.
export function usePlayback() {
  const [state, setState] = useState(() => audioEngine.getState())
  useEffect(() => audioEngine.onStateChange(setState), [])
  return state
}
