export type GenerationMediaType = 'image' | 'video'

export interface GenerationSizeValidation {
  valid: boolean
  normalized?: string
  message?: string
}

const DEFAULT_SIZE: Record<GenerationMediaType, string> = {
  image: '720x1280',
  video: '544x960'
}

const RATIO_PRESETS: Record<GenerationMediaType, Record<string, string>> = {
  image: {
    '16:9': '1280x720',
    '9:16': '720x1280',
    '1:1': '1024x1024',
    '4:3': '1024x768',
    '3:4': '768x1024',
    '3:2': '1536x1024',
    '2:3': '1024x1536'
  },
  video: {
    '16:9': '960x544',
    '9:16': '544x960',
    '1:1': '768x768',
    '4:3': '1024x768',
    '3:4': '768x1024',
    '3:2': '1152x768',
    '2:3': '768x1152'
  }
}

function parsePixelSize(value: string): [number, number] | undefined {
  const match = value.trim().match(/^(\d+)\s*[*xX×]\s*(\d+)$/)
  if (!match) return undefined
  return [Number(match[1]), Number(match[2])]
}

function nearestMultiple(value: number, multiple: number): number {
  return Math.max(multiple, Math.round(value / multiple) * multiple)
}

/**
 * Resolve a drama aspect-ratio setting to a visible, executable generation size.
 * Explicit pixel dimensions are aligned here so the form displays the actual size
 * before submission. Manually entered sizes are still strictly validated on submit.
 */
export function resolveGenerationSize(value: string | undefined, mediaType: GenerationMediaType): string {
  if (!value?.trim()) return DEFAULT_SIZE[mediaType]

  const cleaned = value.trim().toLowerCase()
  const preset = RATIO_PRESETS[mediaType][cleaned]
  if (preset) return preset

  const parsed = parsePixelSize(cleaned)
  if (!parsed) return DEFAULT_SIZE[mediaType]

  const multiple = mediaType === 'video' ? 32 : 16
  const [width, height] = parsed
  return `${nearestMultiple(width, multiple)}x${nearestMultiple(height, multiple)}`
}

export function validateGenerationSize(
  value: string | undefined,
  mediaType: GenerationMediaType
): GenerationSizeValidation {
  const parsed = value ? parsePixelSize(value) : undefined
  const label = mediaType === 'video' ? '视频' : '图片'
  const multiple = mediaType === 'video' ? 32 : 16

  if (!parsed) {
    return {
      valid: false,
      message: `${label}尺寸格式应为“宽x高”，例如 ${DEFAULT_SIZE[mediaType]}`
    }
  }

  const [width, height] = parsed
  if (width < multiple || height < multiple) {
    return { valid: false, message: `${label}宽高不能小于 ${multiple} 像素` }
  }
  if (width % multiple !== 0 || height % multiple !== 0) {
    return {
      valid: false,
      message: `${label}宽高必须都是 ${multiple} 的整数倍；系统不会自动换算，请选择有效规格`
    }
  }

  return { valid: true, normalized: `${width}x${height}` }
}
