import type { ShotRefImage, ShotRefVideo, ShotRefAudio, CharacterShotRefInfo, PropShotRefInfo, DirectorPlan } from './drama'
import type { H3ControlType } from '@/constants/h3ControlTypes'
export interface ContinuationSegment {
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
  controlNetEnabled: boolean
  controlVideoId?: string
  controlType: H3ControlType
  controlStrength: number
}
export interface ContinuationDraft {
  schemaVersion: number
  enabled: boolean
  durationSeconds: number
  segmentFrames: number
  overlapFrames: number
  autoSplitReferenceVideo?: boolean
  videoSources?: ShotRefVideo[]
  imageSources?: ShotRefImage[]
  controlNet?: { enabled: boolean; referenceId?: string; controlType: string; strength: number }
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
  ranges: Array<{ segmentId: string; startFrame: number; endFrame: number; overlapFrames: number; keepStartFrame: number; keepEndFrame: number }>
}
