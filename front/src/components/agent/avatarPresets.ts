import type { AgentPet, AvatarState } from '@/types/agent'
const base = `${import.meta.env.BASE_URL}agent/freyja/`
export const freyjaDefaultPet: AgentPet = {
  id: 'freyja', displayName: 'FREYJA', description: '银白发、紫金花饰的 Q 版创作助手',
  spritesheetUrl: `${base}spritesheet.webp`, contentType: 'image/webp', spriteVersionNumber: 1,
  frameWidth: 192, frameHeight: 208, columns: 8, rows: 9
}
export const freyjaPetDownload = `${base}freyja.codex-pet.zip`
export const petPlaybackRate = 0.5
export const petRows = [
  { name: 'idle', label: '待机', durations: [280, 110, 110, 140, 140, 320], loopPauseMs: 1000 },
  { name: 'running-right', label: '向右移动', durations: [120, 120, 120, 120, 120, 120, 120, 220] },
  { name: 'running-left', label: '向左移动', durations: [120, 120, 120, 120, 120, 120, 120, 220] },
  { name: 'waving', label: '招手', durations: [140, 140, 140, 280] },
  { name: 'jumping', label: '完成', durations: [140, 140, 140, 140, 280] },
  { name: 'failed', label: '遇到问题', durations: [140, 140, 140, 140, 140, 140, 140, 240] },
  { name: 'waiting', label: '等待处理', durations: [150, 150, 150, 150, 150, 260] },
  { name: 'running', label: '工作', durations: [120, 120, 120, 120, 120, 220] },
  { name: 'review', label: '思考', durations: [150, 150, 150, 150, 150, 280] }
]
export const avatarRows: Record<AvatarState, number> = { IDLE: 0, THINKING: 8, WORKING: 7, SUCCESS: 4, ATTENTION: 6, ERROR: 5 }
