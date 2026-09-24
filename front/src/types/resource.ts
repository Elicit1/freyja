export interface ResCharacterLook {
  id?: string | number
  characterId?: string | number
  lookName: string
  outfitName?: string
  isDefault: number
  referenceImageUrl?: string
  previewImageUrl?: string
  imageStatus?: 'MISSING' | 'SYNCED' | 'STALE'
  visualVersion?: number
  imageVisualVersion?: number
  outfitPrompt?: string
  lookType?: string
  designDesc?: string
  appearancePrompt?: string
  negativePrompt?: string
  loraName?: string
  loraWeight?: number
  sortOrder?: number
  status?: number
  remark?: string
  createTime?: string
  updateTime?: string
}

export type ResCharacterOutfit = ResCharacterLook

export interface ResCharacterAlias {
  id?: string | number
  dramaId?: string | number
  characterId: string | number
  alias: string
  aliasType?: string
  sourceEpisodeId?: string | number
  confidence?: number
  status?: number
  remark?: string
  createTime?: string
}

export interface ResCharacterEvidence {
  id?: string | number
  dramaId?: string | number
  characterId: string | number
  episodeId?: string | number
  sceneId?: string | number
  sourceText: string
  evidenceType: string
  confidence: number
  reason?: string
  status?: number
  createTime?: string
}

export interface ResCharacterResolution {
  id?: string | number
  dramaId?: string | number
  episodeId?: string | number
  sceneId?: string | number
  sourceMention: string
  sourceText: string
  candidateCharacterIds?: (string | number)[]
  candidateCharacters?: ResCharacterOption[]
  resolutionStatus: string
  resolvedCharacterId?: string | number
  resolvedCharacterName?: string
  confidence?: number
  reason?: string
  createTime?: string
}

export interface CharacterMergeParam {
  sourceCharacterId: string | number
  targetCharacterId: string | number
  dramaId?: string | number
  reason?: string
}

export interface ResCharacter {
  id?: string | number
  dramaId?: string | number
  name: string
  canonicalName?: string
  displayName?: string
  referenceImageUrl?: string
  avatarUrl?: string
  gender: string
  ageGroup?: string
  roleType: string
  identityStatus?: string // UNKNOWN, PARTIAL, CONFIRMED, UNRESOLVED, MERGED
  mergedToId?: number
  personality?: string
  appearanceDesc?: string
  appearancePrompt?: string
  triggerWords?: string
  negativePrompt?: string
  avatarPrompt?: string
  avatarNegativePrompt?: string
  facePrompt?: string
  faceNegativePrompt?: string
  triviewPrompt?: string
  triviewNegativePrompt?: string
  loraName?: string
  loraWeight?: number
  faceImageUrl?: string
  triviewImageUrl?: string
  voiceId?: string
  voiceDesc?: string
  voiceSampleText?: string
  voiceSampleUrl?: string
  sortOrder?: number
  status?: number
  firstAppearance?: string
  lastAppearance?: string
  remark?: string
  createTime?: string
  updateTime?: string
  outfits?: ResCharacterOutfit[]
  looks?: ResCharacterLook[]
  defaultOutfit?: ResCharacterOutfit
  defaultLook?: ResCharacterLook
  outfitCount?: number
  aliases?: ResCharacterAlias[]
  evidences?: ResCharacterEvidence[]
  // 用于创建时的便捷字段
  defaultOutfitName?: string
  defaultOutfitPrompt?: string
  aliasList?: string[]
}

export interface CharacterVisualPromptDeriveDTO {
  characterId?: string | number
  dramaId?: string | number
  name?: string
  gender?: string
  ageGroup?: string
  roleType?: string
  personality?: string
  appearanceDesc?: string
  stylePreset?: string
  styleTone?: string
  visualStyle?: string
  providerId?: string | number
  modelCode?: string
  requiredSkillNames?: string[]
  selectedSkillNames?: string[]
}

export interface CharacterVisualPromptDeriveVO {
  appearancePrompt: string
  negativePrompt?: string
}

export interface OutfitPromptDeriveDTO {
  lookId?: string | number
  characterId?: string | number
  outfitName?: string
  lookType?: string
  designDesc?: string
  characterName?: string
  characterGender?: string
  characterAppearanceDesc?: string
  dramaId?: string | number
  stylePreset?: string
  styleTone?: string
  providerId?: string | number
  modelCode?: string
  requiredSkillNames?: string[]
  selectedSkillNames?: string[]
}

export interface OutfitPromptDeriveVO {
  outfitPrompt: string
  appearancePrompt?: string
  negativePrompt?: string
}

export interface AssetPromptPackageVO {
  assetType: 'CHARACTER' | 'CHARACTER_IDENTITY' | 'CHARACTER_OUTFIT' | 'PROP' | 'SCENE'
  assetId?: string | number
  assetName?: string
  systemPrompt?: string
  userPrompt?: string
  combinedPrompt?: string
  outputFormat?: string
  templateVersion?: string
  contextFingerprint?: string
}

export interface ResCharacterQuery {
  current?: number
  size?: number
  dramaId?: string | number
  name?: string
  gender?: string
  roleType?: string
  identityStatus?: string
  excludeMerged?: boolean
  status?: number
}

export interface ResCharacterOption {
  id: string | number
  dramaId: string | number
  name: string
  referenceImageUrl?: string
  avatarUrl?: string
  roleType: string
  triggerWords?: string
  appearancePrompt?: string
  voiceSampleUrl?: string
  voiceSampleText?: string
  voiceDesc?: string
  outfits?: ResCharacterOutfit[]
}

export interface ResScene {
  id?: string | number
  dramaId?: string | number
  name: string
  coverUrl?: string
  sceneType: string
  timeOfDay: string
  weatherAtmosphere?: string
  description?: string
  scenePrompt: string
  negativePrompt?: string
  loraName?: string
  loraWeight?: number
  referenceImageUrl?: string
  sortOrder?: number
  status?: number
  remark?: string
  createTime?: string
  updateTime?: string
}

export interface ScenePromptDeriveDTO {
  sceneId?: string | number
  dramaId?: string | number
  name?: string
  sceneType?: string
  timeOfDay?: string
  weatherAtmosphere?: string
  description?: string
  stylePreset?: string
  styleTone?: string
  visualStyle?: string
  providerId?: string | number
  modelCode?: string
  requiredSkillNames?: string[]
  selectedSkillNames?: string[]
}

export interface ScenePromptDeriveVO {
  scenePrompt?: string
  negativePrompt?: string
}

export interface ScenePromptParseRequestDTO {
  sceneId?: string | number
  dramaId?: string | number
  name?: string
  sceneType?: string
  timeOfDay?: string
  weatherAtmosphere?: string
  description?: string
  stylePreset?: string
  styleTone?: string
  visualStyle?: string
  rawResponse: string
  contextFingerprint?: string
  selectedSkillNames?: string[]
}

export interface ScenePromptValidationResult {
  result?: ScenePromptDeriveVO
  errors?: string[]
  warnings?: string[]
  fingerprintMatched?: boolean
}

export interface ResSceneQuery {
  current?: number
  size?: number
  dramaId?: string | number
  name?: string
  sceneType?: string
  timeOfDay?: string
  weatherAtmosphere?: string
  status?: number
}

export interface ResSceneOption {
  id: string | number
  dramaId: string | number
  name: string
  coverUrl?: string
  sceneType: string
  timeOfDay: string
  weatherAtmosphere?: string
  description?: string
  scenePrompt: string
  loraName?: string
  referenceImageUrl?: string
}

export interface ResProp {
  id?: string | number
  dramaId?: string | number
  name: string
  propType?: string
  description?: string
  propPrompt?: string
  negativePrompt?: string
  coverUrl?: string
  sortOrder?: number
  status?: number
  remark?: string
  createTime?: string
  updateTime?: string
}

export interface PropPromptDeriveDTO {
  propId?: string | number
  dramaId?: string | number
  name?: string
  propType?: string
  description?: string
  stylePreset?: string
  styleTone?: string
  visualStyle?: string
  providerId?: string | number
  modelCode?: string
  requiredSkillNames?: string[]
  selectedSkillNames?: string[]
}

export interface PropPromptDeriveVO {
  propPrompt?: string
  negativePrompt?: string
}

export interface PropPromptParseRequestDTO {
  propId?: string | number
  dramaId?: string | number
  name?: string
  propType?: string
  description?: string
  stylePreset?: string
  styleTone?: string
  visualStyle?: string
  rawResponse: string
  contextFingerprint?: string
  selectedSkillNames?: string[]
}

export interface PropPromptValidationResult {
  result?: PropPromptDeriveVO
  errors?: string[]
  warnings?: string[]
  fingerprintMatched?: boolean
}

export interface ResPropQuery {
  current?: number
  size?: number
  dramaId?: string | number
  name?: string
  propType?: string
  status?: number
}

export interface ResPropOption {
  id: string | number
  dramaId: string | number
  name: string
  propType?: string
  propPrompt?: string
  negativePrompt?: string
  coverUrl?: string
}

export interface CharacterShotRef {
  characterId: string | number
  lookId?: string | number
  actionPrompt?: string
  emotionPrompt?: string
  positionTag?: string
}

export interface LoraItem {
  loraName: string
  weight: number
  source: string
  assetName: string
}

export interface ControlImage {
  controlType: string
  imageUrl: string
  weight: number
  label: string
}

export interface PromptAssembleRequest {
  dramaId?: string | number
  sceneId?: string | number
  customScenePrompt?: string
  characterRefs?: CharacterShotRef[]
  shotPrompt?: string
  customPositivePrompt?: string
  customNegativePrompt?: string
  stylePreset?: string
}

export interface PromptAssembleResult {
  positivePrompt: string
  videoPrompt?: string
  negativePrompt: string
  loraList: LoraItem[]
  controlImages: ControlImage[]
  sceneSummary?: ResScene
  characterSummaries?: ResCharacter[]
}

export interface AssetUploadResult {
  bucket: string
  objectPath: string
  url: string
  originalFilename: string
  size: number
  contentType: string
}
