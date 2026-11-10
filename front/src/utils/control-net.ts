export interface ControlNetInput {
  enabled: boolean
  referenceId?: string
  controlType: string
  inputMode: 'RAW' | 'PREPROCESSED'
  strength: number
}

export interface ControlNetConfiguration {
  enabled: boolean
  controls?: ControlNetInput[] | null
  // Historical single-control drafts remain readable.
  referenceId?: string
  controlType?: string
  strength?: number
}

export const MAX_CONTROL_INPUTS = 3

export function controlInputs(value: ControlNetConfiguration): ControlNetInput[] {
  if (value.controls != null) return value.controls
  const controlType = value.controlType || 'CANNY'
  return [{ enabled: true, referenceId: value.referenceId, controlType, inputMode: ['PREPARED', 'LAYOUT'].includes(controlType) ? 'PREPROCESSED' : 'RAW', strength: value.strength ?? 1 }]
}

export function removeControlVideo(value: ControlNetConfiguration, referenceId: string, remainingVideos: number): ControlNetConfiguration {
  return { enabled: remainingVideos > 0 && value.enabled, controls: controlInputs(value).map(input => input.referenceId === referenceId
    ? { ...input, referenceId: undefined, enabled: false } : input) }
}
