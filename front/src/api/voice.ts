import { runAiTaskResult } from './ai-task-stream'

export interface CharacterVoiceDesignDTO {
  characterId?: number | string
  providerId?: number | string
  modelCode?: string
  voiceDesc: string
  sampleText?: string
  autoSave?: boolean
}

export interface CharacterVoiceResultVO {
  characterId?: number | string
  characterName?: string
  voiceSampleUrl: string
  voiceDesc?: string
  sampleText?: string
  duration?: number
  modelCode?: string
}

export interface ShotVoiceGenerateDTO {
  shotId?: number | string
  characterId?: number | string
  customDialogue?: string
  emotion?: string
  referenceAudioUrl?: string
  providerId?: number | string
  modelCode?: string
  autoSave?: boolean
}

export interface ShotVoiceItemResult {
  shotId: number | string
  shotNo: number
  shotName: string
  speakerName?: string
  dialogue?: string
  audioUrl?: string
  duration?: number
  success: boolean
  message?: string
}

export interface BatchVoiceResultVO {
  episodeId: number | string
  totalShots: number
  matchedShots: number
  successCount: number
  failureCount: number
  skippedCount: number
  items: ShotVoiceItemResult[]
}

export const voiceApi = {
  /**
   * 为指定角色设计并固化专属母音样音 (MiMo-V2.5-TTS-VoiceDesign)
   */
  designCharacterVoice(characterId: number | string, data: CharacterVoiceDesignDTO) {
    return runAiTaskResult<CharacterVoiceResultVO>(`/ai/tasks/character/${String(characterId)}/voice-design`, data)
  },

  /**
   * 音色打样试听 (仅生成试听音频，不强制持久化至角色)
   */
  previewVoiceDesign(data: CharacterVoiceDesignDTO) {
    return runAiTaskResult<CharacterVoiceResultVO>('/ai/tasks/voice-preview', data)
  },

  /**
   * 单分镜台词声音克隆合成 (MiMo-V2.5-TTS-VoiceClone)
   */
  generateShotVoice(shotId: number | string, data?: ShotVoiceGenerateDTO) {
    return runAiTaskResult<ShotVoiceItemResult>(`/ai/tasks/shot/${String(shotId)}/voice`, data)
  },

  /**
   * 剧集一键整集批量分镜台词克隆配音
   */
  batchGenerateEpisodeVoice(episodeId: number | string) {
    return runAiTaskResult<BatchVoiceResultVO>(`/ai/tasks/episode/${String(episodeId)}/voice`)
  }
}
