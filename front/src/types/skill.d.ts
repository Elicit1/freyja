/** AI Skill 标准包模型。 */
export interface AiSkill {
  id: string
  name: string
  displayName: string
  description: string
  enabled: number
  currentVersionId?: string | null
  currentVersion?: string | null
  sortOrder: number
  createTime?: string
  updateTime?: string
}

export interface AiSkillVersion {
  id: string
  skillId: string
  skillName: string
  version: string
  status: 'CURRENT' | 'HISTORY' | string
  contentHash: string
  contentSize: number | string
  createTime?: string
}

export interface AiSkillPreview {
  version: AiSkillVersion
  content: string
  files?: AiSkillFile[]
}

export interface AiSkillFile {
  id: string
  skillVersionId: string
  relativePath: string
  fileType: 'ENTRYPOINT' | 'SCRIPT' | 'REFERENCE' | 'ASSET' | 'RESOURCE' | string
  contentHash: string
  contentSize: number | string
}

export interface AiSkillSwitchVersionDTO {
  versionId: string
}
