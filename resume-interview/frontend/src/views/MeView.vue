<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ReportView from './ReportView.vue'
import { meApi, reuseApi } from '../api'
import { notifyAsync, EST } from '../notify'

const emit = defineEmits(['use-profile', 'reuse-paper', 'answer-paper', 'home'])

const username = ref(localStorage.getItem('username') || '')
const loading = ref(false)

const stats = ref({ resumes: 0, papers: 0, submissions: 0 })
const activeTab = ref('resumes')
const resumes = ref([])
const papers = ref([])
const submissions = ref([])

const reportVisible = ref(false)
const reportLoading = ref(false)
const report = ref(null)
const reusingPaperId = ref(null)

// 解析记录详情弹窗（画像展示）
const resumeVisible = ref(false)
const curResume = ref(null)
const levelColor = { 精通: 'danger', 熟悉: 'success', 了解: 'info' }

// 学习路线弹窗
const planVisible = ref(false)
const planLoading = ref(false)
const plan = ref(null)

function openResume(item) {
  curResume.value = item
  resumeVisible.value = true
}

function fmt(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return String(ts)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function pctNum(p) {
  if (p == null) return 0
  const v = Number(p)
  return v <= 1 ? v * 100 : v
}
function pctText(p) {
  return p == null ? '—' : `${Math.round(pctNum(p))}%`
}
function pctClass(p) {
  const v = pctNum(p)
  return v >= 80 ? 'good' : v >= 50 ? 'mid' : 'low'
}

let pollTimer = null
/** 有「待解析/判分中」记录时轮询刷新；全部落定后停止 */
function schedulePoll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  if (resumes.value.some((r) => r.status === 'PROCESSING')
      || submissions.value.some((s) => s.status === 'PROCESSING')
      || papers.value.some((p) => p.status === 'PROCESSING')) {
    pollTimer = setTimeout(() => loadAll(true), 5000)
  }
}

async function loadAll(silent = false) {
  if (!silent) loading.value = true
  try {
    const [meRes, resuRes, papRes, subRes] = await Promise.all([
      meApi.me().catch(() => null),
      meApi.resumes(),
      meApi.papers(),
      meApi.submissions(),
    ])
    if (meRes?.data?.username) username.value = meRes.data.username
    stats.value = {
      resumes: resuRes.data.count ?? 0,
      papers: papRes.data.count ?? 0,
      submissions: subRes.data.count ?? 0,
    }
    resumes.value = resuRes.data.items ?? []
    papers.value = papRes.data.items ?? []
    submissions.value = subRes.data.items ?? []
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '加载个人数据失败')
  } finally {
    loading.value = false
    schedulePoll()
  }
}

/** 用历史简历直接走正常出题流程；带上简历 id，供后端建立试卷→简历的级联关联 */
function useProfile(item) {
  if (!item.profile) {
    ElMessage.warning('该简历缺少能力画像，无法直接出题，请重新上传')
    return
  }
  emit('use-profile', item.profile, item.id)
}

/** 删除一条解析记录（连带学习路线），需二次确认；防误删 */
async function removeResume(item) {
  try {
    await ElMessageBox.confirm(
      '删除后，该份简历的解析记录、学习路线，以及由它生成的试卷与作答报告都将在本平台被永久清除。删除不可恢复，确定删除吗？',
      '删除解析记录',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
        distinguishCancelAndClose: true,
      }
    )
  } catch {
    return
  }
  try {
    await meApi.deleteResume(item.id)
    ElMessage.success('解析记录已删除')
    resumes.value = resumes.value.filter((r) => r.id !== item.id)
    stats.value.resumes = resumes.value.length
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '删除失败，请重试')
  }
}

/** 基于历史试卷复用出题（后端 reuse 接口，异步） */
async function reusePaper(item) {
  try {
    await ElMessageBox.confirm(`基于该试卷重新生成一份同题量新卷（${item.questionCount} 题）？`, '重新出题', {
      type: 'info',
      confirmButtonText: '生成',
      cancelButtonText: '取消',
      distinguishCancelAndClose: true,
    })
  } catch {
    return
  }
  reusingPaperId.value = item.id
  try {
    const res = await reuseApi(item.id)
    emit('reuse-paper', res.data)
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '重新出题失败，请重试')
  } finally {
    reusingPaperId.value = null
  }
}

/** 查看单次作答的完整评分报告（判分中则轮询） */
async function viewSubmission(item) {
  reportVisible.value = true
  reportLoading.value = true
  report.value = null
  try {
    for (let i = 0; i < 300; i++) {
      const res = await meApi.submissionDetail(item.id)
      const d = res.data || {}
      if (d.status === 'DONE' && d.report) {
        report.value = d.report
        break
      }
      if (d.status === 'FAILED') {
        ElMessage.error(d.errorMsg || '判分失败')
        reportVisible.value = false
        break
      }
      await new Promise((r) => setTimeout(r, 3000))
    }
  } catch (e) {
    reportVisible.value = false
    ElMessage.error(e.response?.data?.message || '加载评分报告失败')
  } finally {
    reportLoading.value = false
  }
}

/** 查看该简历的学习路线（未生成则触发异步生成并轮询） */
async function openPlan(item) {
  notifyAsync({ title: '🎓 学习路线生成中', message: `已提交，${EST.plan}，请耐心等待…` })
  planVisible.value = true
  planLoading.value = true
  plan.value = null
  try {
    let res = await meApi.genResumePlan(item.id)
    let d = res.data || {}
    for (let i = 0; i < 200 && !plan.value; i++) {
      if (d.status === 'DONE' && d.plan) {
        plan.value = d.plan
        break
      }
      if (d.status === 'FAILED') {
        throw new Error(d.errorMsg || '学习路线生成失败')
      }
      await new Promise((r) => setTimeout(r, 3000))
      res = await meApi.resumePlan(item.id)
      d = res.data || {}
    }
    if (!plan.value) {
      throw new Error('生成超时，请稍后重试')
    }
  } catch (e) {
    planVisible.value = false
    ElMessage.error(e.response?.data?.message || e.message || '学习路线生成失败')
  } finally {
    planLoading.value = false
  }
}

onMounted(() => loadAll())
onUnmounted(() => {
  if (pollTimer) clearTimeout(pollTimer)
})
</script>

<template>
  <div class="me-view">
    <div class="welcome">
      <div>
        <div class="welcome-title">你好，{{ username }} 👋</div>
        <div class="welcome-sub">这里记录着你的面试练习足迹：解析了哪些简历、出过哪些卷、答得怎么样</div>
      </div>
      <el-button class="home-btn" @click="emit('home')">返回主页</el-button>
    </div>

    <div class="stats">
      <div class="stat-card">
        <div class="stat-num">{{ stats.resumes }}</div>
        <div class="stat-label">简历解析</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.papers }}</div>
        <div class="stat-label">生成试卷</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.submissions }}</div>
        <div class="stat-label">完成作答</div>
      </div>
    </div>

    <el-card shadow="never" class="tabs-card" v-loading="loading">
      <el-tabs v-model="activeTab">
        <el-tab-pane :label="`解析记录（${resumes.length}）`" name="resumes">
          <el-empty v-if="!loading && !resumes.length" description="还没有解析过简历，去上传一份吧" />
          <div v-else class="rec-list">
            <el-card v-for="r in resumes" :key="r.id" shadow="hover" class="rec-card">
              <div class="rec-line">
                <el-tag size="small" type="info" effect="plain">{{ r.sourceType || '未知来源' }}</el-tag>
                <el-tag v-if="r.status === 'PROCESSING'" size="small" type="primary" effect="plain">待解析…</el-tag>
                <el-tag v-else-if="r.status === 'FAILED'" size="small" type="danger" effect="plain">解析失败</el-tag>

                <span v-if="r.status === 'DONE' && r.profile" class="rec-skill">
                  {{ r.profile.skills?.length ?? 0 }} 个技能
                  <el-tag v-if="r.profile.targetRole" size="small" type="warning" effect="plain" class="rec-tag">
                    {{ r.profile.targetRole }}
                  </el-tag>
                  <span v-if="r.profile.yearsExperience != null" class="rec-exp">
                    {{ r.profile.yearsExperience }} 年经验
                  </span>
                </span>
                <span v-else-if="r.status === 'PROCESSING'" class="rec-skill processing">待解析，请稍候…</span>
                <span v-else-if="r.status === 'FAILED'" class="rec-skill failed">解析失败：{{ r.errorMsg || '未知错误' }}</span>
                <span v-else class="rec-skill">暂无画像数据</span>

                <span class="rec-right">
                  <span class="rec-time">{{ fmt(r.createdAt) }}</span>
                  <el-button class="soft-btn" size="small" :disabled="r.status !== 'DONE' || !r.profile" @click="openResume(r)">
                    查看技能
                  </el-button>
                  <el-button class="soft-btn" size="small" :disabled="r.status !== 'DONE' || !r.profile" @click="openPlan(r)">
                    学习路线
                  </el-button>
                  <el-button class="soft-btn" size="small" :disabled="r.status !== 'DONE' || !r.profile" @click="useProfile(r)">
                    用这份简历出题
                  </el-button>
                  <el-button size="small" type="danger" plain @click="removeResume(r)">
                    删除
                  </el-button>
                </span>
              </div>
            </el-card>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="`作答记录（${submissions.length}）`" name="submissions">
          <el-empty v-if="!loading && !submissions.length" description="还没有作答记录，去做一份面试题吧" />
          <div v-else class="sub-list">
            <el-card v-for="s in submissions" :key="s.id" shadow="hover" class="sub-card">
              <div class="sub-line">
                <template v-if="s.status === 'PROCESSING'">
                  <span class="sub-pct">…</span>
                  <span class="sub-score">判分中，请稍候</span>
                </template>
                <template v-else-if="s.status === 'FAILED'">
                  <span class="sub-pct low">!</span>
                  <span class="sub-score">判分失败</span>
                </template>
                <template v-else>
                  <span class="sub-pct" :class="pctClass(s.pct)">{{ pctText(s.pct) }}</span>
                  <span class="sub-score">{{ s.totalScore }}/{{ s.fullScore }} 分</span>
                </template>
                <span class="sub-time">{{ fmt(s.createdAt) }}</span>
                <span class="sub-right">
                  <el-button size="small" type="primary" text @click="viewSubmission(s)">
                    {{ s.status === 'PROCESSING' ? '查看进度' : '查看完整报告' }}
                  </el-button>
                </span>
              </div>
            </el-card>
          </div>
        </el-tab-pane>

        <el-tab-pane :label="`出题记录（${papers.length}）`" name="papers">
          <el-empty v-if="!loading && !papers.length" description="还没有生成过试卷，去上传简历吧" />
          <div v-else class="paper-list">
            <el-card v-for="p in papers" :key="p.id" shadow="hover" class="paper-card">
              <div class="paper-line">
                <span class="paper-count">{{ p.questionCount }} 题</span>
                <el-tag v-if="p.status === 'PROCESSING'" size="small" type="primary" effect="plain">出题中…</el-tag>
                <el-tag v-else-if="p.status === 'FAILED'" size="small" type="danger" effect="plain">出题失败</el-tag>
                <span class="paper-time">{{ fmt(p.createdAt) }}</span>
                <span class="paper-right">
                  <el-button class="soft-btn" size="small"
                    :disabled="p.status !== 'DONE' || !p.questionCount" @click="emit('answer-paper', p.id)">
                    去答题
                  </el-button>
                  <el-button size="small" type="primary" plain
                    :loading="reusingPaperId === p.id" @click="reusePaper(p)">
                    用这份卷重新出题
                  </el-button>
                </span>
              </div>
            </el-card>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="resumeVisible" title="📄 简历能力画像" width="720px" top="6vh">
      <template v-if="curResume?.profile">
        <el-descriptions :column="2" size="small">
          <el-descriptions-item label="姓名">{{ curResume.profile.name || '—' }}</el-descriptions-item>
          <el-descriptions-item label="目标岗位">{{ curResume.profile.targetRole || '—' }}</el-descriptions-item>
          <el-descriptions-item label="经验">{{ curResume.profile.yearsExperience ?? '—' }} 年</el-descriptions-item>
          <el-descriptions-item label="教育">{{ curResume.profile.school || '—' }} {{ curResume.profile.degree || '' }}</el-descriptions-item>
        </el-descriptions>
        <div class="rv-label">技能（{{ (curResume.profile.skills || []).length }} 个）</div>
        <div class="rv-skills">
          <el-tag v-for="s in curResume.profile.skills || []" :key="s.name" :type="levelColor[s.level] || 'primary'"
            effect="plain" class="rv-tag" :title="(s.keywords || []).join('、')">
            {{ s.name }} · {{ s.level }}
          </el-tag>
        </div>
        <template v-if="(curResume.profile.projects || []).length">
          <div class="rv-label">项目经历</div>
          <div v-for="p in curResume.profile.projects" :key="p.name" class="rv-proj">
            <b>{{ p.name }}</b> <span class="rv-role">[{{ p.role }}]</span>
            <el-tag v-for="t in p.techStack || []" :key="t" size="small" class="rv-tech">{{ t }}</el-tag>
            <div class="rv-desc">{{ p.description }}</div>
          </div>
        </template>
      </template>
      <el-empty v-else description="该份简历缺少画像数据" />
      <template #footer>
        <el-button @click="resumeVisible = false">关闭</el-button>
        <el-button v-if="curResume?.profile" class="soft-btn" @click="useProfile(curResume); resumeVisible = false">
          用这份简历出题
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="planVisible" title="🎓 学习路线" width="780px" top="6vh" destroy-on-close>
      <div v-loading="planLoading" class="plan-body">
        <template v-if="plan">
          <el-alert type="info" :closable="false" title="学习建议" :description="plan.summary" class="plan-alert" />
          <div class="plan-duration">建议周期：{{ plan.duration }}</div>
          <div v-for="(ph, i) in plan.phases" :key="i" class="phase">
            <div class="phase-head">
              <span class="phase-idx">{{ i + 1 }}</span>
              <b>{{ ph.title }}</b>
              <span class="phase-days">{{ ph.days }}</span>
            </div>
            <div v-if="ph.goals && ph.goals.length" class="phase-block">
              <div class="phase-label">🎯 目标</div>
              <ul><li v-for="g in ph.goals" :key="g">{{ g }}</li></ul>
            </div>
            <div v-if="ph.tasks && ph.tasks.length" class="phase-block">
              <div class="phase-label">📚 任务</div>
              <ul><li v-for="t in ph.tasks" :key="t">{{ t }}</li></ul>
            </div>
            <div v-if="ph.practice && ph.practice.length" class="phase-block">
              <div class="phase-label">🛠 练习</div>
              <ul><li v-for="p in ph.practice" :key="p">{{ p }}</li></ul>
            </div>
          </div>
        </template>
        <el-empty v-else-if="!planLoading" description="暂无学习路线" />
      </div>
    </el-dialog>

    <el-dialog v-model="reportVisible" title="评分报告" width="880px" top="4vh" destroy-on-close>
      <div v-loading="reportLoading" class="report-body">
        <ReportView v-if="report" :report="report" @reset="reportVisible = false" />
        <el-empty v-else-if="!reportLoading" description="报告为空" />
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.me-view {
  max-width: 1080px;
  margin: 0 auto;
  padding: 44px 16px 64px;
}
.welcome {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.welcome-title {
  font-size: clamp(26px, 4vw, 36px);
  font-weight: 700;
  letter-spacing: -0.04em;
  color: var(--c-text);
}
.welcome-sub {
  font-size: 15px;
  color: var(--c-text-3);
  margin-top: 10px;
}
.stats {
  display: flex;
  gap: 18px;
  margin: 28px 0 26px;
}
.stat-card {
  flex: 1;
  background: var(--c-surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-2);
  padding: 26px 28px;
  display: flex;
  align-items: baseline;
  gap: 12px;
  transition: box-shadow 0.25s ease, transform 0.25s ease;
}
.stat-card:hover {
  box-shadow: 0 16px 44px rgba(0, 0, 0, 0.1);
  transform: translateY(-3px);
}
.stat-num {
  font-size: 46px;
  font-weight: 700;
  letter-spacing: -0.04em;
  background: linear-gradient(180deg, var(--c-primary-2), var(--c-primary));
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.stat-label {
  font-size: 15px;
  color: var(--c-text-2);
}
.tabs-card {
  border-radius: var(--radius-lg);
  min-height: 280px;
}
.rec-line,
.sub-line,
.paper-line {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
}
.rec-skill {
  flex: 1;
  font-size: 15px;
  color: var(--c-text);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.rec-skill.processing { color: var(--c-text-3); }
.rec-skill.failed { color: var(--c-danger); }
.rec-exp { font-size: 12px; color: var(--c-text-3); }
.rec-right,
.sub-right,
.paper-right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.rec-time,
.sub-time,
.paper-time {
  font-size: 13px;
  color: var(--c-text-3);
}
.soft-btn {
  background: rgba(0, 113, 227, 0.1) !important;
  border: none !important;
  color: var(--c-primary) !important;
  box-shadow: none !important;
}
.soft-btn:hover,
.soft-btn:focus {
  background: rgba(0, 113, 227, 0.18) !important;
  color: var(--c-primary) !important;
  transform: none;
}
.soft-btn.is-disabled,
.soft-btn.is-disabled:hover {
  background: rgba(0, 0, 0, 0.04) !important;
  color: var(--c-text-3) !important;
}
.sub-pct {
  font-size: 20px;
  font-weight: 700;
  width: 78px;
}
.sub-pct.good { color: var(--c-success); }
.sub-pct.mid { color: var(--c-warning); }
.sub-pct.low { color: var(--c-danger); }
.sub-score {
  flex: 1;
  font-size: 15px;
  color: var(--c-text);
}
.paper-count {
  flex: 1;
  font-size: 15px;
  color: var(--c-text);
}
.rec-card,
.sub-card,
.paper-card {
  margin-bottom: 12px;
  border-radius: var(--radius-md);
}
.report-body { min-height: 120px; }
.rv-label {
  font-size: 14px;
  font-weight: 700;
  color: var(--c-text-2);
  margin: 16px 0 8px;
}
.rv-skills { display: flex; flex-wrap: wrap; gap: 8px; }
.rv-tag { margin-right: 0; }
.rv-proj { margin-bottom: 8px; font-size: 14px; }
.rv-role { color: var(--c-text-3); margin-left: 6px; margin-right: 8px; }
.rv-tech { margin-right: 4px; }
.rv-desc { margin-top: 4px; color: var(--c-text-2); line-height: 1.7; }
.plan-body { min-height: 160px; }
.plan-alert { margin-bottom: 12px; }
.plan-duration { margin: 12px 0 4px; font-size: 15px; font-weight: 600; color: var(--c-text-2); }
.phase { margin: 14px 0; padding: 18px 22px; background: var(--c-bg-2); border-radius: var(--radius-lg); }
.phase-head { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.phase-idx { display: flex; align-items: center; justify-content: center; width: 26px; height: 26px; border-radius: 50%; background: linear-gradient(180deg, var(--c-primary-2), var(--c-primary)); color: #fff; font-size: 13px; font-weight: 700; }
.phase-days { margin-left: auto; font-size: 12px; color: var(--c-text-3); }
.phase-block { margin-top: 8px; }
.phase-label { font-size: 14px; font-weight: 600; color: var(--c-text-2); }
.phase-block ul { margin: 4px 0 0; padding-left: 20px; font-size: 14px; color: var(--c-text); }
.phase-block li { line-height: 1.75; }
@media (max-width: 720px) {
  .stats { flex-direction: column; }
  .welcome { flex-direction: column; align-items: flex-start; gap: 12px; }
  .rec-line, .sub-line, .paper-line { flex-wrap: wrap; }
  .rec-right, .sub-right, .paper-right { width: 100%; flex-wrap: wrap; gap: 10px; }
  .rec-skill { width: 100%; }
  .rec-time, .sub-time, .paper-time { display: none; }
}
</style>