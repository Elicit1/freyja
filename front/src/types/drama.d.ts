export interface PositionInfo {
  location?: string
  relativeTo?: string
  framePosition?: string
}

export interface BodyInfo {
  pose?: string
  facing?: string
  movement?: string
}

export interface GazeInfo {
  target?: string
  direction?: string
  contact?: string
}

export interface HandsInfo {
  left?: string
  right?: string
  action?: string
}

export interface EmotionInfo {
  primary?: string
  intensity?: number
  facialExpression?: string
}

export interface ClothingInfo {
  lookId?: number
  status?: string
  modifications?: string
}

export interface HairInfo {
  style?: string
  status?: string
}

export interface CharacterState {
  characterId?: number
  characterName?: string
  position?: PositionInfo
  body?: BodyInfo
  gaze?: GazeInfo
  hands?: HandsInfo
  emotion?: EmotionInfo
  clothing?: ClothingInfo
  hair?: HairInfo
  holdingSummary?: string
  conditionSummary?: string
  spatialSummary?: string
}

export interface PropState {
  name: string
  owner?: string
  state?: string
  location?: string
  isVisible?: boolean
  significance?: string
}

export interface EnvironmentState {
  location?: string
  timeOfDay?: string
  lightingStyle?: string
  weather?: string
  atmosphere?: string
}

export interface CameraState {
  shotType?: string
  cameraMovement?: string
  angle?: string
  focusTarget?: string
}

export interface ShotState {
  characters?: CharacterState[]
  props?: Record<string, PropState>
  environment?: EnvironmentState
  camera?: CameraState
  notes?: string
}

export interface DramaShotGroup {
  id?: number | string
  dramaId?: number | string
  episodeId?: number | string
  sceneId: number | string
  groupNo?: number
  name: string
  purpose?: string
  sortOrder?: number
  shotCount?: number
  renderedShotCount?: number
  totalDuration?: number
  shots?: DramaShot[]
  remark?: string
  createTime?: string
  updateTime?: string
}

export interface DramaShotGroupSplit {
  sourceGroupId: number | string
  splitAtShotId: number | string
  newGroupName?: string
  newGroupPurpose?: string
}

export interface DramaShotGroupMerge {
  sourceGroupIds: (number | string)[]
  targetGroupId?: number | string
  mergedGroupName?: string
}

export interface DramaShotGroupReorder {
  sceneId: number | string
  groupIds: (number | string)[]
}

export interface Drama {
  id?: number
  title: string
  coverUrl?: string
  genre: string
  targetEpisodes: number
  aspectRatio: string
  stylePreset: string
  styleTone?: string
  synopsis?: string
  status: string
  sortOrder?: number
  remark?: string
  createTime?: string
  updateTime?: string
  episodeCount?: number
  sceneCount?: number
  shotCount?: number
  renderedShotCount?: number
  progressPercentage?: number
}

export interface DramaQuery {
  current?: number
  size?: number
  title?: string
  genre?: string
  status?: string
}

export interface DramaOption {
  id: number
  title: string
  coverUrl?: string
  genre: string
  aspectRatio: string
  stylePreset: string
  styleTone?: string
  status: string
}

export interface DramaStats {
  dramaId: number
  title: string
  targetEpisodes: number
  actualEpisodes: number
  totalScenes: number
  totalShots: number
  renderedShots: number
  renderingShots: number
  failedShots: number
  initShots: number
  progressPercentage: number
  totalEstimatedDuration: number
}

export interface DramaEpisode {
  id?: number
  dramaId: number
  episodeNo: number
  title: string
  summary?: string
  scriptContent?: string
  targetDuration?: number
  actualDuration?: number
  status: string
  sortOrder?: number
  remark?: string
  createTime?: string
  updateTime?: string
  sceneCount?: number
  shotCount?: number
  renderedShotCount?: number
}

export interface DramaEpisodeBatch {
  dramaId: number
  startEpisodeNo?: number
  count?: number
  titlePrefix?: string
  titleSuffix?: string
  targetDuration?: number
}

export interface DramaScene {
  id?: number | string
  dramaId: number | string
  episodeId: number | string
  sceneNo: number
  name: string
  resSceneId?: number | string
  resSceneName?: string
  resSceneCoverUrl?: string
  sceneType: string
  timeOfDay: string
  weatherAtmosphere?: string
  locationName?: string
  summary?: string
  scriptContent?: string
  sortOrder?: number
  remark?: string
  createTime?: string
  updateTime?: string
  shotCount?: number
  renderedShotCount?: number
}

export interface CharacterShotRefInfo {
  characterId?: string | number
  characterName?: string
  avatarUrl?: string
  roleType?: string
  lookId?: string | number
  outfitName?: string
  outfitPreviewUrl?: string
  designDesc?: string
  outfitPrompt?: string
  appearancePrompt?: string
  actionPrompt?: string
  emotionPrompt?: string
  positionTag?: string
}

export interface PropShotRefInfo {
  propId?: string | number
  propName?: string
  propType?: string
  propPrompt?: string
  coverUrl?: string
}

export interface ShotRefImage {
  id?: string
  sourceType: 'SCENE' | 'CHARACTER_REFERENCE' | 'CHARACTER' | 'PROP' | 'UPLOAD'
  sourceId?: string | number
  characterId?: string | number
  lookId?: string | number
  name: string
  imageUrl: string
  referenceRole?: 'IDENTITY' | 'LOOK' | 'COMBINED' | 'POSE' | 'STYLE' | string
  usageRole?: 'SUBJECT' | 'FIRST_FRAME' | 'END_FRAME' | 'SCENE' | 'PROP' | string
}

export interface ShotRefAudio {
  id?: string
  sourceType: 'TTS' | 'UPLOAD' | 'CHARACTER'
  characterId?: string | number
  characterName?: string
  audioUrl: string
  duration?: number // 2-15s
  name?: string
  text?: string
  usageMode?: 'DIALOGUE_REUSE' | 'VOICE_TIMBRE' | 'BGM_REUSE' | 'AMBIENT_REUSE' | 'SOUND_EFFECT' | string
  language?: string
}

export interface DramaShot {
  id?: number | string
  dramaId: number | string
  episodeId: number | string
  sceneId: number | string
  shotGroupId?: number | string
  shotNo: number
  shotName: string
  shotType?: string
  cameraMovement?: string
  shotTypeLocked?: boolean
  cameraMovementLocked?: boolean
  duration: number
  scriptContent?: string
  actionDescription?: string
  dialogue?: string
  dialogueSpeaker?: string
  voiceover?: string
  soundEffect?: string
  resSceneId?: number | string
  resSceneName?: string
  resSceneCoverUrl?: string
  customScenePrompt?: string
  characterRefs?: CharacterShotRefInfo[]
  propRefs?: PropShotRefInfo[]
  generationMode?: 'FIRST_LAST_FRAME' | 'REFERENCE_MODE'
  firstFramePrompt?: string
  endFramePrompt?: string
  endFrameImageUrl?: string
  refImages?: ShotRefImage[]
  refAudios?: ShotRefAudio[]
  startState?: ShotState
  endState?: ShotState
  startStateJson?: string
  endStateJson?: string
  prompt?: string
  firstFrameVisual?: string
  videoPrompt?: string
  negativePrompt?: string
  stylePreset?: string
  previewImageUrl?: string
  firstFrameSourceType?: 'MANUAL_UPLOAD' | 'AI_GENERATED' | 'PREVIOUS_VIDEO_TAIL' | string
  firstFrameSourceShotId?: number | string
  firstFrameSourceVideoUrl?: string
  firstFrameSourceVideoTakeId?: string | number
  videoUrl?: string
  currentVideoTakeId?: string | number
  videoTakeCount?: number
  audioUrl?: string
  lastFrameUrl?: string
  lastFrameSourceVideoUrl?: string
  lastFrameSourceTakeId?: string | number
  renderStatus: 'INIT' | 'QUEUED' | 'RENDERING' | 'SUCCESS' | 'FAILED'
  latestTaskId?: string
  renderProgress?: number
  currentNode?: string
  comfyWorkflowTemplateId?: string
  directorPlanJson?: string
  directorPlan?: DirectorPlan
  sortOrder?: number
  remark?: string
  createTime?: string
  updateTime?: string
}

export interface ShotVideoTake {
  id: string
  shotId: string
  takeNo: number
  taskId?: string
  sourceType: 'AI_GENERATED' | 'LEGACY_BACKFILL' | 'VIDEO_UPSCALE' | 'FRAME_INTERPOLATION' | string
  status: 'AVAILABLE' | 'UNAVAILABLE' | 'ARCHIVED' | string
  videoUrl: string
  current: boolean
  providerId?: string
  providerName?: string
  modelCode?: string
  generationMode?: 'FIRST_LAST_FRAME' | 'REFERENCE_MODE' | string
  seed?: number
  size?: string
  duration?: number
  promptSnapshot?: string
  negativePromptSnapshot?: string
  firstFrameUrl?: string
  endFrameUrl?: string
  createTime?: string
}

export interface ShotVideoTakeSelectResult {
  shotId: string
  previousTakeId?: string
  currentTakeId: string
  videoUrl: string
  changed: boolean
}

export interface CameraBeat {
  startSec: number
  endSec: number
  movement: string
  direction?: string
  speed?: string
  startCue?: string
  stopCue?: string
  narrativePurpose?: string
}

export interface AppliedSkillRef {
  name: string
  version: string
  manifestHash?: string
}

export interface DirectorPlan {
  schemaVersion?: string
  duration?: number
  shotSize?: string
  cameraAngle?: string
  cameraBeats?: CameraBeat[]
  subjectAction?: string
  gaze?: string
  narrativeIntent?: string
  lockedFields?: string[]
  skills?: AppliedSkillRef[]
}

export interface ShotAiVisualPlan {
  shotId?: number
  firstFramePrompt: string
  negativePrompt?: string
  videoPrompt?: string
  controlImages?: Array<{
    controlType: string
    imageUrl: string
    weight?: number
    label?: string
  }>
}

export interface ShotPromptDeriveDTO {
  shotId?: number | string
  dramaId?: number | string
  episodeId?: number | string
  sceneId?: number | string
  shotNo?: number
  shotName?: string
  shotType?: string
  cameraMovement?: string
  shotTypeLocked?: boolean
  cameraMovementLocked?: boolean
  duration?: number
  scriptContent?: string
  actionDescription?: string
  dialogue?: string
  dialogueSpeaker?: string
  voiceover?: string
  soundEffect?: string
  resSceneId?: number | string
  customScenePrompt?: string
  characterRefs?: CharacterShotRefInfo[]
  propRefs?: PropShotRefInfo[]
  episodeSummary?: string
  stylePreset?: string
  styleTone?: string
  providerId?: number | string
  modelCode?: string
  userInstruction?: string
  generationMode?: 'FIRST_LAST_FRAME' | 'REFERENCE_MODE'
  promptTarget?: 'GENERIC' | 'MINIMAX_H3' | string
  refImages?: ShotRefImage[]
  refAudios?: ShotRefAudio[]
  requiredSkillNames?: string[]
  selectedSkillNames?: string[]
  /** 是否包含背景配乐 (BGM)，默认为 false */
  includeBgm?: boolean
}

export interface ShotPromptDeriveVO {
  prompt?: string
  firstFramePrompt?: string
  endFramePrompt?: string
  videoPrompt?: string
  negativePrompt?: string
  directorPlan?: DirectorPlan
}

export interface ShotPromptPackageVO {
  generationMode: 'FIRST_LAST_FRAME' | 'REFERENCE_MODE' | string
  systemPrompt: string
  userPrompt: string
  combinedPrompt: string
  outputFormat: string
  referenceManifest?: {
    pictures?: Array<{
      pictureIndex: number
      referenceId?: string
      sourceType?: string
      sourceId?: number
      entityName?: string
      usageRole?: string
      description?: string
      imageUrl?: string
    }>
    audios?: Array<{
      audioIndex: number
      referenceId?: string
      sourceType?: string
      characterId?: number
      characterName?: string
      usageMode?: string
      language?: string
      text?: string
      audioUrl?: string
      duration?: number
    }>
  }
  templateVersion?: string
  contextFingerprint?: string
}

export interface ShotPromptParseRequestDTO {
  shotId?: number | string
  dramaId?: number | string
  episodeId?: number | string
  sceneId?: number | string
  shotNo?: number
  generationMode?: 'FIRST_LAST_FRAME' | 'REFERENCE_MODE' | string
  rawResponse: string
  contextFingerprint?: string
  dialogue?: string
  characterRefs?: CharacterShotRefInfo[]
  propRefs?: PropShotRefInfo[]
  refImages?: ShotRefImage[]
  refAudios?: ShotRefAudio[]
  selectedSkillNames?: string[]
  /** 是否包含背景配乐 (BGM)，默认为 false */
  includeBgm?: boolean
}

export interface ShotPromptValidationResult {
  result?: ShotPromptDeriveVO
  errors: string[]
  warnings: string[]
  fingerprintMatched?: boolean | null
}

/** 持久化的分镜提示词分析任务（ID 始终按字符串使用）。 */
export interface ShotPromptTask {
  id: string | number
  dramaId?: string | number
  episodeId?: string | number
  taskType?: string
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'PARTIAL_SUCCESS' | 'FAILED' | 'RETRYING' | 'CANCELLED' | string
  targetId?: string
  inputPayload?: string
  outputPayload?: string
  modelCode?: string
  errorMessage?: string
  startedAt?: string
  finishedAt?: string
  createTime?: string
}

export interface DramaShotReorder {
  sceneId: number
  shotIds: number[]
}

export interface DramaShotBatchAssemble {
  sceneId: number
  shotIds?: number[]
}

export interface DramaShotFirstFrame {
  engineType?: 'API' | string
  providerId?: number | string
  modelCode?: string
  workflowTemplateId?: string
  size?: string
  seed?: number
  customPrompt?: string
  negativePrompt?: string
  referenceImageUrls?: string[]
  frameType?: 'FIRST_FRAME' | 'END_FRAME'
}

export interface DramaShotRenderRequest {
  providerId?: number | string
  workflowTemplateId?: string
  seed?: number
  size?: string
}

export interface ShotSummary {
  id: number
  sceneId: number
  shotGroupId?: number
  shotNo: number
  shotName: string
  shotType?: string
  cameraMovement?: string
  duration: number
  scriptContent?: string
  actionDescription?: string
  dialogue?: string
  dialogueSpeaker?: string
  renderStatus: string
  generationMode?: 'FIRST_LAST_FRAME' | 'REFERENCE_MODE'
  previewImageUrl?: string
  endFrameImageUrl?: string
  videoUrl?: string
  sortOrder: number
}

export interface SceneTree {
  id: number
  episodeId: number
  sceneNo: number
  name: string
  resSceneId?: number
  resSceneName?: string
  resSceneCoverUrl?: string
  sceneType: string
  timeOfDay: string
  weatherAtmosphere?: string
  locationName?: string
  summary?: string
  sortOrder: number
  shotGroups?: DramaShotGroup[]
  shots: ShotSummary[]
}

export interface EpisodeTree {
  id: number
  dramaId: number
  episodeNo: number
  title: string
  summary?: string
  targetDuration: number
  actualDuration: number
  status: string
  sortOrder: number
  sceneCount: number
  shotCount: number
  renderedShotCount: number
  scenes: SceneTree[]
}

export interface DramaTree {
  id: number
  title: string
  coverUrl?: string
  genre: string
  targetEpisodes: number
  aspectRatio: string
  stylePreset: string
  synopsis?: string
  status: string
  totalEpisodes: number
  totalScenes: number
  totalShots: number
  renderedShots: number
  progressPercentage: number
  episodes: EpisodeTree[]
}

export interface PreviousVideoTailResult {
  currentShotId: string | number
  sourceShotId: string | number
  sourceShotNo: number
  sourceShotName: string
  sourceVideoUrl: string
  tailFrameUrl: string
  reused: boolean
  applied: boolean
}
