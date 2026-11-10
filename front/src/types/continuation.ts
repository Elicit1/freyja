import type { ControlNetConfiguration, ControlNetInput } from '@/utils/control-net'
import type { ShotRefImage, ShotRefVideo, ShotRefAudio, CharacterShotRefInfo, PropShotRefInfo, DirectorPlan } from './drama'
import type { H3ControlType } from '@/constants/h3ControlTypes'
export type ContinuityMode = 'GUIDE' | 'GUIDE_CHROMA' | 'LATENT_REDRAW' | 'LATENT_PROTECTED'
export interface ContinuityOptions {
  mode: ContinuityMode
  chroma?: { strength: number; seamStrength: number; taperFrames: number }
  redraw?: { strength: number }
  protection?: { noiseStrength: number; useFirstFrameAnchor: boolean; useTailAnchor: boolean; tailAnchorStartFraction: number; anchorSourceSegmentId?: string }
}
export interface ContinuityCapability { available: boolean; reason?: string }
export interface ContinuationSegment {
  continuityOverride?: ContinuityOptions | null
  id: string
  name?: string
  scriptContent?: string
  actionDescription?: string
  soundEffect?: string
  shotType?: string
  cameraMovement?: string
  shotTypeLocked?: boolean
  cameraMovementLocked?: boolean
  resSceneId?: string | number
  resKeyframeId?: string | number
  customScenePrompt?: string
  characterRefs?: CharacterShotRefInfo[]
  propRefs?: PropShotRefInfo[]
  directorPlan?: DirectorPlan
  firstFramePrompt?: string
  endFramePrompt?: string
  negativePrompt?: string
  referenceAudios?: ShotRefAudio[]
  startFrame: number
  endFrame: number
  prompt: string
  locked: boolean
  firstFrameImageUrl?: string
  lastFrameImageUrl?: string
  referenceImages: ShotRefImage[]
  referenceVideos: ShotRefVideo[]
  controlInputs?: ControlNetInput[] | null
  controlNetEnabled: boolean
  controlVideoId?: string
  controlType: H3ControlType
  controlStrength: number
}
export interface ContinuationDraft {
  continuity?: ContinuityOptions
  schemaVersion: number
  enabled: boolean
  durationSeconds: number
  segmentFrames: number
  overlapFrames: number
  autoSplitReferenceVideo?: boolean
  videoSources?: ShotRefVideo[]
  imageSources?: ShotRefImage[]
  controlNet?: ControlNetConfiguration
  contextWindows: boolean
  reuseFromTaskId?: string
  segments: ContinuationSegment[]
}
export interface ContinuationPreset { id: string; label: string; segmentFrames: number; overlapFrames: number }
export interface ContinuationPlan {
  draft: ContinuationDraft
  outputFrames: number
  renderFrames: number
  segmentCount: number
  planHash: string
  renderable: boolean
  issues: string[]
  continuationModes?: Partial<Record<ContinuityMode, ContinuityCapability>>
  ranges: Array<{ segmentId: string; startFrame: number; endFrame: number; overlapFrames: number; keepStartFrame: number; keepEndFrame: number; effectiveContinuity?: ContinuityOptions; saveLatent?: boolean; contextRequirement?: string; anchorSourceSegmentId?: string }>
}
