<template>
  <el-dialog
    v-model="visible"
    title="✂️ 拆分连续镜头组"
    width="540px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <div class="mb-4 bg-amber-50 border border-amber-200 rounded-lg p-3 text-xs text-amber-900 leading-relaxed">
      💡 拆分将从您指定的起始分镜开始，将其及后续分镜移入新创建的镜头组中。
    </div>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" label-position="top">
      <el-form-item label="拆分起点分镜" prop="splitAtShotId">
        <el-select v-model="form.splitAtShotId" placeholder="选择从哪个分镜开始拆分" class="w-full">
          <el-option
            v-for="s in candidateShots"
            :key="s.id"
            :label="`#${s.shotNo} ${s.shotName || ''} - ${s.actionDescription || '无描述'}`"
            :value="s.id"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="新镜头组名称" prop="newGroupName">
        <el-input v-model="form.newGroupName" placeholder="如：冲突激化与对白升级" />
      </el-form-item>

      <el-form-item label="新镜头组戏剧目的">
        <el-input v-model="form.newGroupPurpose" placeholder="如：情绪突变，制造戏剧转折" />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="flex items-center justify-end gap-2">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          确认拆分
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { shotGroupApi } from '@/api/drama'
import type { DramaShot, DramaShotGroup, DramaShotGroupSplit } from '@/types/drama'

const emit = defineEmits<{
  (e: 'success'): void
}>()

const visible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const candidateShots = ref<DramaShot[]>([])

const form = reactive<DramaShotGroupSplit>({
  sourceGroupId: 0,
  splitAtShotId: 0,
  newGroupName: '',
  newGroupPurpose: ''
})

const rules: FormRules = {
  splitAtShotId: [{ required: true, message: '请选择拆分起点分镜', trigger: 'change' }],
  newGroupName: [{ required: true, message: '请输入新镜头组名称', trigger: 'blur' }]
}

function open(group: DramaShotGroup, shots: DramaShot[]) {
  form.sourceGroupId = group.id!
  candidateShots.value = shots.slice(1) // 至少从第2个镜头拆分
  if (candidateShots.value.length === 0) {
    ElMessage.warning('该镜头组分镜少于2个，无法拆分')
    return
  }
  form.splitAtShotId = candidateShots.value[0].id!
  form.newGroupName = `${group.name} (后段)`
  form.newGroupPurpose = ''
  visible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      await shotGroupApi.split(form)
      ElMessage.success('镜头组拆分成功')
      visible.value = false
      emit('success')
    } catch (e: any) {
      ElMessage.error(e.message || '拆分失败')
    } finally {
      submitting.value = false
    }
  })
}

defineExpose({
  open
})
</script>
