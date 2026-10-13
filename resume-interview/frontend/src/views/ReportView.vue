<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  report: Object,   // GradeResult
})
const emit = defineEmits(['reset'])

const active = ref([])

const pct = computed(() => {
  if (!props.report?.totalFullScore) return 0
  return Math.round((props.report.totalScore / props.report.totalFullScore) * 100 || 0)
})
const color = computed(() => (pct.value >= 80 ? '#67c23a' : pct.value >= 50 ? '#e6a23c' : '#f56c6c'))

const diffLabel = { BASIC: '基础', INTERMEDIATE: '进阶', ADVANCED: '深入' }
const diffColor = { BASIC: 'success', INTERMEDIATE: 'warning', ADVANCED: 'danger' }

function scoreClass(it) {
  if (it.score >= it.fullScore && it.fullScore > 0) return 'full'
  if (it.score === 0) return 'zero'
  return 'mid'
}
</script>

<template>
  <div class="report-view">
    <div class="summary">
      <el-progress type="dashboard" :percentage="pct" :color="color" :width="150" :stroke-width="12">
        <template #default="{ percentage }">
          <div class="score-num">{{ report.totalScore }}<span class="score-total">/{{ report.totalFullScore }}</span></div>
          <div class="score-pct">{{ percentage }}%</div>
        </template>
      </el-progress>
      <div class="summary-side">
        <div class="summary-title">评分报告</div>
        <div class="summary-sub">按『要点判分法』逐题给出得分、错点讲解与标准回答</div>
      </div>
    </div>

    <el-collapse v-model="active" class="items">
      <el-collapse-item v-for="(it, i) in report.items" :key="it.questionId" :name="i">
        <template #title>
          <div class="item-title">
            <span class="item-index">{{ i + 1 }}</span>
            <span class="item-stem">{{ it.stem }}</span>
            <span class="item-score" :class="scoreClass(it)">{{ it.score }}/{{ it.fullScore }}</span>
          </div>
        </template>
        <div class="item-body">
          <div class="item-tags">
            <el-tag :type="diffColor[it.difficulty]" size="small">{{ diffLabel[it.difficulty] }}</el-tag>
            <el-tag type="info" size="small" effect="plain">{{ it.skill }}</el-tag>
          </div>

          <div class="block-title">📝 你的作答</div>
          <div class="block-content">{{ it.userAnswer || '(未作答)' }}</div>

          <div class="block-title">🎯 要点判定（{{ it.points.filter(p => p.hit).length }}/{{ it.points.length }} 命中）</div>
          <div v-for="p in it.points" :key="p.point" class="point-row">
            <span class="point-mark" :class="p.hit ? 'hit' : 'miss'">{{ p.hit ? '✓' : '✗' }}</span>
            <span class="point-text">{{ p.point }}</span>
            <span class="point-score">{{ p.hit ? '+' + p.score : '+0' }}</span>
            <span v-if="p.hit && p.evidence" class="point-evidence">「{{ p.evidence }}」</span>
          </div>

          <div v-if="it.briefFeedback" class="block-title">💬 评语</div>
          <div v-if="it.briefFeedback" class="block-content">{{ it.briefFeedback }}</div>

          <div class="block-title">📖 标准回答</div>
          <div class="block-content">{{ it.perfectAnswer || '—' }}</div>

          <div v-if="it.explanation" class="block-title">🔍 拆解</div>
          <div v-if="it.explanation" class="block-content explain">{{ it.explanation }}</div>
        </div>
      </el-collapse-item>
    </el-collapse>

    <div class="report-foot">
      <el-button size="large" @click="emit('reset')">重新开始</el-button>
    </div>
  </div>
</template>

<style scoped>
.report-view { max-width: 920px; margin: 0 auto; }
.summary { display: flex; align-items: center; gap: 36px; margin-bottom: 24px; padding: 32px 38px; background: var(--c-surface); border-radius: var(--radius-xl); box-shadow: var(--shadow-2); }
.score-num { font-size: 34px; font-weight: 700; letter-spacing: -0.03em; }
.score-total { font-size: 16px; font-weight: 400; color: var(--c-text-3); }
.score-pct { margin-top: 2px; font-size: 14px; color: var(--c-text-2); }
.summary-title { font-size: clamp(22px, 3vw, 28px); font-weight: 700; letter-spacing: -0.03em; }
.summary-sub { margin-top: 8px; font-size: 15px; color: var(--c-text-3); }
.items { border-radius: var(--radius-lg); }
.item-title { display: flex; align-items: center; gap: 12px; width: 100%; padding-right: 16px; }
.item-index { font-weight: 700; color: var(--c-primary); }
.item-stem { flex: 1; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item-score { font-size: 15px; font-weight: 700; }
.item-score.full { color: var(--c-success); }
.item-score.mid { color: var(--c-warning); }
.item-score.zero { color: var(--c-danger); }
.item-body { padding: 6px 8px; }
.item-tags { display: flex; gap: 6px; margin-bottom: 8px; }
.block-title { margin: 16px 0 8px; font-size: 14px; font-weight: 700; color: var(--c-text-2); }
.block-content { margin-bottom: 4px; padding: 16px 20px; font-size: 15px; line-height: 1.7; color: var(--c-text); background: var(--c-bg-2); border-radius: var(--radius-md); white-space: pre-wrap; }
.explain { background: #f0f9eb; }
.point-row { display: flex; align-items: baseline; gap: 10px; padding: 7px 0; font-size: 14px; }
.point-mark { width: 18px; font-weight: 700; }
.point-mark.hit { color: var(--c-success); }
.point-mark.miss { color: var(--c-danger); }
.point-text { flex: 1; color: var(--c-text); }
.point-score { font-weight: 700; color: var(--c-text-2); }
.point-evidence { font-size: 12px; color: var(--c-text-3); }
.report-foot { display: flex; justify-content: center; padding: 28px 0 56px; }
@media (max-width: 640px) {
  .summary { flex-direction: column; align-items: flex-start; gap: 18px; padding: 22px; }
  .item-stem { white-space: normal; }
  .point-row { flex-wrap: wrap; }
  .point-evidence { width: 100%; }
  .report-foot .el-button { width: 100%; }
}
</style>