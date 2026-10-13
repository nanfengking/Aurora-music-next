import { CandidateStream } from '../../src/services/candidateStream'
import { generateHybridPlaylistCore, type HybridRequest } from '../../src/services/hybridPlaylist'
import { getLocale } from './i18n'
import { mobileMusic } from './native'

export async function generateMobilePlaylist(prompt: string, useAi: boolean, offlineOnly: boolean, signal: AbortSignal, onProgress: (count: number, total: number) => void) {
  const check = () => { if (signal.aborted) throw new DOMException('Cancelled', 'AbortError') }
  const [overview, providers, first] = await Promise.all([
    mobileMusic.overview(), useAi ? mobileMusic.aiProviders() : Promise.resolve({ providers: [] }),
    mobileMusic.catalogPage({ cursor: 0, offlineOnly }),
  ])
  check()
  const requestJson: HybridRequest = async (stage, system, user, validate, deadline) => {
    check()
    const reply = await mobileMusic.requestAi({ stage, system, user, timeoutMillis: Math.max(1, deadline - Date.now()) })
    check(); return { data: validate(reply.value), providerName: reply.providerName, attempts: reply.attempts }
  }
  return generateHybridPlaylistCore({ songs: overview.songs, history: overview.history, prompt, useAi,
    librarySize: first.total, responseLanguage: getLocale(), requestJson,
    configured: providers.providers.some(provider => provider.enabled && provider.configured),
    retrieve: async (model, intent) => {
      const collector = new CandidateStream(model, prompt, intent)
      let page = first, count = 0
      for (;;) {
        check(); collector.accept(page.songs); count += page.songs.length; onProgress(count, first.total)
        if (!page.hasMore) break
        const previous = page.cursor
        page = await mobileMusic.catalogPage({ cursor: previous, ceiling: first.ceiling, offlineOnly })
        if (page.hasMore && page.cursor <= previous) throw new Error('Catalog pagination did not advance')
      }
      check(); return collector.finish()
    },
  })
}
