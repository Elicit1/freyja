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
            <span class="text-xs text-slate-400">角色与场景资产 / 道具资产管理 /</span>
            <h2 class="text-base font-bold text-slate-800 tracking-tight">
              {{ isEdit ? `编辑道具资产 - ${formData.name || ''}` : '新建道具资产' }}
            </h2>
            <el-tag v-if="formData.propType" size="small" type="info" effect="plain">
              {{ formData.propType }}
            </el-tag>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <el-button @click="handleClose()">取消</el-button>
          <el-button type="primary" :loading="saving" @click="handleSubmit">
            保存道具
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
        <!-- 标签页 1: 道具基本信息 -->
        <el-tab-pane label="基本信息与特征" name="basic">
          <el-form-item label="道具名称" prop="name">
            <el-input
              v-model="formData.name"
              placeholder="如：繁复小座钟 / 幽冥玄铁剑 / 翡翠玉镯"
              maxlength="100"
              show-word-limit
            />
          </el-form-item>

          <div class="grid grid-cols-2 gap-2">
            <el-form-item label="道具类型" prop="propType">
              <el-select v-model="formData.propType" placeholder="选择道具类型">
                <el-option label="核心叙事道具 (Key Prop)" value="KEY_PROP" />
                <el-option label="武器装备 (Weapon)" value="WEAPON" />
                <el-option label="服饰配饰 (Accessory)" value="COSTUME_ACCESSORY" />
                <el-option label="日常杂物 (Daily)" value="DAILY" />
              </el-select>
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

          <el-form-item label="中文特征描述" prop="description">
            <el-input
              v-model="formData.description"
              type="textarea"
              :rows="3"
              placeholder="描述道具的外形特征、材质细节、叙事作用等，如：古典雕花黄铜怀表，表盖内侧刻有密文，表面有轻微岁月磨损痕迹"
            />
          </el-form-item>

          <!-- 更多道具扩展设置折叠面板 -->
          <el-collapse v-model="activeCollapseNames" class="more-collapse !border-none mt-2">
            <el-collapse-item name="more" class="!border-none">
              <template #title>
                <div class="flex items-center gap-2 text-xs font-semibold text-slate-700 w-full pr-2">
                  <el-icon class="text-indigo-500"><Operation /></el-icon>
                  <span>更多设置 (显示排序、状态与备注)</span>
                  <el-tag v-if="formData.sortOrder" size="small" type="info" class="!text-[10px] !h-4 !px-1.5">
                    排序: {{ formData.sortOrder }}
                  </el-tag>
                </div>
              </template>

              <div class="pt-3">
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
                  <el-input
                    v-model="formData.remark"
                    type="textarea"
                    :rows="2"
                    placeholder="内部备忘或道具参考备注"
                  />
                </el-form-item>
              </div>
            </el-collapse-item>
          </el-collapse>
        </el-tab-pane>

        <!-- 标签页 2: Prompt 提示词设定 -->
        <el-tab-pane label="道具 Prompt 设定" name="prompt">
          <div class="flex items-center justify-between bg-slate-50 p-3 rounded-lg border border-slate-200 mb-4">
            <div class="text-xs text-slate-600">
              💡 道具 Prompt 专供单物微观特写与首帧特写镜头，强调微观质感并杜绝背景杂质。
            </div>
            <el-button
              size="small"
              type="primary"
              plain
              class="font-medium hover:scale-[1.02] transition-transform"
              @click="handleDerivePrompts"
            >
              ⚡ 依据特征一键智能生成道具 Prompt
            </el-button>
          </div>

          <el-form-item label="核心道具 Prompt" prop="propPrompt">
            <el-input
              v-model="formData.propPrompt"
              type="textarea"
              :rows="4"
              placeholder="道具主体英文描述，如: ornate antique brass pocket watch, intricate floral engravings on casing, worn metallic luster, cracked glass surface, macro detail"
            />
            <div class="text-xs text-gray-400 mt-1">
              分镜工作台中关联此道具时，将自动提取此 Prompt 参与首帧画面组装
            </div>
          </el-form-item>

          <el-form-item label="专属负向词" prop="negativePrompt">
            <el-input
              v-model="formData.negativePrompt"
              type="textarea"
              :rows="2"
              placeholder="如: human hands, fingers, holding, person, modern plastic, blurry background"
            />
            <div class="text-xs text-gray-400 mt-1">
              严格排除人手持物、多余肢体与冲突材质
            </div>
          </el-form-item>

          <!-- 常用修饰词快捷预设 -->
          <el-form-item label="快捷修饰词">
            <div class="flex flex-wrap gap-1.5">
              <el-tag
                v-for="keyword in quickKeywords"
                :key="keyword"
                size="small"
                class="cursor-pointer hover:opacity-80"
                type="info"
                effect="plain"
                @click="appendKeyword(keyword)"
              >
                + {{ keyword }}
              </el-tag>
            </div>
          </el-form-item>
        </el-tab-pane>

        <!-- 标签页 3: 参考图与封面 -->
        <el-tab-pane label="道具参考图与封面" name="cover">
          <div class="text-xs text-slate-500 mb-3 bg-slate-50 p-2.5 rounded-lg border border-slate-200">
            💡 建议生成或上传高清白底图、微距设计图或特写图，用于在生成分镜或微观特写时提供视觉参考。
          </div>

          <el-form-item label="道具封面/设计图">
            <div class="flex items-center gap-3">
              <div v-if="formData.coverUrl" class="relative group cursor-pointer">
                <el-image
                  :src="formData.coverUrl"
                  :preview-src-list="[formData.coverUrl]"
                  preview-teleported
                  fit="cover"
                  class="w-24 h-24 rounded border border-gray-200 block"
                />
                <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded flex items-center justify-center text-white text-xs pointer-events-none">
                  🔍 预览
                </div>
              </div>
              <div
                v-else
                class="w-24 h-24 rounded border border-dashed border-gray-300 flex items-center justify-center text-gray-400 text-xs"
              >
                未上传封面
              </div>
              <div class="space-y-2">
                <div class="flex items-center gap-2">
                  <el-upload
                    action="#"
                    :show-file-list="false"
                    :http-request="handleCoverUpload"
                    accept="image/*"
                  >
                    <el-button size="small" type="primary" :loading="uploadingCover">
                      {{ formData.coverUrl ? '重新上传封面' : '上传封面图' }}
                    </el-button>
                  </el-upload>
                  <AssetImageGenerator
                    target-type="PROP"
                    :target-id="formData.id"
                    image-slot="PROP_COVER"
                    :prompt="formData.propPrompt"
                    :negative-prompt="formData.negativePrompt"
                    :reference-image-url="formData.coverUrl"
                    @applied="formData.coverUrl = $event"
                  />
                  <el-button v-if="formData.coverUrl" size="small" type="danger" text @click="formData.coverUrl = ''">
                    移除
                  </el-button>
                </div>
                <div class="text-xs text-gray-400">支持 jpg, png, webp 等格式，或使用 AI 直接生成</div>
                <div class="text-[11px] text-slate-500 line-clamp-2 bg-slate-50 px-2 py-1 rounded border border-slate-100">
                  <span class="font-medium text-slate-600">已绑定 Prompt:</span>
                  {{ formData.propPrompt || '（未配置核心道具 Prompt）' }}
                </div>
              </div>
            </div>
          </el-form-item>
        </el-tab-pane>
      </el-tabs>
    </el-form>
    </div>
  </div>

  <!-- 依据道具设定生成提示词的专属流式与预览确认弹窗 -->
  <PropPromptDeriveModal
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
      {{ panel.running ? '生成中' : '待采纳' }} · 道具提示词 · {{ panel.targetName || '未命名道具' }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, nextTick } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'
import { ArrowLeft, Operation } from '@element-plus/icons-vue'
import { propApi } from '@/api/res-prop'
import { dramaApi } from '@/api/drama'
import { assetApi } from '@/api/res-asset'
import type { ResProp, PropPromptDeriveVO } from '@/types/resource'
import type { DramaOption } from '@/types/drama'
import AssetImageGenerator from './AssetImageGenerator.vue'
import PropPromptDeriveModal from './PropPromptDeriveModal.vue'

const emit = defineEmits<{
  (e: 'success', propId?: string | number): void
  (e: 'cancel'): void
  (e: 'visible-change', val: boolean): void
}>()

type PromptPanelSession = { key: string; targetId: string; targetName: string; formVersion: number; running: boolean }
const promptPanels = ref<PromptPanelSession[]>([])
const promptPanelRefs = ref<Record<string, InstanceType<typeof PropPromptDeriveModal>>>({})
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
const uploadingCover = ref(false)
const activeTab = ref('basic')
const activeCollapseNames = ref<string[]>([])
const formRef = ref<FormInstance>()
const dramaOptions = ref<DramaOption[]>([])

const quickKeywords = [
  'antique brass',
  'metallic luster',
  'intricate engravings',
  'worn leather',
  'glowing gemstone',
  'cyberpunk neon',
  'scratched metal',
  'polished silver',
  'photorealistic',
  'macro lens 8k'
]

const formData = reactive<Partial<ResProp>>({
  id: undefined,
  dramaId: '0',
  name: '',
  propType: 'KEY_PROP',
  description: '',
  propPrompt: '',
  negativePrompt: '',
  coverUrl: '',
  sortOrder: 0,
  status: 1,
  remark: ''
})

const isEdit = computed(() => !!formData.id)

const formRules: FormRules = {
  name: [{ required: true, message: '请输入道具名称', trigger: 'blur' }]
}

function handleDerivePrompts() {
  if (!formData.name && !formData.description) {
    return ElMessage.warning('请先填写道具名称或中文特征描述')
  }
  const panel: PromptPanelSession = {
    key: `prop-prompt-${Date.now()}-${++promptPanelSequence}`,
    targetId: String(formData.id ?? ''),
    targetName: String(formData.name || ''),
    formVersion,
    running: false
  }
  promptPanels.value.push(panel)
  void nextTick(() => promptPanelRefs.value[panel.key]?.open({ ...formData }))
}

function applyPropPrompts(res: PropPromptDeriveVO) {
  if (res.propPrompt) formData.propPrompt = res.propPrompt
  if (res.negativePrompt) formData.negativePrompt = res.negativePrompt
}

async function handleApplyDerivedPromptsForPanel(panel: PromptPanelSession, res: PropPromptDeriveVO) {
  if (panel.targetId && String(formData.id ?? '') === panel.targetId) {
    applyPropPrompts(res)
    return
  }
  if (!panel.targetId && panel.formVersion === formVersion) {
    applyPropPrompts(res)
    return
  }
  if (!panel.targetId) {
    ElMessage.warning('该道具尚未保存，提示词结果保留在任务面板中；请重新打开原道具后采纳')
    return
  }
  try {
    await propApi.update({ id: panel.targetId, propPrompt: res.propPrompt, negativePrompt: res.negativePrompt })
    emit('success', panel.targetId)
    ElMessage.success('道具提示词已保存到对应道具')
  } catch (error: any) {
    ElMessage.error(error?.message || '保存对应道具提示词失败')
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

async function loadDramaOptions() {
  try {
    const res = await dramaApi.getOptions()
    dramaOptions.value = res || []
  } catch (err) {
    console.error('加载短剧列表失败', err)
  }
}

function open(prop?: ResProp, presetDramaId?: string | number) {
  formVersion++
  activeTab.value = 'basic'
  activeCollapseNames.value = []
  loadDramaOptions()
  if (prop) {
    const rawDramaId = prop.dramaId
    const normalizedDramaId = (rawDramaId === undefined || rawDramaId === null || rawDramaId === 0 || rawDramaId === '0' || String(rawDramaId) === '0')
      ? '0'
      : String(rawDramaId)
    Object.assign(formData, {
      ...prop,
      dramaId: normalizedDramaId
    })
  } else {
    Object.assign(formData, {
      id: undefined,
      dramaId: presetDramaId !== undefined && presetDramaId !== null && presetDramaId !== ''
        ? String(presetDramaId)
        : '0',
      name: '',
      propType: 'KEY_PROP',
      description: '',
      propPrompt: '',
      negativePrompt: '',
      coverUrl: '',
      sortOrder: 0,
      status: 1,
      remark: ''
    })
  }
  visible.value = true
}

function appendKeyword(keyword: string) {
  if (!formData.propPrompt) {
    formData.propPrompt = keyword
  } else {
    formData.propPrompt = `${formData.propPrompt.trim().replace(/,+$/, '')}, ${keyword}`
  }
}

async function handleCoverUpload(options: UploadRequestOptions) {
  try {
    uploadingCover.value = true
    const res = await assetApi.upload(options.file, 'prop')
    formData.coverUrl = res.url
    ElMessage.success('道具图片上传成功')
  } catch (error) {
    ElMessage.error('上传道具图片失败')
  } finally {
    uploadingCover.value = false
  }
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      saving.value = true
      if (isEdit.value) {
        await propApi.update(formData)
        ElMessage.success('道具资产更新成功')
      } else {
        const newId = await propApi.create(formData)
        ElMessage.success('道具资产创建成功')
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

