import request from '@/api/request'

export interface VideoProcessSubmitDTO {
  operation: 'VIDEO_UPSCALE' | 'FRAME_INTERPOLATION'
  sourceType?: 'DIRECT_URL' | 'SHOT_CURRENT' | 'SHOT_VIDEO_TAKE'
  sourceVideoUrl?: string
  sourceShotId?: string
  sourceVideoTakeId?: string
  providerId?: string
  modelId?: string
  modelCode?: string
  sourceFps?: number
  targetFps?: number
  scale?: number
  multiplier?: number
  crf?: number
  preserveAudio?: boolean
  clearCacheFrames?: number
  autoSaveToShot?: boolean
  setAsCurrent?: boolean
  batchSize?: number
}

export interface VideoProcessResultVO {
  id: string
  taskId: string
  operation: 'VIDEO_UPSCALE' | 'FRAME_INTERPOLATION'
  sourceType?: 'DIRECT_URL' | 'SHOT_CURRENT' | 'SHOT_VIDEO_TAKE'
  sourceVideoUrl: string
  sourceDramaId?: string
  sourceEpisodeId?: string
  sourceSceneId?: string
  sourceShotId?: string
  sourceVideoTakeId?: string
  sourceShotNo?: number
  sourceShotName?: string
  sourceDramaTitle?: string
  sourceEpisodeTitle?: string
  sourceSceneName?: string
  outputVideoUrl?: string
  coverImageUrl?: string
  providerId?: string
  providerName?: string
  modelId?: string
  modelCode: string
  status: 'QUEUED' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
  progress: number
  currentNode?: string
  sourceFps?: number
  targetFps?: number
  scale?: number
  multiplier?: number
  crf?: number
  preserveAudio?: boolean
  clearCacheFrames?: number
  width?: number
  height?: number
  duration?: number
  costMs?: number
  errorMessage?: string
  submitTime?: string
  startTime?: string
  finishTime?: string
  renderTaskId?: string
  autoSaveToShot?: boolean
  setAsCurrent?: boolean
  savedTakeId?: string
  savedTakeNo?: number
  isCurrentTake?: boolean
}

export interface VideoProcessQuery {
  current?: number
  size?: number
  operation?: string
  status?: string
  keyword?: string
  startTime?: string
  endTime?: string
}

export interface VideoProbeInfoVO {
  width?: number
  height?: number
  fps?: number
  duration?: number
  videoCodec?: string
  audioCodec?: string
  hasAudio?: boolean
  sourceUrl?: string
}

export interface VideoProcessingModelOption {
  id: string
  providerId: string
  modelCode: string
  modelName: string
  modelType: 'VIDEO_UPSCALE' | 'FRAME_INTERPOLATION'
  sortOrder?: number
  config: {
    defaultScale?: number
    allowedScales?: number[]
    defaultMultiplier?: number
    allowedMultipliers?: number[]
    defaultCrf: number
    minCrf: number
    maxCrf: number
    defaultClearCacheFrames?: number
    minClearCacheFrames?: number
    maxClearCacheFrames?: number
    defaultPreserveAudio: boolean
    defaultBatchSize?: number
  }
}

export interface ShotVideoSourceOptionVO {
  dramaId?: string
  dramaTitle?: string
  episodeId?: string
  episodeNo?: number
  episodeTitle?: string
  sceneId?: string
  sceneNo?: number
  sceneName?: string
  shotGroupId?: string
  shotId: string
  shotNo: number
  shotName?: string
  duration?: number
  generationMode?: string
  videoUrl: string
  posterUrl?: string
  currentVideoTakeId?: string
  videoTakeCount?: number
  videoUpdatedTime?: string
}

export interface ShotVideoSourceQuery {
  dramaId?: string | number
  episodeId?: string | number
  sceneId?: string | number
  keyword?: string
  current?: number
  size?: number
}

export interface VideoProcessProbeSourceDTO {
  sourceType: 'DIRECT_URL' | 'SHOT_CURRENT' | 'SHOT_VIDEO_TAKE'
  sourceVideoUrl?: string
  sourceShotId?: string
  sourceVideoTakeId?: string
}

/**
 * 提交视频后处理任务 (超分 / 补帧)
 */
export function submitVideoProcessTask(data: VideoProcessSubmitDTO): Promise<VideoProcessResultVO> {
  return request({
    url: '/video-processing/tasks',
    method: 'post',
    data
  })
}

/**
 * 获取任务详情
 */
export function getVideoProcessTask(taskId: string): Promise<VideoProcessResultVO> {
  return request({
    url: `/video-processing/tasks/${taskId}`,
    method: 'get'
  })
}

/**
 * 分页查询历史任务
 */
export function getVideoProcessTasks(params: VideoProcessQuery): Promise<{
  records: VideoProcessResultVO[]
  total: number
  current: number
  size: number
}> {
  return request({
    url: '/video-processing/tasks/page',
    method: 'get',
    params
  })
}

/**
 * 取消任务
 */
export function cancelVideoProcessTask(taskId: string): Promise<boolean> {
  return request({
    url: `/video-processing/tasks/${taskId}/cancel`,
    method: 'post'
  })
}

/**
 * 探测视频元信息 (URL 兼容方式)
 */
export function probeVideoInfo(videoUrl: string): Promise<VideoProbeInfoVO> {
  return request({
    url: '/video-processing/probe',
    method: 'post',
    data: { videoUrl }
  })
}

/**
 * 探测结构化来源视频元信息
 */
export function probeVideoSource(data: VideoProcessProbeSourceDTO): Promise<VideoProbeInfoVO> {
  return request({
    url: '/video-processing/probe-source',
    method: 'post',
    data
  })
}

/**
 * 查询可选分镜视频列表
 */
export function getSourceShots(params: ShotVideoSourceQuery): Promise<{
  records: ShotVideoSourceOptionVO[]
  total: number
  current: number
  size: number
}> {
  return request({
    url: '/video-processing/source-shots',
    method: 'get',
    params
  })
}

/**
 * 获取指定提供商下已启用的后处理模型列表与安全参数范围
 */
export function getVideoProcessingModels(
  providerId: string | number,
  operation: 'VIDEO_UPSCALE' | 'FRAME_INTERPOLATION'
): Promise<VideoProcessingModelOption[]> {
  return request({
    url: '/video-processing/models',
    method: 'get',
    params: {
      providerId: String(providerId),
      operation
    }
  })
}

export interface VideoProcessSaveToShotDTO {
  shotId?: string | number
  setAsCurrent?: boolean
}

/**
 * 将视频后处理任务产物保存为分镜历史 (Take)，默认设为分镜当前生效视频
 */
export function saveVideoProcessToShot(
  taskId: string,
  data?: VideoProcessSaveToShotDTO
): Promise<any> {
  return request({
    url: `/video-processing/tasks/${taskId}/save-to-shot`,
    method: 'post',
    data: data || { setAsCurrent: true }
  })
}
