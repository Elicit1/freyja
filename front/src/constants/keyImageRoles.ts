export const KEY_IMAGE_ROLE_OPTIONS = [
  { value: 'FIRST_FRAME', label: '首帧（具体画面）', group: 'Concrete Frame' },
  { value: 'KEYFRAME', label: '过程关键帧（具体画面）', group: 'Concrete Frame' },
  { value: 'LAST_FRAME', label: '尾帧（具体画面）', group: 'Concrete Frame' },
  { value: 'EDITED_KEYFRAME', label: '编辑后关键帧（具体画面）', group: 'Concrete Frame' },
  { value: 'COMPOSITION_ANCHOR', label: '构图锚点（规划参考）', group: 'Planning / Reference' },
  { value: 'STORYBOARD_REFERENCE', label: '故事板参考（规划参考）', group: 'Planning / Reference' },
  { value: 'SHOT_PLANNING_REFERENCE', label: '镜头规划参考（规划参考）', group: 'Planning / Reference' }
] as const

export type KeyImageRole = typeof KEY_IMAGE_ROLE_OPTIONS[number]['value']

export function normalizeKeyImageRole(value?: string): KeyImageRole | undefined {
  return KEY_IMAGE_ROLE_OPTIONS.find(option => option.value === value)?.value
}

export function requireKeyImageRole(value: string): KeyImageRole {
  const role = normalizeKeyImageRole(value)
  if (!role) throw new Error(`不支持的关键图用途: ${value}`)
  return role
}

export function keyImageRoleLabel(value?: string): string {
  return KEY_IMAGE_ROLE_OPTIONS.find(option => option.value === normalizeKeyImageRole(value))?.label || value || '未指定'
}
