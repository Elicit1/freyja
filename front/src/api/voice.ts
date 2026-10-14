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
  }

}
