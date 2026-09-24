import { ref, computed, type Ref } from 'vue'
import type { CharacterShotRefInfo, PropShotRefInfo, ShotRefImage, ShotRefAudio } from '@/types/drama'
import type { ResCharacterOption, ResSceneOption, ResPropOption } from '@/types/resource'

export interface ShotPromptDraft {
  characterRefs: CharacterShotRefInfo[]
  propRefs: PropShotRefInfo[]
  resSceneId?: string | number
  refImages: ShotRefImage[]
  refAudios: ShotRefAudio[]
}

export interface CandidateImageItem {
  id: string
  name: string
  imageUrl: string
  sourceType: 'CHARACTER_REFERENCE' | 'CHARACTER' | 'SCENE' | 'PROP' | 'UPLOAD'
  sourceId?: string | number
  characterId?: string | number
  lookId?: string | number
  referenceRole?: 'IDENTITY' | 'LOOK' | 'COMBINED' | 'POSE' | 'STYLE' | string
  usageRole: 'SUBJECT' | 'SCENE' | 'PROP' | 'FIRST_FRAME' | 'END_FRAME' | string
  tag: string
  assetName: string
  assetType: 'CHARACTER' | 'SCENE' | 'PROP' | 'UPLOAD'
  isOccupied?: boolean
  occupiedSlotIndex?: number
}

export interface CandidateAudioItem {
  id: string
  characterId?: string | number
  characterName?: string
  name: string
  audioUrl: string
  text?: string
  duration: number
  sourceType: 'CHARACTER' | 'UPLOAD' | 'TTS'
  usageMode: 'VOICE_TIMBRE' | 'DIALOGUE_REUSE' | string
  language: string
  tag: string
  isOccupied?: boolean
  occupiedSlotIndex?: number
}

export const MAX_REF_IMAGES = 9
export const MAX_REF_AUDIOS = 3
export const MIN_AUDIO_DURATION = 2.0
export const MAX_AUDIO_DURATION = 15.0
export const MAX_TOTAL_AUDIO_DURATION = 15.0

export function useShotReferenceAssets(
  draft: Ref<ShotPromptDraft>,
  options: {
    characterOptions: Ref<ResCharacterOption[]>
    sceneOptions: Ref<ResSceneOption[]>
    propOptions: Ref<ResPropOption[]>
  }
) {
  // 1. 候选图片池 (根据 draft.characterRefs、draft.resSceneId、draft.propRefs 与已有 UPLOAD 提取)
  const candidateImages = computed<CandidateImageItem[]>(() => {
    const list: CandidateImageItem[] = []
    const seen = new Set<string>()

    const pushImage = (item: CandidateImageItem) => {
      const key = `${item.imageUrl}|${item.sourceType}|${item.sourceId || ''}`
      if (seen.has(key)) return
      seen.add(key)

      // 检查是否已被占用
      const occIdx = draft.value.refImages.findIndex(
        img => img.imageUrl === item.imageUrl && img.sourceType === item.sourceType && String(img.sourceId || '') === String(item.sourceId || '')
      )
      list.push({
        ...item,
        isOccupied: occIdx >= 0,
        occupiedSlotIndex: occIdx >= 0 ? occIdx : undefined
      })
    }

    // A. 选中的出场角色 (单参考图决策：选中的造型若有参考图则独占进入 COMBINED，否则回退至人物身份参考图 IDENTITY)
    for (const charRef of draft.value.characterRefs) {
      if (!charRef.characterId) continue
      const char = options.characterOptions.value.find(c => String(c.id) === String(charRef.characterId))
      if (!char) continue

      // 寻找当前选中的造型（或默认造型）
      const selectedOutfit = char.outfits?.find(o =>
        (charRef.lookId && String(charRef.lookId) === String(o.id)) ||
        (!charRef.lookId && o.isDefault === 1)
      )
      const outfitImg = selectedOutfit?.referenceImageUrl

      if (selectedOutfit && outfitImg) {
        // 造型图自包含人设与服装，独占进入
        pushImage({
          id: `cand_char_outfit_${selectedOutfit.id}`,
          name: `${char.name} - ${selectedOutfit.lookName}`,
          imageUrl: outfitImg,
          sourceType: 'CHARACTER_REFERENCE',
          sourceId: String(selectedOutfit.id),
          characterId: String(char.id),
          lookId: String(selectedOutfit.id),
          referenceRole: 'COMBINED',
          usageRole: 'SUBJECT',
          tag: '造型参考图',
          assetName: char.name,
          assetType: 'CHARACTER'
        })
      } else {
        // Fallback 到人物稳定身份参考图
        const identityImg = char.referenceImageUrl || char.avatarUrl
        if (identityImg) {
          pushImage({
            id: `cand_char_identity_${char.id}`,
            name: `${char.name} (身份参考图)`,
            imageUrl: identityImg,
            sourceType: 'CHARACTER',
            sourceId: String(char.id),
            characterId: String(char.id),
            referenceRole: 'IDENTITY',
            usageRole: 'SUBJECT',
            tag: '人物设定图',
            assetName: char.name,
            assetType: 'CHARACTER'
          })
        }
      }
    }

    // B. 选中的环境场景 (空间参考图优先、场景封面)
    if (draft.value.resSceneId) {
      const sc = options.sceneOptions.value.find(s => String(s.id) === String(draft.value.resSceneId))
      if (sc) {
        // 空间参考图优先
        if (sc.referenceImageUrl) {
          pushImage({
            id: `cand_scene_ref_${sc.id}`,
            name: `${sc.name} (空间参考图)`,
            imageUrl: sc.referenceImageUrl,
            sourceType: 'SCENE',
            sourceId: String(sc.id),
            usageRole: 'SCENE',
            tag: '空间参考图',
            assetName: sc.name,
            assetType: 'SCENE'
          })
        }
        // 场景封面
        if (sc.coverUrl && sc.coverUrl !== sc.referenceImageUrl) {
          pushImage({
            id: `cand_scene_cover_${sc.id}`,
            name: `${sc.name} (场景封面)`,
            imageUrl: sc.coverUrl,
            sourceType: 'SCENE',
            sourceId: String(sc.id),
            usageRole: 'SCENE',
            tag: '场景封面',
            assetName: sc.name,
            assetType: 'SCENE'
          })
        }
      }
    }

    // C. 选中的关键道具 (封面图 / 设计图)
    for (const propRef of draft.value.propRefs) {
      if (!propRef.propId) continue
      const pr = options.propOptions.value.find(p => String(p.id) === String(propRef.propId))
      const cover = pr?.coverUrl || propRef.coverUrl
      const pName = pr?.name || propRef.propName || `道具#${propRef.propId}`
      if (cover) {
        pushImage({
          id: `cand_prop_${pr?.id || propRef.propId}`,
          name: `${pName} (道具设计图)`,
          imageUrl: cover,
          sourceType: 'PROP',
          sourceId: String(pr?.id || propRef.propId),
          usageRole: 'PROP',
          tag: '道具设计图',
          assetName: pName,
          assetType: 'PROP'
        })
      }
    }

    // D. 保留草稿中已存在的自定义上传图片 (sourceType === 'UPLOAD')
    for (const img of draft.value.refImages) {
      if (img.sourceType === 'UPLOAD' && img.imageUrl) {
        pushImage({
          id: img.id || `cand_upload_${img.imageUrl}`,
          name: img.name || '本地上传图片',
          imageUrl: img.imageUrl,
          sourceType: 'UPLOAD',
          usageRole: img.usageRole || 'SUBJECT',
          tag: '本地上传',
          assetName: img.name || '本地上传',
          assetType: 'UPLOAD'
        })
      }
    }

    return list
  })

  // 2. 候选音频池 (仅从已选人物提取专属母音样本 + 已存在的上传音频)
  const candidateAudios = computed<CandidateAudioItem[]>(() => {
    const list: CandidateAudioItem[] = []
    const seen = new Set<string>()

    const pushAudio = (item: CandidateAudioItem) => {
      if (seen.has(item.audioUrl)) return
      seen.add(item.audioUrl)

      const occIdx = draft.value.refAudios.findIndex(a => a.audioUrl === item.audioUrl)
      list.push({
        ...item,
        isOccupied: occIdx >= 0,
        occupiedSlotIndex: occIdx >= 0 ? occIdx : undefined
      })
    }

    // A. 已选人物的专属声音样本 (默认用途: VOICE_TIMBRE)
    for (const charRef of draft.value.characterRefs) {
      if (!charRef.characterId) continue
      const char = options.characterOptions.value.find(c => String(c.id) === String(charRef.characterId))
      if (char && char.voiceSampleUrl) {
        pushAudio({
          id: `cand_aud_char_${char.id}`,
          characterId: String(char.id),
          characterName: char.name,
          name: `${char.name} (角色母音)`,
          audioUrl: char.voiceSampleUrl,
          text: char.voiceSampleText || char.voiceDesc || '',
          duration: 3.5, // 默认估算时长，若有元数据后续自动更新
          sourceType: 'CHARACTER',
          usageMode: 'VOICE_TIMBRE', // 规避模型复用旧台词，默认音色参考
          language: 'zh',
          tag: '专属母音'
        })
      }
    }

    // B. 草稿中已存在的自定义上传音频
    for (const aud of draft.value.refAudios) {
      if (aud.sourceType === 'UPLOAD' && aud.audioUrl) {
        pushAudio({
          id: aud.id || `cand_upload_aud_${aud.audioUrl}`,
          characterName: aud.characterName,
          name: aud.name || '本地上传音频',
          audioUrl: aud.audioUrl,
          text: aud.text || '',
          duration: aud.duration || 3.0,
          sourceType: 'UPLOAD',
          usageMode: aud.usageMode || 'VOICE_TIMBRE',
          language: aud.language || 'zh',
          tag: '本地上传'
        })
      }
    }

    return list
  })

  // 3. 统计指标与物理规则校验
  const totalAudioDuration = computed(() => {
    return draft.value.refAudios.reduce((sum, a) => sum + (Number(a.duration) || 0), 0)
  })

  const validationErrors = computed<string[]>(() => {
    const errs: string[] = []

    if (draft.value.refImages.length > MAX_REF_IMAGES) {
      errs.push(`参考图片数量 (${draft.value.refImages.length}) 超过上限 ${MAX_REF_IMAGES} 张`)
    }

    if (draft.value.refAudios.length > MAX_REF_AUDIOS) {
      errs.push(`参考音频数量 (${draft.value.refAudios.length}) 超过上限 ${MAX_REF_AUDIOS} 段`)
    }

    if (totalAudioDuration.value > MAX_TOTAL_AUDIO_DURATION) {
      errs.push(`参考音频总时长 (${totalAudioDuration.value.toFixed(1)}s) 超过 ${MAX_TOTAL_AUDIO_DURATION} 秒上限`)
    }

    draft.value.refAudios.forEach((aud, idx) => {
      const dur = Number(aud.duration) || 0
      if (dur < MIN_AUDIO_DURATION || dur > MAX_AUDIO_DURATION) {
        errs.push(`音频 <Audio ${idx + 1}>「${aud.name || '音频'}」时长为 ${dur.toFixed(1)}s，官方要求在 2~15 秒之间`)
      }
    })

    if (draft.value.refAudios.length > 0 && draft.value.refImages.length === 0) {
      errs.push('存在参考音频时，MiniMax H3 规范要求必须至少配置 1 张参考图')
    }

    return errs
  })

  const validationWarnings = computed<string[]>(() => {
    const warns: string[] = []
    if (draft.value.refImages.length === 0 && draft.value.refAudios.length === 0) {
      warns.push('尚未编排任何参考图或音频槽位，模型将基于纯文本剧本与文字设定生成。')
    }
    return warns
  })

  // 4. 槽位操作方法
  function addRefImage(cand: CandidateImageItem): boolean {
    if (draft.value.refImages.length >= MAX_REF_IMAGES) return false
    // 去重检查
    const exists = draft.value.refImages.some(
      img => img.imageUrl === cand.imageUrl && img.sourceType === cand.sourceType && String(img.sourceId || '') === String(cand.sourceId || '')
    )
    if (exists) return false

    draft.value.refImages.push({
      id: `img_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
      name: cand.name,
      imageUrl: cand.imageUrl,
      sourceType: cand.sourceType,
      sourceId: cand.sourceId,
      characterId: cand.characterId,
      lookId: cand.lookId,
      referenceRole: cand.referenceRole,
      usageRole: cand.usageRole
    })
    return true
  }

  function replaceRefImage(index: number, cand: CandidateImageItem): boolean {
    if (index < 0 || index >= draft.value.refImages.length) return false
    draft.value.refImages[index] = {
      id: `img_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
      name: cand.name,
      imageUrl: cand.imageUrl,
      sourceType: cand.sourceType,
      sourceId: cand.sourceId,
      characterId: cand.characterId,
      lookId: cand.lookId,
      referenceRole: cand.referenceRole,
      usageRole: cand.usageRole
    }
    return true
  }

  function removeRefImage(index: number) {
    if (index >= 0 && index < draft.value.refImages.length) {
      draft.value.refImages.splice(index, 1)
    }
  }

  function moveRefImage(fromIdx: number, toIdx: number) {
    if (fromIdx < 0 || fromIdx >= draft.value.refImages.length) return
    if (toIdx < 0 || toIdx >= draft.value.refImages.length) return
    const [moved] = draft.value.refImages.splice(fromIdx, 1)
    draft.value.refImages.splice(toIdx, 0, moved)
  }

  function addRefAudio(cand: CandidateAudioItem): boolean {
    if (draft.value.refAudios.length >= MAX_REF_AUDIOS) return false
    const exists = draft.value.refAudios.some(a => a.audioUrl === cand.audioUrl)
    if (exists) return false

    draft.value.refAudios.push({
      id: `aud_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
      name: cand.name,
      audioUrl: cand.audioUrl,
      sourceType: cand.sourceType,
      characterId: cand.characterId,
      characterName: cand.characterName,
      text: cand.text,
      duration: cand.duration,
      usageMode: cand.usageMode || 'VOICE_TIMBRE',
      language: cand.language || 'zh'
    })
    return true
  }

  function replaceRefAudio(index: number, cand: CandidateAudioItem): boolean {
    if (index < 0 || index >= draft.value.refAudios.length) return false
    draft.value.refAudios[index] = {
      id: `aud_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
      name: cand.name,
      audioUrl: cand.audioUrl,
      sourceType: cand.sourceType,
      characterId: cand.characterId,
      characterName: cand.characterName,
      text: cand.text,
      duration: cand.duration,
      usageMode: cand.usageMode || 'VOICE_TIMBRE',
      language: cand.language || 'zh'
    }
    return true
  }

  function removeRefAudio(index: number) {
    if (index >= 0 && index < draft.value.refAudios.length) {
      draft.value.refAudios.splice(index, 1)
    }
  }

  function moveRefAudio(fromIdx: number, toIdx: number) {
    if (fromIdx < 0 || fromIdx >= draft.value.refAudios.length) return
    if (toIdx < 0 || toIdx >= draft.value.refAudios.length) return
    const [moved] = draft.value.refAudios.splice(fromIdx, 1)
    draft.value.refAudios.splice(toIdx, 0, moved)
  }

  // 5. AI 素材快照与失效保护 (Staleness Tracking)
  const aiGeneratedFingerprint = ref<string | null>(null)

  function computeDraftFingerprint(): string {
    const chars = draft.value.characterRefs.map(c => `${c.characterId}:${c.lookId || ''}`).sort().join(';')
    const scene = String(draft.value.resSceneId || '')
    const props = draft.value.propRefs.map(p => String(p.propId || '')).sort().join(';')
    const imgs = draft.value.refImages.map(i => `${i.imageUrl}|${i.usageRole || ''}`).join(';')
    const auds = draft.value.refAudios.map(a => `${a.audioUrl}|${a.usageMode || ''}`).join(';')
    return `${chars}__${scene}__${props}__${imgs}__${auds}`
  }

  const isResultStale = computed(() => {
    if (!aiGeneratedFingerprint.value) return false
    return aiGeneratedFingerprint.value !== computeDraftFingerprint()
  })

  function recordAiGeneratedSnapshot() {
    aiGeneratedFingerprint.value = computeDraftFingerprint()
  }

  function clearAiGeneratedSnapshot() {
    aiGeneratedFingerprint.value = null
  }

  return {
    candidateImages,
    candidateAudios,
    totalAudioDuration,
    validationErrors,
    validationWarnings,
    addRefImage,
    replaceRefImage,
    removeRefImage,
    moveRefImage,
    addRefAudio,
    replaceRefAudio,
    removeRefAudio,
    moveRefAudio,
    isResultStale,
    recordAiGeneratedSnapshot,
    clearAiGeneratedSnapshot
  }
}
