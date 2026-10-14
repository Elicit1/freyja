import type { ShotRefAudio } from '@/types/drama'
import type { ResCharacterOption } from '@/types/resource'

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

/** Build candidates from explicit references, without claiming they speak in this shot. */
export function characterVoiceReferences(characterIds: Array<string | number>, options: ResCharacterOption[]): ShotRefAudio[] {
  const selected = new Set(characterIds.map(String))
  const seenUrls = new Set<string>()
  return options.filter(character => selected.has(String(character.id)) && character.voiceSampleUrl).flatMap(character => {
    const audioUrl = character.voiceSampleUrl!
    if (seenUrls.has(audioUrl)) return []
    seenUrls.add(audioUrl)
    return [{ id: `aud_char_${character.id}`, sourceType: 'CHARACTER' as const,
      characterId: String(character.id), characterName: character.name, name: `${character.name}（音色参考）`,
      audioUrl, usageMode: 'VOICE_TIMBRE', text: character.voiceSampleText, duration: undefined }]
  })
}

export function initialVoiceReferences(configured: boolean | undefined, existing: ShotRefAudio[], characterIds: Array<string | number>, options: ResCharacterOption[], capacity: number): { audios: ShotRefAudio[]; requiresSelection: boolean } {
  if (configured === true || existing.length > 0) return { audios: existing.map(audio => ({ ...audio })), requiresSelection: false }
  const candidates = characterVoiceReferences(characterIds, options)
  // Never silently choose the first N speakers or rewrite a saved empty choice.
  return candidates.length > capacity ? { audios: [], requiresSelection: true } : { audios: candidates, requiresSelection: false }
}
