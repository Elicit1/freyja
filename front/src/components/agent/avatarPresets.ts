import type { AgentPet, AvatarState } from '@/types/agent'
const base = `${import.meta.env.BASE_URL}agent/freyja/`
export const freyjaDefaultPet: AgentPet = {
  id: 'freyja', displayName: 'FREYJA', description: '银白发、紫金花饰的 Q 版创作助手',
  spritesheetUrl: `${base}spritesheet.webp`, contentType: 'image/webp', spriteVersionNumber: 1,
  frameWidth: 192, frameHeight: 208, columns: 8, rows: 9
}
export const freyjaPetDownload = `${base}freyja-smooth.codex-pet.zip`
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

export interface PetAnimation { durations: number[]; frames?: number[]; loopPauseMs?: number }
export interface PetAnimationSource {
  spritesheetUrl: string; columns: number; rows: number; smooth: boolean; animations: PetAnimation[]
}
const standardAnimations: PetAnimation[] = petRows.map(animation => ({
  durations: animation.durations.map(duration => duration / petPlaybackRate),
  loopPauseMs: animation.loopPauseMs
}))
// More poses change too rapidly at the community cadence; keep the default pet calm.
export const freyjaAnimationDurationScale = 4
// The built-in pet gets additional drawings. Community ZIPs retain Codex's standard grid/timing.
const smoothAnimations: PetAnimation[] = standardAnimations.map((animation, index) => {
  const cycle = petCycleMillis(animation) * freyjaAnimationDurationScale
  const pause = index === 0 ? 1600 * freyjaAnimationDurationScale : 0
  // Gentle gestures reverse through the in-between poses, avoiding a hard last-to-first cut.
  const frames = Array.from({ length: 16 }, (_, frame) => frame)
  if (index !== 1 && index !== 2) frames.push(...Array.from({ length: 14 }, (_, frame) => 14 - frame))
  return { frames, durations: frames.map(() => (cycle - pause) / frames.length), loopPauseMs: pause }
})
export function petAnimationSource(pet: AgentPet, smoothAvailable = true): PetAnimationSource {
  const builtin = pet.spritesheetUrl === freyjaDefaultPet.spritesheetUrl
  return builtin && smoothAvailable
    ? { spritesheetUrl: `${base}spritesheet-smooth-v2.webp`, columns: 16, rows: 9, smooth: true, animations: smoothAnimations }
    : { spritesheetUrl: pet.spritesheetUrl, columns: pet.columns, rows: pet.rows, smooth: false, animations: standardAnimations }
}
export function petCycleMillis(animation: PetAnimation): number {
  return animation.durations.reduce((total, duration) => total + duration, 0) + (animation.loopPauseMs || 0)
}
/** Sample elapsed active time instead of accumulating timeout drift or replaying missed frames. */
export function petFrameAt(animation: PetAnimation, elapsed: number): number {
  let position = Math.max(0, elapsed) % petCycleMillis(animation)
  for (let frame = 0; frame < animation.durations.length; frame++) {
    if (position < animation.durations[frame]!) return animation.frames?.[frame] ?? frame
    position -= animation.durations[frame]!
  }
  return animation.frames?.at(-1) ?? animation.durations.length - 1 // Hold the settled drawing during the loop pause.
}
