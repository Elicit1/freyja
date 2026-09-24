<template>
  <el-dialog
    v-model="visible"
    :title="`🎬 分镜 [S${shotNo} ${shotName || ''}] 首尾帧渲染工坊`"
    width="920px"
    destroy-on-close
    append-to-body
    :close-on-click-modal="false"
  >
    <!-- 顶部横幅提示：首尾帧模式规则 -->
    <div class="p-3.5 mb-4 bg-purple-50/80 border border-purple-200 rounded-xl flex items-start gap-2.5 text-xs text-purple-900">
      <span class="text-lg leading-none">⚠️</span>
      <div class="flex-1 space-y-1">
        <div class="font-bold flex items-center gap-2">
          <span>首尾帧生成模式 (First & End Frame Mode)</span>
          <el-tag size="small" type="purple" effect="dark" class="!bg-purple-600 !border-purple-600 !text-white text-[10px]">
            首尾对齐
          </el-tag>
        </div>
        <p class="text-purple-700 leading-relaxed">
          本模式专供关键帧运动生成模型（如 MiniMax H3 FL2VA、Wan 2.1 等），<strong>仅支持首帧图、尾帧图与提示词作为参考</strong>。
          首尾帧<strong>不强制提供</strong>，可以仅凭提示词生成。若上一镜头已生成尾帧，可一键引用为本镜首帧。
        </p>
      </div>
    </div>

    <div class="space-y-4 max-h-[72vh] overflow-y-auto px-1 py-1 custom-scrollbar">
      <!-- 渲染引擎与参数 -->
      <div class="bg-slate-50 border border-slate-200 rounded-xl p-3.5 space-y-3">
        <div class="flex items-center justify-between text-xs font-bold text-slate-700">
          <span>⚙️ 渲染引擎与模型参数</span>
          <span class="text-[11px] font-normal text-slate-400">兼容 FastAPI / 云端生图与视频 API</span>
        </div>
        <div class="grid grid-cols-4 gap-3 text-xs">
          <div>
            <label class="text-[11px] font-medium text-slate-600 mb-1 block">AI 提供商</label>
            <el-select v-model="renderConfig.providerId" placeholder="系统默认" clearable size="small" class="w-full" @change="handleProviderChange">
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
              allow-create
              default-first-option
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
            <el-input-number v-model="renderConfig.seed" size="small" :min="-1" placeholder="随机" class="!w-full" />
          </div>
        </div>
      </div>

      <!-- 提示词与尾帧提示词工作区 -->
      <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
        <!-- 提示词 (Prompt) -->
        <div class="bg-indigo-50/40 p-3 rounded-xl border border-indigo-100 flex flex-col justify-between">
          <div class="flex items-center justify-between mb-1.5">
            <label class="text-xs font-bold text-indigo-950 flex items-center gap-1">
              <span>🎬</span> 提示词 (Prompt)
            </label>
            <el-button size="small" link type="primary" @click="copyText(prompt, '提示词已复制')">
              复制
            </el-button>
          </div>
          <el-input
            v-model="prompt"
            type="textarea"
            :rows="3"
            placeholder="主镜头画面与运镜动力提示词，可直接凭提示词独立生成画面..."
          />
        </div>

        <!-- 尾帧提示词 (End Frame Prompt) -->
        <div class="bg-purple-50/40 p-3 rounded-xl border border-purple-100 flex flex-col justify-between">
          <div class="flex items-center justify-between mb-1.5">
            <label class="text-xs font-bold text-purple-950 flex items-center gap-1">
              <span>🏁</span> 尾帧提示词 (End Frame Prompt)
            </label>
            <el-button size="small" link type="primary" @click="copyText(endFramePrompt, '尾帧提示词已复制')">
              复制
            </el-button>
          </div>
          <el-input
            v-model="endFramePrompt"
            type="textarea"
            :rows="3"
            placeholder="尾帧演变结果提示词 (如：人物走到会议室门前，神情转为冰冷，手握门把手)..."
          />
        </div>
      </div>

      <!-- 负向提示词 -->
      <div>
        <el-input
          v-model="negativePrompt"
          placeholder="负向提示词 (Negative Prompt): 如 low quality, blurry, distorted, bad anatomy..."
          size="small"
          clearable
        />
      </div>

      <!-- 核心资产：首帧图 与 尾帧图 双画面卡片 -->
      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <!-- 首帧图卡片 -->
        <div class="border-2 border-indigo-200/80 rounded-xl p-4 bg-white flex flex-col gap-3 shadow-sm hover:border-indigo-400 transition-colors">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-1.5">
              <span class="text-sm font-bold text-indigo-900">🖼️ 首帧图 (First Frame)</span>
              <el-tag size="small" :type="firstFrameUrl ? 'success' : 'info'" effect="plain">
                {{ firstFrameUrl ? '已设置' : '可选/未设置' }}
              </el-tag>
            </div>
            <!-- 首帧来源与按需提取上一镜视频尾帧操作 -->
            <div class="flex items-center gap-1.5">
              <span v-if="tailSourceInfo" class="text-[11px] text-indigo-600 font-medium">
                🔗 {{ tailSourceInfo }}
              </span>
              <el-button
                size="small"
                type="primary"
                plain
                :loading="extractingTail"
                @click="handleInheritPreviousTail(false)"
                title="从同镜头组上一镜已生成的实际视频中提取尾部稳定帧并引用为本镜首帧"
              >
                📎 从上一镜视频提取并引用尾帧
              </el-button>
              <el-popconfirm
                v-if="firstFrameUrl && firstFrameSourceType === 'PREVIOUS_VIDEO_TAIL'"
                title="确定要忽略缓存强制重新提取上一镜视频尾帧吗？"
                @confirm="handleInheritPreviousTail(true)"
              >
                <template #reference>
                  <el-button
                    size="small"
                    text
                    type="warning"
                    :loading="extractingTail"
                    title="忽略缓存强制重新截取上一镜视频末帧"
                  >
                    🔄 重新提取
                  </el-button>
                </template>
              </el-popconfirm>
            </div>
          </div>

          <!-- 图片展示/占位框 -->
          <div class="h-52 bg-slate-900 rounded-lg overflow-hidden border border-slate-700 flex items-center justify-center relative group">
            <el-image
              v-if="firstFrameUrl"
              :src="firstFrameUrl"
              :preview-src-list="[firstFrameUrl]"
              preview-teleported
              fit="contain"
              class="w-full h-full cursor-pointer"
            />
            <div v-else-if="generatingFirst" class="flex flex-col items-center gap-2 text-indigo-400 text-xs font-mono">
              <el-icon class="is-loading text-2xl"><Loading /></el-icon>
              <span>正在生成首帧关键图...</span>
            </div>
            <div v-else class="flex flex-col items-center gap-1 text-slate-400 text-xs">
              <span class="text-3xl">🌅</span>
              <span>未设置首帧图 (可凭提示词生成)</span>
            </div>

            <div v-if="firstFrameUrl" class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center text-white text-xs pointer-events-none gap-1">
              <span>🔍 点击放大预览</span>
            </div>
          </div>

          <!-- 首帧操作栏 -->
          <div class="flex items-center justify-between gap-2 pt-1 border-t border-gray-100">
            <div class="flex items-center gap-1.5">
              <el-button
                size="small"
                type="primary"
                :loading="generatingFirst"
                @click="handleGenerateFirstFrame"
              >
                ⚡ AI 生首帧
              </el-button>
              <el-upload
                :show-file-list="false"
                :before-upload="handleUploadFirstFrame"
                accept="image/*"
              >
                <el-button size="small" plain :loading="uploadingFirst">
                  📤 上传首帧
                </el-button>
              </el-upload>
            </div>
            <el-button
              v-if="firstFrameUrl"
              size="small"
              type="danger"
              text
              @click="handleClearFirstFrame"
            >
              清除
            </el-button>
          </div>
        </div>

        <!-- 尾帧图卡片 -->
        <div class="border-2 border-purple-200/80 rounded-xl p-4 bg-white flex flex-col gap-3 shadow-sm hover:border-purple-400 transition-colors">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-1.5">
              <span class="text-sm font-bold text-purple-900">🏁 尾帧图 (End Frame)</span>
              <el-tag size="small" :type="endFrameUrl ? 'success' : 'info'" effect="plain">
                {{ endFrameUrl ? '已设置' : '可选/未设置' }}
              </el-tag>
            </div>
            <span class="text-[11px] text-gray-400">过渡重点终点</span>
          </div>

          <!-- 图片展示/占位框 -->
          <div class="h-52 bg-slate-900 rounded-lg overflow-hidden border border-slate-700 flex items-center justify-center relative group">
            <el-image
              v-if="endFrameUrl"
              :src="endFrameUrl"
              :preview-src-list="[endFrameUrl]"
              preview-teleported
              fit="contain"
              class="w-full h-full cursor-pointer"
            />
            <div v-else-if="generatingEnd" class="flex flex-col items-center gap-2 text-purple-400 text-xs font-mono">
              <el-icon class="is-loading text-2xl"><Loading /></el-icon>
              <span>正在生成尾帧关键图...</span>
            </div>
            <div v-else class="flex flex-col items-center gap-1 text-slate-400 text-xs">
              <span class="text-3xl">🌆</span>
              <span>未设置尾帧图 (可选)</span>
            </div>

            <div v-if="endFrameUrl" class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center text-white text-xs pointer-events-none gap-1">
              <span>🔍 点击放大预览</span>
            </div>
          </div>

          <!-- 尾帧操作栏 -->
          <div class="flex items-center justify-between gap-2 pt-1 border-t border-gray-100">
            <div class="flex items-center gap-1.5">
              <el-button
                size="small"
                type="primary"
                class="!bg-purple-600 !border-purple-600 !text-white hover:!bg-purple-500"
                :loading="generatingEnd"
                @click="handleGenerateEndFrame"
              >
                ⚡ AI 生尾帧
              </el-button>
              <el-upload
                :show-file-list="false"
                :before-upload="handleUploadEndFrame"
                accept="image/*"
              >
                <el-button size="small" plain :loading="uploadingEnd">
                  📤 上传尾帧
                </el-button>
              </el-upload>
            </div>
            <el-button
              v-if="endFrameUrl"
              size="small"
              type="danger"
              text
              @click="handleClearEndFrame"
            >
              清除
            </el-button>
          </div>
        </div>
      </div>

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

    <!-- AI 首尾帧生图工坊 (多参考图多模态生图) -->
    <ShotImageGenerateModal
      ref="imageGenerateModalRef"
      @applied="handleImageApplied"
    />

    <!-- 底部操作栏 -->
    <template #footer>
      <div class="flex justify-between items-center">
        <div class="text-xs text-gray-500">
          <span>分镜: <strong>#{{ shotNo }} {{ shotName }}</strong></span>
          <span v-if="tailSourceInfo" class="ml-3 text-indigo-600 font-medium">✓ {{ tailSourceInfo }}</span>
        </div>
        <div class="flex items-center gap-2.5">
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" plain :loading="savingConfig" @click="handleSaveConfig">
            💾 仅保存配置
          </el-button>
          <el-button
            type="primary"
            class="!bg-purple-600 !border-purple-600 hover:!bg-purple-500 !font-bold"
            :loading="renderingVideo"
            @click="handleStartRender"
          >
            🚀 启动首尾帧渲染
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { shotApi, dramaApi } from '@/api/drama'
import { assetApi } from '@/api/res-asset'
import { aiProviderApi } from '@/api/ai-provider'
import ShotImageGenerateModal from './ShotImageGenerateModal.vue'
import type { DramaShot } from '@/types/drama'
import type { AiProviderVO, AiModel } from '@/types/ai-provider'
import { resolveGenerationSize, validateGenerationSize } from '@/utils/generation-size'

const emit = defineEmits<{
  (e: 'success', shotId: string | number): void
}>()

const visible = ref(false)
const currentShotId = ref<string | number>()
const shotNo = ref<number>(1)
const shotName = ref<string>('')
const dramaId = ref<string | number>(0)
const sceneId = ref<string | number>(0)

const prompt = ref<string>('')
const endFramePrompt = ref<string>('')
const negativePrompt = ref<string>('')
const firstFrameUrl = ref<string>('')
const firstFrameSourceType = ref<string>('')
const tailSourceInfo = ref<string>('')
const endFrameUrl = ref<string>('')
const videoUrl = ref<string>('')
const extractingTail = ref(false)
const dramaAspectRatio = ref<string>('')

const imageGenerateModalRef = ref<InstanceType<typeof ShotImageGenerateModal> | null>(null)

const generatingFirst = ref(false)
const generatingEnd = ref(false)
const uploadingFirst = ref(false)
const uploadingEnd = ref(false)
const savingConfig = ref(false)
const renderingVideo = ref(false)

const enabledProviders = ref<AiProviderVO[]>([])
const models = ref<AiModel[]>([])
const renderConfig = reactive({
  providerId: '',
  modelCode: 'minimax-h3-fl2va',
  size: '544x960',
  seed: -1
})

function getStandardSizeByRatio(ratio?: string): string {
  return resolveGenerationSize(ratio, 'video')
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
    enabledProviders.value = list || []
    if (!renderConfig.providerId && enabledProviders.value.length > 0) {
      renderConfig.providerId = String(enabledProviders.value[0].id)
    }
    if (renderConfig.providerId) {
      await handleProviderChange(renderConfig.providerId)
    }
  } catch (ignored) {}
}

async function handleProviderChange(pid: string) {
  models.value = []
  if (!pid) {
    return
  }
  try {
    const list = await aiProviderApi.getModelList(pid, 'TXT2VIDEO_FIRST_LAST,TXT2VIDEO_REF,TXT2VIDEO,VIDEO,I2V,T2V,IMG2VIDEO')
    let validModels = (list || []).filter(m => m.status === 1)
    if (validModels.length === 0) {
      const allList = await aiProviderApi.getModelList(pid)
      validModels = (allList || []).filter(m => m.status === 1)
    }
    models.value = validModels
    if (models.value.length && !models.value.find(m => m.modelCode === renderConfig.modelCode)) {
      renderConfig.modelCode = models.value[0].modelCode
    }
  } catch (e: any) {
    console.error('加载视频模型列表失败:', e)
  }
}

async function open(shot: DramaShot, _prevShotTailUrl?: string, dramaAspectRatioParam?: string) {
  currentShotId.value = shot.id
  shotNo.value = shot.shotNo || 1
  shotName.value = shot.shotName || ''
  dramaId.value = shot.dramaId || 0
  sceneId.value = shot.sceneId || 0

  prompt.value = shot.prompt || shot.scriptContent || shot.actionDescription || ''
  endFramePrompt.value = shot.endFramePrompt || ''
  negativePrompt.value = shot.negativePrompt || ''
  firstFrameUrl.value = shot.previewImageUrl || ''
  firstFrameSourceType.value = shot.firstFrameSourceType || ''
  tailSourceInfo.value = shot.firstFrameSourceShotId ? `已引用上一镜 (ID: ${shot.firstFrameSourceShotId}) 尾帧` : ''
  endFrameUrl.value = shot.endFrameImageUrl || ''
  videoUrl.value = shot.videoUrl || ''

  // 尝试获取短剧配置中的画幅比例与尺寸
  dramaAspectRatio.value = dramaAspectRatioParam || ''
  if (dramaAspectRatio.value) {
    renderConfig.size = getStandardSizeByRatio(dramaAspectRatio.value)
  } else if (shot.dramaId) {
    dramaApi.getById(String(shot.dramaId)).then((res: any) => {
      if (res?.aspectRatio) {
        dramaAspectRatio.value = res.aspectRatio
        renderConfig.size = getStandardSizeByRatio(res.aspectRatio)
      }
    }).catch(() => {})
  } else {
    renderConfig.size = '544x960'
  }

  visible.value = true
  loadProviders()
}

// 从上一镜视频按需提取并引用尾帧
async function handleInheritPreviousTail(forceExtract = false) {
  if (!currentShotId.value) return
  extractingTail.value = true
  try {
    const res = await shotApi.inheritPreviousVideoTail(currentShotId.value, { forceExtract })
    firstFrameUrl.value = res.tailFrameUrl
    firstFrameSourceType.value = 'PREVIOUS_VIDEO_TAIL'
    tailSourceInfo.value = `来源: S${res.sourceShotNo || ''} ${res.sourceShotName || ''}`
    if (res.reused) {
      ElMessage.success('已引用缓存的上一镜视频尾帧')
    } else {
      ElMessage.success('已从上一镜视频提取尾帧并设为当前首帧')
    }
    emit('success', currentShotId.value)
  } catch (e: any) {
    ElMessage.error(e.message || '提取/引用上一镜尾帧失败')
  } finally {
    extractingTail.value = false
  }
}

// 打开生图工坊生成首帧 (支持多选人物、道具、场景参考图)
function handleGenerateFirstFrame() {
  if (!currentShotId.value) return
  imageGenerateModalRef.value?.open({
    shotId: currentShotId.value,
    dramaId: dramaId.value,
    frameType: 'FIRST_FRAME',
    shotTitle: `#${shotNo.value} ${shotName.value}`,
    prompt: prompt.value,
    negativePrompt: negativePrompt.value,
    slotOriginUrl: firstFrameUrl.value,
    aspectRatio: dramaAspectRatio.value
  })
}

// 打开生图工坊生成尾帧 (支持以首帧为参考图、多选人物、道具、场景参考图)
function handleGenerateEndFrame() {
  if (!currentShotId.value) return
  imageGenerateModalRef.value?.open({
    shotId: currentShotId.value,
    dramaId: dramaId.value,
    frameType: 'END_FRAME',
    shotTitle: `#${shotNo.value} ${shotName.value}`,
    prompt: endFramePrompt.value || prompt.value,
    negativePrompt: negativePrompt.value,
    slotOriginUrl: endFrameUrl.value,
    firstFrameUrl: firstFrameUrl.value,
    aspectRatio: dramaAspectRatio.value
  })
}

// 接收生图工坊应用回调
async function handleImageApplied(payload: { frameType: 'FIRST_FRAME' | 'END_FRAME'; url: string }) {
  if (payload.frameType === 'FIRST_FRAME') {
    firstFrameUrl.value = payload.url
    ElMessage.success('已应用生成图为首帧！')
  } else if (payload.frameType === 'END_FRAME') {
    endFrameUrl.value = payload.url
    ElMessage.success('已应用生成图为尾帧！')
  }
  if (currentShotId.value) {
    emit('success', currentShotId.value)
  }
}

// 本地上传首帧
async function handleUploadFirstFrame(file: File) {
  if (!currentShotId.value) return false
  uploadingFirst.value = true
  try {
    const res = await assetApi.upload(file, 'shots')
    if (res && res.url) {
      firstFrameUrl.value = res.url
      firstFrameSourceType.value = 'MANUAL_UPLOAD'
      tailSourceInfo.value = ''
      await shotApi.setFirstFrame(currentShotId.value, res.url)
      ElMessage.success('首帧图上传并保存成功！')
      emit('success', currentShotId.value)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '上传首帧失败')
  } finally {
    uploadingFirst.value = false
  }
  return false
}

// 清除首帧
async function handleClearFirstFrame() {
  firstFrameUrl.value = ''
  firstFrameSourceType.value = ''
  tailSourceInfo.value = ''
  if (currentShotId.value) {
    try {
      await shotApi.update({
        id: currentShotId.value,
        previewImageUrl: ''
      })
      ElMessage.info('已清除首帧')
      emit('success', currentShotId.value)
    } catch (ignored) {}
  }
}

// 本地上传尾帧
async function handleUploadEndFrame(file: File) {
  if (!currentShotId.value) return false
  uploadingEnd.value = true
  try {
    const res = await assetApi.upload(file, 'shots')
    if (res && res.url) {
      endFrameUrl.value = res.url
      await shotApi.setEndFrame(currentShotId.value, res.url)
      ElMessage.success('尾帧图上传并保存成功！')
      emit('success', currentShotId.value)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '上传尾帧失败')
  } finally {
    uploadingEnd.value = false
  }
  return false
}

// 清除尾帧
async function handleClearEndFrame() {
  endFrameUrl.value = ''
  if (currentShotId.value) {
    try {
      await shotApi.setEndFrame(currentShotId.value, '')
      ElMessage.info('已清除尾帧')
      emit('success', currentShotId.value)
    } catch (ignored) {}
  }
}

// 保存配置
async function handleSaveConfig() {
  if (!currentShotId.value) return
  savingConfig.value = true
  try {
    await shotApi.update({
      id: currentShotId.value,
      generationMode: 'FIRST_LAST_FRAME',
      prompt: prompt.value,
      endFramePrompt: endFramePrompt.value,
      negativePrompt: negativePrompt.value,
      previewImageUrl: firstFrameUrl.value,
      endFrameImageUrl: endFrameUrl.value
    })
    ElMessage.success('首尾帧配置保存成功！')
    emit('success', currentShotId.value)
  } catch (e: any) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    savingConfig.value = false
  }
}

// 启动首尾帧渲染
async function handleStartRender() {
  if (!currentShotId.value) return
  if (!renderConfig.modelCode) {
    ElMessage.warning('请选择或输入视频模型代码')
    return
  }
  const sizeValidation = validateGenerationSize(renderConfig.size, 'video')
  if (!sizeValidation.valid) {
    ElMessage.warning(sizeValidation.message)
    return
  }
  await handleSaveConfig()
  renderingVideo.value = true
  try {
    await shotApi.submitRender(currentShotId.value, {
      providerId: renderConfig.providerId ? (renderConfig.providerId as any) : undefined,
      workflowTemplateId: renderConfig.modelCode,
      size: sizeValidation.normalized,
      seed: (renderConfig.seed !== undefined && renderConfig.seed >= 0) ? renderConfig.seed : undefined
    })
    ElMessage.success('首尾帧视频渲染任务已提交！')
    emit('success', currentShotId.value)
    visible.value = false
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
