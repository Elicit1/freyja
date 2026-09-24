<template>
  <span>
    <el-button size="small" type="success" plain :disabled="!targetId" @click="open">AI 生成</el-button>
    <el-dialog
      v-model="visible"
      title="AI 资产生图与多模态参考"
      width="1020px"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="!generating"
      :show-close="!generating"
    >
      <div class="grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_360px] gap-6 max-h-[74vh] overflow-y-auto pr-1">
        <el-form label-position="top">
          <!-- 供应商与模型配置 -->
          <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
            <el-form-item label="AI 供应商" required>
              <el-select
                v-model="form.providerId"
                class="w-full"
                filterable
                clearable
                :disabled="generating"
                placeholder="请选择供应商"
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
                <span>当前模型支持【文生图/图生图 (双模)】：已开启多模态参考图引导功能</span>
              </span>
              <el-tag size="small" type="warning" effect="dark">图生图就绪</el-tag>
            </div>
            <div
              v-else
              class="flex items-center justify-between text-xs px-3 py-2 rounded-md bg-slate-100 text-slate-600 border border-slate-200"
            >
              <span class="flex items-center gap-1.5">
                <span>💡</span>
                <span>当前模型为【纯文生图】：不可使用参考图功能，将纯粹依据提示词生成</span>
              </span>
              <el-tag size="small" type="info">纯文生图</el-tag>
            </div>
          </div>

          <!-- 参考图控制区 (仅在模型支持图生图时可用) -->
          <div
            v-if="isImg2imgSupported"
            class="p-3 mb-4 rounded-lg border border-purple-200 bg-purple-50/40 space-y-3"
          >
            <div class="flex items-center justify-between">
              <span class="text-xs font-bold text-slate-800 flex items-center gap-1">
                <span>🖼️</span> 参考图来源选择 (定妆照 / 历史图 / 自定义)
              </span>
              <span class="text-[11px] text-purple-600">引导生成人脸骨相、三视图服饰与姿态</span>
            </div>

            <el-radio-group v-model="refMode" size="small" class="!flex flex-wrap gap-2">
              <el-radio-button v-if="props.avatarUrl" value="AVATAR">
                👤 角色定妆照 (推荐)
              </el-radio-button>
              <el-radio-button v-if="props.referenceImageUrl" value="CURRENT">
                🔄 槽位原有图
              </el-radio-button>
              <el-radio-button value="CUSTOM">
                📎 自定义图片
              </el-radio-button>
              <el-radio-button value="NONE">
                🚫 不使用参考图
              </el-radio-button>
            </el-radio-group>

            <!-- 自定义上传 / 输入 -->
            <div v-if="refMode === 'CUSTOM'" class="flex items-center gap-2 pt-1">
              <el-input
                v-model="customRefUrl"
                placeholder="输入参考图 URL，或点击右侧上传"
                size="small"
                class="flex-1"
                clearable
              />
              <el-upload
                action="#"
                :show-file-list="false"
                :http-request="handleCustomUpload"
                accept="image/*"
              >
                <el-button size="small" type="primary" plain :loading="uploadingRef">上传本地图片</el-button>
              </el-upload>
            </div>

            <!-- 当前参考图小缩略信息 -->
            <div v-if="effectiveRefImage" class="flex items-center gap-2 text-xs text-slate-600 bg-white p-1.5 rounded border border-purple-100">
              <el-avatar :size="28" :src="effectiveRefImage" shape="square" class="shrink-0 border border-slate-200" />
              <span class="truncate flex-1 font-mono text-[11px] text-slate-500">{{ effectiveRefImage }}</span>
              <el-tag size="small" type="success" effect="plain">生效中</el-tag>
            </div>
          </div>

          <el-form-item label="正向 Prompt" required>
            <el-input
              v-model="form.prompt"
              type="textarea"
              :rows="5"
              :disabled="generating"
              placeholder="输入正向生成提示词（支持由角色抽屉一键填充）"
            />
          </el-form-item>

          <el-form-item label="负向 Prompt">
            <el-input
              v-model="form.negativePrompt"
              type="textarea"
              :rows="3"
              :disabled="generating"
              placeholder="规避特征与负向提示词"
            />
          </el-form-item>

          <el-form-item label="图片尺寸">
            <el-select
              v-model="form.size"
              class="w-full"
              filterable
              allow-create
              default-first-option
              :disabled="generating"
              placeholder="选择或输入尺寸 (如 1024x1024)"
            >
              <el-option-group label="图片尺寸（16 像素对齐）">
                <el-option label="1280 x 720 (16:9 标清横屏)" value="1280x720" />
                <el-option label="720 x 1280 (9:16 短剧竖屏)" value="720x1280" />
                <el-option label="1920 x 1088 (近 16:9 高清横屏，实际生成尺寸)" value="1920x1088" />
                <el-option label="1088 x 1920 (近 9:16 高清竖屏，实际生成尺寸)" value="1088x1920" />
              </el-option-group>
              <el-option-group label="通用正方与人像海报">
                <el-option label="1024 x 1024 (1:1 正方形 - 特征图/定妆照推荐)" value="1024x1024" />
                <el-option label="1024 x 1536 (2:3 经典人像竖版)" value="1024x1536" />
                <el-option label="1536 x 1024 (3:2 场景横幅 / 三视图推荐)" value="1536x1024" />
              </el-option-group>
            </el-select>
          </el-form-item>
        </el-form>

        <!-- 右侧预览面板 -->
        <aside class="rounded-xl border border-slate-200 bg-slate-50/70 p-4 self-start">
          <div class="flex items-center justify-between mb-3">
            <div class="text-sm font-bold text-slate-800">生图监视器</div>
            <el-tag v-if="task" size="small" :type="task.status === 'FAILED' ? 'danger' : task.status === 'SUCCESS' ? 'success' : 'info'">
              {{ statusText(task.status) }}
            </el-tag>
          </div>
          <div class="space-y-4">
            <!-- 参考图预览 -->
            <section>
              <div class="flex items-center justify-between mb-1.5">
                <div class="text-xs font-semibold text-slate-500">当前参考图 (Input Ref)</div>
                <el-tag v-if="isImg2imgSupported && effectiveRefImage" size="small" type="purple" effect="plain">
                  {{ refMode === 'AVATAR' ? '角色定妆照' : refMode === 'CURRENT' ? '槽位原图' : '自定义图' }}
                </el-tag>
              </div>
              <div v-if="isImg2imgSupported && effectiveRefImage" class="relative group">
                <el-image
                  :src="effectiveRefImage"
                  fit="contain"
                  class="w-full h-36 rounded-lg border border-purple-200 bg-white"
                  :preview-src-list="[effectiveRefImage]"
                  preview-teleported
                />
              </div>
              <div v-else class="w-full h-24 rounded-lg border border-dashed border-slate-300 bg-white flex flex-col items-center justify-center text-xs text-slate-400 p-2 text-center">
                <span>{{ isImg2imgSupported ? '未启用参考图 (纯文本生图)' : '纯文生图模型，不使用参考图' }}</span>
              </div>
            </section>

            <!-- 生成结果预览 -->
            <section>
              <div class="text-xs font-semibold text-slate-500 mb-1.5">AI 生成结果 (Output)</div>
              <div v-if="generating" class="w-full h-56 rounded-lg border border-indigo-200 bg-indigo-50 flex flex-col items-center justify-center text-indigo-700" v-loading="true">
                <div class="mt-8 text-sm font-semibold">AI 生成中，请稍候...</div>
                <div class="text-xs mt-1">正在生图并自动归档至 MinIO</div>
              </div>
              <el-image
                v-else-if="task?.outputUrl"
                :src="task.outputUrl"
                fit="contain"
                class="w-full h-56 rounded-lg border border-emerald-200 bg-white shadow-xs"
                :preview-src-list="[task.outputUrl]"
                preview-teleported
              />
              <div
                v-else-if="task?.status === 'FAILED'"
                class="w-full h-56 rounded-lg border border-dashed border-rose-300 bg-rose-50 flex items-center justify-center text-center text-xs text-rose-600 px-4"
              >
                {{ task.errorMessage || '生成失败，请调整参数后重试' }}
              </div>
              <div v-else class="w-full h-56 rounded-lg border border-dashed border-slate-300 bg-white flex items-center justify-center text-xs text-slate-400">
                生成结果将在这里预览
              </div>
            </section>
            <div v-if="task?.outputUrl && !generating" class="text-xs text-emerald-600 font-medium">
              ✓ 生成图片已自动无损归档至 MinIO
            </div>
          </div>
        </aside>
      </div>

      <template #footer>
        <el-button :disabled="generating" @click="visible = false">取消</el-button>
        <el-button v-if="task?.outputUrl" type="success" :disabled="generating" @click="applyResult">
          应用到当前槽位
        </el-button>
        <el-button type="primary" :loading="loading || generating" :disabled="!canSubmit" @click="submit">
          {{ generating ? 'AI 生成中...' : '开始生成' }}
        </el-button>
      </template>
    </el-dialog>
  </span>
</template>

<script setup lang="ts">
import { reactive, ref, computed, onBeforeUnmount } from 'vue'
import { ElMessage, type UploadRequestOptions } from 'element-plus'
import { assetImageGenerationApi, type AssetImageGenerationTask } from '@/api/asset-image-generation'
import { aiProviderApi } from '@/api/ai-provider'
import { assetApi } from '@/api/res-asset'
import type { AiModel, AiProviderVO } from '@/types/ai-provider'
import { validateGenerationSize } from '@/utils/generation-size'

const props = defineProps<{
  targetType: 'CHARACTER' | 'CHARACTER_OUTFIT' | 'SCENE' | 'PROP'
  targetId?: string | number
  imageSlot: string
  prompt?: string
  negativePrompt?: string
  referenceImageUrl?: string // 槽位原有图
  avatarUrl?: string         // 角色封面定妆照
}>()

const emit = defineEmits<{ (e: 'applied', url: string): void }>()

const visible = ref(false)
const loading = ref(false)
const generating = ref(false)
const modelsLoading = ref(false)
const uploadingRef = ref(false)
const task = ref<AssetImageGenerationTask>()
const providers = ref<AiProviderVO[]>([])
const models = ref<AiModel[]>([])

// 参考图模式: AVATAR(定妆照), CURRENT(槽位原有), CUSTOM(自定义), NONE(不使用)
const refMode = ref<'AVATAR' | 'CURRENT' | 'CUSTOM' | 'NONE'>('NONE')
const customRefUrl = ref('')

const form = reactive({
  mode: 'API',
  providerId: undefined as string | undefined,
  modelCode: '',
  prompt: '',
  negativePrompt: '',
  size: '1024x1024'
})

const availableProviders = computed(() => providers.value)

const currentModel = computed(() => {
  return models.value.find((m) => m.modelCode === form.modelCode)
})

// 核心能力判定：只有 文生图/图生图 (TXT_IMG2IMG) 模型支持参考图功能；若选用 文生图 (TXT2IMG) 模型，则不可使用参考图功能
const isImg2imgSupported = computed(() => {
  if (!currentModel.value) {
    const code = (form.modelCode || '').toLowerCase()
    return code.includes('flux') || code.includes('img2img')
  }
  return currentModel.value.modelType === 'TXT_IMG2IMG' || currentModel.value.modelType === 'IMAGE'
})

// 生效的参考图 URL
const effectiveRefImage = computed(() => {
  if (!isImg2imgSupported.value) return undefined
  if (refMode.value === 'AVATAR' && props.avatarUrl) return props.avatarUrl
  if (refMode.value === 'CURRENT' && props.referenceImageUrl) return props.referenceImageUrl
  if (refMode.value === 'CUSTOM' && customRefUrl.value.trim()) return customRefUrl.value.trim()
  return undefined
})

const canSubmit = computed(() => !generating.value && !!form.providerId && !!form.prompt.trim() && !!form.modelCode)

let timer: ReturnType<typeof setTimeout> | undefined

function modelTypeLabel(type?: string) {
  if (type === 'TXT_IMG2IMG') return '文生图/图生图'
  if (type === 'TXT2IMG') return '文生图'
  if (type === 'IMAGE') return '图像'
  return type || ''
}

async function open() {
  form.prompt = props.prompt || ''
  form.negativePrompt = props.negativePrompt || ''
  task.value = undefined
  form.modelCode = ''
  customRefUrl.value = ''

  // 根据当前槽位与定妆照情况，初始化默认参考图策略
  if (props.imageSlot !== 'AVATAR' && props.avatarUrl) {
    refMode.value = 'AVATAR'
  } else if (props.referenceImageUrl) {
    refMode.value = 'CURRENT'
  } else {
    refMode.value = 'NONE'
  }

  visible.value = true
  await loadProviders()
}

async function loadProviders() {
  providers.value = await aiProviderApi.getListEnabled()
  selectDefaultProvider()
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
    // 优先推荐 TXT_IMG2IMG 模型
    const preferred = models.value.find((m) => m.modelType === 'TXT_IMG2IMG') || models.value[0]
    form.modelCode = preferred?.modelCode || ''
  } finally {
    modelsLoading.value = false
  }
}

async function handleCustomUpload(options: UploadRequestOptions) {
  try {
    uploadingRef.value = true
    const res = await assetApi.upload(options.file, props.targetType.toLowerCase())
    customRefUrl.value = res.url
    refMode.value = 'CUSTOM'
    ElMessage.success('自定义参考图上传成功')
  } catch (err: any) {
    ElMessage.error(err.message || '上传参考图失败')
  } finally {
    uploadingRef.value = false
  }
}

async function submit() {
  if (!props.targetId || !form.prompt.trim()) return ElMessage.warning('请先填写 Prompt，并确保资产已保存')
  if (!canSubmit.value) return ElMessage.warning('请选择供应商和生图模型')

  const sizeValidation = validateGenerationSize(form.size, 'image')
  if (!sizeValidation.valid) return ElMessage.warning(sizeValidation.message)

  loading.value = true
  generating.value = true
  const normalizedSize = sizeValidation.normalized!

  // 严格受控：只有当所选模型支持图生图时，才传递参考图；文生图模型则不使用参考图
  const refImages = (isImg2imgSupported.value && effectiveRefImage.value) ? [effectiveRefImage.value] : undefined

  try {
    task.value = await assetImageGenerationApi.submit({
      targetType: props.targetType,
      targetId: props.targetId,
      slot: props.imageSlot,
      mode: form.mode,
      providerId: form.providerId,
      modelCode: form.modelCode || undefined,
      prompt: form.prompt,
      negativePrompt: form.negativePrompt,
      size: normalizedSize,
      referenceImageUrls: refImages
    })
    await poll()
  } catch (error: any) {
    generating.value = false
    ElMessage.error(error.message || 'AI 生图提交失败')
  } finally {
    loading.value = false
  }
}

async function poll() {
  if (!task.value) return
  if (task.value.status === 'SUCCESS') {
    generating.value = false
    ElMessage.success('图片已生成并归档到 MinIO')
    return
  }
  if (task.value.status === 'FAILED') {
    generating.value = false
    ElMessage.error(task.value.errorMessage || 'AI 生图失败')
    return
  }
  await new Promise<void>((resolve) => {
    timer = setTimeout(() => resolve(), 1200)
  })
  task.value = await assetImageGenerationApi.getTask(task.value.taskId)
  await poll()
}

async function applyResult() {
  if (!props.targetId || !task.value?.outputUrl) return
  await assetImageGenerationApi.apply(props.targetType, props.targetId, props.imageSlot, task.value.outputUrl)
  emit('applied', task.value.outputUrl)
  ElMessage.success('已应用生成图片')
  visible.value = false
}

function statusText(status: string) {
  return ({ PENDING: '排队中', RUNNING: 'AI 生成中', SUCCESS: '生成完成', FAILED: '生成失败' } as Record<string, string>)[status] || status
}

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>
