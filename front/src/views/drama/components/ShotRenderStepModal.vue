<template>
  <el-dialog
    v-model="visible"
    :title="`🎬 分镜 [S${shotNo} ${shotName || ''}] 两阶段步进渲染工坊`"
    width="860px"
    destroy-on-close
    append-to-body
    :close-on-click-modal="false"
  >
    <!-- 顶部步骤指示器 -->
    <div class="px-6 py-2 mb-4 bg-slate-50 border border-slate-100 rounded-xl">
      <el-steps :active="activeStep" finish-status="success" simple>
        <el-step title="步骤 1：首帧生图与定妆 (T2I)" />
        <el-step title="步骤 2：视频生成与归档 (I2V)" />
      </el-steps>
    </div>

    <!-- 步骤 1：首帧生图与定妆 (T2I) -->
    <div v-show="activeStep === 0" class="space-y-4 max-h-[68vh] overflow-y-auto px-1 py-1 custom-scrollbar">
      <!-- 生图配置卡片 -->
      <div class="bg-indigo-50/40 border border-indigo-100 rounded-xl p-4 space-y-3">
        <div class="flex items-center justify-between">
          <span class="text-xs font-bold text-indigo-950">AI 生图配置 (OpenAI / FastAPI 标准规范)：</span>
          <span class="text-[11px] text-indigo-700">自动调用 FastAPI 网关或云端兼容 API 并归档 MinIO</span>
        </div>

        <div class="grid grid-cols-4 gap-3 pt-2 border-t border-indigo-100/80">
          <div>
            <label class="text-[11px] font-bold text-slate-600 mb-1 block">AI 提供商</label>
            <el-select v-model="step1Form.providerId" placeholder="系统默认" clearable size="small" class="w-full" @change="handleProviderChange">
              <el-option
                v-for="p in enabledProviders"
                :key="String(p.id)"
                :label="p.providerName"
                :value="String(p.id)"
              />
            </el-select>
          </div>
          <div>
            <label class="text-[11px] font-bold text-slate-600 mb-1 block">模型标识 (Model Code)</label>
            <el-select v-model="step1Form.modelCode" placeholder="选择生图模型" size="small" filterable class="w-full" :disabled="!step1Form.providerId">
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
              <label class="text-[11px] font-bold text-slate-600 block">分辨率尺寸 (Size)</label>
              <span v-if="dramaAspectRatio" class="text-[10px] text-sky-600 font-mono">
                短剧配置: {{ dramaAspectRatio }}
              </span>
            </div>
            <el-select v-model="step1Form.size" size="small" filterable allow-create default-first-option class="w-full">
              <el-option label="720x1280 (9:16 短剧竖屏)" value="720x1280" />
              <el-option label="1280x720 (16:9 标清横屏)" value="1280x720" />
              <el-option label="1088x1920 (近 9:16 高清竖屏，实际生成尺寸)" value="1088x1920" />
              <el-option label="1920x1088 (近 16:9 高清横屏，实际生成尺寸)" value="1920x1088" />
              <el-option label="1024x1024 (1:1 正方形)" value="1024x1024" />
              <el-option label="1024x768 (4:3 经典横屏)" value="1024x768" />
              <el-option label="768x1024 (3:4 经典竖屏)" value="768x1024" />
              <el-option label="1024x1792 (9:16 进阶竖屏)" value="1024x1792" />
              <el-option label="1792x1024 (16:9 进阶横屏)" value="1792x1024" />
            </el-select>
          </div>
          <div>
            <label class="text-[11px] font-bold text-slate-600 mb-1 block">随机种子 Seed</label>
            <el-input-number v-model="step1Form.seed" size="small" :min="-1" placeholder="自动随机" class="!w-full" />
          </div>
        </div>
      </div>

      <!-- 生图 Prompt 工作区 -->
      <div class="grid grid-cols-1 gap-2">
        <div class="flex items-center justify-between">
          <label class="text-xs font-bold text-slate-700 flex items-center gap-1">
            <span>🖼️</span> 首帧生图提示词 (Prompt)
          </label>
          <span class="text-[10px] text-slate-400">已自动注入当前绑定的角色外观与场景光影</span>
        </div>
        <el-input
          v-model="step1Form.prompt"
          type="textarea"
          :rows="3"
          placeholder="请输入或微调首帧生图提示词..."
        />
      </div>

      <!-- 首帧大图效果与抽卡区 -->
      <div class="bg-slate-900 rounded-xl p-4 flex flex-col md:flex-row gap-4 items-center justify-between shadow-inner">
        <!-- 左侧大图预览 -->
        <div class="w-full md:w-80 h-72 bg-slate-950 rounded-lg overflow-hidden border border-slate-800 flex items-center justify-center relative flex-shrink-0 group">
          <!-- 生成完成的大图 -->
          <div v-if="currentPreviewUrl" class="w-full h-full relative group flex items-center justify-center">
            <el-image
              :src="currentPreviewUrl"
              :preview-src-list="[currentPreviewUrl]"
              preview-teleported
              fit="contain"
              class="w-full h-full cursor-pointer flex items-center justify-center"
              alt="首帧图预览"
            />
            <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded flex items-center justify-center text-white text-xs font-medium pointer-events-none gap-1.5 shadow-lg">
              <span>🔍 点击预览全屏大图</span>
            </div>
          </div>

          <!-- 加载动效 -->
          <div v-else-if="step1Generating" class="flex flex-col items-center gap-2 text-indigo-400">
            <el-icon class="is-loading text-3xl"><Loading /></el-icon>
            <span class="text-xs font-mono">{{ generatingStatusText }}</span>
          </div>

          <!-- 空占位 -->
          <div v-else class="flex flex-col items-center gap-2 text-slate-500 select-none">
            <span class="text-4xl opacity-50">🖼️</span>
            <span class="text-xs font-mono">尚未生成首帧关键图</span>
          </div>

          <!-- 已确认为分镜首帧徽章 -->
          <div v-if="currentPreviewUrl && currentPreviewUrl === originalPreviewUrl" class="absolute top-2 left-2 bg-emerald-600/90 backdrop-blur-sm text-white text-[10px] font-bold px-2 py-0.5 rounded shadow pointer-events-none">
            ✓ 已保存为分镜首帧
          </div>
          <div v-else-if="currentPreviewUrl" class="absolute top-2 left-2 bg-amber-600/90 backdrop-blur-sm text-white text-[10px] font-bold px-2 py-0.5 rounded shadow pointer-events-none">
            ⏳ 待保存确认
          </div>
        </div>

        <!-- 右侧操作与说明 -->
        <div class="flex-1 flex flex-col justify-between h-full space-y-3 text-slate-200">
          <div class="space-y-1.5">
            <h4 class="text-sm font-bold text-white flex items-center gap-1.5">
              <span>🎯</span> 首帧关键图定妆法则
            </h4>
            <p class="text-xs text-slate-400 leading-relaxed">
              第一帧是后续视频的基石。请先在此检视画面质量与角色长相，若不满意可反复 <strong>🎲 重新抽取 (Re-roll)</strong> 或 <strong>📁 本地上传</strong>。
            </p>
            <div class="bg-slate-800/80 rounded-lg p-2.5 text-[11px] text-slate-300 space-y-1">
              <div>• 必须拥有有效首帧图后，才允许解锁进入「步骤 2：生成视频」；</div>
              <div>• 确认首帧后，后续图生视频 (I2V) 将 100% 以此图进行连续演进。</div>
            </div>
          </div>

          <!-- 操作按钮行 -->
          <div class="flex flex-wrap items-center gap-2 pt-2">
            <el-button
              type="primary"
              :loading="step1Generating"
              @click="handleGenerateFirstFrame"
            >
              🎲 {{ currentPreviewUrl ? '重新抽取 (Re-roll)' : '立即生成首帧' }}
            </el-button>

            <!-- 本地文件上传 -->
            <el-upload
              :show-file-list="false"
              :before-upload="handleUploadLocalImage"
              accept="image/*"
            >
              <el-button type="info" plain :loading="uploading">
                📁 本地上传替换
              </el-button>
            </el-upload>

            <el-button
              v-if="currentPreviewUrl && currentPreviewUrl !== originalPreviewUrl"
              type="success"
              :loading="savingFirstFrame"
              @click="handleSaveFirstFrame"
            >
              💾 确认为分镜首帧
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 步骤 2：视频生成与归档 (I2V) -->
    <div v-show="activeStep === 1" class="space-y-4 max-h-[68vh] overflow-y-auto px-1 py-1 custom-scrollbar">
      <!-- 首帧底图确认条 -->
      <div class="bg-emerald-50/60 border border-emerald-100 rounded-xl p-3 flex items-center justify-between">
        <div class="flex items-center gap-3">
          <div class="relative group cursor-pointer w-14 h-14 shrink-0">
            <el-image
              :src="currentPreviewUrl"
              :preview-src-list="[currentPreviewUrl]"
              preview-teleported
              fit="cover"
              class="w-14 h-14 rounded-lg border border-emerald-200 shadow-sm block"
              alt="输入首帧"
            />
            <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded-lg flex items-center justify-center text-white text-[10px] pointer-events-none">
              🔍 预览
            </div>
          </div>
          <div>
            <div class="text-xs font-bold text-emerald-950 flex items-center gap-1.5">
              <span>🖼️</span> 已锁定输入首帧图 (I2V Base Frame)
            </div>
            <p class="text-[11px] text-emerald-800 line-clamp-1 mt-0.5">
              视频生成将完全继承此首帧的人物外貌、构图与环境光影。
            </p>
          </div>
        </div>
        <el-button size="small" link type="primary" @click="activeStep = 0">
          ✏️ 换一张首帧
        </el-button>
      </div>

      <!-- 视频工作流与参数设置 -->
      <div class="bg-slate-50 border border-slate-200 rounded-xl p-4 space-y-3">
        <div class="grid grid-cols-2 gap-4">
          <div>
            <label class="text-xs font-bold text-slate-700 mb-1.5 block">视频生成工作流模板</label>
            <el-select v-model="step2Form.workflowTemplateId" class="w-full">
              <el-option label="Wan 2.1 视频生成标准工作流 (WAN_VIDEO_GEN)" value="WAN_VIDEO_GEN" />
              <el-option label="可灵 I2V 高清工作流 (KLING_I2V)" value="KLING_I2V" />
            </el-select>
          </div>
          <div>
            <label class="text-xs font-bold text-slate-700 mb-1.5 block">视频随机种子 (Seed)</label>
            <el-input-number v-model="step2Form.seed" :min="-1" placeholder="留空自动随机" class="!w-full" />
          </div>
        </div>

        <div>
          <label class="text-xs font-bold text-slate-700 mb-1.5 block">
            🎥 视频动态运镜提示词 (Video Motion Prompt)
          </label>
          <el-input
            v-model="step2Form.videoPrompt"
            type="textarea"
            :rows="3"
            placeholder="描述镜头运镜轨迹、肢体动作演进与光影变化..."
          />
        </div>
      </div>

      <!-- 视频产物展示与播放区 -->
      <div class="bg-slate-900 rounded-xl p-4 flex flex-col md:flex-row gap-4 items-center justify-between shadow-inner">
        <!-- 播放器 / 动效 -->
        <div class="w-full md:w-80 h-72 bg-slate-950 rounded-lg overflow-hidden border border-slate-800 flex items-center justify-center relative flex-shrink-0">
          <video
            v-if="currentVideoUrl"
            :src="currentVideoUrl"
            controls
            autoplay
            loop
            class="w-full h-full object-contain"
          ></video>

          <div v-else-if="step2Rendering" class="flex flex-col items-center gap-2 text-blue-400">
            <el-icon class="is-loading text-3xl"><Loading /></el-icon>
            <span class="text-xs font-mono">{{ videoStatusText }}</span>
          </div>

          <div v-else class="flex flex-col items-center gap-2 text-slate-500 select-none">
            <span class="text-4xl opacity-50">🎬</span>
            <span class="text-xs font-mono">点击下方按钮启动视频生成</span>
          </div>
        </div>

        <!-- 右侧视频详情与动作 -->
        <div class="flex-1 flex flex-col justify-between h-full space-y-3 text-slate-200">
          <div class="space-y-2">
            <div class="flex items-center justify-between">
              <h4 class="text-sm font-bold text-white flex items-center gap-1.5">
                <span>🚀</span> 启动视频渲染管线
              </h4>
              <el-button
                size="small"
                link
                type="primary"
                class="!text-indigo-400 hover:!text-indigo-300"
                @click="historyDrawerVisible = true"
              >
                🎞️ 抽卡历史与重选
              </el-button>
            </div>
            <p class="text-xs text-slate-400 leading-relaxed">
              系统将把步骤 1 确定的首帧图注入，并结合视频动态运镜提示词生成流畅的视频片段并提取末帧。
            </p>
            <div v-if="currentLastFrameUrl" class="bg-slate-800/80 rounded-lg p-2.5 flex items-center gap-3">
              <div class="relative group cursor-pointer w-12 h-12 shrink-0">
                <el-image
                  :src="currentLastFrameUrl"
                  :preview-src-list="[currentLastFrameUrl]"
                  preview-teleported
                  fit="cover"
                  class="w-12 h-12 rounded border border-slate-700 block"
                  alt="末帧"
                />
                <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded flex items-center justify-center text-white text-[9px] pointer-events-none">
                  🔍
                </div>
              </div>
              <div class="text-[11px] text-slate-300">
                <span class="font-bold text-emerald-400">✓ 视频末帧已提取</span>
                <p class="text-slate-400">可作为镜头组内下一镜的续接首帧。</p>
              </div>
            </div>
          </div>

          <div class="pt-2 flex items-center gap-2">
            <el-button
              type="success"
              size="large"
              class="flex-1 !font-bold"
              :loading="step2Rendering"
              @click="handleRenderVideo"
            >
              {{ currentVideoUrl ? '🔄 重新抽卡渲染 (Re-roll)' : '🚀 启动视频渲染' }}
            </el-button>

            <el-button
              size="large"
              type="info"
              plain
              class="!font-medium"
              @click="historyDrawerVisible = true"
            >
              🎞️ 抽卡历史
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部导航与操作条 -->
    <template #footer>
      <div class="flex items-center justify-between">
        <div class="text-xs text-slate-500">
          <span v-if="activeStep === 0 && !currentPreviewUrl" class="text-amber-600">
            ⚠️ 必须先生成或上传首帧，才能解锁生成视频
          </span>
          <span v-else-if="activeStep === 0" class="text-emerald-600">
            ✓ 首帧已就绪，可随时进入步骤 2 生成视频
          </span>
        </div>

        <div class="flex items-center gap-2">
          <!-- 上一步 -->
          <el-button v-if="activeStep === 1" @click="activeStep = 0">
            ⬅ 返回首帧定妆
          </el-button>

          <!-- 下一步 -->
          <el-button
            v-if="activeStep === 0"
            type="primary"
            :disabled="!currentPreviewUrl || step1Generating"
            @click="activeStep = 1"
          >
            下一步：生成视频 (Step 2) ➔
          </el-button>

          <!-- 关闭 -->
          <el-button @click="visible = false">
            {{ currentVideoUrl ? '完成并关闭' : '关闭' }}
          </el-button>
        </div>
      </div>
    </template>

    <!-- 视频抽卡候选历史抽屉 -->
    <ShotVideoHistoryDrawer
      v-if="currentShotId"
      v-model="historyDrawerVisible"
      :shot-id="currentShotId"
      :shot-no="shotNo"
      :shot-name="shotName"
      @selected="handleTakeSelected"
    />
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { shotApi, dramaApi } from '@/api/drama'
import { assetApi } from '@/api/res-asset'
import { aiProviderApi } from '@/api/ai-provider'
import ShotVideoHistoryDrawer from './ShotVideoHistoryDrawer.vue'
import type { AiProviderVO, AiModel } from '@/types/ai-provider'
import type { DramaShot } from '@/types/drama'
import { resolveGenerationSize, validateGenerationSize } from '@/utils/generation-size'

const emit = defineEmits<{
  (e: 'success', shotId: string | number): void
}>()

const visible = ref(false)
const activeStep = ref<number>(0)
const historyDrawerVisible = ref(false)
const currentShot = ref<DramaShot | null>(null)

const currentShotId = ref<string | number>()
const shotNo = ref<number>(1)
const shotName = ref<string>('')
const dramaId = ref<string | number>(0)
const dramaAspectRatio = ref<string>('')

const originalPreviewUrl = ref<string>('')
const currentPreviewUrl = ref<string>('')
const currentVideoUrl = ref<string>('')
const currentLastFrameUrl = ref<string>('')

const step1Generating = ref<boolean>(false)
const generatingStatusText = ref<string>('正在排队渲染...')
const savingFirstFrame = ref<boolean>(false)
const uploading = ref<boolean>(false)

const step2Rendering = ref<boolean>(false)
const videoStatusText = ref<string>('视频排队渲染中...')

const enabledProviders = ref<AiProviderVO[]>([])
const models = ref<AiModel[]>([])

// 步骤 1 表单
const step1Form = reactive({
  engineType: 'API' as 'API' | string,
  providerId: undefined as string | number | undefined,
  modelCode: 'flux-2-klein-9b',
  workflowTemplateId: undefined as string | undefined,
  size: '720x1280',
  seed: undefined as number | undefined,
  prompt: '',
  negativePrompt: ''
})

// 步骤 2 表单
const step2Form = reactive({
  workflowTemplateId: 'WAN_VIDEO_GEN',
  seed: undefined as number | undefined,
  videoPrompt: ''
})

function getStandardSizeByRatio(ratio?: string): string {
  return resolveGenerationSize(ratio, 'image')
}

function handleTakeSelected(payload: { takeId: string; videoUrl: string }) {
  currentVideoUrl.value = payload.videoUrl
  currentLastFrameUrl.value = ''
  if (currentShot.value) {
    currentShot.value.videoUrl = payload.videoUrl
    currentShot.value.currentVideoTakeId = payload.takeId
    currentShot.value.lastFrameUrl = undefined
    currentShot.value.lastFrameSourceTakeId = undefined
  }
  if (currentShotId.value) {
    emit('success', currentShotId.value)
  }
}

async function open(shot: DramaShot, dramaAspectRatioParam?: string) {
  if (!shot.id) return
  currentShot.value = shot
  currentShotId.value = shot.id
  shotNo.value = shot.shotNo
  shotName.value = shot.shotName
  dramaId.value = shot.dramaId || 0

  originalPreviewUrl.value = shot.previewImageUrl || ''
  currentPreviewUrl.value = shot.previewImageUrl || ''
  currentVideoUrl.value = shot.videoUrl || ''
  currentLastFrameUrl.value = shot.lastFrameUrl || ''

  // 尝试获取短剧配置中的画幅比例与尺寸
  dramaAspectRatio.value = dramaAspectRatioParam || ''
  if (dramaAspectRatio.value) {
    step1Form.size = getStandardSizeByRatio(dramaAspectRatio.value)
  } else if (shot.dramaId) {
    dramaApi.getById(shot.dramaId).then((res: any) => {
      if (res?.aspectRatio) {
        dramaAspectRatio.value = res.aspectRatio
        step1Form.size = getStandardSizeByRatio(res.aspectRatio)
      }
    }).catch(() => {})
  } else {
    step1Form.size = '720x1280'
  }

  // 默认如果已有视频则进入 Step 2，否则进入 Step 1
  activeStep.value = (shot.previewImageUrl && shot.videoUrl) ? 1 : 0

  step1Form.prompt = shot.prompt || ''
  step1Form.negativePrompt = shot.negativePrompt || ''
  step2Form.videoPrompt = shot.videoPrompt || shot.prompt || ''

  visible.value = true
  loadProviders()
}

async function loadProviders() {
  try {
    const list = await aiProviderApi.getListEnabled()
    enabledProviders.value = list || []
  } catch (ignored) {
  }
}

async function handleProviderChange(pid: string) {
  models.value = []
  if (!pid) {
    step1Form.modelCode = ''
    return
  }
  try {
    const list = await aiProviderApi.getModelList(String(pid), 'TXT2IMG,IMG2IMG,TXT_IMG2IMG')
    models.value = (list || []).filter(m => m.status === 1)
    if (models.value.length && !models.value.find(m => m.modelCode === step1Form.modelCode)) {
      step1Form.modelCode = models.value[0].modelCode
    }
  } catch (ignored) {}
}

// 步骤 1：生成首帧图
async function handleGenerateFirstFrame() {
  if (!currentShotId.value) return
  const sizeValidation = validateGenerationSize(step1Form.size, 'image')
  if (!sizeValidation.valid) return ElMessage.warning(sizeValidation.message)
  step1Generating.value = true
  generatingStatusText.value = '正在下发生图任务...'

  try {
    const res = await shotApi.generateFirstFrame(currentShotId.value, {
      engineType: step1Form.engineType,
      providerId: step1Form.providerId,
      modelCode: step1Form.modelCode,
      workflowTemplateId: step1Form.workflowTemplateId,
      size: sizeValidation.normalized,
      seed: (step1Form.seed !== undefined && step1Form.seed >= 0) ? step1Form.seed : undefined,
      customPrompt: step1Form.prompt,
      negativePrompt: step1Form.negativePrompt
    })

    if (res && res.outputUrl) {
      currentPreviewUrl.value = res.outputUrl
      ElMessage.success('首帧生图完成！')
      emit('success', currentShotId.value)
    } else if (res && res.taskId) {
      // 轮询等待任务完成
      await pollTask(res.taskId, (status, output) => {
        generatingStatusText.value = `渲染中: ${status}`
        if (output) {
          currentPreviewUrl.value = output
        }
      })
      ElMessage.success('首帧图已生成！')
      emit('success', currentShotId.value)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '首帧生成失败')
  } finally {
    step1Generating.value = false
  }
}

// 步骤 1：本地上传首帧图
async function handleUploadLocalImage(file: File) {
  if (!currentShotId.value) return false
  uploading.value = true
  try {
    const res = await assetApi.upload(file, 'shots')
    if (res && res.url) {
      currentPreviewUrl.value = res.url
      await handleSaveFirstFrame()
      ElMessage.success('首帧图上传并保存成功！')
    }
  } catch (e: any) {
    ElMessage.error(e.message || '上传失败')
  } finally {
    uploading.value = false
  }
  return false
}

// 步骤 1：确认为分镜首帧
async function handleSaveFirstFrame() {
  if (!currentShotId.value || !currentPreviewUrl.value) return
  savingFirstFrame.value = true
  try {
    await shotApi.setFirstFrame(currentShotId.value, currentPreviewUrl.value)
    originalPreviewUrl.value = currentPreviewUrl.value
    ElMessage.success('已将当前画面确认为分镜首帧！')
    emit('success', currentShotId.value)
  } catch (e: any) {
    ElMessage.error(e.message || '保存首帧失败')
  } finally {
    savingFirstFrame.value = false
  }
}

// 步骤 2：启动视频渲染
async function handleRenderVideo() {
  if (!currentShotId.value) return
  if (!currentPreviewUrl.value) {
    ElMessage.warning('请先完成步骤 1 选定首帧图')
    activeStep.value = 0
    return
  }

  step2Rendering.value = true
  videoStatusText.value = '正在下发视频工作流...'

  try {
    const res = await shotApi.submitRender(currentShotId.value, {
      workflowTemplateId: step2Form.workflowTemplateId,
      seed: (step2Form.seed !== undefined && step2Form.seed >= 0) ? step2Form.seed : undefined
    })

    if (res && res.taskId) {
      await pollTask(res.taskId, (status, output, lastFrame) => {
        videoStatusText.value = `视频渲染中 (${status})...`
        if (output) currentVideoUrl.value = output
        if (lastFrame) currentLastFrameUrl.value = lastFrame
      })
      ElMessage.success('视频渲染完成！')
      emit('success', currentShotId.value)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '视频渲染失败')
  } finally {
    step2Rendering.value = false
  }
}

// 简易轮询工具
async function pollTask(_taskId: string, onProgress: (status: string, outputUrl?: string, lastFrameUrl?: string) => void) {
  const maxAttempts = 300
  for (let i = 0; i < maxAttempts; i++) {
    await new Promise(resolve => setTimeout(resolve, 2000))
    if (!currentShotId.value) break
    const fresh = await shotApi.getById(currentShotId.value)
    if (fresh) {
      onProgress(fresh.renderStatus || 'RUNNING', fresh.previewImageUrl || fresh.videoUrl, fresh.lastFrameUrl)
      if (fresh.renderStatus === 'SUCCESS') {
        if (fresh.videoUrl) currentVideoUrl.value = fresh.videoUrl
        if (fresh.previewImageUrl) currentPreviewUrl.value = fresh.previewImageUrl
        if (fresh.lastFrameUrl) currentLastFrameUrl.value = fresh.lastFrameUrl
        return
      }
      if (fresh.renderStatus === 'FAILED') {
        throw new Error('渲染任务执行失败')
      }
    }
  }
}


defineExpose({
  open
})
</script>

<style scoped>
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background: #cbd5e1;
  border-radius: 6px;
}
</style>
