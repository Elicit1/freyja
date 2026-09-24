<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑短剧项目' : '新建短剧项目'"
    width="680px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
      <el-row :gutter="16">
        <el-col :span="16">
          <el-form-item label="短剧名称" prop="title">
            <el-input v-model="form.title" placeholder="如：《逆袭从赘婿开始》" maxlength="100" show-word-limit />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="规划集数" prop="targetEpisodes">
            <el-input-number v-model="form.targetEpisodes" :min="1" :max="300" class="w-full" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="题材类型" prop="genre">
            <DictSelect v-model="form.genre" dict-type="drama_genre" placeholder="请选择短剧题材" class="w-full" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="画幅比例" prop="aspectRatio">
            <DictSelect v-model="form.aspectRatio" dict-type="drama_aspect_ratio" placeholder="请选择画幅比例" class="w-full" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="全局风格" prop="stylePreset">
            <DictSelect v-model="form.stylePreset" dict-type="drama_style_preset" placeholder="请选择画面风格" class="w-full" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="项目状态" prop="status">
            <DictSelect v-model="form.status" dict-type="drama_status" placeholder="请选择状态" class="w-full" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="视觉基调指南" prop="styleTone">
        <div class="w-full space-y-1.5">
          <el-input
            v-model="form.styleTone"
            type="textarea"
            :rows="2"
            placeholder="为全剧确立统一的导演摄影与视觉基调（AI 拆解时将自然融入英文分镜提示词，无需程序机械拼接），如：赛博朋克电影质感，高反差冷色调，雨夜霓虹漫射，浅景深"
            maxlength="300"
            show-word-limit
          />
          <!-- 快捷预设风格标签 -->
          <div class="flex flex-wrap items-center gap-1.5 text-xs">
            <span class="text-gray-400 text-[11px]">快捷预设:</span>
            <el-tag
              v-for="tag in stylePresets"
              :key="tag.label"
              size="small"
              class="cursor-pointer hover:opacity-80 transition-all select-none"
              type="info"
              @click="applyStylePreset(tag.prompt)"
            >
              {{ tag.label }}
            </el-tag>
          </div>
        </div>
      </el-form-item>

      <el-form-item label="封面图片" prop="coverUrl">
        <div class="flex items-center gap-3 w-full">
          <el-input v-model="form.coverUrl" placeholder="输入封面图 URL 或上传" class="flex-1" />
          <el-upload
            :show-file-list="false"
            :http-request="handleCustomUpload"
            accept="image/*"
          >
            <el-button type="primary" plain :loading="uploading">
              <el-icon class="mr-1"><UploadFilled /></el-icon>上传图片
            </el-button>
          </el-upload>
        </div>
        <div v-if="form.coverUrl" class="mt-2 relative w-32 h-44 rounded-md overflow-hidden border border-gray-200 shadow-sm bg-gray-50">
          <img :src="form.coverUrl" class="w-full h-full object-cover" alt="封面预览" />
        </div>
      </el-form-item>

      <el-form-item label="故事梗概" prop="synopsis">
        <el-input
          v-model="form.synopsis"
          type="textarea"
          :rows="4"
          placeholder="短剧的主线剧情、背景大纲、核心冲突与爆点描述..."
          maxlength="1000"
          show-word-limit
        />
      </el-form-item>

      <el-form-item label="备注说明" prop="remark">
        <el-input v-model="form.remark" placeholder="团队内部备忘或制作要求" />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="flex justify-end gap-2">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">
          {{ isEdit ? '保存修改' : '立即创建' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import DictSelect from '@/components/DictSelect.vue'
import { dramaApi } from '@/api/drama'
import { assetApi } from '@/api/res-asset'
import type { Drama } from '@/types/drama'

const emit = defineEmits<{
  (e: 'success', id: number): void
}>()

const visible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const uploading = ref(false)
const formRef = ref<FormInstance>()

const stylePresets = [
  { label: '🎬 现代都市微悬疑', prompt: '现代都市微悬疑，冷色调，浅景深，高对比度胶片质感，局部点光源' },
  { label: '🏮 国风玄幻写实', prompt: '国风东方玄幻，厚重电影级写实，古朴金木质感，丁达尔漫射光，暗调神秘' },
  { label: '🌆 王家卫复古港风', prompt: '90年代复古港风电影，暖黄低饱和色调，百叶窗阴影，强烈胶片颗粒与浅景深' },
  { label: '⚡ 赛博朋克霓虹', prompt: '赛博朋克电影质感，高反差冷青色与洋红撞色，雨夜霓虹漫射反射，迷雾光晕' },
  { label: '🌸 新海诚唯美水彩', prompt: '唯美日系动漫光影，通透水彩纯净感，温暖高调漫射光，细腻天空与微光云层' },
  { label: '⚔️ 3D国漫史诗', prompt: '次世代3D国漫大作质感，精细次表面散射皮肤，金石流光与体积光特效' }
]

function applyStylePreset(prompt: string) {
  form.styleTone = prompt
}

const form = reactive<Drama>({
  id: undefined,
  title: '',
  coverUrl: '',
  genre: 'DOMINANT_CEO',
  targetEpisodes: 80,
  aspectRatio: '9:16',
  stylePreset: 'cinematic-realism',
  styleTone: '',
  synopsis: '',
  status: 'PLANNING',
  remark: ''
})

const rules: FormRules = {
  title: [{ required: true, message: '请输入短剧名称', trigger: 'blur' }],
  genre: [{ required: true, message: '请选择题材类型', trigger: 'change' }],
  aspectRatio: [{ required: true, message: '请选择画幅比例', trigger: 'change' }]
}

function openCreate() {
  isEdit.value = false
  Object.assign(form, {
    id: undefined,
    title: '',
    coverUrl: '',
    genre: 'DOMINANT_CEO',
    targetEpisodes: 80,
    aspectRatio: '9:16',
    stylePreset: 'cinematic-realism',
    styleTone: '',
    synopsis: '',
    status: 'PLANNING',
    remark: ''
  })
  visible.value = true
}

async function openEdit(id: number) {
  isEdit.value = true
  visible.value = true
  try {
    const res = await dramaApi.getById(id)
    if (res) {
      Object.assign(form, res)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '加载短剧详情失败')
  }
}

async function handleCustomUpload(options: UploadRequestOptions) {
  try {
    uploading.value = true
    const res = await assetApi.upload(options.file as File, 'general')
    if (res?.url) {
      form.coverUrl = res.url
      ElMessage.success('封面上传成功')
    }
  } catch (e: any) {
    ElMessage.error(e.message || '封面上传失败')
  } finally {
    uploading.value = false
  }
}

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      if (isEdit.value && form.id) {
        await dramaApi.update(form)
        ElMessage.success('短剧信息修改成功')
        visible.value = false
        emit('success', form.id)
      } else {
        const res = await dramaApi.create(form)
        ElMessage.success('短剧创建成功')
        visible.value = false
        if (res) {
          emit('success', res)
        }
      }
    } catch (e: any) {
      ElMessage.error(e.message || '保存短剧失败')
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
