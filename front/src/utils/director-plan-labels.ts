type LabelGroup = 'shotSize' | 'cameraAngle' | 'movement' | 'speed' | 'direction' | 'lockedField' | 'skill'

// Display labels only. The original protocol values remain in plans and request payloads.
const labels: Record<LabelGroup, Record<string, string>> = {
  shotSize: {
    EXTREME_CLOSE_UP: '大特写', CLOSE_UP: '特写', MEDIUM_CLOSE_UP: '中特写',
    MEDIUM_SHOT: '中景', FULL_SHOT: '全景', LONG_SHOT: '远景',
    OVER_SHOULDER: '过肩镜头', TOP_DOWN: '俯拍 / 鸟瞰'
  },
  cameraAngle: {
    EYE_LEVEL: '平视', LOW_ANGLE: '仰拍', HIGH_ANGLE: '俯拍',
    DUTCH_ANGLE: '倾斜机位', OVER_THE_SHOULDER: '过肩机位', BIRDS_EYE: '鸟瞰'
  },
  movement: {
    STATIC: '固定镜头', PUSH_IN: '推进', PULL_OUT: '拉远',
    PAN_LEFT: '向左摇摄', PAN_RIGHT: '向右摇摄', TILT_UP: '向上摇摄',
    TILT_DOWN: '向下摇摄', TRACKING: '跟拍', ORBIT: '环绕', ZOOM_IN: '放大变焦'
  },
  speed: { NONE: '无运动', SLOW: '缓慢', NORMAL: '正常', FAST: '快速' },
  direction: {
    FORWARD: '向前', BACKWARD: '向后', LEFT: '向左', RIGHT: '向右', UP: '向上', DOWN: '向下',
    CLOCKWISE: '顺时针', COUNTERCLOCKWISE: '逆时针'
  },
  lockedField: { duration: '时长', shotSize: '景别', cameraMovement: '运镜' },
  skill: { cinematography: '视听摄影指导' }
}

export function directorPlanLabel(group: LabelGroup, value?: string | null, empty = '未指定'): string {
  if (!value) return empty
  return Object.prototype.hasOwnProperty.call(labels[group], value) ? labels[group][value]! : value
}

const options = (group: LabelGroup) => Object.entries(labels[group]).map(([value, label]) => ({ value, label }))

export const directorPlanOptions = {
  shotSize: options('shotSize'), cameraAngle: options('cameraAngle'),
  movement: options('movement'), speed: options('speed'), direction: options('direction')
}
