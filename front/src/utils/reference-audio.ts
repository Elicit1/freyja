
const AUDIO_METADATA_TIMEOUT_MS = 10_000

/** Browser metadata only; failed probes never invent a duration. */
export function probeReferenceAudioDuration(url: string): Promise<number | undefined> {
  return new Promise(resolve => {
    const audio = new Audio()
    let settled = false
    const finish = (duration?: number) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      audio.onloadedmetadata = null
      audio.onerror = null
      audio.removeAttribute('src')
      audio.load()
      resolve(duration)
    }
    const timer = setTimeout(() => finish(), AUDIO_METADATA_TIMEOUT_MS)
    audio.preload = 'metadata'
    audio.onloadedmetadata = () => finish(Number.isFinite(audio.duration) && audio.duration > 0 ? audio.duration : undefined)
    audio.onerror = () => finish()
    audio.src = url
  })
}
