<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑连续镜头组 (ShotGroup)' : '新建连续镜头组 (ShotGroup)'"
    width="560px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" label-position="top">
      <el-row :gutter="16">
        <el-col :span="8">
          <el-form-item label="镜头组序号" prop="groupNo">
            <el-input-number v-model="form.groupNo" :min="1" :max="100" class="!w-full" />
          </el-form-item>
        </el-col>
        <el-col :span="16">
          <el-form-item label="镜头组名称" prop="name">
            <el-input v-model="form.name" placeholder="如：葛明进门与对峙" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="导演叙事意图 / 戏剧目的" prop="purpose">
        <el-input
          v-model="form.purpose"
          type="textarea"
          :rows="2"
          placeholder="描述本镜头组的核心叙事目的（如：建立人物空间关系，制造压迫感与初次冲突）..."
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="flex items-center justify-end gap-2">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          确定保存
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { shotGroupApi } from '@/api/drama'
import type { DramaShotGroup } from '@/types/drama'

const emit = defineEmits<{
  (e: 'success'): void
}>()

const visible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const isEdit = computed(() => !!form.id)

const form = reactive<Partial<DramaShotGroup>>({
  id: undefined,
  sceneId: undefined,
  groupNo: 1,
  name: '',
  purpose: ''
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入镜头组名称', trigger: 'blur' }]
}

function openCreate(sceneId: number, nextGroupNo: number) {
  form.id = undefined
  form.sceneId = sceneId
  form.groupNo = nextGroupNo
  form.name = `镜头组 ${String(nextGroupNo).padStart(2, '0')}`
  form.purpose = ''
  visible.value = true
}

function openEdit(group: DramaShotGroup) {
  form.id = group.id
  form.sceneId = group.sceneId
  form.groupNo = group.groupNo
  form.name = group.name
  form.purpose = group.purpose
  visible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (isEdit.value) {
        await shotGroupApi.update(form)
        ElMessage.success('镜头组修改成功')
      } else {
        await shotGroupApi.create(form)
        ElMessage.success('镜头组创建成功')
      }
      visible.value = false
      emit('success')
    } catch (e: any) {
      ElMessage.error(e.message || '操作失败')
    } finally {
      submitting.value = false
    }
  })
}

defineExpose({
  openCreate,
  openEdit
})
</script>
