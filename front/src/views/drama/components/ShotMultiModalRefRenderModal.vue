<template>
  <el-dialog
    v-model="visible"
    :title="`🎬 分镜 [S${shotNo} ${shotName || ''}] 多模态参考渲染工坊`"
    width="960px"
    destroy-on-close
    append-to-body
    :close-on-click-modal="false"
  >
    <!-- 顶部横幅提示：多模态参考素材规则 -->
    <div class="p-3.5 mb-4 bg-sky-50/80 border border-sky-200 rounded-xl flex items-start gap-2.5 text-xs text-sky-950">
      <span class="text-lg leading-none">💡</span>
      <div class="flex-1 space-y-1">
        <div class="font-bold flex items-center gap-2">
          <span>多模态参考生成模式（图片 / 视频 / 音频）</span>
          <el-tag size="small" type="primary" effect="dark" class="!bg-sky-600 !border-sky-600 text-[10px]">
            多模态特征融合
          </el-tag>
        </div>
        <p class="text-sky-800 leading-relaxed">
          本模式支持多重视觉特征与语音韵律协同驱动。
          <strong>参考图上限 {{ maxRefImages }} 张</strong>（可从场景、人物造型、道具资产库多选选取或自行上传）；
          <strong>参考视频{{ maxRefVideos == null ? '数量以所选模型配置为准' : `上限 ${maxRefVideos} 段` }}</strong>（可上传或选择视频，并分别设置片段范围）；
          <strong>参考音频上限 {{ maxRefAudios }} 段</strong>（单段2至15秒，总长不超过15秒，从当前短剧角色的配音中心选择）。
          参考素材<strong>不强制要求</strong>，亦可仅凭提示词直接渲染。
        </p>
      </div>
    </div>

    <div class="space-y-4 max-h-[72vh] overflow-y-auto px-1 py-1 custom-scrollbar">
      <!-- 渲染引擎与参数 -->
      <div class="bg-slate-50 border border-slate-200 rounded-xl p-3.5 space-y-3">
        <div class="flex items-center justify-between text-xs font-bold text-slate-700">
          <span>⚙️ 渲染引擎与多模态模型参数</span>
          <span class="text-[11px] font-normal text-slate-400">支持的参考素材类型以所选模型能力为准</span>
        </div>
        <div class="grid grid-cols-4 gap-3 text-xs">
          <div>
            <label class="text-[11px] font-medium text-slate-600 mb-1 block">AI 提供商</label>
            <el-select v-model="renderConfig.providerId" placeholder="请选择支持参考模式的提供商" size="small" class="w-full" @change="handleProviderChange">
              <el-option
                v-for="p in enabledProviders"
                :key="String(p.id)"
                :label="p.providerName"
                :value="String(p.id)"
              />
            </el-select>
          </div>
          <div>
            <label class="text-[11px] font-medium text-slate-600 mb-1 block">视频模型代码</label>
            <el-select
              v-model="renderConfig.modelCode"
              placeholder="请选择已配置的视频模型"
              size="small"
              filterable
              :disabled="!renderConfig.providerId || models.length === 0"
              class="w-full"
            >
              <el-option
                v-for="m in models"
                :key="m.modelCode"
                :label="`${m.modelName} (${m.modelCode})`"
                :value="m.modelCode"
              />
            </el-select>
          </div>
          <div>
            <div class="flex items-center justify-between mb-1">
              <label class="text-[11px] font-medium text-slate-600 block">画幅尺寸</label>
              <span v-if="dramaAspectRatio" class="text-[10px] text-sky-600 font-mono">
                短剧配置: {{ dramaAspectRatio }}
              </span>
            </div>
            <el-select v-model="renderConfig.size" size="small" filterable allow-create default-first-option class="w-full">
              <el-option-group label="H3 横屏规格（32 像素对齐）">
                <el-option label="960x544 (16:9 最低/默认推荐)" value="960x544" />
                <el-option label="1024x576 (精确 16:9)" value="1024x576" />
                <el-option label="1280x736 (近 16:9 高清横屏)" value="1280x736" />
                <el-option label="1344x768 (H3 官方 768p 规格)" value="1344x768" />
              </el-option-group>
              <el-option-group label="H3 竖屏规格（32 像素对齐）">
                <el-option label="544x960 (9:16 标清竖屏)" value="544x960" />
                <el-option label="576x1024 (精确 9:16)" value="576x1024" />
                <el-option label="736x1280 (近 9:16 高清竖屏)" value="736x1280" />
                <el-option label="768x1344 (H3 官方 768p 竖屏)" value="768x1344" />
              </el-option-group>
              <el-option-group label="其他常用画幅">
                <el-option label="1024x1024 (1:1 正方形)" value="1024x1024" />
                <el-option label="1024x768 (4:3 经典横屏)" value="1024x768" />
                <el-option label="768x1024 (3:4 经典竖屏)" value="768x1024" />
              </el-option-group>
            </el-select>
          </div>
          <div>
            <label class="text-[11px] font-medium text-slate-600 mb-1 block">随机种子 Seed</label>
            <el-input :model-value="shotSeed || '镜头未设置'" size="small" readonly class="!w-full font-mono" />
          </div>
        </div>
      </div>

      <!-- 提示词与负向词 -->
      <div class="space-y-2">
        <div class="flex items-center justify-between">
          <label class="text-xs font-bold text-slate-800 flex items-center gap-1">
            <span>🎬</span> 提示词 (Prompt / 运镜动力与动作)
          </label>
          <el-button size="small" link type="primary" @click="copyText(prompt, '提示词已复制')">
            复制
          </el-button>
        </div>
        <el-input
          v-model="prompt"
          type="textarea"
          :rows="6"
          placeholder="请输入或微调镜头提示词，即使无参考图亦可凭此提示词生成画面..."
        />
        <el-input
          v-model="negativePrompt"
          placeholder="负向提示词 (Negative Prompt): 如 low quality, blurry, bad anatomy..."
          size="small"
          clearable
        />
      </div>

      <!-- 1. 参考图资产工作台 (上限 5 张) -->
      <!-- 1. 参考图资产工作台 (上限动态绑定模型配置) -->
      <div class="border border-sky-200 rounded-xl p-4 bg-sky-50/20 space-y-3">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-bold text-sky-950 flex items-center gap-1">
              <span>🖼️</span> 参考图资产 (最多 {{ maxRefImages }} 张)
            </span>
            <el-tag size="small" :type="refImages.length >= maxRefImages ? 'danger' : 'primary'" effect="plain" class="font-mono">
              {{ refImages.length }} / {{ maxRefImages }}
            </el-tag>
            <span class="text-[11px] text-gray-400">支持人物、场景、道具多选与本地上传</span>
          </div>

          <!-- 添加入口按钮组 -->
          <div class="flex items-center gap-2">
            <el-button
              size="small"
              type="primary"
              plain
              :disabled="refImages.length >= maxRefImages"
              @click="handleOpenAssetPicker"
            >
              + 从资产库多选引用 (人物/道具/场景)
            </el-button>

            <el-upload
              :show-file-list="false"
              :before-upload="handleUploadRefImage"
              accept="image/*"
              :disabled="refImages.length >= maxRefImages"
            >
              <el-button size="small" plain :disabled="refImages.length >= maxRefImages" :loading="uploadingImage">
                📤 本地上传
              </el-button>
            </el-upload>
          </div>
        </div>

        <!-- 空状态 -->
        <div v-if="refImages.length === 0" class="text-center py-6 text-xs text-gray-400 bg-white border border-dashed border-gray-200 rounded-lg">
          暂无参考图，点击上方按钮可从资产库选取或本地上传 (可选，非强制)
        </div>

        <!-- 已选参考图网格 -->
        <div v-else class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3">
          <div
            v-for="(img, idx) in refImages"
            :key="img.id || idx"
            class="bg-white border border-slate-200 rounded-lg overflow-hidden shadow-sm flex flex-col group relative"
          >
            <div class="h-28 bg-slate-900 relative overflow-hidden flex items-center justify-center">
              <el-image
                :src="img.imageUrl"
                :preview-src-list="refImages.map(x => x.imageUrl)"
                preview-teleported
                fit="cover"
                class="w-full h-full cursor-pointer"
              />
              <span class="absolute top-1 left-1 bg-black/75 text-white font-mono text-[9px] px-1 py-0.5 rounded">
                Picture {{ idx + 1 }}
              </span>
              <button
                type="button"
                class="absolute top-1 right-1 w-5 h-5 rounded-full bg-black/60 text-white hover:bg-red-600 flex items-center justify-center text-xs opacity-0 group-hover:opacity-100 transition-opacity"
                title="移除该参考图"
                @click.stop="handleRemoveImage(idx)"
              >
                ✕
              </button>
            </div>
            <div class="p-1.5 text-[11px] text-gray-700 truncate" :title="img.name">
              {{ img.name || `参考图 ${idx + 1}` }}
            </div>
            <div class="px-1.5 pb-1.5">
              <el-select v-model="img.usageRole" size="small" placeholder="角色" class="w-full !text-[10px]">
                <el-option label="主体 (SUBJECT)" value="SUBJECT" />
                <el-option
                  v-if="['CHARACTER', 'CHARACTER_REFERENCE', 'UPLOAD'].includes(img.sourceType)"
                  label="非主体参考（第一人称 / 画外人物）"
                  value="NON_SUBJECT"
                />
                <el-option label="场景 (SCENE)" value="SCENE" />
                <el-option v-for="role in KEY_IMAGE_ROLE_OPTIONS" :key="role.value" :label="role.label" :value="role.value" />
                <el-option label="道具 (PROP)" value="PROP" />
              </el-select>
              <p v-if="img.usageRole === 'NON_SUBJECT'" class="mt-1 text-[10px] text-amber-700">
                仅作人物设定参考，不据此安排人物出镜。更改用途后请重新生成提示词。
              </p>
            </div>
          </div>
        </div>
      </div>

      <!-- 2. 参考音频工作台 (上限动态绑定模型配置) -->
      <div class="border border-indigo-200 rounded-xl p-4 bg-indigo-50/20 space-y-3">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-bold text-indigo-950 flex items-center gap-1">
              <span>🎵</span> 参考音频资产 (最多 {{ maxRefAudios }} 段 · 单段 2~15s · 总长 ≤ 15s)
            </span>
            <el-tag size="small" :type="refAudios.length >= maxRefAudios ? 'danger' : 'primary'" effect="plain" class="font-mono">
              {{ refAudios.length }} / {{ maxRefAudios }}
            </el-tag>
          </div>

          <!-- 添加入口按钮组 -->
          <div class="flex items-center gap-2">
            <el-button size="small" plain :disabled="refAudios.length >= maxRefAudios" @click="openAudioPicker()">选择参考音频</el-button>
          </div>
        </div>

        <!-- 音频时长统计与状态条 -->
        <div class="bg-white border border-slate-200 rounded-lg p-2.5 flex items-center justify-between gap-4 text-xs">
          <div class="flex items-center gap-2">
            <span class="font-medium text-gray-600">音频总时长:</span>
            <span class="font-mono font-bold text-sm" :class="getDurationColorClass(totalAudioDuration)">
              {{ totalAudioDuration.toFixed(1) }}s
            </span>
            <span class="text-[11px] text-gray-400 font-mono">(规范上限: ≤ 15.0s)</span>
          </div>

          <!-- 达标状态胶囊 -->
          <div>
            <el-tag v-if="refAudios.length === 0" size="small" type="info">可选（未添加）</el-tag>
            <el-tag v-else-if="refAudios.some(audio => audio.duration === undefined)" size="small" type="warning">部分时长待检测，渲染前检查</el-tag>
            <el-tag v-else-if="totalAudioDuration <= 15" size="small" type="success">✓ 总长 ≤ 15s 合规</el-tag>
            <el-tag v-else size="small" type="danger">⚠️ 总长超标 (&gt;15s，请裁减)</el-tag>
          </div>
        </div>

        <!-- 空状态 -->
        <div v-if="refAudios.length === 0" class="text-center py-6 text-xs text-gray-400 bg-white border border-dashed border-gray-200 rounded-lg">
          暂无参考音频，点击上方按钮可从配音中心选择角色参考音频 (可选，非强制)
        </div>

        <!-- 音频列表 -->
        <div v-else class="space-y-2">
          <div
            v-for="(aud, aIdx) in refAudios"
            :key="aud.id || aIdx"
            class="bg-white border border-slate-200 rounded-lg p-2.5 space-y-2 shadow-sm hover:border-indigo-300 transition-colors"
          >
            <div class="flex items-center justify-between gap-3 text-xs">
              <div class="flex items-center gap-2 flex-1 overflow-hidden">
                <span class="font-mono font-bold text-sky-700 bg-sky-100 px-1.5 py-0.5 rounded text-[11px]">
                  Audio {{ aIdx + 1 }}
                </span>

                <div class="overflow-hidden flex-1">
                  <div class="font-medium text-gray-800 truncate" :title="aud.name">
                    {{ aud.name || (aud.characterName ? `${aud.characterName} 对白` : `音频 ${aIdx + 1}`) }}
                  </div>
                </div>
              </div>

              <!-- 时长与单段合规判断 -->
              <div class="flex items-center gap-2 flex-shrink-0">
                <div class="text-right">
                  <div class="font-mono text-xs font-bold" :class="isAudioDurationValid(aud.duration) ? 'text-gray-800' : 'text-amber-600'">
                    {{ (aud.duration || 0).toFixed(1) }}s
                  </div>
                  <div class="text-[10px]" :class="isAudioDurationValid(aud.duration) ? 'text-emerald-600' : 'text-amber-500'">
                    {{ isAudioDurationValid(aud.duration) ? '✓ 2~15s合规' : '⚠️ 需2~15s' }}
                  </div>
                </div>

                <!-- 播放控制 -->
                <el-button
                  size="small"
                  circle
                  :type="playingAudioId === (aud.id || aIdx) ? 'success' : 'primary'"
                  plain
                  @click="togglePlayAudio(aud, aud.id || aIdx)"
                >
                  {{ playingAudioId === (aud.id || aIdx) ? '⏸' : '▶' }}
                </el-button>

                <el-button size="small" @click="audioReplaceIndex = aIdx; audioPickerVisible = true">替换</el-button>

                <el-button
                  size="small"
                  circle
                  type="danger"
                  plain
                  @click="handleRemoveAudio(aIdx)"
                >
                  ✕
                </el-button>
              </div>
            </div>

            <div class="grid grid-cols-1 md:grid-cols-3 gap-2 pt-1 border-t border-slate-100 items-center text-xs">
              <div class="flex items-center gap-1.5">
                <span class="text-[11px] text-gray-500 flex-shrink-0">参考关系:</span>
                <el-select v-model="aud.referenceType" size="small" class="w-full">
                  <el-option v-for="option in REFERENCE_AUDIO_TYPE_OPTIONS" :key="option.value" :value="option.value" :label="option.label" />
                </el-select>
              </div>
              <div class="flex items-center gap-1.5">
                <span class="text-[11px] text-gray-500 flex-shrink-0">语种:</span>
                <el-select v-model="aud.language" size="small" class="w-full">
                  <el-option label="中文 (zh)" value="zh" />
                  <el-option label="英文 (en)" value="en" />
                  <el-option label="日文 (ja)" value="ja" />
                  <el-option label="其他 (other)" value="other" />
                </el-select>
              </div>
              <div class="flex items-center gap-1.5">
                <span class="text-[11px] text-gray-500 flex-shrink-0">参考说明:</span>
                <el-input v-model="aud.referenceDescription" size="small" placeholder="说明复用或参考哪些内容" clearable />
              </div>
            </div>
          </div>
        </div>
      </div>

      <ShotVideoReferenceEditor v-model="refVideos" :shot-id="currentShotId ? String(currentShotId) : undefined" :drama-id="dramaId" :provider-id="renderConfig.providerId" :model-code="renderConfig.modelCode" :max-videos="maxRefVideos" :option="videoModelOption" :target-duration="targetDuration" />

      <!-- 最终生成视频预览区 (若已有产物) -->
      <div v-if="videoUrl" class="p-3 bg-slate-900 rounded-xl border border-slate-800 flex flex-col gap-2">
        <div class="flex items-center justify-between text-xs text-white">
          <span class="font-bold flex items-center gap-1">
            <span>📺</span> 已生成视频预览
          </span>
          <span class="text-[11px] text-emerald-400 font-mono">✓ 渲染就绪</span>
        </div>
        <div class="h-64 rounded-lg overflow-hidden bg-black flex items-center justify-center">
          <video :src="videoUrl" controls class="w-full h-full object-contain"></video>
        </div>
      </div>
    </div>

    <!-- 通用资产多选弹窗 (人物/造型/道具/场景) -->
    <AssetMultiSelectDialog ref="assetMultiSelectDialogRef" @confirm="handleAssetConfirm" />

    <!-- 底部操作栏 -->
    <template #footer>
      <el-alert v-if="renderBlockReason" :title="renderBlockReason" type="warning" :closable="false" show-icon class="mb-3 text-left" />
      <div class="flex justify-between items-center">
        <div class="text-xs text-gray-500">
          <span>分镜: <strong>#{{ shotNo }} {{ shotName }}</strong></span>
          <span class="ml-3 text-sky-700">图: {{ refImages.length }}/{{ maxRefImages }} · 视频: {{ refVideos.length }}{{ maxRefVideos == null ? '' : `/${maxRefVideos}` }} · 音: {{ refAudios.length }}/{{ maxRefAudios }} ({{ totalAudioDuration.toFixed(1) }}s)</span>
        </div>
        <div class="flex items-center gap-2.5">
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" plain :loading="savingConfig" @click="handleSaveConfig()">
            💾 仅保存配置
          </el-button>
          <el-button
            type="primary"
            class="!font-bold"
            :loading="renderingVideo"
            :disabled="!!renderBlockReason"
            @click="handleStartRender"
          >
            {{ renderingVideo ? '正在提交，请稍候…' : '🚀 启动参考模态渲染' }}
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
  <VoiceAudioPicker v-model="audioPickerVisible" :drama-id="dramaId" :characters="dubbingCharacters" :selected-audios="refAudios" :replace-index="audioReplaceIndex" @select="selectAudioReferences" />

</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { dramaApi, shotApi } from '@/api/drama'
import { assetApi } from '@/api/res-asset'
import { aiProviderApi } from '@/api/ai-provider'
import AssetMultiSelectDialog, { type AssetSelectItem } from './AssetMultiSelectDialog.vue'
import type { DramaShot, ShotRefImage, ShotRefAudio, ShotRefVideo, ShotVideoModelOption } from '@/types/drama'
import ShotVideoReferenceEditor from './ShotVideoReferenceEditor.vue'
import { shotVideoReferenceApi } from '@/api/shot-video-reference'
import type { AiProviderVO, AiModel } from '@/types/ai-provider'
import { resolveGenerationSize, validateGenerationSize } from '@/utils/generation-size'
import { KEY_IMAGE_ROLE_OPTIONS } from '@/constants/keyImageRoles'
import VoiceAudioPicker from '@/views/dubbing/components/VoiceAudioPicker.vue'
import { characterApi } from '@/api/res-character'
import type { ResCharacterOption } from '@/types/resource'
import { REFERENCE_AUDIO_TYPE_OPTIONS } from '@/constants/referenceMediaOptions'
import { MAX_REF_AUDIOS } from '../composables/useShotReferenceAssets'

const emit = defineEmits<{
  (e: 'success', shotId: string | number): void
}>()

const visible = ref(false)
const currentShotId = ref<string | number>()
const shotNo = ref<number>(1)
const shotName = ref<string>('')
const dramaId = ref<string | number>(0)
const sceneId = ref<string | number>(0)
const dramaAspectRatio = ref<string>('')
const shotSeed = ref<string>('')

const prompt = ref<string>('')
const negativePrompt = ref<string>('')
const videoUrl = ref<string>('')

const refImages = ref<ShotRefImage[]>([])
const refAudios = ref<ShotRefAudio[]>([])
const refVideos = ref<ShotRefVideo[]>([])
const targetDuration = ref<number>()
const videoModelOptions = ref<ShotVideoModelOption[]>([])
const videoModelOption = computed(() => videoModelOptions.value.find(model => model.modelCode === renderConfig.modelCode))
const audioPickerVisible = ref(false), audioReplaceIndex = ref<number | null>(null)
const audioCharacters = ref<ResCharacterOption[]>([]), explicitCharacterIds = ref<string[]>([])
const dubbingCharacters = computed(() => audioCharacters.value.filter(c => String(c.dramaId) === String(dramaId.value) || String(c.dramaId) === '0' && explicitCharacterIds.value.includes(String(c.id))))
function openAudioPicker(index: number | null = null) { audioReplaceIndex.value = index; audioPickerVisible.value = true }
function selectAudioReferences(selected: ShotRefAudio[]) {
  const index = audioReplaceIndex.value
  if (index != null && refAudios.value[index]) refAudios.value[index] = { ...selected[0]!, id: refAudios.value[index]!.id || selected[0]!.id }
  else refAudios.value.push(...selected)
}


const uploadingImage = ref(false)
const savingConfig = ref(false)
const renderingVideo = ref(false)

const enabledProviders = ref<AiProviderVO[]>([])
const models = ref<AiModel[]>([])
const renderConfig = reactive({
  providerId: '',
  modelCode: 'minimax-h3-ref2va',
  size: '960x544'
})
let submissionRevision = 0
watch(visible, value => { if (!value) submissionRevision++ })
onBeforeUnmount(() => { submissionRevision++ })

function getStandardSizeByRatio(ratio?: string): string {
  return resolveGenerationSize(ratio, 'video')
}

// 模型最大限制
const currentModel = computed(() => {
  return models.value.find(m => m.modelCode === renderConfig.modelCode)
})

const maxRefVideos = computed(() => {
  const values = [currentModel.value?.maxVideos, videoModelOption.value?.maxVideos, videoModelOption.value?.referenceVideo.maxVideos].filter((n): n is number => typeof n === 'number' && n >= 0)
  return values.length ? Math.min(...values) : undefined
})

const renderBlockReason = computed(() => {
  if (!renderConfig.providerId || !models.value.some(m => m.modelCode === renderConfig.modelCode)) {
    return '请选择支持参考模态渲染的提供商和视频模型。'
  }
  if (!refVideos.value.length) return ''
  if (!videoModelOption.value) return '尚未取得当前模型的参考视频能力，请重新选择提供商加载配置。'
  if (!videoModelOption.value.renderable) {
    return videoModelOption.value.reason || '当前模型参考视频能力未就绪，请检查模型中心及网关配置。'
  }
  if (maxRefVideos.value != null && refVideos.value.length > maxRefVideos.value) {
    return `参考视频超过当前模型上限（${maxRefVideos.value} 段）。`
  }
  return ''
})

const maxRefImages = computed(() => {
  if (currentModel.value && currentModel.value.maxImages !== undefined && currentModel.value.maxImages !== null) {
    return currentModel.value.maxImages
  }
  return 5
})

const maxRefAudios = computed(() => {
  if (currentModel.value && currentModel.value.maxAudios !== undefined && currentModel.value.maxAudios !== null) {
    return Math.min(currentModel.value.maxAudios, MAX_REF_AUDIOS)
  }
  return MAX_REF_AUDIOS
})

// 通用资产多选弹窗引用
const assetMultiSelectDialogRef = ref<InstanceType<typeof AssetMultiSelectDialog> | null>(null)

// 音频播放控制
let currentAudioPlayer: HTMLAudioElement | null = null
const playingAudioId = ref<string | number | null>(null)

// 计算音频总时长
const totalAudioDuration = computed(() => {
  return refAudios.value.reduce((acc, curr) => acc + (Number(curr.duration) || 0), 0)
})

function isAudioDurationValid(dur?: number) {
  if (!dur) return false
  return dur >= 2.0 && dur <= 15.0
}

function getDurationColorClass(total: number) {
  if (total === 0) return 'text-gray-500'
  if (total <= 15) return 'text-emerald-600'
  return 'text-red-600'
}


function copyText(text?: string, msg = '已复制') {
  if (!text) {
    ElMessage.warning('内容为空')
    return
  }
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success(msg)
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

async function loadProviders() {
  try {
    const list = await aiProviderApi.getListEnabled()
    const candidates = await Promise.all((list || []).map(async p => ({
      provider: p,
      models: await aiProviderApi.getModelList(String(p.id), 'TXT2VIDEO_REF').catch(() => [] as AiModel[])
    })))
    enabledProviders.value = candidates.filter(item => item.models.length > 0).map(item => item.provider)
    if (!enabledProviders.value.some(p => String(p.id) === renderConfig.providerId)) {
      renderConfig.providerId = ''
    }
    if (!renderConfig.providerId && enabledProviders.value.length > 0) {
      renderConfig.providerId = String(enabledProviders.value[0].id)
    }
    if (renderConfig.providerId) {
      await handleProviderChange(renderConfig.providerId)
    }
  } catch (ignored) {}
}

let providerLoadRevision = 0
async function handleProviderChange(pid: string) {
  const revision = ++providerLoadRevision
  videoModelOptions.value = []
  models.value = []
  renderConfig.modelCode = ''
  if (!pid) {
    return
  }
  try {
    const list = await aiProviderApi.getModelList(pid, 'TXT2VIDEO_REF')
    if (revision !== providerLoadRevision || renderConfig.providerId !== String(pid)) return
    models.value = (list || []).filter((m: AiModel) => m.status === 1 && m.modelType === 'TXT2VIDEO_REF')
    renderConfig.modelCode = models.value[0]?.modelCode || ''
    const options = await shotVideoReferenceApi.options(String(pid))
    if (revision === providerLoadRevision && renderConfig.providerId === String(pid)) {
      videoModelOptions.value = options
      if (refVideos.value.length) {
        const readyModel = options.find(option => option.renderable
          && models.value.some(model => model.modelCode === option.modelCode))
        if (readyModel) renderConfig.modelCode = readyModel.modelCode
      }
    }
  } catch (e: any) {
    console.error('加载视频模型列表失败:', e)
    if (revision === providerLoadRevision) ElMessage.error(e.message || '加载视频模型能力失败，请重试')
  }
}

async function open(shot: DramaShot, dramaAspectRatioParam?: string) {
  submissionRevision++
  currentShotId.value = shot.id
  shotSeed.value = shot.seed || ''
  shotNo.value = shot.shotNo || 1
  shotName.value = shot.shotName || ''
  dramaId.value = shot.dramaId || 0
  audioCharacters.value = []
  explicitCharacterIds.value = (shot.characterRefs || []).flatMap(c => c.characterId ? [String(c.characterId)] : [])
  if (shot.dramaId) void characterApi.getOptions(shot.dramaId).then(options => { if (String(currentShotId.value) === String(shot.id)) audioCharacters.value = options })
  sceneId.value = shot.sceneId || 0

  prompt.value = shot.prompt || shot.scriptContent || shot.actionDescription || ''
  negativePrompt.value = shot.negativePrompt || ''
  videoUrl.value = shot.videoUrl || ''

  refImages.value = shot.refImages ? [...shot.refImages] : []
  refAudios.value = shot.refAudios ? [...shot.refAudios] : []
  refVideos.value = (shot.refVideos || []).map(item => ({ ...item }))
  targetDuration.value = shot.duration

  // 读取短剧配置并预先解析为 H3 可原样生成的 32 对齐尺寸。
  dramaAspectRatio.value = dramaAspectRatioParam || ''
  if (dramaAspectRatio.value) {
    renderConfig.size = getStandardSizeByRatio(dramaAspectRatio.value)
  } else if (shot.dramaId) {
    dramaApi.getById(shot.dramaId).then((res: any) => {
      if (res?.aspectRatio) {
        dramaAspectRatio.value = res.aspectRatio
        renderConfig.size = getStandardSizeByRatio(res.aspectRatio)
      }
    }).catch(() => {})
  } else {
    renderConfig.size = '960x544'
  }

  visible.value = true
  loadProviders()
}

// 移除参考图
function handleRemoveImage(idx: number) {
  refImages.value.splice(idx, 1)
}

// 打开多选资产库弹窗
function handleOpenAssetPicker() {
  const currentUrls = refImages.value.map(x => x.imageUrl)
  assetMultiSelectDialogRef.value?.open({
    dramaId: dramaId.value,
    maxSelectable: maxRefImages.value - refImages.value.length,
    alreadySelectedUrls: currentUrls,
    title: '多选引用参考资产 (人物/道具/场景)'
  })
}

// 接收多选资产库确认回调
function handleAssetConfirm(items: AssetSelectItem[]) {
  const remainSlots = maxRefImages.value - refImages.value.length
  if (remainSlots <= 0) {
    ElMessage.warning(`参考图已达上限（${maxRefImages.value} 张）`)
    return
  }
  const toAdd = items.slice(0, remainSlots)
  for (const item of toAdd) {
    let defaultRole = 'SUBJECT'
    if (item.sourceType === 'SCENE') defaultRole = 'SCENE'
    else if (item.sourceType === 'PROP') defaultRole = 'PROP'
    else if (item.sourceType === 'KEYFRAME') defaultRole = item.usageRole || 'COMPOSITION_ANCHOR'

    refImages.value.push({
      id: item.uniqueKey || `img_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
      name: item.name,
      imageUrl: item.imageUrl,
      sourceType: item.sourceType,
      sourceId: item.sourceId,
      characterId: item.characterId,
      lookId: item.lookId,
      referenceRole: item.referenceRole,
      usageRole: defaultRole
    })
  }
  ElMessage.success(`已成功添加 ${toAdd.length} 张参考图`)
}

// 本地上传参考图
async function handleUploadRefImage(file: File) {
  if (refImages.value.length >= maxRefImages.value) {
    ElMessage.warning(`参考图最多支持 ${maxRefImages.value} 张`)
    return false
  }
  uploadingImage.value = true
  try {
    const res = await assetApi.upload(file, 'shots')
    if (res && res.url) {
      refImages.value.push({
        id: `img_${Date.now()}`,
        name: file.name,
        imageUrl: res.url,
        sourceType: 'UPLOAD',
        usageRole: 'SUBJECT'
      })
      ElMessage.success('参考图上传成功！')
    }
  } catch (e: any) {
    ElMessage.error(e.message || '上传参考图失败')
  } finally {
    uploadingImage.value = false
  }
  return false
}

// 移除参考音频
function handleRemoveAudio(idx: number) {
  if (currentAudioPlayer) {
    currentAudioPlayer.pause()
    playingAudioId.value = null
  }
  refAudios.value.splice(idx, 1)
}

// 播放/暂停音频
function togglePlayAudio(item: ShotRefAudio, id: string | number) {
  if (playingAudioId.value === id) {
    currentAudioPlayer?.pause()
    playingAudioId.value = null
    return
  }
  if (currentAudioPlayer) {
    currentAudioPlayer.pause()
  }
  currentAudioPlayer = new Audio(item.audioUrl)
  playingAudioId.value = id
  currentAudioPlayer.play()
  currentAudioPlayer.onended = () => {
    playingAudioId.value = null
  }
}

// 保存配置
async function handleSaveConfig(notify = true) {
  if (!currentShotId.value) return
  savingConfig.value = true
  try {
    await shotApi.update({
      id: currentShotId.value,
      generationMode: 'REFERENCE_MODE',
      prompt: prompt.value,
      negativePrompt: negativePrompt.value,
      refImages: refImages.value,
      refAudios: refAudios.value,
      refVideos: refVideos.value
    })
    if (notify) {
      ElMessage.success('多模态参考配置保存成功！')
      emit('success', currentShotId.value)
    }
    return true
  } catch (e: any) {
    ElMessage.error(e.message || '保存配置失败')
    return false
  } finally {
    savingConfig.value = false
  }
}

// 启动参考图与音频渲染
async function handleStartRender() {
  if (renderingVideo.value) return
  if (!currentShotId.value) return
  if (renderBlockReason.value) {
    ElMessage.warning(renderBlockReason.value)
    return
  }
  if (!renderConfig.providerId || !models.value.some(m => m.modelCode === renderConfig.modelCode)) {
    ElMessage.warning('请选择支持 TXT2VIDEO_REF 的提供商和视频模型')
    return
  }
  const sizeValidation = validateGenerationSize(renderConfig.size, 'video')
  if (!sizeValidation.valid) {
    ElMessage.warning(sizeValidation.message)
    return
  }
  // MiniMax H3 官方多模态参考约束校验
  if (refAudios.value.length > 0 && refImages.value.length === 0 && refVideos.value.length === 0) {
    ElMessage.warning('参考音频不可作为唯一模态，请添加参考图或参考视频')
    return
  }
  if (totalAudioDuration.value > 15.0) {
    ElMessage.warning(`参考音频总时长不能超过 15 秒（当前 ${totalAudioDuration.value.toFixed(1)} 秒）`)
    return
  }
  const invalidAudio = refAudios.value.find(a => a.duration !== undefined && !isAudioDurationValid(a.duration))
  if (invalidAudio) {
    ElMessage.warning(`参考音频「${invalidAudio.name || '音频'}」时长 (${invalidAudio.duration}s) 不在 2~15 秒规范区间内`)
    return
  }

  const revision = ++submissionRevision
  const shotId = currentShotId.value
  const providerId = renderConfig.providerId
  const modelCode = renderConfig.modelCode
  const configuration = () => JSON.stringify([currentShotId.value, renderConfig, prompt.value, negativePrompt.value, refImages.value, refAudios.value, refVideos.value])
  const submittedConfiguration = configuration()
  const isCurrent = () => {
    if (revision !== submissionRevision || !visible.value) return false
    if (submittedConfiguration !== configuration()) throw new Error('配置已修改，本次提交已取消，请重新提交')
    return true
  }
  renderingVideo.value = true
  try {
    if (!await handleSaveConfig(false) || !isCurrent()) return
    let preflightId: string | undefined
    if (refVideos.value.length) {
      let prepared = await shotVideoReferenceApi.prepare(String(shotId), providerId, modelCode)
      const deadline = Date.now() + 6 * 60 * 1000
      while (prepared.status === 'PREPARING') {
        if (!isCurrent()) return
        if (Date.now() >= deadline) throw new Error('参考片段准备超时，请稍后重新提交')
        await new Promise(resolve => window.setTimeout(resolve, 1200))
        if (!isCurrent()) return
        prepared = await shotVideoReferenceApi.get(String(shotId), prepared.preflightId)
      }
      if (!isCurrent()) return
      if (prepared.status !== 'READY') throw new Error(prepared.errorMessage || '参考片段无法处理，请检查视频格式和片段范围')
      preflightId = prepared.preflightId
    }
    if (!isCurrent()) return
    await shotApi.submitRender(shotId, {
      providerId,
      workflowTemplateId: modelCode,
      preflightId,
      size: sizeValidation.normalized
    })
    ElMessage.success('多模态视频渲染任务已提交！')
    emit('success', shotId)
    if (revision === submissionRevision) visible.value = false
  } catch (e: any) {
    ElMessage.error(e.message || '提交渲染失败')
  } finally {
    renderingVideo.value = false
  }
}

defineExpose({
  open
})
</script>
