<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const props = defineProps({
  questions: Array,
  paperId: Number, // 所属试卷 id，作答落盘的稳定 key
})
const emit = defineEmits(['submit'])

// 题目唯一键：bank/project/bigtech 多张表的 id 可能重复，用 store+id 组合
const keyOf = (q) => `${q.store || 'bank'}-${q.id}`

// key -> answerText
const answers = ref({})

/* ---------------- 作答落盘：按试卷 id 存 localStorage，刷新 / 误关不丢 ---------------- */
const storageKey = computed(() => (props.paperId ? `quiz-answers-${props.paperId}` : null))
function loadSaved() {
  if (!storageKey.value) return
  try {
    const raw = localStorage.getItem(storageKey.value)
    if (raw) Object.assign(answers.value, JSON.parse(raw))
  } catch { /* 损坏数据直接忽略 */ }
}
function persist() {
  if (!storageKey.value) return
  try { localStorage.setItem(storageKey.value, JSON.stringify(answers.value)) } catch { /* 存储满则忽略 */ }
}
onMounted(loadSaved)

const typing = (q) => answers.value[keyOf(q)] || ''

function setText(q, v) {
  answers.value[keyOf(q)] = v
  persist()
}

const progress = computed(() => {
  const done = Object.values(answers.value).filter(a => (a || '').trim()).length
  return Math.round((done / (props.questions?.length || 1)) * 100)
})

const unansweredCount = computed(() =>
  props.questions.filter(q => !(answers.value[keyOf(q)] || '').trim()).length)

/** 交卷：允许空题（按 0 分计），有空题时二次确认，不再硬拦截 */
function submit() {
  if (unansweredCount.value > 0) {
    ElMessageBox.confirm(
      `还有 ${unansweredCount.value} 题未作答，未作答的题将按 0 分计。确定交卷吗？`,
      '交卷确认',
      {
        type: 'warning',
        confirmButtonText: '确定交卷',
        cancelButtonText: '再想想',
        confirmButtonClass: 'el-button--danger',
      }
    ).then(doSubmit).catch(() => {})
  } else {
    doSubmit()
  }
}

function doSubmit() {
  // 交卷后清掉本草稿，避免下次同卷残留
  if (storageKey.value) {
    try { localStorage.removeItem(storageKey.value) } catch { /* 忽略 */ }
  }
  const list = props.questions.map(q => ({
    questionId: q.id,
    store: q.store || 'bank',
    fullScore: q.fullScore || 20,
    answerText: answers.value[keyOf(q)] || '',
  }))
  emit('submit', list)
}

const diffColor = { BASIC: 'success', INTERMEDIATE: 'warning', ADVANCED: 'danger' }
const diffLabel = { BASIC: '基础', INTERMEDIATE: '进阶', ADVANCED: '深入' }
const storeLabel = { project: '项目实战', bigtech: '大厂真题' }
</script>

<template>
  <div class="quiz-view">
    <div class="quiz-head">
      <div class="quiz-title">面试题 · 共 {{ props.questions?.length }} 题</div>
      <div class="quiz-progress">
        <el-progress :percentage="progress" :stroke-width="10" style="width: 220px" />
        <span class="quiz-answered">{{ progress }}%</span>
      </div>
    </div>

    <div v-for="(q, i) in props.questions" :key="keyOf(q)" class="question-card el-card">
      <div class="q-head">
        <span class="q-index">{{ i + 1 }}</span>
        <el-tag :type="diffColor[q.difficulty]" size="small">{{ diffLabel[q.difficulty] }}</el-tag>
        <el-tag :type="q.store === 'bigtech' ? 'danger' : q.store === 'project' ? 'warning' : 'info'"
          size="small" effect="plain">{{ storeLabel[q.store] || q.skill }}</el-tag>
        <el-tag v-if="q.store === 'bigtech' && q.company" type="danger" size="small">{{ q.company }}</el-tag>
        <span class="q-score">{{ q.fullScore || 20 }} 分</span>
      </div>
      <div class="q-stem">{{ q.stem }}</div>

      <el-input type="textarea" :rows="4" placeholder="写出你的回答（尽量分点、给出关键术语与原因）"
        :model-value="typing(q)" @update:model-value="v => setText(q, v)" class="q-textarea" />
    </div>

    <div class="quiz-foot">
      <el-button type="primary" size="large" @click="submit">交卷评分</el-button>
    </div>
  </div>
</template>

<style scoped>
.quiz-view { max-width: 920px; margin: 0 auto; }
.quiz-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 26px; }
.quiz-title { font-size: clamp(20px, 3vw, 28px); font-weight: 700; letter-spacing: -0.03em; }
.quiz-progress { display: flex; align-items: center; gap: 12px; }
.quiz-answered { font-size: 15px; font-weight: 600; color: var(--c-text-2); }
.question-card { position: relative; padding: 28px 30px 28px 36px; margin-bottom: 20px; border-radius: var(--radius-lg); box-shadow: var(--shadow-2); transition: box-shadow 0.25s ease, transform 0.25s ease; }
.question-card::before { content: ''; position: absolute; left: 0; top: 24px; bottom: 24px; width: 4px; border-radius: 0 4px 4px 0; background: linear-gradient(180deg, var(--c-primary-2), var(--c-primary)); }
.question-card:hover { box-shadow: 0 14px 40px rgba(0, 0, 0, 0.1); transform: translateY(-3px); }
.q-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.q-index { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border-radius: 50%; background: linear-gradient(180deg, var(--c-primary-2), var(--c-primary)); color: #fff; font-size: 14px; font-weight: 700; box-shadow: var(--shadow-brand); }
.q-score { margin-left: auto; font-size: 13px; font-weight: 600; color: var(--c-text-3); }
.q-stem { font-size: 17px; font-weight: 500; color: var(--c-text); line-height: 1.65; margin-bottom: 20px; white-space: pre-wrap; }
.q-textarea { margin-top: 4px; }
.quiz-foot { display: flex; justify-content: center; padding: 20px 0 56px; }
@media (max-width: 640px) {
  .quiz-head { flex-direction: column; align-items: flex-start; gap: 10px; }
  .quiz-progress { width: 100%; }
  .question-card { padding: 20px 18px 20px 24px; }
  .q-head { flex-wrap: wrap; }
  .q-score { margin-left: 0; width: 100%; }
  .q-stem { font-size: 16px; }
  .quiz-foot .el-button { width: 100%; }
}
</style>