<template>
  <div class="space-y-3">
    <!-- 1. 参考图槽位 (Picture 1..N，上限 9 张) -->
    <div class="media-block media-block--image space-y-2">
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-1.5">
          <span class="text-xs font-bold text-sky-950 flex items-center gap-1">
            <span>🖼️</span> 参考图槽位 (Picture 1..N)
          </span>
          <el-tag size="small" :type="refImages.length > 0 ? 'primary' : 'info'" effect="plain" class="font-mono !text-[10px]">
            {{ refImages.length }} / {{ maxImages }} 张
          </el-tag>
          <span class="text-[10px] text-slate-400">槽位顺序即为模型 Prompt 中的 &lt;Picture N&gt; 映射编号</span>
        </div>

        <el-button
          v-if="refImages.length < maxImages"
          size="small"
          type="primary"
          plain
          :disabled="disabled || candidateImages.filter(c => !c.isOccupied).length === 0"
          @click="openImagePicker()"
        >
          + 添加参考图槽位
        </el-button>
      </div>

      <!-- 槽位空状态 -->
      <div
        v-if="refImages.length === 0"
        class="text-center py-6 text-xs text-slate-400 bg-sky-50/40 rounded-lg border border-dashed border-sky-200 space-y-1.5"
      >
        <div class="text-xl">🖼️</div>
        <div>当前未编排参考图槽位</div>
        <div class="text-[11px] text-slate-400">
          <span v-if="candidateImages.length > 0">
            上方已选资产共有 <strong>{{ candidateImages.length }}</strong> 张可用素材，点击上方「+ 添加参考图槽位」开始编排
          </span>
          <span v-else>
            请先在上方「资产选择区」选择人物、场景或道具，候选图片将自动汇聚在此
          </span>
        </div>
      </div>

      <!-- 槽位卡片网格流 -->
      <div v-else class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-2 pt-1">
        <div
          v-for="(img, idx) in refImages"
          :key="img.id || idx"
          class="media-slot overflow-hidden bg-white flex flex-col transition-all group"
        >
          <!-- 图片预览与 Picture 标签 -->
          <div class="h-24 bg-slate-900 relative flex items-center justify-center overflow-hidden">
            <el-image
              :src="img.imageUrl"
              fit="cover"
              class="w-full h-full cursor-pointer"
              :preview-src-list="[img.imageUrl]"
              preview-teleported
            />
            <!-- 槽位角标 Picture N -->
            <span class="absolute top-1 left-1 bg-sky-600/90 text-white font-mono text-[10px] font-bold px-1.5 py-0.5 rounded shadow-xs">
              &lt;Picture {{ idx + 1 }}&gt;
            </span>

            <!-- 来源类型标签 -->
            <span
              class="absolute bottom-1 left-1 text-[9px] px-1 py-0.2 rounded font-medium bg-black/60 text-white"
            >
              {{ getSourceTypeLabel(img.sourceType) }}
            </span>

            <!-- 快捷删除 -->
            <button
              type="button"
              class="absolute top-1 right-1 w-5 h-5 rounded-full bg-black/60 text-white hover:bg-rose-600 flex items-center justify-center text-xs opacity-0 group-hover:opacity-100 transition-opacity"
              :disabled="disabled"
              @click="handleRemoveImage(idx)"
              title="移除该槽位"
            >
              ✕
            </button>
          </div>

          <!-- 素材名称与信息 -->
          <div class="p-1.5 space-y-1 bg-white">
            <div class="text-[11px] font-medium text-slate-800 truncate" :title="img.name">
              {{ img.name }}
            </div>

            <!-- 用途角色选择 -->
            <div class="flex items-center gap-1">
              <span class="text-[10px] text-slate-400 shrink-0">用途:</span>
              <el-select
                v-model="img.usageRole"
                size="small"
                class="w-full !text-[10px]"
                :disabled="disabled"
                @change="handleImageRoleChange"
              >
                <el-option label="主体 (SUBJECT)" value="SUBJECT" />
                <el-option label="场景 (SCENE)" value="SCENE" />
                <el-option label="道具 (PROP)" value="PROP" />
                <el-option label="首帧 (FIRST_FRAME)" value="FIRST_FRAME" />
                <el-option label="尾帧 (END_FRAME)" value="END_FRAME" />
              </el-select>
            </div>

            <!-- 排序与替换按钮条 -->
            <div class="flex items-center justify-between pt-1 border-t border-slate-100 text-[11px]">
              <div class="flex items-center gap-0.5">
                <el-button
                  size="small"
                  link
                  :disabled="disabled || idx === 0"
                  class="!p-0.5 !text-[10px]"
                  title="前移（减小 Picture 编号）"
                  @click="handleMoveImage(idx, idx - 1)"
                >
                  ◀
                </el-button>
                <el-button
                  size="small"
                  link
                  :disabled="disabled || idx === refImages.length - 1"
                  class="!p-0.5 !text-[10px]"
                  title="后移（增大 Picture 编号）"
                  @click="handleMoveImage(idx, idx + 1)"
                >
                  ▶
                </el-button>
              </div>

              <el-button
                size="small"
                link
                type="primary"
                :disabled="disabled"
                class="!p-0 !text-[10px]"
                @click="openImagePicker(idx)"
              >
                🔄 替换
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 2. 参考音频槽位 (Audio 1..N，上限 3 段，总长 ≤ 15s) -->
    <div class="media-block media-block--audio space-y-2">
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-2">
          <span class="text-xs font-bold text-indigo-950 flex items-center gap-1">
            <span>🎵</span> 参考音频槽位 (Audio 1..N)
          </span>
          <el-tag size="small" :type="refAudios.length > 0 ? 'primary' : 'info'" effect="plain" class="font-mono !text-[10px]">
            {{ refAudios.length }} / {{ maxAudios }} 段
          </el-tag>
          <span
            class="font-mono text-xs font-bold px-1.5 py-0.5 rounded"
            :class="totalAudioDuration <= maxTotalAudioDuration ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-700'"
          >
            总长: {{ totalAudioDuration.toFixed(1) }}s / {{ maxTotalAudioDuration }}s
          </span>
        </div>

        <el-button
          v-if="refAudios.length < maxAudios"
          size="small"
          type="primary"
          plain
          :disabled="disabled || candidateAudios.filter(a => !a.isOccupied).length === 0"
          @click="openAudioPicker()"
        >
          + 引入人物母音
        </el-button>
      </div>

      <!-- 音频槽位空状态 -->
      <div
        v-if="refAudios.length === 0"
        class="text-center py-4 text-xs text-slate-400 bg-indigo-50/40 rounded-lg border border-dashed border-indigo-200 space-y-1"
      >
        <div>暂未绑定参考音频（非必填）</div>
        <div class="text-[11px] text-slate-400">
          <span v-if="candidateAudios.length > 0">
            上方已选角色拥有 <strong>{{ candidateAudios.length }}</strong> 段母音样本，可点击上方「+ 引入人物母音」绑定
          </span>
          <span v-else>
            若需配置参考音频，可在上方选择已在角色库中完成母音设计的角色
          </span>
        </div>
      </div>

      <!-- 音频槽位列表 -->
      <div v-else class="space-y-2 pt-1">
        <div
          v-for="(aud, aIdx) in refAudios"
          :key="aud.id || aIdx"
          class="media-audio-row p-2.5 space-y-2 text-xs transition-all"
        >
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2 truncate flex-1 mr-2">
              <span class="font-mono font-bold text-indigo-700 bg-indigo-100 px-1.5 py-0.5 rounded text-[11px] shrink-0">
                &lt;Audio {{ aIdx + 1 }}&gt;
              </span>
              <span class="font-bold text-slate-800 truncate">{{ aud.characterName || aud.name || `音频#${aIdx + 1}` }}</span>
              <el-tag size="small" type="info" class="!text-[9px] !px-1">
                {{ aud.name }}
              </el-tag>
              <el-tag
                size="small"
                :type="(aud.duration || 0) >= 2 && (aud.duration || 0) <= 15 ? 'success' : 'danger'"
                class="!text-[9px] !px-1 font-mono"
              >
                {{ (aud.duration || 0).toFixed(1) }}s
              </el-tag>
            </div>

            <!-- 试听与操作 -->
            <div class="flex items-center gap-2 shrink-0">
              <el-button
                size="small"
                :type="playingAudioUrl === aud.audioUrl ? 'warning' : 'primary'"
                link
                @click="togglePlayAudio(aud.audioUrl)"
              >
                {{ playingAudioUrl === aud.audioUrl ? '⏸ 暂停' : '▶ 试听' }}
              </el-button>

              <div class="flex items-center gap-0.5">
                <el-button
                  size="small"
                  link
                  :disabled="disabled || aIdx === 0"
                  class="!p-0.5 !text-[10px]"
                  title="前移"
                  @click="handleMoveAudio(aIdx, aIdx - 1)"
                >
                  ▲
                </el-button>
                <el-button
                  size="small"
                  link
                  :disabled="disabled || aIdx === refAudios.length - 1"
                  class="!p-0.5 !text-[10px]"
                  title="后移"
                  @click="handleMoveAudio(aIdx, aIdx + 1)"
                >
                  ▼
                </el-button>
              </div>

              <el-button
                size="small"
                link
                type="primary"
                :disabled="disabled"
                @click="openAudioPicker(aIdx)"
              >
                🔄 替换
              </el-button>

              <el-button
                size="small"
                link
                type="danger"
                :disabled="disabled"
                @click="handleRemoveAudio(aIdx)"
              >
                ✕ 移除
              </el-button>
            </div>
          </div>

          <!-- 用途与语种配置行 (默认 VOICE_TIMBRE) -->
          <div class="grid grid-cols-1 sm:grid-cols-3 gap-2 pt-1 border-t border-slate-200/60 items-center">
            <div class="flex items-center gap-1.5 sm:col-span-2">
              <span class="text-[11px] text-slate-500 shrink-0">用途模式:</span>
              <el-select
                v-model="aud.usageMode"
                size="small"
                class="w-full"
                :disabled="disabled"
                @change="handleAudioModeChange"
              >
                <el-option label="音色参考 (VOICE_TIMBRE - 仅参考音色质感，推荐)" value="VOICE_TIMBRE" />
                <el-option label="台词复用 (DIALOGUE_REUSE - 严格复用该句语音)" value="DIALOGUE_REUSE" />
                <el-option label="背景音乐 (BGM_REUSE)" value="BGM_REUSE" />
                <el-option label="环境声效 (AMBIENT_REUSE)" value="AMBIENT_REUSE" />
              </el-select>
            </div>
            <div class="flex items-center gap-1.5">
              <span class="text-[11px] text-slate-500 shrink-0">语种:</span>
              <el-select
                v-model="aud.language"
                size="small"
                class="w-full"
                :disabled="disabled"
              >
                <el-option label="中文 (zh)" value="zh" />
                <el-option label="英文 (en)" value="en" />
              </el-select>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 3. 实时合规性校验警告条 -->
    <div v-if="validationAlerts.length > 0" class="space-y-1">
      <el-alert
        v-for="(alert, idx) in validationAlerts"
        :key="idx"
        type="warning"
        show-icon
        :closable="false"
        :title="alert"
        class="!py-1"
      />
    </div>

    <!-- 候选图片挑选弹窗 -->
    <el-dialog
      v-model="imagePickerDialogVisible"
      :title="pickerTargetSlotIndex !== null ? `替换 <Picture ${pickerTargetSlotIndex + 1}> 参考图` : `添加 <Picture ${refImages.length + 1}> 参考图`"
      width="640px"
      append-to-body
      destroy-on-close
    >
      <div class="space-y-3 p-1">
        <div class="text-xs text-slate-500 flex items-center justify-between gap-2">
          <span>从当前已选人物、场景、道具中挑选参考图（严格一一对应）：</span>
          <div class="flex items-center gap-2 shrink-0">
            <span class="text-[11px] text-slate-400">共 {{ candidateImages.length }} 张候选</span>
            <el-button
              v-if="pickerTargetSlotIndex === null"
              size="small"
              type="primary"
              plain
              :disabled="disabled || refImages.length >= maxImages || !candidateImages.some(c => !c.isOccupied)"
              @click="handleAddAllImages"
            >
              一键全选添加
            </el-button>
          </div>
        </div>

        <div v-if="candidateImages.length === 0" class="text-center py-10 text-xs text-slate-400">
          上方已选资产暂无可用图片。请检查所选人物是否配置头像/造型，场景是否配置空间图/封面。
        </div>
        <div v-else class="grid grid-cols-3 gap-3 max-h-96 overflow-y-auto pr-1">
          <div
            v-for="cand in candidateImages"
            :key="cand.id"
            class="border rounded-lg p-1.5 flex flex-col gap-1 transition-all relative group cursor-pointer"
            :class="cand.isOccupied ? 'border-slate-200 bg-slate-50 opacity-60 cursor-not-allowed' : 'border-slate-200 hover:border-sky-500 bg-white hover:shadow-xs'"
            @click="handleSelectCandidateImage(cand)"
          >
            <div class="h-24 bg-slate-100 rounded overflow-hidden flex items-center justify-center relative">
              <el-image :src="cand.imageUrl" fit="cover" class="w-full h-full" />
              <span class="absolute bottom-1 right-1 px-1 py-0.2 rounded text-[9px] font-medium bg-black/70 text-white">
                {{ cand.tag }}
              </span>
              <span
                v-if="cand.isOccupied"
                class="absolute top-1 left-1 px-1.5 py-0.5 rounded text-[10px] font-bold bg-amber-500 text-white shadow-xs"
              >
                已在 Picture {{ (cand.occupiedSlotIndex || 0) + 1 }}
              </span>
            </div>

            <div class="text-xs font-medium text-slate-800 truncate" :title="cand.name">
              {{ cand.name }}
            </div>
            <div class="flex items-center justify-between text-[10px] text-slate-400">
              <span>{{ cand.assetName }}</span>
              <span class="text-sky-600 font-mono">{{ cand.usageRole }}</span>
            </div>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- 候选音频挑选弹窗 -->
    <el-dialog
      v-model="audioPickerDialogVisible"
      :title="pickerTargetAudioSlotIndex !== null ? `替换 <Audio ${pickerTargetAudioSlotIndex + 1}> 参考音频` : `添加 <Audio ${refAudios.length + 1}> 参考音频`"
      width="540px"
      append-to-body
      destroy-on-close
    >
      <div class="space-y-3 p-1">
        <div class="text-xs text-slate-500 flex items-center justify-between gap-2">
          <span>仅可选择当前已选人物的专属母音样本：</span>
          <el-button
            v-if="pickerTargetAudioSlotIndex === null"
            size="small"
            type="primary"
            plain
            :disabled="disabled || refAudios.length >= maxAudios || !candidateAudios.some(a => !a.isOccupied)"
            @click="handleAddAllAudios"
          >
            一键全选添加
          </el-button>
        </div>

        <div v-if="candidateAudios.length === 0" class="text-center py-8 text-xs text-slate-400">
          当前已选人物暂无可用的母音样本。请在资产管理中为角色设计并保存专属声音母音。
        </div>
        <div v-else class="space-y-2 max-h-80 overflow-y-auto pr-1">
          <div
            v-for="cand in candidateAudios"
            :key="cand.id"
            class="border rounded-xl p-2.5 flex items-center justify-between gap-3 transition-all cursor-pointer"
            :class="cand.isOccupied ? 'border-slate-200 bg-slate-50 opacity-60 cursor-not-allowed' : 'border-slate-200 hover:border-indigo-400 bg-white hover:shadow-xs'"
            @click="handleSelectCandidateAudio(cand)"
          >
            <div class="min-w-0 flex-1 space-y-0.5">
              <div class="flex items-center gap-2">
                <span class="text-xs font-bold text-slate-800 truncate">{{ cand.characterName }}</span>
                <el-tag size="small" type="primary" class="!text-[9px] !px-1">{{ cand.tag }}</el-tag>
                <span
                  v-if="cand.isOccupied"
                  class="text-[10px] font-bold text-amber-600 bg-amber-50 px-1.5 py-0.5 rounded"
                >
                  已在 Audio {{ (cand.occupiedSlotIndex || 0) + 1 }}
                </span>
              </div>
              <div class="text-[11px] text-slate-500 truncate" :title="cand.text || cand.name">
                {{ cand.text || cand.name }}
              </div>
            </div>

            <div class="flex items-center gap-2 shrink-0">
              <el-button
                size="small"
                :type="playingAudioUrl === cand.audioUrl ? 'warning' : 'primary'"
                link
                @click.stop="togglePlayAudio(cand.audioUrl)"
              >
                {{ playingAudioUrl === cand.audioUrl ? '⏸ 暂停' : '▶ 试听' }}
              </el-button>
              <el-button
                size="small"
                type="primary"
                plain
                :disabled="cand.isOccupied"
              >
                选择
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import type { ShotRefImage, ShotRefAudio } from '@/types/drama'
import type { CandidateImageItem, CandidateAudioItem } from '../composables/useShotReferenceAssets'
import {
  MAX_REF_IMAGES,
  MAX_REF_AUDIOS,
  MIN_AUDIO_DURATION,
  MAX_AUDIO_DURATION,
  MAX_TOTAL_AUDIO_DURATION
} from '../composables/useShotReferenceAssets'

const props = defineProps<{
  refImages: ShotRefImage[]
  refAudios: ShotRefAudio[]
  candidateImages: CandidateImageItem[]
  candidateAudios: CandidateAudioItem[]
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:refImages', val: ShotRefImage[]): void
  (e: 'update:refAudios', val: ShotRefAudio[]): void
  (e: 'change'): void
}>()

const maxImages = MAX_REF_IMAGES
const maxAudios = MAX_REF_AUDIOS
const maxTotalAudioDuration = MAX_TOTAL_AUDIO_DURATION

// 图片弹窗
const imagePickerDialogVisible = ref(false)
const pickerTargetSlotIndex = ref<number | null>(null)

// 音频弹窗
const audioPickerDialogVisible = ref(false)
const pickerTargetAudioSlotIndex = ref<number | null>(null)

// 试听控制器
const playingAudioUrl = ref<string | null>(null)
let audioPlayer: HTMLAudioElement | null = null

const totalAudioDuration = computed(() => {
  return props.refAudios.reduce((sum, a) => sum + (Number(a.duration) || 0), 0)
})

const validationAlerts = computed(() => {
  const alerts: string[] = []
  if (totalAudioDuration.value > maxTotalAudioDuration) {
    alerts.push(`参考音频总时长 (${totalAudioDuration.value.toFixed(1)}s) 已超过 15 秒上限，渲染时可能被模型截断。`)
  }
  if (props.refAudios.length > 0 && props.refImages.length === 0) {
    alerts.push('注意：已配置参考音频，MiniMax H3 规范要求必须至少配置 1 张参考图。')
  }
  return alerts
})

function getSourceTypeLabel(type?: string) {
  switch (type) {
    case 'CHARACTER': return '人物'
    case 'SCENE': return '场景'
    case 'PROP': return '道具'
    case 'UPLOAD': return '上传'
    default: return '素材'
  }
}

function openImagePicker(slotIndex: number | null = null) {
  pickerTargetSlotIndex.value = slotIndex
  imagePickerDialogVisible.value = true
}

function createRefImage(cand: CandidateImageItem): ShotRefImage {
  return {
    id: `img_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
    name: cand.name,
    imageUrl: cand.imageUrl,
    sourceType: cand.sourceType,
    sourceId: cand.sourceId,
    characterId: cand.characterId,
    lookId: cand.lookId,
    referenceRole: cand.referenceRole,
    usageRole: cand.usageRole
  }
}

function handleSelectCandidateImage(cand: CandidateImageItem) {
  if (props.disabled) return
  if (cand.isOccupied) {
    ElMessage.warning('该图片素材已在其他槽位中使用，不可重复占用')
    return
  }

  const next = [...props.refImages]
  const newImg = createRefImage(cand)

  if (pickerTargetSlotIndex.value !== null && pickerTargetSlotIndex.value < next.length) {
    next[pickerTargetSlotIndex.value] = newImg
  } else {
    next.push(newImg)
  }

  emit('update:refImages', next)
  emit('change')
  imagePickerDialogVisible.value = false
}

function handleAddAllImages() {
  if (props.disabled || pickerTargetSlotIndex.value !== null) return
  const available = props.candidateImages.filter(cand => !cand.isOccupied)
  const selected = available.slice(0, Math.max(0, maxImages - props.refImages.length))
  if (selected.length === 0) return

  emit('update:refImages', [...props.refImages, ...selected.map(createRefImage)])
  emit('change')
  imagePickerDialogVisible.value = false
  ElMessage.success(`已添加 ${selected.length} 张参考图${selected.length < available.length ? '，其余候选已达槽位上限' : ''}`)
}

function handleRemoveImage(index: number) {
  const next = [...props.refImages]
  next.splice(index, 1)
  emit('update:refImages', next)
  emit('change')
}

function handleMoveImage(fromIdx: number, toIdx: number) {
  if (fromIdx < 0 || fromIdx >= props.refImages.length) return
  if (toIdx < 0 || toIdx >= props.refImages.length) return
  const next = [...props.refImages]
  const [moved] = next.splice(fromIdx, 1)
  next.splice(toIdx, 0, moved)
  emit('update:refImages', next)
  emit('change')
}

function handleImageRoleChange() {
  emit('update:refImages', [...props.refImages])
  emit('change')
}

function openAudioPicker(slotIndex: number | null = null) {
  pickerTargetAudioSlotIndex.value = slotIndex
  audioPickerDialogVisible.value = true
}

function createRefAudio(cand: CandidateAudioItem): ShotRefAudio {
  return {
    id: `aud_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
    name: cand.name,
    audioUrl: cand.audioUrl,
    sourceType: cand.sourceType,
    characterId: cand.characterId,
    characterName: cand.characterName,
    text: cand.text,
    duration: cand.duration || 3.5,
    usageMode: cand.usageMode || 'VOICE_TIMBRE',
    language: cand.language || 'zh'
  }
}

function probeAudioDuration(audio: ShotRefAudio) {
  const probe = new Audio(audio.audioUrl)
  probe.addEventListener('loadedmetadata', () => {
    if (!Number.isFinite(probe.duration) || probe.duration <= 0) return
    const index = props.refAudios.findIndex(item => item.id === audio.id)
    if (index < 0) return
    const next = props.refAudios.map(item => ({ ...item }))
    next[index].duration = Math.round(probe.duration * 10) / 10
    emit('update:refAudios', next)
    emit('change')
  }, { once: true })
}

function handleSelectCandidateAudio(cand: CandidateAudioItem) {
  if (props.disabled) return
  if (cand.isOccupied) {
    ElMessage.warning('该音频已在其他槽位中使用')
    return
  }

  const next = [...props.refAudios]
  const newAud = createRefAudio(cand)

  if (pickerTargetAudioSlotIndex.value !== null && pickerTargetAudioSlotIndex.value < next.length) {
    next[pickerTargetAudioSlotIndex.value] = newAud
  } else {
    next.push(newAud)
  }

  emit('update:refAudios', next)
  emit('change')
  probeAudioDuration(newAud)
  audioPickerDialogVisible.value = false
}

function handleAddAllAudios() {
  if (props.disabled || pickerTargetAudioSlotIndex.value !== null) return
  const available = props.candidateAudios.filter(cand => !cand.isOccupied)
  const selected: ShotRefAudio[] = []
  let duration = totalAudioDuration.value
  for (const cand of available) {
    if (props.refAudios.length + selected.length >= maxAudios) break
    const candidateDuration = Number(cand.duration) || 3.5
    if (candidateDuration < MIN_AUDIO_DURATION || candidateDuration > MAX_AUDIO_DURATION
      || duration + candidateDuration > maxTotalAudioDuration) continue
    selected.push(createRefAudio(cand))
    duration += candidateDuration
  }
  if (selected.length === 0) {
    ElMessage.warning('剩余音频槽位或时长不足，无法批量添加')
    return
  }

  emit('update:refAudios', [...props.refAudios, ...selected])
  emit('change')
  selected.forEach(probeAudioDuration)
  audioPickerDialogVisible.value = false
  ElMessage.success(`已添加 ${selected.length} 段参考音频${selected.length < available.length ? '，其余候选受槽位或时长限制未添加' : ''}`)
}

function handleRemoveAudio(index: number) {
  const next = [...props.refAudios]
  next.splice(index, 1)
  emit('update:refAudios', next)
  emit('change')
}

function handleMoveAudio(fromIdx: number, toIdx: number) {
  if (fromIdx < 0 || fromIdx >= props.refAudios.length) return
  if (toIdx < 0 || toIdx >= props.refAudios.length) return
  const next = [...props.refAudios]
  const [moved] = next.splice(fromIdx, 1)
  next.splice(toIdx, 0, moved)
  emit('update:refAudios', next)
  emit('change')
}

function handleAudioModeChange() {
  emit('update:refAudios', [...props.refAudios])
  emit('change')
}

function togglePlayAudio(url: string) {
  if (playingAudioUrl.value === url && audioPlayer) {
    audioPlayer.pause()
    playingAudioUrl.value = null
    return
  }
  if (audioPlayer) {
    audioPlayer.pause()
  }
  audioPlayer = new Audio(url)
  playingAudioUrl.value = url
  audioPlayer.onended = () => {
    playingAudioUrl.value = null
  }
  audioPlayer.onerror = () => {
    playingAudioUrl.value = null
    ElMessage.error('音频播放失败')
  }
  audioPlayer.play().catch(() => {
    playingAudioUrl.value = null
  })
}

onBeforeUnmount(() => {
  if (audioPlayer) {
    audioPlayer.pause()
    audioPlayer = null
  }
})
</script>

<style scoped>
.media-block {
  padding: 12px 14px;
  border-radius: 14px;
  background: #fff;
}

.media-block--image {
  border: 1px solid #bfdbfe;
}

.media-block--audio {
  border: 1px solid #c7d2fe;
}

.media-slot {
  border: 1px solid #dbeafe;
  border-radius: 10px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .04);
}

.media-slot:hover {
  border-color: #60a5fa;
  box-shadow: 0 4px 12px rgba(59, 130, 246, .10);
  transform: translateY(-1px);
}

.media-audio-row {
  border: 1px solid #e0e7ff;
  border-radius: 10px;
  background: #f8faff;
}

.media-audio-row:hover {
  border-color: #a5b4fc;
  background: #fff;
}
</style>
