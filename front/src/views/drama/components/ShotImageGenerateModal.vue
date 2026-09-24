<template>
  <el-dialog
    v-model="visible"
    :title="modalTitle"
    width="1020px"
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="!generating"
    :show-close="!generating"
  >
    <div class="grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_360px] gap-6 max-h-[74vh] overflow-y-auto pr-1">
      <el-form label-position="top">
        <!-- 供应商与生图模型配置 -->
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <el-form-item label="AI 供应商" required>
            <el-select
              v-model="form.providerId"
              class="w-full"
              filterable
              clearable
              :disabled="generating"
              placeholder="请选择生图供应商"
              @change="handleProviderChange"
            >
              <el-option
                v-for="provider in availableProviders"
                :key="String(provider.id)"
                :label="`${provider.providerName} (${provider.providerCode})`"
                :value="String(provider.id)"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="生图模型" required>
            <el-select
              v-model="form.modelCode"
              class="w-full"
              filterable
              :loading="modelsLoading"
              :disabled="generating || !form.providerId"
              placeholder="请选择已配置的生图模型"
            >
              <el-option
                v-for="model in models"
                :key="model.id"
                :label="`${model.modelName} (${model.modelCode}) [${modelTypeLabel(model.modelType)}]`"
                :value="model.modelCode"
              />
            </el-select>
          </el-form-item>
        </div>

        <!-- 模型能力状态横幅 -->
        <div v-if="form.modelCode" class="mb-4">
          <div
            v-if="isImg2imgSupported"
            class="flex items-center justify-between text-xs px-3 py-2 rounded-md bg-purple-50 text-purple-700 border border-purple-200"
          >
            <span class="flex items-center gap-1.5 font-medium">
              <span>🎨</span>
              <span>当前模型支持【文生图/图生图 (双模)】：已就绪多模态参考图引导</span>
            </span>
            <el-tag size="small" type="warning" effect="dark">图生图就绪</el-tag>
          </div>
          <div
            v-else
            class="flex items-center justify-between text-xs px-3 py-2 rounded-md bg-slate-100 text-slate-600 border border-slate-200"
          >
            <span class="flex items-center gap-1.5">
              <span>💡</span>
              <span>当前模型为【纯文生图】：不使用参考图，将纯粹依据提示词生成画面</span>
            </span>
            <el-tag size="small" type="info">纯文生图</el-tag>
          </div>
        </div>

        <!-- 参考图控制区 (当模型支持图生图时可用) -->
        <div
          v-if="isImg2imgSupported"
          class="p-3.5 mb-4 rounded-xl border border-purple-200 bg-purple-50/40 space-y-3"
        >
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <span class="text-xs font-bold text-slate-800 flex items-center gap-1">
                <span>🖼️</span> 参考图资产 (最多 {{ maxImagesLimit }} 张)
              </span>
              <el-tag size="small" :type="refImages.length >= maxImagesLimit ? 'danger' : 'primary'" effect="plain" class="font-mono font-bold">
                {{ refImages.length }} / {{ maxImagesLimit }}
              </el-tag>
            </div>
            <span class="text-[11px] text-purple-600">
              {{ isEndFrame ? '以首帧为基准引导尾帧画面，保持角色骨相、服饰与光影一致性' : '引导画面主体骨相、光影构图与空间透视' }}
            </span>
          </div>

          <!-- 添加入口按钮栏 -->
          <div class="flex items-center gap-2 flex-wrap">
            <el-button
              size="small"
              type="primary"
              plain
              :disabled="refImages.length >= maxImagesLimit"
              @click="handleOpenAssetPicker"
            >
              + 从资产库多选引用 (人物/道具/场景)
            </el-button>

            <!-- 尾帧推荐引用首帧 -->
            <el-button
              v-if="isEndFrame && firstFrameUrl && !isUrlInRefList(firstFrameUrl)"
              size="small"
              type="warning"
              plain
              :disabled="refImages.length >= maxImagesLimit"
              @click="handleAddFirstFrameRef"
            >
              🌅 引用本镜首帧
            </el-button>

            <!-- 首帧推荐引用上一镜尾帧 -->
            <el-button
              v-if="!isEndFrame"
              size="small"
              type="success"
              plain
              :loading="loadingPrevTail"
              :disabled="refImages.length >= maxImagesLimit"
              @click="handleAddPrevTailRef"
            >
              📎 引用上一镜尾帧
            </el-button>

            <!-- 引用槽位原图 -->
            <el-button
              v-if="slotOriginUrl && !isUrlInRefList(slotOriginUrl)"
              size="small"
              plain
              :disabled="refImages.length >= maxImagesLimit"
              @click="handleAddSlotOriginRef"
            >
              🔄 引用槽位原图
            </el-button>

            <!-- 本地上传 -->
            <el-upload
              action="#"
              :show-file-list="false"
              :http-request="handleCustomUpload"
              accept="image/*"
              :disabled="refImages.length >= maxImagesLimit"
            >
              <el-button size="small" plain :disabled="refImages.length >= maxImagesLimit" :loading="uploadingRef">
                📤 本地上传
              </el-button>
            </el-upload>
          </div>

          <!-- 空状态 -->
          <div v-if="refImages.length === 0" class="text-center py-4 text-xs text-gray-400 bg-white border border-dashed border-purple-200 rounded-lg">
            暂无参考图，可从资产库选取人物造型、道具、场景或上传本地图 (可选，不选则纯提示词文生图)
          </div>

          <!-- 已添加参考图卡片列表 -->
          <div v-else class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2.5">
            <div
              v-for="(img, idx) in refImages"
              :key="img.id || idx"
              class="relative rounded-lg border border-purple-200 bg-white overflow-hidden shadow-xs flex flex-col group"
            >
              <div class="h-24 bg-slate-900 overflow-hidden flex items-center justify-center relative">
                <el-image
                  :src="img.imageUrl"
                  fit="cover"
                  class="w-full h-full cursor-pointer"
                  :preview-src-list="refImages.map(x => x.imageUrl)"
                  preview-teleported
                />
                <div class="absolute top-1 left-1">
                  <el-tag size="small" effect="dark" :type="getSourceTagType(img.sourceType)" class="!px-1 !text-[9px] !h-4 leading-none">
                    {{ getSourceLabel(img.sourceType) }}
                  </el-tag>
                </div>
                <button
                  type="button"
                  class="absolute top-1 right-1 w-4 h-4 rounded-full bg-black/70 text-white hover:bg-red-600 flex items-center justify-center text-[10px] opacity-0 group-hover:opacity-100 transition-opacity"
                  title="移除此参考图"
                  @click.stop="handleRemoveRefImage(idx)"
                >
                  ✕
                </button>
              </div>
              <div class="p-1 text-[11px] text-gray-700 truncate" :title="img.name">
                {{ img.name }}
              </div>
            </div>
          </div>
        </div>

        <!-- 正向 Prompt 调整区 -->
        <el-form-item required>
          <template #label>
            <div class="flex items-center justify-between w-full">
              <span class="font-bold text-gray-800">
                正向生图 Prompt (可在此微调调整)
              </span>
              <el-button
                v-if="originalPrompt && form.prompt !== originalPrompt"
                size="small"
                link
                type="primary"
                @click="form.prompt = originalPrompt"
              >
                ↺ 恢复初始提示词
              </el-button>
            </div>
          </template>
          <el-input
            v-model="form.prompt"
            type="textarea"
            :rows="5"
            :disabled="generating"
            placeholder="输入生成画面的正向提示词..."
          />
        </el-form-item>

        <!-- 负向 Prompt -->
        <el-form-item label="负向规避 Prompt (Negative Prompt)">
          <el-input
            v-model="form.negativePrompt"
            type="textarea"
            :rows="2"
            :disabled="generating"
            placeholder="规避特征与负向提示词 (如 low quality, deformed hands)..."
          />
        </el-form-item>

        <!-- 图片画幅与尺寸 -->
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <el-form-item>
            <template #label>
              <div class="flex items-center gap-2">
                <span class="font-bold text-slate-800">画幅比例 / 尺寸</span>
                <el-tag v-if="dramaAspectRatio" size="small" type="primary" effect="plain" class="!text-[11px] !h-5 !px-1.5">
                  短剧配置: {{ dramaAspectRatio }}
                </el-tag>
              </div>
            </template>
            <el-select
              v-model="form.size"
              class="w-full"
              filterable
              allow-create
              default-first-option
              :disabled="generating"
              placeholder="选择画幅尺寸"
            >
              <el-option-group label="图片尺寸（16 像素对齐）">
                <el-option label="720 x 1280 (9:16 短剧竖屏 - 推荐)" value="720x1280" />
                <el-option label="1280 x 720 (16:9 标清横屏)" value="1280x720" />
                <el-option label="1088 x 1920 (近 9:16 高清竖屏，实际生成尺寸)" value="1088x1920" />
                <el-option label="1920 x 1088 (近 16:9 高清横屏，实际生成尺寸)" value="1920x1088" />
              </el-option-group>
              <el-option-group label="通用正方与经典比例">
                <el-option label="1024 x 1024 (1:1 正方形)" value="1024x1024" />
                <el-option label="1024 x 768 (4:3 经典横屏)" value="1024x768" />
                <el-option label="768 x 1024 (3:4 经典竖屏)" value="768x1024" />
                <el-option label="1024 x 1536 (2:3 经典人像竖屏)" value="1024x1536" />
                <el-option label="1536 x 1024 (3:2 场景横幅)" value="1536x1024" />
              </el-option-group>
            </el-select>
          </el-form-item>

          <el-form-item label="随机种子 Seed (可选)">
            <el-input-number
              v-model="form.seed"
              :min="-1"
              :disabled="generating"
              placeholder="留空或-1为随机"
              class="!w-full"
            />
          </el-form-item>
        </div>
      </el-form>

      <!-- 右侧生图监视器面板 -->
      <aside class="rounded-xl border border-slate-200 bg-slate-50/70 p-4 self-start space-y-4">
        <div class="flex items-center justify-between">
          <div class="text-sm font-bold text-slate-800">生图监视器</div>
          <el-tag size="small" :type="generating ? 'warning' : outputUrl ? 'success' : hasError ? 'danger' : 'info'">
            {{ generating ? 'AI 生成中' : outputUrl ? '生成成功' : hasError ? '生成失败' : '待生成' }}
          </el-tag>
        </div>

        <!-- 参考图监视 -->
        <div>
          <div class="flex items-center justify-between mb-1.5">
            <div class="text-xs font-semibold text-slate-500">已引用的参考图 (Input Refs)</div>
            <el-tag v-if="isImg2imgSupported && refImages.length > 0" size="small" type="purple" effect="plain" class="font-mono">
              已选 {{ refImages.length }} / {{ maxImagesLimit }} 张
            </el-tag>
          </div>
          <div v-if="isImg2imgSupported && refImages.length > 0" class="grid grid-cols-3 gap-1.5">
            <div
              v-for="(img, rIdx) in refImages"
              :key="img.id || rIdx"
              class="relative group rounded-md overflow-hidden border border-purple-200 aspect-square bg-slate-900"
            >
              <el-image
                :src="img.imageUrl"
                fit="cover"
                class="w-full h-full cursor-pointer"
                :preview-src-list="refImages.map(x => x.imageUrl)"
                preview-teleported
              />
              <div class="absolute bottom-0 inset-x-0 bg-black/60 text-[9px] text-white px-1 py-0.5 truncate text-center font-mono">
                {{ img.name }}
              </div>
            </div>
          </div>
          <div v-else class="w-full h-20 rounded-lg border border-dashed border-slate-300 bg-white flex flex-col items-center justify-center text-xs text-slate-400 p-2 text-center select-none">
            <span>{{ isImg2imgSupported ? '未引用参考图 (纯提示词生成)' : '纯文生图模型，不使用参考图' }}</span>
          </div>
        </div>

        <!-- 生成结果大图监视 -->
        <div>
          <div class="text-xs font-semibold text-slate-500 mb-1.5">AI 生成结果 (Output Preview)</div>
          <div v-if="generating" class="w-full h-60 rounded-lg border border-indigo-200 bg-indigo-50 flex flex-col items-center justify-center text-indigo-700" v-loading="true">
            <div class="mt-8 text-sm font-semibold">AI 模型渲染生成中...</div>
            <div class="text-xs mt-1 text-indigo-500">正在生图并自动无损归档至 MinIO</div>
          </div>
          <div v-else-if="outputUrl" class="space-y-1.5">
            <el-image
              :src="outputUrl"
              fit="contain"
              class="w-full h-60 rounded-lg border border-emerald-200 bg-white shadow-xs cursor-pointer"
              :preview-src-list="[outputUrl]"
              preview-teleported
            />
            <div class="text-xs text-emerald-600 font-medium text-center">
              ✓ 生成图片已自动无损归档至 MinIO
            </div>
          </div>
          <div
            v-else-if="hasError"
            class="w-full h-60 rounded-lg border border-dashed border-rose-300 bg-rose-50 flex items-center justify-center text-center text-xs text-rose-600 px-4"
          >
            {{ errorMessage || '生图失败，请调整参数后重试' }}
          </div>
          <div v-else class="w-full h-60 rounded-lg border border-dashed border-slate-300 bg-white flex items-center justify-center text-xs text-slate-400 select-none">
            生成结果将在这里预览
          </div>
        </div>
      </aside>
    </div>

    <template #footer>
      <div class="flex justify-between items-center">
        <span class="text-xs text-slate-400">
          * 支持自由调整提示词与画幅，生成满意后点击「应用」即可写回分镜
        </span>
        <div class="flex gap-2">
          <el-button :disabled="generating" @click="visible = false">取消</el-button>
          <el-button
            v-if="outputUrl"
            type="success"
            :disabled="generating"
            @click="handleApply"
          >
            ✓ 应用并保存到{{ isEndFrame ? '尾帧' : '首帧' }}
          </el-button>
          <el-button
            type="primary"
            class="!bg-indigo-600 !border-indigo-600 hover:!bg-indigo-500"
            :loading="generating"
            :disabled="!canSubmit"
            @click="handleGenerate"
          >
            {{ generating ? 'AI 生成中...' : outputUrl ? '✨ 重新生成' : '✨ 开始生成' }}
          </el-button>
        </div>
      </div>
    </template>

    <!-- 资产库多选引用弹窗 -->
    <AssetMultiSelectDialog
      ref="assetMultiSelectDialogRef"
      @confirm="handleAssetConfirm"
    />
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage, type UploadRequestOptions } from 'element-plus'
import { shotApi, dramaApi } from '@/api/drama'
import { aiProviderApi } from '@/api/ai-provider'
import { assetApi } from '@/api/res-asset'
import type { AiModel, AiProviderVO } from '@/types/ai-provider'
import AssetMultiSelectDialog, { type AssetSelectItem } from './AssetMultiSelectDialog.vue'
import { resolveGenerationSize, validateGenerationSize } from '@/utils/generation-size'

export interface ShotImageGenerateOpenParams {
  shotId: string | number
  frameType: 'FIRST_FRAME' | 'END_FRAME'
  shotTitle?: string
  prompt: string
  negativePrompt?: string
  slotOriginUrl?: string
  firstFrameUrl?: string
  dramaId?: string | number
  aspectRatio?: string
}

const emit = defineEmits<{
  (e: 'applied', payload: { frameType: 'FIRST_FRAME' | 'END_FRAME'; url: string }): void
}>()

const visible = ref(false)
const generating = ref(false)
const modelsLoading = ref(false)
const uploadingRef = ref(false)

const assetMultiSelectDialogRef = ref()

const currentShotId = ref<string | number>()
const currentDramaId = ref<string | number>(0)
const currentFrameType = ref<'FIRST_FRAME' | 'END_FRAME'>('FIRST_FRAME')
const shotTitle = ref('')
const originalPrompt = ref('')
const slotOriginUrl = ref('')
const loadingPrevTail = ref(false)
const firstFrameUrl = ref('')
const dramaAspectRatio = ref<string>('')

const providers = ref<AiProviderVO[]>([])
const models = ref<AiModel[]>([])

const outputUrl = ref('')
const hasError = ref(false)
const errorMessage = ref('')

// 多参考图列表: 支持人物造型、道具、场景、首帧、尾帧、原图与上传
export interface ShotRefImageItem {
  id: string
  name: string
  imageUrl: string
  sourceType: string
  sourceId?: string | number
  characterId?: string | number
  lookId?: string | number
  referenceRole?: string
}

const refImages = ref<ShotRefImageItem[]>([])

const form = reactive({
  providerId: undefined as string | undefined,
  modelCode: '',
  prompt: '',
  negativePrompt: '',
  size: '720x1280',
  seed: undefined as number | undefined
})

function getStandardSizeByRatio(ratio?: string): string {
  return resolveGenerationSize(ratio, 'image')
}

const isEndFrame = computed(() => currentFrameType.value === 'END_FRAME')

const modalTitle = computed(() => {
  const frameText = isEndFrame.value ? '尾帧图 (End Frame)' : '首帧图 (First Frame)'
  return `🎨 分镜${frameText}生成 ${shotTitle.value ? `[${shotTitle.value}]` : ''}`
})

const availableProviders = computed(() => providers.value)

const currentModel = computed(() => {
  return models.value.find((m) => m.modelCode === form.modelCode)
})

// 模型配置的最大参考图限制 (必填项，>= 0)
const maxImagesLimit = computed(() => {
  if (currentModel.value && currentModel.value.maxImages !== undefined && currentModel.value.maxImages !== null) {
    return currentModel.value.maxImages
  }
  return 1
})

// 模型能力判定：TXT_IMG2IMG、IMG2IMG 或包含 flux/img2img 支持参考图，且配置上限 > 0
const isImg2imgSupported = computed(() => {
  if (maxImagesLimit.value <= 0) return false
  if (!currentModel.value) {
    const code = (form.modelCode || '').toLowerCase()
    return code.includes('flux') || code.includes('img2img')
  }
  return currentModel.value.modelType === 'TXT_IMG2IMG' || currentModel.value.modelType === 'IMG2IMG' || currentModel.value.modelType === 'IMAGE'
})

const canSubmit = computed(() => !generating.value && !!form.providerId && !!form.prompt.trim() && !!form.modelCode)

function modelTypeLabel(type?: string) {
  if (type === 'TXT_IMG2IMG') return '文生图/图生图'
  if (type === 'TXT2IMG') return '纯文生图'
  if (type === 'IMG2IMG') return '图生图'
  if (type === 'IMAGE') return '图像'
  return type || ''
}

function isUrlInRefList(url?: string): boolean {
  if (!url) return false
  return refImages.value.some((x) => x.imageUrl === url)
}

function getSourceLabel(type: string): string {
  switch (type) {
    case 'CHARACTER': return '人物'
    case 'PROP': return '道具'
    case 'SCENE': return '场景'
    case 'FIRST_FRAME': return '首帧'
    case 'PREV_TAIL': return '尾帧'
    case 'ORIGIN': return '原图'
    default: return '上传'
  }
}

function getSourceTagType(type: string): 'warning' | 'danger' | 'success' | 'primary' | 'info' {
  switch (type) {
    case 'CHARACTER': return 'warning'
    case 'PROP': return 'danger'
    case 'SCENE': return 'success'
    case 'FIRST_FRAME': return 'primary'
    case 'PREV_TAIL': return 'success'
    default: return 'info'
  }
}

function handleOpenAssetPicker() {
  if (refImages.value.length >= maxImagesLimit.value) {
    return ElMessage.warning(`参考图数量已达当前模型上限 (${maxImagesLimit.value} 张)`)
  }
  assetMultiSelectDialogRef.value?.open({
    dramaId: currentDramaId.value,
    maxSelectable: maxImagesLimit.value - refImages.value.length,
    alreadySelectedUrls: refImages.value.map((x) => x.imageUrl),
    title: '多选引用资产库图片 (人物/道具/场景)'
  })
}

function handleAssetConfirm(items: AssetSelectItem[]) {
  for (const item of items) {
    if (refImages.value.length < maxImagesLimit.value && !isUrlInRefList(item.imageUrl)) {
      refImages.value.push({
        id: item.uniqueKey || `ref_${Date.now()}_${Math.random().toString(36).substring(2, 6)}`,
        name: item.name,
        imageUrl: item.imageUrl,
        sourceType: item.sourceType,
        sourceId: item.sourceId,
        characterId: item.characterId,
        lookId: item.lookId,
        referenceRole: item.referenceRole
      })
    }
  }
  ElMessage.success(`已添加 ${items.length} 张参考图`)
}

function handleAddFirstFrameRef() {
  if (refImages.value.length >= maxImagesLimit.value) {
    return ElMessage.warning(`参考图已达模型上限 (${maxImagesLimit.value} 张)`)
  }
  if (!firstFrameUrl.value || isUrlInRefList(firstFrameUrl.value)) return
  refImages.value.push({
    id: `first_frame_${Date.now()}`,
    name: '本镜首帧图',
    imageUrl: firstFrameUrl.value,
    sourceType: 'FIRST_FRAME'
  })
  ElMessage.success('已添加本镜首帧为参考图')
}

async function handleAddPrevTailRef() {
  if (refImages.value.length >= maxImagesLimit.value) {
    return ElMessage.warning(`参考图已达模型上限 (${maxImagesLimit.value} 张)`)
  }
  if (!currentShotId.value) {
    return ElMessage.warning('分镜ID不存在')
  }
  try {
    loadingPrevTail.value = true
    const res = await shotApi.inheritPreviousVideoTail(currentShotId.value)
    if (!res?.tailFrameUrl) {
      ElMessage.warning('未能获取到上一镜尾帧')
      return
    }
    if (isUrlInRefList(res.tailFrameUrl)) {
      ElMessage.info('上一镜尾帧已在参考图列表中')
      return
    }
    refImages.value.push({
      id: `prev_tail_${Date.now()}`,
      name: `上一镜 (S#${res.sourceShotNo || ''}) 尾帧`,
      imageUrl: res.tailFrameUrl,
      sourceType: 'PREV_TAIL',
      sourceId: res.sourceShotId
    })
    ElMessage.success('已从上一镜视频提取尾帧并添加为参考图')
  } catch (err: any) {
    ElMessage.error(err?.message || '提取上一镜视频尾帧失败')
  } finally {
    loadingPrevTail.value = false
  }
}

function handleAddSlotOriginRef() {
  if (refImages.value.length >= maxImagesLimit.value) {
    return ElMessage.warning(`参考图已达模型上限 (${maxImagesLimit.value} 张)`)
  }
  if (!slotOriginUrl.value || isUrlInRefList(slotOriginUrl.value)) return
  refImages.value.push({
    id: `slot_origin_${Date.now()}`,
    name: '槽位原图',
    imageUrl: slotOriginUrl.value,
    sourceType: 'ORIGIN'
  })
  ElMessage.success('已添加槽位原图为参考图')
}

function handleRemoveRefImage(index: number) {
  refImages.value.splice(index, 1)
}

async function handleCustomUpload(options: UploadRequestOptions) {
  if (refImages.value.length >= maxImagesLimit.value) {
    return ElMessage.warning(`参考图数量已达当前模型上限 (${maxImagesLimit.value} 张)`)
  }
  try {
    uploadingRef.value = true
    const res = await assetApi.upload(options.file, 'shot')
    if (res && res.url) {
      refImages.value.push({
        id: `upload_${Date.now()}`,
        name: options.file.name || '本地上传图',
        imageUrl: res.url,
        sourceType: 'UPLOAD'
      })
      ElMessage.success('自定义参考图上传成功')
    }
  } catch (err: any) {
    ElMessage.error(err?.message || '上传参考图失败')
  } finally {
    uploadingRef.value = false
  }
}

async function open(params: ShotImageGenerateOpenParams) {
  currentShotId.value = params.shotId
  currentDramaId.value = params.dramaId || 0
  currentFrameType.value = params.frameType
  shotTitle.value = params.shotTitle || ''
  originalPrompt.value = params.prompt || ''
  slotOriginUrl.value = params.slotOriginUrl || ''
  firstFrameUrl.value = params.firstFrameUrl || ''

  form.prompt = params.prompt || ''
  form.negativePrompt = params.negativePrompt || ''
  form.seed = undefined
  outputUrl.value = ''
  hasError.value = false
  errorMessage.value = ''

  refImages.value = []

  // 尝试获取短剧配置中的画幅比例与尺寸
  dramaAspectRatio.value = params.aspectRatio || ''
  if (dramaAspectRatio.value) {
    form.size = getStandardSizeByRatio(dramaAspectRatio.value)
  } else if (params.dramaId) {
    dramaApi.getById(params.dramaId).then((res: any) => {
      if (res?.aspectRatio) {
        dramaAspectRatio.value = res.aspectRatio
        form.size = getStandardSizeByRatio(res.aspectRatio)
      }
    }).catch(() => {})
  } else {
    form.size = '720x1280'
  }

  // 默认推荐参考图初始化
  if (params.frameType === 'END_FRAME' && params.firstFrameUrl) {
    refImages.value.push({
      id: `init_first_${Date.now()}`,
      name: '本镜首帧图',
      imageUrl: params.firstFrameUrl,
      sourceType: 'FIRST_FRAME'
    })
  } else if (params.slotOriginUrl) {
    refImages.value.push({
      id: `init_origin_${Date.now()}`,
      name: '槽位原图',
      imageUrl: params.slotOriginUrl,
      sourceType: 'ORIGIN'
    })
  }

  visible.value = true
  await loadProviders()
}

async function loadProviders() {
  try {
    providers.value = await aiProviderApi.getListEnabled()
    selectDefaultProvider()
  } catch (e: any) {
    ElMessage.error(e?.message || '获取 AI 提供商列表失败')
  }
}

function selectDefaultProvider() {
  if (!availableProviders.value.some((p) => String(p.id) === form.providerId)) {
    form.providerId = availableProviders.value[0] ? String(availableProviders.value[0].id) : undefined
  }
  loadModels()
}

async function handleProviderChange() {
  form.modelCode = ''
  await loadModels()
}

async function loadModels() {
  models.value = []
  if (!form.providerId) return
  modelsLoading.value = true
  try {
    models.value = await aiProviderApi.getModelList(form.providerId, 'TXT2IMG,IMG2IMG,TXT_IMG2IMG')
    // 优先推荐图生图双模模型
    const preferred = models.value.find((m) => m.modelType === 'TXT_IMG2IMG') || models.value[0]
    form.modelCode = preferred?.modelCode || (models.value.length ? models.value[0].modelCode : 'flux-2-klein-9b')
  } finally {
    modelsLoading.value = false
  }
}

async function handleGenerate() {
  if (!currentShotId.value) {
    return ElMessage.warning('分镜不存在')
  }
  if (!form.prompt.trim()) {
    return ElMessage.warning('生图 Prompt 不能为空')
  }
  if (!form.providerId || !form.modelCode) {
    return ElMessage.warning('请选择供应商和生图模型')
  }
  const sizeValidation = validateGenerationSize(form.size, 'image')
  if (!sizeValidation.valid) {
    return ElMessage.warning(sizeValidation.message)
  }

  generating.value = true
  hasError.value = false
  errorMessage.value = ''

  const refImageUrls = (isImg2imgSupported.value && refImages.value.length > 0)
    ? refImages.value.map((x) => x.imageUrl)
    : undefined

  try {
    const res = await shotApi.generateFirstFrame(currentShotId.value, {
      engineType: 'API',
      providerId: form.providerId,
      modelCode: form.modelCode,
      customPrompt: form.prompt.trim(),
      negativePrompt: form.negativePrompt?.trim(),
      size: sizeValidation.normalized,
      seed: form.seed,
      referenceImageUrls: refImageUrls,
      frameType: currentFrameType.value
    })

    if (res && res.outputUrl) {
      outputUrl.value = res.outputUrl
      ElMessage.success(`分镜${isEndFrame.value ? '尾帧' : '首帧'}生成成功！`)
    } else {
      throw new Error('生图任务未返回有效图片地址')
    }
  } catch (e: any) {
    hasError.value = true
    errorMessage.value = e?.message || '生图请求失败'
    ElMessage.error(errorMessage.value)
  } finally {
    generating.value = false
  }
}

function handleApply() {
  if (!outputUrl.value) return
  emit('applied', {
    frameType: currentFrameType.value,
    url: outputUrl.value
  })
  ElMessage.success(`已应用并保存到${isEndFrame.value ? '尾帧' : '首帧'}`)
  visible.value = false
}

defineExpose({
  open
})
</script>
