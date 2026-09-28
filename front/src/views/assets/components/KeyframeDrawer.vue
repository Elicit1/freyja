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
            <span class="text-xs text-slate-400">数字资产中心 / 关键帧资产管理 /</span>
            <h2 class="text-base font-bold text-slate-800 tracking-tight">
              {{ isEdit ? `编辑关键帧 - ${formData.name || ''}` : '新建关键帧资产' }}
            </h2>
            <el-tag v-if="formData.frameType" size="small" type="primary" effect="plain">
              {{ getFrameTypeLabel(formData.frameType) }}
            </el-tag>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <el-button @click="handleClose()">取消</el-button>
          <el-button type="primary" :loading="saving" @click="handleSubmit">
            保存关键帧
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
          <!-- 标签页 1: 关键帧基本信息与关联 -->
          <el-tab-pane label="基本信息与绑定" name="basic">
            <el-form-item label="关键帧名称" prop="name">
              <el-input
                v-model="formData.name"
                placeholder="如：雨夜决战首帧 / 回眸特写关键帧"
                maxlength="100"
                show-word-limit
              />
            </el-form-item>

            <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
              <el-form-item label="关键帧类型" prop="frameType">
                <el-select v-model="formData.frameType" placeholder="选择关键帧类型" class="w-full">
                  <el-option label="🎬 普通关键帧 (KEYFRAME)" value="KEYFRAME" />
                  <el-option label="🏁 首帧 (FIRST_FRAME)" value="FIRST_FRAME" />
                  <el-option label="🛑 尾帧 (END_FRAME)" value="END_FRAME" />
                  <el-option label="⚡ 动作节奏帧 (ACTION_BEAT)" value="ACTION_BEAT" />
                </el-select>
              </el-form-item>

              <el-form-item label="画幅比例" prop="aspectRatio">
                <el-select v-model="formData.aspectRatio" placeholder="默认 16:9" class="w-full" clearable>
                  <el-option label="16:9 (横屏微电影/宽幅)" value="16:9" />
                  <el-option label="9:16 (竖屏短剧主流)" value="9:16" />
                  <el-option label="1:1 (正方形)" value="1:1" />
                  <el-option label="4:3 (复古画幅)" value="4:3" />
                  <el-option label="2.35:1 (宽银幕电影)" value="2.35:1" />
                </el-select>
              </el-form-item>
            </div>

            <!-- 绑定短剧与分镜级联 -->
            <div class="grid grid-cols-1 md:grid-cols-2 gap-4 bg-slate-50/80 p-4 rounded-lg border border-slate-200 mb-4">
              <el-form-item label="归属短剧" prop="dramaId" class="!mb-0">
                <el-select
                  v-model="formData.dramaId"
                  placeholder="选择所属短剧"
                  filterable
                  class="w-full"
                  @change="handleDramaChange"
                >
                  <el-option label="🌐 全局公共资源库 (通用关键帧)" value="0" />
                  <el-option
                    v-for="d in dramaOptions"
                    :key="String(d.id)"
                    :label="`🎬 ${d.title}`"
                    :value="String(d.id)"
                  />
                </el-select>
              </el-form-item>

              <el-form-item label="关联分镜镜头" prop="shotId" class="!mb-0">
                <el-select
                  v-model="formData.shotId"
                  placeholder="未指定分镜 (通用帧)"
                  filterable
                  clearable
                  class="w-full"
                  :disabled="formData.dramaId === '0' || !formData.dramaId"
                  :loading="loadingShots"
                >
                  <el-option
                    v-for="s in shotOptions"
                    :key="String(s.id)"
                    :label="formatShotLabel(s)"
                    :value="String(s.id)"
                  />
                </el-select>
              </el-form-item>
            </div>

            <el-form-item label="画面动作描述" prop="description">
              <el-input
                v-model="formData.description"
                type="textarea"
                :rows="3"
                placeholder="描述关键帧的画面构图、人物动作姿态、视听焦点与情绪氛围..."
              />
            </el-form-item>

            <!-- 更多设置折叠面板 -->
            <el-collapse v-model="activeCollapseNames" class="more-collapse !border-none mt-2">
              <el-collapse-item name="more" class="!border-none">
                <template #title>
                  <div class="flex items-center gap-2 text-xs font-semibold text-slate-700 w-full pr-2">
                    <el-icon class="text-indigo-500"><Operation /></el-icon>
                    <span>更多设置 (显示排序、启用状态与备注)</span>
                  </div>
                </template>
                <div class="pt-3">
                  <div class="grid grid-cols-2 gap-4">
                    <el-form-item label="显示排序" prop="sortOrder">
                      <el-input-number v-model="formData.sortOrder" :min="0" :max="9999" class="!w-full" />
                    </el-form-item>
                    <el-form-item label="启用状态" prop="status">
                      <el-radio-group v-model="formData.status">
                        <el-radio :value="1">正常启用</el-radio>
                        <el-radio :value="0">停用归档</el-radio>
                      </el-radio-group>
                    </el-form-item>
                  </div>
                  <el-form-item label="资产备注" prop="remark">
                    <el-input
                      v-model="formData.remark"
                      type="textarea"
                      :rows="2"
                      placeholder="内部创作备注说明..."
                    />
                  </el-form-item>
                </div>
              </el-collapse-item>
            </el-collapse>
          </el-tab-pane>

          <!-- 标签页 2: 关键帧图片与上传 -->
          <el-tab-pane label="关键帧图像" name="image">
            <div class="text-xs text-slate-500 mb-4 bg-slate-50 p-3 rounded-lg border border-slate-200">
              💡 关键帧图像可作为镜头渲染的首帧、尾帧或 ControlNet / IP-Adapter 的动态演进参考（MOTION_KEYFRAME）。
            </div>

            <el-form-item label="关键帧图像" prop="frameUrl">
              <div class="flex flex-col gap-3">
                <div class="flex items-center gap-4">
                  <div v-if="formData.frameUrl" class="relative group cursor-pointer">
                    <el-image
                      :src="formData.frameUrl"
                      :preview-src-list="[formData.frameUrl]"
                      preview-teleported
                      fit="cover"
                      class="w-36 h-24 rounded-lg border border-gray-200 block shadow-2xs"
                    />
                    <div class="absolute inset-0 bg-black/40 opacity-0 group-hover:opacity-100 transition-opacity rounded-lg flex items-center justify-center text-white text-xs pointer-events-none">
                      🔍 预览大图
                    </div>
                  </div>
                  <div
                    v-else
                    class="w-36 h-24 rounded-lg border border-dashed border-gray-300 flex items-center justify-center text-gray-400 text-xs"
                  >
                    暂无图片
                  </div>

                  <div class="space-y-2">
                    <el-upload
                      action="#"
                      :show-file-list="false"
                      :http-request="handleImageUpload"
                      accept="image/*"
                    >
                      <el-button type="primary" :loading="uploadingImage">
                        <el-icon class="mr-1"><Upload /></el-icon>
                        {{ formData.frameUrl ? '重新上传关键帧' : '上传关键帧图像' }}
                      </el-button>
                    </el-upload>
                    <div class="text-xs text-slate-400">支持 JPG/PNG/WebP 格式</div>
                  </div>
                </div>

                <div v-if="formData.frameUrl" class="text-xs text-slate-500 break-all font-mono bg-slate-50 p-2 rounded">
                  URL: {{ formData.frameUrl }}
                </div>
              </div>
            </el-form-item>
          </el-tab-pane>

          <!-- 标签页 3: 提示词设定 -->
          <el-tab-pane label="视觉生图提示词" name="prompt">
            <el-form-item label="正向视觉提示词" prop="prompt">
              <el-input
                v-model="formData.prompt"
                type="textarea"
                :rows="4"
                placeholder="英文生图或视觉渲染提示词，例如：cinematic frame, sharp focus, 8k resolution, dramatic rim lighting, shallow depth of field..."
              />
            </el-form-item>

            <el-form-item label="负向提示词" prop="negativePrompt">
              <el-input
                v-model="formData.negativePrompt"
                type="textarea"
                :rows="3"
                placeholder="排除元素，例如：blurry, low quality, distorted, extra limbs, watermark..."
              />
            </el-form-item>
          </el-tab-pane>
        </el-tabs>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ArrowLeft, Operation, Upload } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'
import { keyframeApi } from '@/api/res-keyframe'
import { dramaApi, shotApi } from '@/api/drama'
import { assetApi } from '@/api/res-asset'
import type { ResKeyframe } from '@/types/resource'

const emit = defineEmits<{
  (e: 'success', keyframe: ResKeyframe): void
  (e: 'cancel'): void
}>()

const visible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const uploadingImage = ref(false)
const loadingShots = ref(false)
const activeTab = ref('basic')
const activeCollapseNames = ref<string[]>([])
const formRef = ref<FormInstance>()

const dramaOptions = ref<Array<{ id: string | number; title: string }>>([])
interface ShotOptionItem {
  id: string
  dramaId: string
  shotNo: number
  shotName: string
  actionDescription?: string
}
const shotOptions = ref<ShotOptionItem[]>([])

const formData = reactive<{
  id?: string
  dramaId: string
  shotId?: string
  name: string
  frameType: string
  frameUrl: string
  prompt?: string
  negativePrompt?: string
  description?: string
  sourceType?: string
  aspectRatio?: string
  sortOrder?: number
  status: number
  remark?: string
}>({
  id: undefined,
  dramaId: '0',
  shotId: undefined,
  name: '',
  frameType: 'KEYFRAME',
  frameUrl: '',
  prompt: '',
  negativePrompt: '',
  description: '',
  sourceType: 'MANUAL_UPLOAD',
  aspectRatio: '16:9',
  sortOrder: 0,
  status: 1,
  remark: ''
})

const formRules: FormRules = {
  name: [
    { required: true, message: '请输入关键帧名称', trigger: 'blur' },
    { min: 2, max: 100, message: '长度在 2 到 100 个字符', trigger: 'blur' }
  ],
  frameUrl: [
    { required: true, message: '请上传关键帧图像', trigger: 'change' }
  ],
  frameType: [
    { required: true, message: '请选择关键帧类型', trigger: 'change' }
  ]
}

function getFrameTypeLabel(type: string): string {
  switch (type) {
    case 'FIRST_FRAME': return '首帧'
    case 'END_FRAME': return '尾帧'
    case 'KEYFRAME': return '普通关键帧'
    case 'ACTION_BEAT': return '动作节奏帧'
    default: return type
  }
}

function formatShotLabel(shot: ShotOptionItem): string {
  const prefix = shot.shotName ? `[${shot.shotName}]` : `[镜 ${shot.shotNo}]`
  const desc = shot.actionDescription ? ` - ${shot.actionDescription.slice(0, 20)}` : ''
  return `${prefix}${desc}`
}

async function loadDramaOptions() {
  try {
    const res = await dramaApi.getOptions()
    dramaOptions.value = res || []
  } catch (error) {
    console.error('加载短剧列表失败', error)
  }
}

async function loadShotOptions(dramaId: string) {
  if (!dramaId || dramaId === '0') {
    shotOptions.value = []
    formData.shotId = undefined
    return
  }
  try {
    loadingShots.value = true
    const res = await shotApi.getOptionsByDrama(dramaId)
    shotOptions.value = (res || []).map((s: any) => ({
      id: String(s.id),
      dramaId: String(s.dramaId),
      shotNo: s.shotNo,
      shotName: s.shotName,
      actionDescription: s.actionDescription
    }))
  } catch (error) {
    console.error('加载分镜选项失败', error)
    shotOptions.value = []
  } finally {
    loadingShots.value = false
  }
}

function handleDramaChange(val: string) {
  formData.shotId = undefined
  loadShotOptions(val)
}

async function handleImageUpload(options: UploadRequestOptions) {
  try {
    uploadingImage.value = true
    const res = await assetApi.upload(options.file, 'keyframe')
    formData.frameUrl = res.url
    ElMessage.success('关键帧图片上传成功')
  } catch (error) {
    ElMessage.error('上传图片失败')
  } finally {
    uploadingImage.value = false
  }
}

async function open(row?: ResKeyframe) {
  await loadDramaOptions()
  activeTab.value = 'basic'
  activeCollapseNames.value = []

  if (row && row.id) {
    isEdit.value = true
    formData.id = String(row.id)
    formData.name = row.name || ''
    formData.dramaId = row.dramaId !== undefined && row.dramaId !== null ? String(row.dramaId) : '0'
    formData.frameType = row.frameType || 'KEYFRAME'
    formData.frameUrl = row.frameUrl || ''
    formData.prompt = row.prompt || ''
    formData.negativePrompt = row.negativePrompt || ''
    formData.description = row.description || ''
    formData.sourceType = row.sourceType || 'MANUAL_UPLOAD'
    formData.aspectRatio = row.aspectRatio || '16:9'
    formData.sortOrder = row.sortOrder ?? 0
    formData.status = row.status ?? 1
    formData.remark = row.remark || ''

    if (formData.dramaId && formData.dramaId !== '0') {
      await loadShotOptions(formData.dramaId)
      formData.shotId = row.shotId !== undefined && row.shotId !== null ? String(row.shotId) : undefined
    } else {
      formData.shotId = undefined
      shotOptions.value = []
    }
  } else {
    isEdit.value = false
    Object.assign(formData, {
      id: undefined,
      dramaId: '0',
      shotId: undefined,
      name: '',
      frameType: 'KEYFRAME',
      frameUrl: '',
      prompt: '',
      negativePrompt: '',
      description: '',
      sourceType: 'MANUAL_UPLOAD',
      aspectRatio: '16:9',
      sortOrder: 0,
      status: 1,
      remark: ''
    })
    shotOptions.value = []
  }
  visible.value = true
}

function handleClose() {
  visible.value = false
  emit('cancel')
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    try {
      saving.value = true
      const payload: any = {
        ...formData,
        dramaId: formData.dramaId === '0' ? 0 : formData.dramaId,
        shotId: formData.shotId ? formData.shotId : undefined
      }

      if (isEdit.value) {
        await keyframeApi.update(payload)
        ElMessage.success('关键帧资产更新成功')
      } else {
        const id = await keyframeApi.create(payload)
        payload.id = id
        ElMessage.success('关键帧资产创建成功')
      }
      visible.value = false
      emit('success', payload as ResKeyframe)
    } catch (error: any) {
      ElMessage.error(error.message || '保存失败')
    } finally {
      saving.value = false
    }
  })
}

defineExpose({
  open
})
</script>
