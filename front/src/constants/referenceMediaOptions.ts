// Keep reference dropdowns aligned with the server contract (McpBusinessSchemasTest).
export const REFERENCE_IMAGE_USAGE_OPTIONS = [
  {
    "value": "SUBJECT",
    "label": "主体"
  },
  {
    "value": "NON_SUBJECT",
    "label": "非主体/画外人物"
  },
  {
    "value": "SCENE",
    "label": "场景"
  },
  {
    "value": "PROP",
    "label": "道具"
  },
  {
    "value": "STYLE",
    "label": "风格"
  },
  {
    "value": "FIRST_FRAME",
    "label": "首帧"
  },
  {
    "value": "KEYFRAME",
    "label": "过程关键帧"
  },
  {
    "value": "LAST_FRAME",
    "label": "尾帧"
  },
  {
    "value": "EDITED_KEYFRAME",
    "label": "编辑后关键帧"
  },
  {
    "value": "COMPOSITION_ANCHOR",
    "label": "构图锚点"
  },
  {
    "value": "STORYBOARD_REFERENCE",
    "label": "故事板参考"
  },
  {
    "value": "SHOT_PLANNING_REFERENCE",
    "label": "镜头规划参考"
  }
] as const

export const REFERENCE_AUDIO_USAGE_OPTIONS = [
  {
    "value": "VOICE_TIMBRE",
    "label": "音色参考"
  },
  {
    "value": "DIALOGUE_REUSE",
    "label": "对白复用"
  },
  {
    "value": "BGM_REUSE",
    "label": "背景音乐复用"
  },
  {
    "value": "BGM_STYLE",
    "label": "音乐风格参考"
  },
  {
    "value": "SFX_REUSE",
    "label": "音效复用"
  },
  {
    "value": "SFX_REFERENCE",
    "label": "音效质感参考"
  },
  {
    "value": "RHYTHM_REFERENCE",
    "label": "节拍参考"
  }
] as const

export const REFERENCE_AUDIO_LANGUAGE_OPTIONS = [
  {
    "value": "zh",
    "label": "中文"
  },
  {
    "value": "en",
    "label": "英文"
  },
  {
    "value": "ja",
    "label": "日文"
  },
  {
    "value": "other",
    "label": "其他"
  }
] as const

export function referenceImageUsageOptions(sourceType: string) {
  if (sourceType === 'KEYFRAME') return REFERENCE_IMAGE_USAGE_OPTIONS.filter(option =>
    !['SUBJECT', 'NON_SUBJECT', 'SCENE', 'PROP', 'STYLE'].includes(option.value))
  return REFERENCE_IMAGE_USAGE_OPTIONS.filter(option => option.value !== 'NON_SUBJECT' ||
    ['CHARACTER', 'CHARACTER_LOOK', 'CHARACTER_REFERENCE', 'UPLOAD'].includes(sourceType))
}
