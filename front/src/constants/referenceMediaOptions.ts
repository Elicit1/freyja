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

export const REFERENCE_AUDIO_TYPE_OPTIONS = [
  {
    "value": "fully_copy",
    "label": "完整复用",
    "description": "将源音频完整作为目标视频最终音轨"
  },
  {
    "value": "partially_copy",
    "label": "部分复用",
    "description": "复用明确指定的时间段或声音层"
  },
  {
    "value": "reference",
    "label": "参考",
    "description": "不复制原始信号，参考明确指定的声音特征"
  },
  {
    "value": "weak_reference",
    "label": "弱参考",
    "description": "仅保留类别或氛围上的宽泛相似性"
  }
] as const

export type AudioReferenceType = typeof REFERENCE_AUDIO_TYPE_OPTIONS[number]['value']
export function audioReferenceTypeLabel(type?: string) {
  return REFERENCE_AUDIO_TYPE_OPTIONS.find(option => option.value === type)?.label || '请选择参考关系'
}

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
