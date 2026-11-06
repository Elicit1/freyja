import type { ShotRefImage, ShotRefVideo } from './drama'
import type { H3ControlType } from '@/constants/h3ControlTypes'
export interface ContinuationSegment {
  id: string
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
  contextWindows: boolean
  lockContext?: boolean
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
