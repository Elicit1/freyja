<template>
  <div class="asset-section space-y-2.5">
    <div class="asset-section__header flex items-center justify-between">
      <span class="text-xs font-bold text-slate-800 flex items-center gap-1.5">
        <span>🎭</span> 资产设定参考 (出场角色、场景与关键道具)
      </span>
      <span class="text-[11px] text-slate-500">
        在此调整镜头资产装配，确认采纳后将同步回填至分镜
      </span>
    </div>

    <!-- 1. 人物与造型 (多选人物，每个选造型) -->
    <div class="asset-block space-y-2">
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-1.5">
          <span class="text-xs font-semibold text-slate-700">👥 出场人物与造型装配 ({{ characterRefs.length }} 人)</span>
          <span class="text-[10px] text-slate-400">选择人物及具体造型，自动为槽位提取头像、FaceID与服装图</span>
        </div>
        <el-select
          v-model="selectedCharIdToAdd"
          placeholder="+ 添加出场人物"
          size="small"
          filterable
          class="w-48"
          :disabled="disabled"
          @change="handleAddCharacter"
        >
          <el-option
            v-for="c in availableCharacterOptions"
            :key="String(c.id)"
            :label="`${c.name} (${c.roleType || '角色'})`"
            :value="String(c.id)"
          />
        </el-select>
      </div>

      <div v-if="characterRefs.length === 0" class="text-slate-400 text-xs py-3 text-center bg-slate-50 rounded border border-dashed border-slate-200">
        （暂未指定出场角色，可通过右上角下拉框添加）
      </div>
      <div v-else class="grid grid-cols-1 md:grid-cols-2 gap-2">
        <div
          v-for="(cRef, idx) in characterRefs"
          :key="String(cRef.characterId || idx)"
          class="asset-item rounded-lg p-2 hover:bg-white transition-all space-y-1.5"
        >
          <div class="flex items-center justify-between gap-2">
            <div class="flex items-center gap-2 min-w-0 flex-1">
              <el-avatar
                :size="28"
                :src="getCharacterAvatar(cRef.characterId)"
                class="bg-indigo-100 text-indigo-700 font-bold shrink-0 text-xs"
              >
                {{ (getCharacterName(cRef.characterId) || '角').substring(0, 1) }}
              </el-avatar>
              <div class="min-w-0 flex-1">
                <div class="flex items-center gap-1.5">
                  <span class="text-xs font-bold text-slate-800 truncate">{{ getCharacterName(cRef.characterId) }}</span>
                  <el-tag size="small" type="info" class="!text-[9px] !px-1 !h-4">
                    {{ getCharacterRole(cRef.characterId) }}
                  </el-tag>
                </div>
              </div>
            </div>

            <!-- 徽章与移除按钮 -->
            <div class="flex items-center gap-1 shrink-0">
              <el-tag
                v-if="getCharacterImageCount(cRef.characterId) > 0"
                size="small"
                type="success"
                effect="plain"
                class="!text-[9px] !px-1 !h-4"
              >
                🖼️ {{ getCharacterImageCount(cRef.characterId) }} 张图
              </el-tag>
              <el-tag
                v-else
                size="small"
                type="warning"
                effect="plain"
                class="!text-[9px] !px-1 !h-4"
              >
                仅文字设定
              </el-tag>

              <el-tag
                v-if="hasVoiceSample(cRef.characterId)"
                size="small"
                type="primary"
                effect="plain"
                class="!text-[9px] !px-1 !h-4"
              >
                🎙️ 专属母音
              </el-tag>

              <el-button
                type="danger"
                link
                size="small"
                class="!p-0 ml-1 text-xs"
                :disabled="disabled"
                @click="handleRemoveCharacter(idx)"
                title="移除此角色"
              >
                ✕
              </el-button>
            </div>
          </div>

          <!-- 造型服装下拉 -->
          <div class="flex items-center gap-2 pt-0.5">
            <span class="text-[11px] text-slate-500 shrink-0">装配造型:</span>
            <el-select
              :model-value="cRef.lookId ? String(cRef.lookId) : undefined"
              placeholder="选择具体造型服装"
              size="small"
              class="flex-1"
              :disabled="disabled"
              @update:model-value="(val: any) => handleOutfitSelect(cRef, val)"
            >
              <el-option
                v-for="o in getOutfitsForCharacter(cRef.characterId)"
                :key="String(o.id)"
                :label="`${o.lookName} ${o.isDefault === 1 ? '(默认)' : ''}`"
                :value="String(o.id)"
              />
            </el-select>
          </div>

          <!-- 文字外观描述摘要 -->
          <div class="text-[11px] text-slate-500 line-clamp-1 bg-white px-1.5 py-0.5 rounded border border-slate-100" :title="getCharacterDesc(cRef.characterId, cRef.lookId)">
            {{ getCharacterDesc(cRef.characterId, cRef.lookId) }}
          </div>
        </div>
      </div>
    </div>

    <!-- 2. 环境场景 (单选一个场景) -->
    <div class="asset-block space-y-2">
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-1.5">
          <span class="text-xs font-semibold text-slate-700">🏞️ 镜头环境场景 (单选)</span>
          <span class="text-[10px] text-slate-400">优先提取空间参考图与封面作为参考图</span>
        </div>
        <div class="flex items-center gap-2">
          <el-select
            :model-value="resSceneId ? String(resSceneId) : undefined"
            placeholder="选择环境场景资产"
            size="small"
            filterable
            clearable
            class="w-64"
            :disabled="disabled"
            @update:model-value="handleSceneSelect"
          >
            <el-option
              v-for="s in sceneOptions"
              :key="String(s.id)"
              :label="`${s.name}${s.sceneType || s.timeOfDay ? ` (${[s.sceneType, s.timeOfDay].filter(Boolean).join('/')})` : ''}${s.referenceImageUrl ? ' 🌐空间图' : (s.coverUrl ? ' 🖼️封面' : ' 📝')}`"
              :value="String(s.id)"
            />
          </el-select>
        </div>
      </div>

      <div v-if="!currentScene" class="text-slate-400 text-xs py-2 text-center bg-slate-50 rounded border border-dashed border-slate-200">
        （未单独指定镜头场景，默认继承剧集场次环境）
      </div>
      <div v-else class="flex items-start gap-3 bg-emerald-50/50 border border-emerald-200/80 rounded-lg p-2">
        <div class="w-14 h-14 rounded overflow-hidden bg-slate-100 shrink-0 border border-emerald-100 flex items-center justify-center">
          <el-image
            v-if="currentScene.referenceImageUrl || currentScene.coverUrl"
            :src="currentScene.referenceImageUrl || currentScene.coverUrl"
            fit="cover"
            class="w-full h-full"
          />
          <span v-else class="text-lg">🏞️</span>
        </div>
        <div class="flex-1 min-w-0 space-y-1">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <span class="text-xs font-bold text-slate-800 truncate">{{ currentScene.name }}</span>
              <el-tag v-if="currentScene.sceneType || currentScene.timeOfDay" size="small" type="success" class="!text-[9px] !px-1 !h-4">
                {{ [currentScene.sceneType, currentScene.timeOfDay].filter(Boolean).join(' · ') }}
              </el-tag>
            </div>
            <div class="flex items-center gap-1.5">
              <el-tag
                v-if="currentScene.referenceImageUrl"
                size="small"
                type="primary"
                effect="plain"
                class="!text-[9px] !px-1 !h-4"
              >
                🌐 空间参考图
              </el-tag>
              <el-tag
                v-if="currentScene.coverUrl"
                size="small"
                type="success"
                effect="plain"
                class="!text-[9px] !px-1 !h-4"
              >
                🖼️ 场景封面
              </el-tag>
              <el-tag
                v-if="!currentScene.referenceImageUrl && !currentScene.coverUrl"
                size="small"
                type="warning"
                effect="plain"
                class="!text-[9px] !px-1 !h-4"
              >
                仅文字设定
              </el-tag>
              <el-button
                type="danger"
                link
                size="small"
                class="!p-0 ml-1 text-xs"
                :disabled="disabled"
                @click="handleClearScene"
                title="清除场景"
              >
                ✕
              </el-button>
            </div>
          </div>
          <div class="text-[11px] text-slate-600 line-clamp-1" :title="currentScene.scenePrompt || currentScene.description || '无详细描述'">
            {{ currentScene.scenePrompt || currentScene.description || '无详细提示词描述' }}
          </div>
        </div>
      </div>
    </div>

    <!-- 3. 关键道具 (多选) -->
    <div class="asset-block space-y-2">
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-1.5">
          <span class="text-xs font-semibold text-slate-700">🗡️ 关键道具装配 ({{ propRefs.length }} 件)</span>
          <span class="text-[10px] text-slate-400">注入核心道具的视觉设定与提示词，自动提供道具设计参考图</span>
        </div>
        <el-select
          v-model="selectedPropIdToAdd"
          placeholder="+ 添加道具"
          size="small"
          filterable
          class="w-48"
          :disabled="disabled"
          @change="handleAddProp"
        >
          <el-option
            v-for="p in availablePropOptions"
            :key="String(p.id)"
            :label="`${p.name} (${p.propType || 'KEY_PROP'})${p.coverUrl ? ' 🖼️' : ' 📝'}`"
            :value="String(p.id)"
          />
        </el-select>
      </div>

      <div v-if="propRefs.length === 0" class="text-slate-400 text-xs py-2 text-center bg-slate-50 rounded border border-dashed border-slate-200">
        （未绑定关键道具设定，可通过右上角下拉框添加）
      </div>
      <div v-else class="flex flex-wrap gap-1.5">
        <div
          v-for="(item, idx) in propRefs"
          :key="String(item.propId || idx)"
          class="flex items-center gap-1.5 border rounded-lg px-2 py-1 text-xs bg-purple-50/70 border-purple-200 text-purple-950"
        >
          <el-avatar
            v-if="item.coverUrl"
            :size="20"
            :src="item.coverUrl"
            shape="square"
            class="shrink-0"
          />
          <span class="font-medium">{{ item.propName || `道具#${item.propId}` }}</span>
          <el-tag
            size="small"
            :type="item.coverUrl ? 'success' : 'warning'"
            effect="plain"
            class="!text-[9px] !px-1 !h-4"
          >
            {{ item.coverUrl ? '文字 + 图片' : '仅文字设定' }}
          </el-tag>
          <el-button
            type="danger"
            link
            size="small"
            class="!p-0 !text-xs ml-0.5"
            :disabled="disabled"
            @click="handleRemoveProp(idx)"
            title="移除此项设定"
          >
            ✕
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { CharacterShotRefInfo, PropShotRefInfo } from '@/types/drama'
import type { ResCharacterOption, ResSceneOption, ResPropOption } from '@/types/resource'

const props = defineProps<{
  characterRefs: CharacterShotRefInfo[]
  resSceneId?: string | number
  propRefs: PropShotRefInfo[]
  characterOptions: ResCharacterOption[]
  sceneOptions: ResSceneOption[]
  propOptions: ResPropOption[]
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:characterRefs', val: CharacterShotRefInfo[]): void
  (e: 'update:resSceneId', val?: string | number): void
  (e: 'update:propRefs', val: PropShotRefInfo[]): void
}>()

const selectedCharIdToAdd = ref<string | undefined>()
const selectedPropIdToAdd = ref<string | undefined>()

// 过滤未添加的角色与道具
const availableCharacterOptions = computed(() => {
  return props.characterOptions.filter(
    c => !props.characterRefs.some(ref => String(ref.characterId) === String(c.id))
  )
})

const availablePropOptions = computed(() => {
  return props.propOptions.filter(
    p => !props.propRefs.some(ref => String(ref.propId) === String(p.id))
  )
})

const currentScene = computed(() => {
  if (!props.resSceneId) return null
  return props.sceneOptions.find(s => String(s.id) === String(props.resSceneId)) || null
})

function getCharacter(characterId?: string | number) {
  if (!characterId) return null
  return props.characterOptions.find(c => String(c.id) === String(characterId)) || null
}

function getCharacterName(characterId?: string | number) {
  const c = getCharacter(characterId)
  return c?.name || (characterId ? `角色#${characterId}` : '未选择')
}

function getCharacterRole(characterId?: string | number) {
  const c = getCharacter(characterId)
  return c?.roleType || '角色'
}

function getCharacterAvatar(characterId?: string | number) {
  const c = getCharacter(characterId)
  return c?.avatarUrl || undefined
}

function getCharacterDesc(characterId?: string | number, lookId?: string | number) {
  const c = getCharacter(characterId)
  const look = c?.outfits?.find(item => lookId && String(item.id) === String(lookId))
  return look?.designDesc || c?.appearancePrompt || c?.triggerWords || c?.voiceDesc || '暂无文字设定描述'
}

function hasVoiceSample(characterId?: string | number) {
  const c = getCharacter(characterId)
  return !!c?.voiceSampleUrl
}

function getOutfitsForCharacter(characterId?: string | number) {
  const c = getCharacter(characterId)
  return c?.outfits || []
}

function getCharacterImageCount(characterId?: string | number): number {
  const c = getCharacter(characterId)
  if (!c) return 0
  let count = 0
  if (c.referenceImageUrl || c.avatarUrl) count++
  for (const o of c.outfits || []) {
    if (o.referenceImageUrl) count++
  }
  return count
}

function handleAddCharacter(charId?: string) {
  if (!charId) return
  const char = props.characterOptions.find(c => String(c.id) === String(charId))
  if (char) {
    const defaultOutfit = char.outfits?.find(o => o.isDefault === 1)
    const next = [...props.characterRefs]
    next.push({
      characterId: String(char.id),
      characterName: char.name,
      avatarUrl: char.avatarUrl,
      roleType: char.roleType,
      lookId: defaultOutfit ? String(defaultOutfit.id) : undefined,
      outfitName: defaultOutfit?.lookName,
      outfitPreviewUrl: defaultOutfit?.referenceImageUrl,
      designDesc: defaultOutfit?.designDesc,
      outfitPrompt: defaultOutfit?.outfitPrompt,
      appearancePrompt: defaultOutfit?.appearancePrompt,
      positionTag: 'center'
    })
    emit('update:characterRefs', next)
  }
  selectedCharIdToAdd.value = undefined
}

function handleRemoveCharacter(index: number) {
  const next = [...props.characterRefs]
  next.splice(index, 1)
  emit('update:characterRefs', next)
}

function handleOutfitSelect(cRef: CharacterShotRefInfo, lookId?: string) {
  const next = props.characterRefs.map(item => {
    if (String(item.characterId) === String(cRef.characterId)) {
      const char = getCharacter(item.characterId)
      const outfit = char?.outfits?.find(o => String(o.id) === String(lookId))
      return {
        ...item,
        lookId: lookId || undefined,
        outfitName: outfit?.lookName,
        outfitPreviewUrl: outfit?.referenceImageUrl,
        designDesc: outfit?.designDesc,
        outfitPrompt: outfit?.outfitPrompt,
        appearancePrompt: outfit?.appearancePrompt
      }
    }
    return item
  })
  emit('update:characterRefs', next)
}

function handleSceneSelect(val?: string) {
  emit('update:resSceneId', val || undefined)
}

function handleClearScene() {
  emit('update:resSceneId', undefined)
}

function handleAddProp(propId?: string) {
  if (!propId) return
  const p = props.propOptions.find(item => String(item.id) === String(propId))
  if (p) {
    const next = [...props.propRefs]
    next.push({
      propId: String(p.id),
      propName: p.name,
      propType: p.propType || 'KEY_PROP',
      propPrompt: p.propPrompt || '',
      coverUrl: p.coverUrl || undefined
    })
    emit('update:propRefs', next)
  }
  selectedPropIdToAdd.value = undefined
}

function handleRemoveProp(index: number) {
  const next = [...props.propRefs]
  next.splice(index, 1)
  emit('update:propRefs', next)
}
</script>

<style scoped>
.asset-section {
  padding: 12px 14px;
  border: 1px solid #dbe4ef;
  border-radius: 14px;
  background: #f8fafc;
}

.asset-section__header {
  padding-bottom: 8px;
  border-bottom: 1px solid #e2e8f0;
}

.asset-block {
  padding: 9px 0;
  border-bottom: 1px solid #e2e8f0;
}

.asset-block:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.asset-item {
  border: 1px solid #e2e8f0;
  background: rgba(248, 250, 252, .72);
}

.asset-item:hover {
  border-color: #93c5fd;
}
</style>
