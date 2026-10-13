import type { SceneIntent, Song } from '../types/music'
import { candidateWarnings, diversify, rankCandidates, type BehaviorModel } from './recommendation'

type Ranked = ReturnType<typeof rankCandidates>['ranked']
const compare = (a: Ranked[number], b: Ranked[number]) => b.score - a.score || a.song.id.localeCompare(b.song.id)
const norm = (value: string) => value.normalize('NFKC').trim().toLowerCase()

/** Visits the complete library with bounded retained metadata; never truncates to its first page. */
export class CandidateStream {
  private general: Ranked = []
  private named: Ranked = []
  private explicitArtistSeen = false
  private eligible = 0
  private namedEligible = 0
  private readonly capacity: number
  constructor(private model: BehaviorModel, private prompt: string, private intent: SceneIntent, private limit = 240) {
    this.limit = Math.max(1, Math.min(240, Math.floor(limit)))
    this.capacity = this.limit * 4
  }
  accept(page: Song[]) {
    const batch = rankCandidates(page, this.model, this.prompt, this.intent)
    this.eligible += batch.ranked.length
    const names = new Set(batch.namedArtists.map(norm))
    this.explicitArtistSeen ||= names.size > 0
    const explicit = batch.ranked.filter(item => names.has(norm(item.song.artist)))
    this.namedEligible += explicit.length
    this.general = this.explicitArtistSeen ? [] : [...this.general, ...batch.ranked].sort(compare).slice(0, this.capacity)
    this.named = [...this.named, ...explicit].sort(compare).slice(0, this.capacity)
  }
  get retainedCount() { return this.general.length + this.named.length }
  finish() {
    const candidates = diversify(this.explicitArtistSeen ? this.named : this.general, this.limit).map(item => item.song)
    return { candidates, eligibleSize: this.explicitArtistSeen ? this.namedEligible : this.eligible, warnings: candidateWarnings(candidates, this.intent) }
  }
}
