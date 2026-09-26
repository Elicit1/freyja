import { shotApi } from '@/api/drama'
import { sceneApi } from '@/api/res-scene'
import type { ShotRefImage } from '@/types/drama'

export async function createPreviousVideoTailSceneReference(
  shotId: string | number,
  dramaId: string | number,
  existingImages: ShotRefImage[]
): Promise<{ image: ShotRefImage | null; reused: boolean }> {
  const tail = await shotApi.extractPreviousVideoTail(shotId)
  if (existingImages.some(image => image.imageUrl === tail.tailFrameUrl)) {
    return { image: null, reused: tail.reused }
  }

  const sourceName = tail.sourceShotName || `S${tail.sourceShotNo}`
  const name = `${sourceName} 视频尾帧场景`
  const sceneId = await sceneApi.create({
    dramaId,
    name,
    coverUrl: tail.tailFrameUrl,
    referenceImageUrl: tail.tailFrameUrl,
    scenePrompt: '',
    remark: `来源：分镜 ${String(tail.sourceShotId)} 的视频尾帧`
  })
  return {
    image: {
      id: `SCENE_${String(sceneId)}`,
      sourceType: 'SCENE',
      sourceId: String(sceneId),
      name,
      imageUrl: tail.tailFrameUrl,
      usageRole: 'SCENE'
    },
    reused: tail.reused
  }
}
