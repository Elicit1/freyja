<template>
  <div
    class="p-3.5 rounded-xl border border-[var(--border-default)] bg-[var(--surface)] hover:bg-[var(--surface-hover)] hover:border-[var(--brand)] transition-all duration-200 flex gap-3.5 cursor-pointer group shadow-xs hover:shadow-hover"
    @click="handleClick"
  >
    <!-- 封面 (海报比例 3:4) -->
    <div class="w-16 h-22 rounded-lg bg-[var(--surface-muted)] overflow-hidden shrink-0 relative border border-[var(--border-default)] flex items-center justify-center">
      <img
        v-if="drama.coverUrl"
        :src="drama.coverUrl"
        class="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
        alt="短剧封面"
      />
      <div v-else class="w-full h-full flex flex-col items-center justify-center bg-[var(--surface-muted)] text-[var(--text-muted)] group-hover:text-[var(--brand)] transition-colors">
        <el-icon class="text-2xl"><Film /></el-icon>
        <span class="text-[9px] font-medium mt-1">剧作</span>
      </div>
    </div>

    <!-- 信息区 -->
    <div class="flex-1 flex flex-col justify-between overflow-hidden min-w-0">
      <div>
        <div class="flex items-start justify-between gap-2 mb-1.5">
          <h4 class="font-bold text-sm text-[var(--text-primary)] truncate group-hover:text-[var(--brand)] transition-colors">
            {{ drama.title }}
          </h4>
          <DictTag
            v-if="drama.genre"
            dict-type="drama_genre"
            :value="drama.genre"
            size="small"
            class="shrink-0"
          />
        </div>

        <div class="flex items-center gap-2 text-xs text-[var(--text-secondary)]">
          <span class="font-medium">{{ drama.episodeCount || 0 }} 集</span>
          <span class="text-[var(--border-strong)]">·</span>
          <span>{{ drama.shotCount || 0 }} 镜头</span>
        </div>
      </div>

      <!-- 底部辅助信息与操作引导 -->
      <div class="flex items-center justify-between text-[11px] text-[var(--text-muted)] pt-2 border-t border-[var(--border-default)]">
        <span>{{ formattedTime }}</span>
        <span class="text-[var(--brand)] font-semibold flex items-center gap-1 group-hover:translate-x-0.5 transition-transform">
          进入大纲 <el-icon class="text-xs"><ArrowRight /></el-icon>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Film, ArrowRight } from '@element-plus/icons-vue'
import DictTag from '@/components/DictTag.vue'

interface DramaItem {
  id: string | number
  title: string
  genre?: string
  coverUrl?: string
  episodeCount?: number
  shotCount?: number
  updateTime?: string
}

const props = defineProps<{
  drama: DramaItem
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
}>()

const formattedTime = computed(() => {
  if (!props.drama.updateTime) return '刚刚'
  try {
    const date = new Date(props.drama.updateTime)
    const month = date.getMonth() + 1
    const day = date.getDate()
    const hours = date.getHours().toString().padStart(2, '0')
    const mins = date.getMinutes().toString().padStart(2, '0')
    return `${month}月${day}日 ${hours}:${mins}`
  } catch {
    return props.drama.updateTime
  }
})

function handleClick() {
  // 铁律：保持原始雪花 ID 字符串，严禁 Number() 转换
  emit('select', String(props.drama.id))
}
</script>
