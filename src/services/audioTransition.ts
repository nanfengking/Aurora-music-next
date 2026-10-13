import type { Howl } from 'howler'

/** At most one retiring HTML audio element. Never decode whole tracks into RAM. */
export class AudioTransition {
  private outgoing: Howl | null = null
  private timer: ReturnType<typeof setTimeout> | undefined

  retain(howl: Howl | null): void {
    this.clear()
    this.outgoing = howl
    // Release even when the next WebDAV request stalls.
    if (howl) this.timer = setTimeout(() => this.clear(), 8000)
  }

  start(incoming: Howl, volume: number, seconds: number): void {
    if (!this.outgoing || !seconds) {
      this.clear()
      incoming.volume(volume)
      return
    }
    clearTimeout(this.timer)
    const duration = seconds * 1000
    this.outgoing.fade(this.outgoing.volume(), 0, duration)
    incoming.fade(0, volume, duration)
    this.timer = setTimeout(() => this.clear(), duration)
  }

  clear(): void {
    clearTimeout(this.timer)
    this.outgoing?.unload()
    this.outgoing = null
  }
}
