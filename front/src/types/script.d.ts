export interface DecomposedCharacter {
  name: string
  canonicalName?: string | null
  displayName?: string | null
  identityStatus?: string // UNKNOWN, PARTIAL, CONFIRMED, UNRESOLVED
  roleType: string
  gender: string
  ageGroup?: string
  personality?: string
  appearanceDesc?: string
  appearancePrompt?: string
  outfitPrompt?: string
  triggerWords?: string
  aliases?: string[]
  matchedCharacterId?: number | null
  confidence?: number | null
  evidenceType?: string | null
  evidenceText?: string | null
  candidateCharacterIds?: number[] | null
  existingCharacterId?: number | null
}

export interface DecomposedScene {
  id?: string
  sceneName: string
  sceneType: string
  timeOfDay: string
  weatherAtmosphere?: string
  description?: string
  locationName?: string
  scenePrompt?: string
  existingSceneId?: string | number | null
}

export interface DecomposedProp {
  id?: string
  name: string
  propType?: string
  propPrompt?: string
  description?: string
  existingPropId?: number | null
}

export interface DecomposedShot {
  shotNo: number
  shotName: string
  shotType?: string | null
  cameraMovement?: string | null
  shotTypeLocked?: boolean
  cameraMovementLocked?: boolean
  duration: number
  scriptContent?: string | null
  actionDescription: string
  dialogueSpeaker?: string | null
  dialogue?: string | null
  voiceover?: string | null
  soundEffect?: string | null
  primaryCharacter?: string | null
  secondaryCharacter?: string | null
  characterNames?: string[]
  sceneId?: string
  resSceneId?: number | null
  propIds?: string[]
  groupId?: string
  prompt?: string
  firstFrameVisual?: string | null
  videoPrompt?: string
  negativePrompt?: string
}

export interface DecomposedShotGroup {
  groupNo: number
  name: string
  purpose?: string
  shots: DecomposedShot[]
}

export interface DecomposedEpisodeScene {
  sceneNo: number
  sceneName: string
  sceneId?: string
  resSceneId?: number | null
  summary?: string
  scriptContent?: string
  shotGroups?: DecomposedShotGroup[]
  shots?: DecomposedShot[]
}

export interface DecomposedEpisode {
  episodeNo: number
  title: string
  summary?: string
  scriptContent?: string
  targetDuration?: number
  scenes: DecomposedEpisodeScene[]
}

export interface StorySegment {
  id: string
  sequence: number
  startOffset: number
  endOffset: number
  title: string
  summary?: string
  sceneId?: string
  characterIds?: string[]
  locationIds?: string[]
  propIds?: string[]
  importantPropIds?: string[]
  narrativePurpose?: string
  rawText?: string
}

export interface FragmentationStats {
  totalShots: number
  shortShots: number
  normalShots: number
  longShots: number
  averageDuration: number
  shortShotRatio: number
  isHighFragmentation: boolean
  warningMessage?: string
}

export interface SegmentShotResult {
  segmentId: string
  sequence: number
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'
  retryCount?: number
  durationMs?: number
  errorMessage?: string | null
}

export interface ChapterDecomposeHistory {
  taskId: string | number
  dramaId: number
  episodeId?: number | null
  dramaTitle?: string
  chapterTitle?: string
  episodeNo?: number
  status: 'PENDING' | 'RUNNING' | 'RETRYING' | 'SUCCESS' | 'PARTIAL_SUCCESS' | 'FAILED'
  totalSegments: number
  successSegments: number
  failedSegments: number
  failedSegmentIds?: string[]
  totalShots?: number
  averageDuration?: number
  modelCode?: string
  durationSeconds?: number
  consumedTokens?: number
  errorMessage?: string | null
  createTime?: string
  finishedAt?: string
}

export interface WorkerRetryRequest {
  segmentId: string
  customInstructions?: string
  rawTextOverride?: string
  providerIdOverride?: number
  modelCodeOverride?: string
  skillPolicyOverride?: ScriptSkillStagePolicy
}

export interface ScriptSkillStagePolicy {
  allowDynamicLoad: boolean
  requiredSkillNames: string[]
}

export interface ScriptSkillEvent {
  stage: 'PLANNER' | 'WORKER'
  segmentId?: string | null
  attempt: number
  name: string
  versionId?: string | null
  version?: string | null
  contentHash?: string | null
  source: 'REQUIRED' | 'TOOL'
  status: string
}

export interface ScriptDecomposeResult {
  skillEvents?: ScriptSkillEvent[]
  taskId?: string | number
  status?: string
  dramaTitle: string
  genre: string
  synopsis?: string
  stylePreset?: string
  styleTone?: string
  aspectRatio?: string
  characters: DecomposedCharacter[]
  scenes: DecomposedScene[]
  props?: DecomposedProp[]
  episodes: DecomposedEpisode[]
  segments?: StorySegment[]
  segmentResults?: SegmentShotResult[]
  fragmentationStats?: FragmentationStats
}

export interface ScriptDecomposeRequest {
  skillPolicy?: {
    planner?: ScriptSkillStagePolicy
    worker?: ScriptSkillStagePolicy
  }
  providerId: number
  modelCode: string
  rawText: string
  dramaId?: number | null
  episodeId?: number | null
  chapterTitle?: string
  startEpisodeNo?: number
  targetEpisodes?: number | null
  targetDurationPerEpisode?: number | null
  stylePreset?: string
  styleTone?: string
  aspectRatio?: string
  pacingPreset?: string
}

export interface ScriptDecomposeCommit {
  taskId?: string | number
  dramaId?: number | null
  commitMode?: 'APPEND_TO_EPISODE' | 'NEW_EPISODE' | 'OVERWRITE_EPISODE' | 'REPLACE_ALL'
  targetEpisodeNo?: number
  targetEpisodeId?: number
  dramaTitle: string
  genre: string
  synopsis?: string
  aspectRatio?: string
  stylePreset?: string
  styleTone?: string
  characters: DecomposedCharacter[]
  scenes: DecomposedScene[]
  props?: DecomposedProp[]
  episodes: DecomposedEpisode[]
}
