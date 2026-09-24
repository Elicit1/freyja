<template>
  <el-dialog
    v-model="visible"
    title="🔗 合并连续镜头组"
    width="540px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <div class="mb-4 bg-blue-50 border border-blue-200 rounded-lg p-3 text-xs text-blue-900 leading-relaxed">
      💡 合并将多个镜头组串联为一个统一的连续动作单元，镜头序号将按时间线顺延重排，并自动沿轴重新传播 StartState / EndState 状态链。
    </div>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" label-position="top">
      <el-form-item label="待合并镜头组 (按选择顺序合并)" prop="sourceGroupIds">
        <el-checkbox-group v-model="form.sourceGroupIds" class="flex flex-col gap-2 w-full">
          <el-checkbox
            v-for="g in availableGroups"
            :key="g.id"
            :value="g.id"
            class="!border !border-gray-200 !rounded-lg !p-2 !w-full hover:bg-gray-50 flex items-center"
          >
            <div class="flex items-center justify-between w-full pr-2">
              <span class="font-bold text-xs">#{{ g.groupNo }} {{ g.name }}</span>
              <span class="text-[11px] text-gray-400 font-mono">{{ g.shotCount || 0 }} 镜</span>
            </div>
          </el-checkbox>
        </el-checkbox-group>
      </el-form-item>

      <el-form-item label="合并后镜头组名称" prop="mergedGroupName">
        <el-input v-model="form.mergedGroupName" placeholder="如：完整对峙与爆发连续组" />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="flex items-center justify-end gap-2">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          确认合并
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { shotGroupApi } from '@/api/drama'
import type { DramaShotGroup, DramaShotGroupMerge } from '@/types/drama'

const emit = defineEmits<{
  (e: 'success'): void
}>()

const visible = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const availableGroups = ref<DramaShotGroup[]>([])

const form = reactive<DramaShotGroupMerge>({
  sourceGroupIds: [],
  targetGroupId: undefined,
  mergedGroupName: ''
})

const rules: FormRules = {
  sourceGroupIds: [
    {
      validator: (_rule, value, callback) => {
        if (!value || value.length < 2) {
          callback(new Error('请至少选择两个待合并的镜头组'))
        } else {
          callback()
        }
      },
      trigger: 'change'
    }
  ],
  mergedGroupName: [{ required: true, message: '请输入合并后的镜头组名称', trigger: 'blur' }]
}

function open(groups: DramaShotGroup[], initialSelectedGroupId?: number) {
  availableGroups.value = groups
  if (groups.length < 2) {
    ElMessage.warning('当前场次下镜头组少于2个，无法执行合并')
    return
  }
  if (initialSelectedGroupId) {
    form.sourceGroupIds = [initialSelectedGroupId]
  } else {
    form.sourceGroupIds = groups.slice(0, 2).map(g => g.id!).filter(Boolean)
  }
  form.targetGroupId = form.sourceGroupIds[0]
  form.mergedGroupName = '合并后的连续镜头组'
  visible.value = true
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      form.targetGroupId = form.sourceGroupIds[0]
      await shotGroupApi.merge(form)
      ElMessage.success('镜头组合并成功')
      visible.value = false
      emit('success')
    } catch (e: any) {
      ElMessage.error(e.message || '合并失败')
    } finally {
      submitting.value = false
    }
  })
}

defineExpose({
  open
})
</script>
