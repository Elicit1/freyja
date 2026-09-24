<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑情景场次' : '新建情景场次'"
    width="660px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="场次序号" prop="sceneNo">
            <el-input-number v-model="form.sceneNo" :min="1" :max="500" class="w-full" />
          </el-form-item>
        </el-col>
        <el-col :span="16">
          <el-form-item label="场次名称" prop="name">
            <el-input v-model="form.name" placeholder="如：场次1-酒店大堂主角受辱" maxlength="100" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="绑定场景资产" prop="resSceneId">
        <el-select
          v-model="form.resSceneId"
          placeholder="可从场景资产库关联已有环境（可选）"
          clearable
          filterable
          class="w-full"
          @change="handleResSceneChange"
        >
          <el-option
            v-for="opt in sceneOptions"
            :key="opt.id"
            :label="`${opt.name}${opt.sceneType || opt.timeOfDay ? ` (${[opt.sceneType, opt.timeOfDay].filter(Boolean).join('/')})` : ''}`"
            :value="opt.id"
          >
            <div class="flex items-center justify-between">
              <span>{{ opt.name }}</span>
              <span class="text-xs text-gray-400">{{ [opt.sceneType, opt.timeOfDay].filter(Boolean).join(' · ') }}</span>
            </div>
          </el-option>
        </el-select>
      </el-form-item>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="空间类型" prop="sceneType">
            <DictSelect v-model="form.sceneType" dict-type="res_scene_type" placeholder="选择空间类型 (选填)" clearable class="w-full" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="时间时段" prop="timeOfDay">
            <DictSelect v-model="form.timeOfDay" dict-type="res_time_of_day" placeholder="选择时段 (选填)" clearable class="w-full" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="天气/氛围" prop="weatherAtmosphere">
            <el-input v-model="form.weatherAtmosphere" placeholder="如：RAINY, NEON, SUNNY" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="具体地点" prop="locationName">
            <el-input v-model="form.locationName" placeholder="如：帝豪大酒店一楼中庭" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="剧情摘要" prop="summary">
        <el-input
          v-model="form.summary"
          type="textarea"
          :rows="2"
          placeholder="本场次剧情发生的事、冲突高潮或交代的信息..."
          maxlength="500"
          show-word-limit
        />
      </el-form-item>

      <el-form-item label="本场剧本片段" prop="scriptContent">
        <el-input
          v-model="form.scriptContent"
          type="textarea"
          :rows="5"
          placeholder="本场次剧本台词、对白与动作描述..."
        />
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
import { dramaSceneApi } from '@/api/drama'
import { sceneApi } from '@/api/res-scene'
import type { DramaScene } from '@/types/drama'
import type { ResSceneOption } from '@/types/resource'

const emit = defineEmits<{
  (e: 'success', sceneId?: string | number, episodeId?: string | number): void
}>()

const visible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const sceneOptions = ref<ResSceneOption[]>([])

const form = reactive<DramaScene>({
  id: undefined,
  dramaId: '',
  episodeId: '',
  sceneNo: 1,
  name: '',
  resSceneId: undefined,
  sceneType: '',
  timeOfDay: '',
  weatherAtmosphere: '',
  locationName: '',
  summary: '',
  scriptContent: ''
})

const rules: FormRules = {
  sceneNo: [{ required: true, message: '请输入场次序号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入场次名称', trigger: 'blur' }]
}

async function loadSceneOptions(dramaId: string | number) {
  try {
    const res = await sceneApi.getOptions(dramaId)
    sceneOptions.value = res || []
  } catch (e) {
    sceneOptions.value = []
  }
}

function handleResSceneChange(resSceneId?: string | number) {
  if (!resSceneId) return
  const found = sceneOptions.value.find(s => String(s.id) === String(resSceneId))
  if (found) {
    if (!form.name || form.name.startsWith('场次')) {
      form.name = `场次${form.sceneNo}-${found.name}`
    }
    if (found.sceneType) form.sceneType = found.sceneType
    if (found.timeOfDay) form.timeOfDay = found.timeOfDay
    if (found.weatherAtmosphere) form.weatherAtmosphere = found.weatherAtmosphere
  }
}

function openCreate(dramaId: string | number, episodeId: string | number, nextSceneNo = 1) {
  isEdit.value = false
  loadSceneOptions(dramaId)
  Object.assign(form, {
    id: undefined,
    dramaId,
    episodeId,
    sceneNo: nextSceneNo,
    name: `场次${nextSceneNo}`,
    resSceneId: undefined,
    sceneType: '',
    timeOfDay: 'DAY',
    weatherAtmosphere: '',
    locationName: '',
    summary: '',
    scriptContent: ''
  })
  visible.value = true
}

async function openEdit(id: string | number, dramaId: string | number) {
  isEdit.value = true
  loadSceneOptions(dramaId)
  visible.value = true
  try {
    const res = await dramaSceneApi.getById(id)
    if (res) {
      Object.assign(form, res)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '加载场次详情失败')
  }
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      if (isEdit.value && form.id) {
        await dramaSceneApi.update(form)
        ElMessage.success('场次修改成功')
        visible.value = false
        emit('success', form.id, form.episodeId)
      } else {
        const res = await dramaSceneApi.create(form)
        ElMessage.success('场次创建成功')
        visible.value = false
        emit('success', res, form.episodeId)
      }
    } catch (e: any) {
      ElMessage.error(e.message || '保存场次失败')
    } finally {
      saving.value = false
    }
  })
}

defineExpose({
  openCreate,
  openEdit
})
</script>
