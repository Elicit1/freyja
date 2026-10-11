<template>
  <section class="question-card" aria-label="Agent 需要确认的问题">
    <strong>确认后继续</strong>
    <fieldset v-for="(question, index) in questions" :key="question.id" :disabled="busy">
      <legend>{{ index + 1 }}. {{ question.header }}</legend>
      <p>{{ question.question }}</p>
      <el-radio-group :model-value="drafts[question.id]?.optionLabel || ''" class="question-options" @update:model-value="choose(question.id, String($event))">
        <el-radio v-for="option in question.options" :key="option.label" :value="option.label" border>
          <span>{{ option.label }} <small v-if="option.recommended">推荐</small></span>
          <span class="option-description">{{ option.description }}</span>
        </el-radio>
      </el-radio-group>
      <el-input :model-value="drafts[question.id]?.customAnswer || ''" type="textarea" :rows="2" maxlength="4000"
        :aria-label="`${question.header}的自定义回答`" :placeholder="question.options.length ? '也可以填写自己的回答…' : '填写你的回答…'"
        @update:model-value="custom(question.id, $event)" />
    </fieldset>
    <div class="question-footer"><span>选择选项或自由填写每个问题</span><el-button type="primary" :loading="busy" :disabled="!complete || busy" @click="submit">回答并继续</el-button></div>
  </section>
</template>
<script setup lang="ts">
import { computed, reactive } from 'vue'
import type { AgentQuestion, AgentAnswer } from '@/types/agent'
const props = defineProps<{ questions: AgentQuestion[]; busy: boolean }>()
const emit = defineEmits<{ answer: [answers: AgentAnswer[]] }>()
const drafts = reactive<Record<string, AgentAnswer>>({})
const complete = computed(() => props.questions.every(q => drafts[q.id]?.optionLabel || drafts[q.id]?.customAnswer?.trim()))
function choose(id: string, label: string) { drafts[id] = { questionId: id, optionLabel: label } }
function custom(id: string, value: string) { drafts[id] = { questionId: id, customAnswer: value } }
function submit() {
  if (complete.value && !props.busy) emit('answer', props.questions.map(q => ({ ...drafts[q.id], customAnswer: drafts[q.id]?.customAnswer?.trim() || undefined })))
}
</script>
<style scoped>
.question-card { margin-top: 14px; padding: 16px; border: 1px solid var(--border-default); border-radius: 12px; background: var(--surface); font-size: 13px; }
fieldset { border: 0; padding: 14px 0 0; margin: 8px 0 14px; min-width: 0; }
legend { font-weight: 600; }
p { margin: 0 0 10px; line-height: 1.6; overflow-wrap: anywhere; }
.question-options { display: flex; flex-direction: column; align-items: stretch; gap: 8px; margin-bottom: 10px; }
.question-options :deep(.el-radio) { margin: 0; height: auto; min-height: 50px; padding: 10px 12px; white-space: normal; }
.question-options :deep(.el-radio__label) { line-height: 1.6; white-space: normal; overflow-wrap: anywhere; }
.option-description { display: block; color: var(--text-secondary); font-size: 12px; }
small { color: var(--brand); margin-left: 6px; }
.question-footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.question-footer > span { font-size: 12px; color: var(--text-secondary); }
</style>
