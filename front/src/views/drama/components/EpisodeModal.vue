<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑剧集' : (activeTab === 'single' ? '新建单集' : '批量快速创建剧集')"
    width="640px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <el-tabs v-if="!isEdit" v-model="activeTab" class="mb-4">
      <el-tab-pane label="创建单集" name="single" />
      <el-tab-pane label="批量创建剧集" name="batch" />
    </el-tabs>

    <!-- 单集表单 -->
    <el-form
      v-if="activeTab === 'single' || isEdit"
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="100px"
    >
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="集数序号" prop="episodeNo">
            <el-input-number v-model="form.episodeNo" :min="1" :max="500" class="w-full" />
          </el-form-item>
        </el-col>
        <el-col :span="16">
          <el-form-item label="本集标题" prop="title">
            <el-input v-model="form.title" placeholder="如：第1集 重逢时刻" maxlength="100" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="目标时长(秒)" prop="targetDuration">
            <el-input-number v-model="form.targetDuration" :min="10" :max="1800" :step="30" class="w-full" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="制作状态" prop="status">
            <DictSelect v-model="form.status" dict-type="episode_status" placeholder="请选择状态" class="w-full" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="剧情简介" prop="summary">
        <el-input
          v-model="form.summary"
          type="textarea"
          :rows="2"
          placeholder="本集核心情节、主要冲突与钩子点..."
          maxlength="500"
          show-word-limit
        />
      </el-form-item>

      <el-form-item label="原始剧本" prop="scriptContent">
        <el-input
          v-model="form.scriptContent"
          type="textarea"
          :rows="6"
          placeholder="粘贴本集原始剧本文本（支持后续 AI 智能剧本拆解与场次提取）..."
        />
      </el-form-item>
    </el-form>

    <!-- 批量创建表单 -->
    <el-form
      v-else
      ref="batchFormRef"
      :model="batchForm"
      :rules="batchRules"
      label-width="110px"
    >
      <div class="bg-blue-50 border border-blue-100 rounded-md p-3 mb-4 text-xs text-blue-700">
        💡 批量创建将快速为您在短剧中初始化指定数量的空剧集卡片，后续可按集填充剧本或拆解分镜。
      </div>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="起始集号" prop="startEpisodeNo">
            <el-input-number v-model="batchForm.startEpisodeNo" :min="1" :max="500" class="w-full" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="创建集数" prop="count">
            <el-input-number v-model="batchForm.count" :min="1" :max="100" class="w-full" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="标题前缀" prop="titlePrefix">
            <el-input v-model="batchForm.titlePrefix" placeholder="如：第" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="标题后缀" prop="titleSuffix">
            <el-input v-model="batchForm.titleSuffix" placeholder="如：集" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="默认单集时长" prop="targetDuration">
        <el-input-number v-model="batchForm.targetDuration" :min="10" :max="1800" :step="30" />
        <span class="ml-2 text-gray-500 text-xs">秒 (默认 300 秒)</span>
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="flex justify-end gap-2">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">
          {{ isEdit ? '保存修改' : '确认创建' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import DictSelect from '@/components/DictSelect.vue'
import { episodeApi } from '@/api/drama'
import type { DramaEpisode, DramaEpisodeBatch } from '@/types/drama'

const emit = defineEmits<{
  (e: 'success', episodeId?: string | number): void
}>()

const visible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const activeTab = ref<'single' | 'batch'>('single')
const formRef = ref<FormInstance>()
const batchFormRef = ref<FormInstance>()

const form = reactive<DramaEpisode>({
  id: undefined,
  dramaId: 0,
  episodeNo: 1,
  title: '',
  summary: '',
  scriptContent: '',
  targetDuration: 300,
  status: 'DRAFT'
})

const batchForm = reactive<DramaEpisodeBatch>({
  dramaId: 0,
  startEpisodeNo: 1,
  count: 10,
  titlePrefix: '第',
  titleSuffix: '集',
  targetDuration: 300
})

const rules: FormRules = {
  episodeNo: [{ required: true, message: '请输入集号', trigger: 'blur' }],
  title: [{ required: true, message: '请输入剧集标题', trigger: 'blur' }]
}

const batchRules: FormRules = {
  startEpisodeNo: [{ required: true, message: '请输入起始集号', trigger: 'blur' }],
  count: [{ required: true, message: '请输入创建数量', trigger: 'blur' }]
}

function openCreate(dramaId: number, nextEpNo = 1) {
  isEdit.value = false
  activeTab.value = 'single'
  Object.assign(form, {
    id: undefined,
    dramaId,
    episodeNo: nextEpNo,
    title: `第${nextEpNo}集`,
    summary: '',
    scriptContent: '',
    targetDuration: 300,
    status: 'DRAFT'
  })
  Object.assign(batchForm, {
    dramaId,
    startEpisodeNo: nextEpNo,
    count: 10,
    titlePrefix: '第',
    titleSuffix: '集',
    targetDuration: 300
  })
  visible.value = true
}

async function openEdit(id: number) {
  isEdit.value = true
  visible.value = true
  try {
    const res = await episodeApi.getById(id)
    if (res) {
      Object.assign(form, res)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '加载剧集详情失败')
  }
}

async function handleSubmit() {
  if (isEdit.value || activeTab.value === 'single') {
    if (!formRef.value) return
    await formRef.value.validate(async (valid) => {
      if (!valid) return
      saving.value = true
      try {
        if (isEdit.value && form.id) {
          await episodeApi.update(form)
          ElMessage.success('剧集修改成功')
          visible.value = false
          emit('success', form.id)
        } else {
          const res = await episodeApi.create(form)
          ElMessage.success('剧集创建成功')
          visible.value = false
          emit('success', res)
        }
      } catch (e: any) {
        ElMessage.error(e.message || '保存剧集失败')
      } finally {
        saving.value = false
      }
    })
  } else {
    if (!batchFormRef.value) return
    await batchFormRef.value.validate(async (valid) => {
      if (!valid) return
      saving.value = true
      try {
        const res = await episodeApi.batchCreate(batchForm)
        ElMessage.success(`批量创建成功，已新增 ${res?.length || 0} 集`)
        visible.value = false
        emit('success', res?.[0])
      } catch (e: any) {
        ElMessage.error(e.message || '批量创建剧集失败')
      } finally {
        saving.value = false
      }
    })
  }
}

defineExpose({
  openCreate,
  openEdit
})
</script>
