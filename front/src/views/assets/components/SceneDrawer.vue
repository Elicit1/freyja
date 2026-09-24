<template>
  <div v-if="visible" class="space-y-4">
    <!-- 主内容区顶部导航与操作栏 -->
    <el-card shadow="never" class="!border-gray-200">
      <div class="flex items-center justify-between flex-wrap gap-3">
        <div class="flex items-center gap-3">
          <el-button @click="handleClose()">
            <el-icon><ArrowLeft /></el-icon>
            返回列表
          </el-button>
          <div class="h-4 w-px bg-slate-200"></div>
          <div class="flex items-center gap-2">
            <span class="text-xs text-slate-400">角色与场景资产 / 场景资产管理 /</span>
            <h2 class="text-base font-bold text-slate-800 tracking-tight">
              {{ isEdit ? `编辑场景环境 - ${formData.name || ''}` : '新建场景环境' }}
            </h2>
            <el-tag v-if="formData.sceneType" size="small" type="info" effect="plain">
              {{ formData.sceneType }}
            </el-tag>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <el-button @click="handleClose()">取消</el-button>
          <el-button type="primary" :loading="saving" @click="handleSubmit">
            保存场景
          </el-button>
        </div>
      </div>
    </el-card>

    <div class="bg-white p-6 rounded-xl border border-slate-200 shadow-2xs">
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="120px"
        label-position="right"
        class="pr-2"
      >
      <el-tabs v-model="activeTab" class="mb-4">
        <!-- 标签页 1: 空间与基本属性 -->
        <el-tab-pane label="空间与基本信息" name="basic">
          <div class="grid grid-cols-3 gap-2">
            <el-form-item label="场景名称" prop="name" class="col-span-2">
              <el-input v-model="formData.name" placeholder="如：顶层总裁办公室 / 暴雨赛博夜市" maxlength="100" show-word-limit />
            </el-form-item>

            <el-form-item label="所属库" prop="dramaId">
              <el-select v-model="formData.dramaId" placeholder="所属资源库" filterable class="w-full">
                <el-option label="🌐 全局公共资源库" value="0" />
                <el-option
                  v-for="d in dramaOptions"
                  :key="String(d.id)"
                  :label="`🎬 ${d.title}`"
                  :value="String(d.id)"
                />
              </el-select>
            </el-form-item>
          </div>

          <el-form-item label="场景描述" prop="description">
            <el-input
              v-model="formData.description"
              type="textarea"
              :rows="3"
              placeholder="原著小说或大纲中的场景视觉细节、地理环境、建筑陈设等中文描述，用于 AI 智能衍生提示词"
            />
          </el-form-item>

          <!-- 更多场景扩展设置折叠面板 -->
          <el-collapse v-model="activeCollapseNames" class="more-collapse !border-none mt-2">
            <el-collapse-item name="more" class="!border-none">
              <template #title>
                <div class="flex items-center gap-2 text-xs font-semibold text-slate-700 w-full pr-2">
                  <el-icon class="text-indigo-500"><Operation /></el-icon>
                  <span>更多设置 (空间类型、时段天气、显示排序与备注)</span>
                  <el-tag v-if="formData.sceneType" size="small" type="info" class="!text-[10px] !h-4 !px-1.5">
                    {{ formData.sceneType }}
                  </el-tag>
                  <el-tag v-if="formData.timeOfDay" size="small" type="info" effect="plain" class="!text-[10px] !h-4 !px-1.5">
                    {{ formData.timeOfDay }}
                  </el-tag>
                </div>
              </template>

              <div class="pt-3">
                <div class="grid grid-cols-3 gap-2">
                  <el-form-item label="空间类型" prop="sceneType">
                    <el-select v-model="formData.sceneType" placeholder="选择空间类型 (选填)" clearable class="w-full">
                      <el-option label="室内 (Indoor)" value="INDOOR" />
                      <el-option label="室外 (Outdoor)" value="OUTDOOR" />
                      <el-option label="摄影棚 (Studio)" value="STUDIO" />
                      <el-option label="虚构/奇幻 (Virtual)" value="VIRTUAL" />
                    </el-select>
                  </el-form-item>

                  <el-form-item label="时段" prop="timeOfDay">
                    <el-select v-model="formData.timeOfDay" placeholder="选择时段 (选填)" clearable class="w-full">
                      <el-option label="日间 (Day)" value="DAY" />
                      <el-option label="夜间 (Night)" value="NIGHT" />
                      <el-option label="黄昏 (Sunset)" value="SUNSET" />
                      <el-option label="拂晓 (Dawn)" value="DAWN" />
                    </el-select>
                  </el-form-item>

                  <el-form-item label="天气与氛围" prop="weatherAtmosphere">
                    <el-select v-model="formData.weatherAtmosphere" placeholder="天气氛围 (选填)" filterable allow-create clearable class="w-full">
                      <el-option label="晴朗明媚 (Sunny)" value="SUNNY" />
                      <el-option label="暴雨倾盆 (Rainy)" value="RAINY" />
                      <el-option label="赛博霓虹 (Cyberpunk/Neon)" value="CYBERPUNK" />
                      <el-option label="薄雾缭绕 (Foggy)" value="FOGGY" />
                      <el-option label="阴暗压抑 (Moody/Dark)" value="MOODY" />
                      <el-option label="飘雪冷冽 (Snowy)" value="SNOWY" />
                    </el-select>
                  </el-form-item>
                </div>

                <div class="grid grid-cols-2 gap-2">
                  <el-form-item label="显示排序" prop="sortOrder">
                    <el-input-number v-model="formData.sortOrder" :min="0" :max="999" class="!w-full" />
                  </el-form-item>
                  <el-form-item label="状态" prop="status">
                    <el-radio-group v-model="formData.status">
                      <el-radio :value="1">启用</el-radio>
                      <el-radio :value="0">停用</el-radio>
                    </el-radio-group>
                  </el-form-item>
                </div>

                <el-form-item label="备注说明" prop="remark">
                  <el-input v-model="formData.remark" type="textarea" :rows="2" placeholder="内部备忘或场景参考备注" />
                </el-form-item>
              </div>
            </el-collapse-item>
          </el-collapse>
        </el-tab-pane>

        <!-- 标签页 2: Prompt 提示词设定 -->
        <el-tab-pane label="环境 Prompt 设定" name="prompt">
          <div class="flex items-center justify-between bg-slate-50 p-3 rounded-lg border border-slate-200 mb-4">
            <div class="text-xs text-slate-600">
              💡 本系统场景 Prompt 聚焦物理空间与固定陈设，与动态角色和道具严格解耦。
            </div>
            <el-button
              size="small"
              type="primary"
              plain
              class="font-medium hover:scale-[1.02] transition-transform"
              @click="handleDerivePrompts"
            >
              ⚡ AI一键生成场景提示词
            </el-button>
          </div>

          <el-form-item label="场景生图 Prompt" prop="scenePrompt">
            <el-input
              v-model="formData.scenePrompt"
              type="textarea"
              :rows="4"
              placeholder="场景环境与光影氛围主干英文描述 (选填)，如: modern luxury office, large floor-to-ceiling windows, rainy city skyline at night, cinematic lighting"
            />
            <div class="text-xs text-gray-400 mt-1">选填。分镜组装时若为空，系统将自动回退采用场景中文描述或场景名称，不影响组装</div>
          </el-form-item>

          <el-form-item label="场景专属负向词" prop="negativePrompt">
            <el-input
              v-model="formData.negativePrompt"
              type="textarea"
              :rows="2"
              placeholder="场景特有规避特征，如: daylight, sunny, outdoor, crowded"
            />
          </el-form-item>
        </el-tab-pane>

        <!-- 标签页 3: 模型与参考图 -->
        <el-tab-pane label="场景参考图与封面" name="model">
          <div class="text-xs text-slate-500 mb-3 bg-slate-50 p-2.5 rounded-lg border border-slate-200">
            💡 本系统全面采用纯净自然语言与多模态场景参考图垫图，无需额外训练或维护 LoRA 模型。
          </div>

          <el-form-item label="场景封面/预览图">
            <div class="flex items-center gap-3">
              <div v-if="formData.coverUrl" class="relative group cursor-pointer">
                <el-image
                  :src="formData.coverUrl"
                  :preview-src-list="[formData.coverUrl]"
                  preview-teleported
                  fit="cover"
                  class="w-24 h-16 rounded border border-gray-200 block"
                />
                <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded flex items-center justify-center text-white text-xs pointer-events-none">
                  🔍 预览
                </div>
              </div>
              <div v-else class="w-24 h-16 rounded border border-dashed border-gray-300 flex items-center justify-center text-gray-400 text-xs">
                未上传封面
              </div>
              <el-upload
                action="#"
                :show-file-list="false"
                :http-request="handleCoverUpload"
                accept="image/*"
              >
                <el-button size="small" type="primary" :loading="uploadingKey === 'coverUrl'">上传封面</el-button>
              </el-upload>
              <AssetImageGenerator target-type="SCENE" :target-id="formData.id" image-slot="SCENE_COVER" :prompt="formData.scenePrompt" :negative-prompt="formData.negativePrompt" :reference-image-url="formData.coverUrl" @applied="formData.coverUrl = $event" />
            </div>
          </el-form-item>

          <el-form-item label="空间参考/ControlNet">
            <div class="flex items-center gap-3">
              <div v-if="formData.referenceImageUrl" class="relative group cursor-pointer">
                <el-image
                  :src="formData.referenceImageUrl"
                  :preview-src-list="[formData.referenceImageUrl]"
                  preview-teleported
                  fit="cover"
                  class="w-24 h-16 rounded border border-gray-200 block"
                />
                <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded flex items-center justify-center text-white text-xs pointer-events-none">
                  🔍 预览
                </div>
              </div>
              <div v-else class="w-24 h-16 rounded border border-dashed border-gray-300 flex items-center justify-center text-gray-400 text-xs">
                未上传参考图
              </div>
              <div>
                <el-upload
                  action="#"
                  :show-file-list="false"
                  :http-request="handleReferenceImageUpload"
                  accept="image/*"
                >
                  <el-button size="small" :loading="uploadingKey === 'referenceImageUrl'">上传空间参考图</el-button>
                </el-upload>
                <AssetImageGenerator target-type="SCENE" :target-id="formData.id" image-slot="SCENE_REFERENCE" :prompt="formData.scenePrompt" :negative-prompt="formData.negativePrompt" :reference-image-url="formData.referenceImageUrl" @applied="formData.referenceImageUrl = $event" />
                <div class="text-xs text-gray-400 mt-1">用于 IP-Adapter 风格注入或 ControlNet 构图约束</div>
              </div>
            </div>
          </el-form-item>
        </el-tab-pane>
      </el-tabs>
    </el-form>
    </div>
  </div>

  <!-- 依据场景设定生成提示词的专属流式与预览确认弹窗 -->
  <ScenePromptDeriveModal
    v-for="panel in promptPanels"
    :key="panel.key"
    :ref="instance => setPromptPanelRef(panel.key, instance)"
    @apply="result => handleApplyDerivedPromptsForPanel(panel, result)"
    @task-starting="setPromptPanelRunning(panel.key, true)"
    @task-finished="setPromptPanelRunning(panel.key, false)"
    @panel-close="closePromptPanel(panel.key)"
  />
  <div v-if="promptPanels.length" class="fixed right-5 bottom-5 z-[2050] flex max-w-[calc(100vw-2.5rem)] flex-col items-end gap-2">
    <el-button
      v-for="panel in promptPanels"
      :key="panel.key"
      size="small"
      :type="panel.running ? 'warning' : 'primary'"
      plain
      @click="reopenPromptPanel(panel.key)"
    >
      {{ panel.running ? '生成中' : '待采纳' }} · 场景提示词 · {{ panel.targetName || '未命名场景' }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, nextTick } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'
import { ArrowLeft, Operation } from '@element-plus/icons-vue'
import { sceneApi } from '@/api/res-scene'
import { dramaApi } from '@/api/drama'
import { assetApi } from '@/api/res-asset'
import type { ResScene, ScenePromptDeriveVO } from '@/types/resource'
import type { DramaOption } from '@/types/drama'
import AssetImageGenerator from './AssetImageGenerator.vue'
import ScenePromptDeriveModal from './ScenePromptDeriveModal.vue'

const emit = defineEmits<{
  (e: 'success', sceneId?: string | number): void
  (e: 'cancel'): void
  (e: 'visible-change', val: boolean): void
}>()

type PromptPanelSession = { key: string; targetId: string; targetName: string; formVersion: number; running: boolean }
const promptPanels = ref<PromptPanelSession[]>([])
const promptPanelRefs = ref<Record<string, InstanceType<typeof ScenePromptDeriveModal>>>({})
let promptPanelSequence = 0
let formVersion = 0

const visible = ref(false)
watch(visible, (val) => {
  emit('visible-change', val)
})

function handleClose() {
  emit('cancel')
  visible.value = false
}
const saving = ref(false)
const activeTab = ref('basic')
const activeCollapseNames = ref<string[]>([])
const uploadingKey = ref<string | null>(null)
const formRef = ref<FormInstance>()
const dramaOptions = ref<DramaOption[]>([])

async function loadDramaOptions() {
  try {
    const res = await dramaApi.getOptions()
    dramaOptions.value = res || []
  } catch (err) {
    console.error('加载短剧列表失败', err)
  }
}

const formData = reactive<Partial<ResScene>>({
  id: undefined,
  dramaId: '0',
  name: '',
  coverUrl: '',
  sceneType: '',
  timeOfDay: '',
  weatherAtmosphere: '',
  description: '',
  scenePrompt: '',
  negativePrompt: '',
  loraName: '',
  loraWeight: 1.0,
  referenceImageUrl: '',
  sortOrder: 0,
  status: 1,
  remark: ''
})

const isEdit = computed(() => !!formData.id)

const formRules: FormRules = {
  name: [{ required: true, message: '请输入场景名称', trigger: 'blur' }]
}

function handleDerivePrompts() {
  if (!formData.name && !formData.description) {
    return ElMessage.warning('请先填写场景名称或中文场景描述')
  }
  const panel: PromptPanelSession = {
    key: `scene-prompt-${Date.now()}-${++promptPanelSequence}`,
    targetId: String(formData.id ?? ''),
    targetName: String(formData.name || ''),
    formVersion,
    running: false
  }
  promptPanels.value.push(panel)
  void nextTick(() => promptPanelRefs.value[panel.key]?.open({ ...formData }))
}

function applyScenePrompts(res: ScenePromptDeriveVO) {
  if (res.scenePrompt) formData.scenePrompt = res.scenePrompt
  if (res.negativePrompt) formData.negativePrompt = res.negativePrompt
}

async function handleApplyDerivedPromptsForPanel(panel: PromptPanelSession, res: ScenePromptDeriveVO) {
  if (panel.targetId && String(formData.id ?? '') === panel.targetId) {
    applyScenePrompts(res)
    return
  }
  if (!panel.targetId && panel.formVersion === formVersion) {
    applyScenePrompts(res)
    return
  }
  if (!panel.targetId) {
    ElMessage.warning('该场景尚未保存，提示词结果保留在任务面板中；请重新打开原场景后采纳')
    return
  }
  try {
    await sceneApi.update({ id: panel.targetId, scenePrompt: res.scenePrompt, negativePrompt: res.negativePrompt })
    emit('success', panel.targetId)
    ElMessage.success('场景提示词已保存到对应场景')
  } catch (error: any) {
    ElMessage.error(error?.message || '保存对应场景提示词失败')
  }
}

function setPromptPanelRef(key: string, instance: any) {
  if (instance) promptPanelRefs.value[key] = instance
  else delete promptPanelRefs.value[key]
}

function setPromptPanelRunning(key: string, running: boolean) {
  const panel = promptPanels.value.find(item => item.key === key)
  if (panel) panel.running = running
}

function reopenPromptPanel(key: string) {
  promptPanelRefs.value[key]?.reopen()
}

function closePromptPanel(key: string) {
  delete promptPanelRefs.value[key]
  promptPanels.value = promptPanels.value.filter(item => item.key !== key)
}

function open(scene?: ResScene, presetDramaId?: string | number) {
  formVersion++
  activeTab.value = 'basic'
  activeCollapseNames.value = []
  loadDramaOptions()
  if (scene) {
    const rawDramaId = scene.dramaId
    const normalizedDramaId = (rawDramaId === undefined || rawDramaId === null || rawDramaId === 0 || rawDramaId === '0' || String(rawDramaId) === '0')
      ? '0'
      : String(rawDramaId)
    Object.assign(formData, {
      ...scene,
      dramaId: normalizedDramaId,
      loraWeight: scene.loraWeight ?? 1.0
    })
  } else {
    Object.assign(formData, {
      id: undefined,
      dramaId: presetDramaId !== undefined && presetDramaId !== null && presetDramaId !== ''
        ? String(presetDramaId)
        : '0',
      name: '',
      coverUrl: '',
      sceneType: '',
      timeOfDay: '',
      weatherAtmosphere: '',
      description: '',
      scenePrompt: '',
      negativePrompt: '',
      loraName: '',
      loraWeight: 1.0,
      referenceImageUrl: '',
      sortOrder: 0,
      status: 1,
      remark: ''
    })
  }
  visible.value = true
}

function handleCoverUpload(options: UploadRequestOptions) {
  return handleUpload(options, 'coverUrl')
}

function handleReferenceImageUpload(options: UploadRequestOptions) {
  return handleUpload(options, 'referenceImageUrl')
}

async function handleUpload(options: UploadRequestOptions, field: 'coverUrl' | 'referenceImageUrl') {
  try {
    uploadingKey.value = field
    const res = await assetApi.upload(options.file, 'scene')
    formData[field] = res.url
    ElMessage.success('图片上传成功')
  } catch (error) {
    ElMessage.error('上传图片失败')
  } finally {
    uploadingKey.value = null
  }
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      saving.value = true
      if (isEdit.value) {
        await sceneApi.update(formData)
        ElMessage.success('场景环境更新成功')
      } else {
        const newId = await sceneApi.create(formData)
        ElMessage.success('场景环境创建成功')
        emit('success', newId)
        visible.value = false
        return
      }
      visible.value = false
      emit('success')
    } catch (error) {
      console.error(error)
    } finally {
      saving.value = false
    }
  })
}

defineExpose({ open })
</script>

<style scoped>
:deep(.more-collapse .el-collapse-item__header) {
  background-color: #f8fafc;
  border-radius: 8px;
  padding: 0 12px;
  border: 1px solid #e2e8f0;
  height: 40px;
  line-height: 40px;
}
:deep(.more-collapse .el-collapse-item__wrap) {
  border-bottom: none;
}
:deep(.more-collapse .el-collapse-item__content) {
  padding-top: 14px;
  padding-bottom: 0;
}
</style>
