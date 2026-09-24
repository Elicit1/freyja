<template>
  <Teleport to="body">
    <button
      v-if="store.activeCount > 0"
      type="button"
      class="prompt-task-float"
      @click="handleClick"
    >
      <span class="prompt-task-float__icon">⚡</span>
      <span class="min-w-0 text-left">
        <strong class="block truncate">AI 提示词后台任务</strong>
        <small class="block truncate">{{ store.activeCount }} 个任务运行中，关闭页面不会中断</small>
      </span>
    </button>
  </Teleport>
</template>

<script setup lang="ts">
import { usePromptTaskStore } from '@/store/promptTask'
import { ElMessage } from 'element-plus'

const store = usePromptTaskStore()

function handleClick() {
  const task = store.activeTasks[0]
  if (task?.shotId) {
    ElMessage.info(`分镜 ${task.shotId} 的提示词分析仍在后台运行，重新打开该镜头即可查看。`)
  } else {
    ElMessage.info('提示词分析仍在后台运行。')
  }
}
</script>

<style scoped>
.prompt-task-float {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 3000;
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: 300px;
  padding: 10px 14px;
  color: #fff;
  text-align: left;
  background: linear-gradient(135deg, #0f172a, #1e293b);
  border: 1px solid rgba(148, 163, 184, .35);
  border-radius: 12px;
  box-shadow: 0 10px 30px rgba(15, 23, 42, .25);
}

.prompt-task-float:hover {
  background: linear-gradient(135deg, #1e293b, #334155);
}

.prompt-task-float__icon {
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  background: rgba(255, 255, 255, .14);
  border-radius: 8px;
}
</style>
