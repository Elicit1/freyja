export const H3_CONTROL_TYPES = [
  { value: 'CANNY', label: '边缘 Canny' },
  { value: 'DEPTH', label: '深度 Depth' },
  { value: 'HED', label: '软边缘 HED' },
  { value: 'MLSD', label: '直线 MLSD' },
  { value: 'POSE', label: '姿态 Pose' },
  { value: 'SCRIBBLE', label: '涂鸦 Scribble' },
  { value: 'LAYOUT', label: 'Layout（需已制作的布局视频）' },
  { value: 'GRAY', label: '灰度 Gray' },
  { value: 'PREPARED', label: '已处理控制视频（不再预处理）' },
] as const

export type H3ControlType = typeof H3_CONTROL_TYPES[number]['value']
