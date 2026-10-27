// Library categories map to H3 documented uses, not to its four retention markers.
export const AUDIO_KIND_OPTIONS = [
  { value: 'VOICE', label: '音色与表达', description: '参考说话人的音色、情绪、语气与表达方式。' },
  { value: 'DIALOGUE', label: '对白与旁白', description: '复用或参考原始对白、旁白；按镜头参考说明决定是否沿用原文。' },
  { value: 'LYRICS', label: '歌词与歌声', description: '参考或复用歌词、歌声；已知歌词可填写原语言文本。' },
  { value: 'MUSIC', label: '背景音乐', description: '参考音乐风格，或明确复用原音乐信号。' },
  { value: 'SOUND_EFFECT', label: '音效与环境声', description: '参考音效质感、物理声音或环境氛围。' },
  { value: 'RHYTHM', label: '节拍与节奏', description: '为动作、剪辑或声音提供节拍与节奏参考。' },
  { value: 'CONTINUITY', label: '声音连续性', description: '参考前后镜头声音的衔接与延续特征。' },
  { value: 'FULL_TRACK', label: '完整音轨', description: '包含多个声音层的整体音轨；完整复用要求作为最终视频的完整音轨。' }
] as const

export function audioKindLabel(kind?: string) {
  return AUDIO_KIND_OPTIONS.find(option => option.value === kind)?.label || '未分类'
}
