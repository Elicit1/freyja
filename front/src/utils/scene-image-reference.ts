import type { ShotRefImage } from '@/types/drama'
import type { ResSceneOption } from '@/types/resource'

export function referenceImageKey(image: { imageUrl: string; sourceType: string; sourceId?: string | number; bindingMode?: string; sourceSlot?: string }): string {
  return image.bindingMode === 'ASSET_LINK'
    ? `${image.sourceType}|${String(image.sourceId || '')}|${image.sourceSlot || ''}`
    : `${image.imageUrl}|${image.sourceType}|${String(image.sourceId || '')}`
}

export function sceneImageCandidates(scene: ResSceneOption) {
  // Reserve the spatial slot even before its image exists. Cover is an explicit alternative.
  return (['SCENE_REFERENCE', 'SCENE_COVER'] as const).map(sourceSlot => ({
    id: `scene_${scene.id}_${sourceSlot}`,
    uniqueKey: `scene_${scene.id}_${sourceSlot}`,
    name: `${scene.name} (${sourceSlot === 'SCENE_REFERENCE' ? '空间参考图' : '封面图'})`,
    imageUrl: (sourceSlot === 'SCENE_REFERENCE' ? scene.referenceImageUrl : scene.coverUrl) || '',
    sourceType: 'SCENE' as const,
    sourceId: String(scene.id),
    bindingMode: 'ASSET_LINK' as const,
    sourceSlot,
    usageRole: 'SCENE',
    tag: sourceSlot === 'SCENE_REFERENCE' ? '空间参考' : '场景封面',
    assetName: scene.name,
    assetType: 'SCENE' as const
  })).filter(candidate => candidate.sourceSlot === 'SCENE_REFERENCE'
    ? !!scene.referenceImageUrl || !scene.coverUrl : !!scene.coverUrl)
}

export function linkSceneSlot(image: ShotRefImage, sourceSlot: 'SCENE_REFERENCE' | 'SCENE_COVER') {
  return { ...image, bindingMode: 'ASSET_LINK' as const, sourceSlot, imageUrl: '' }
}
