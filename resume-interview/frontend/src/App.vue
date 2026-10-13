<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import UploadView from './views/UploadView.vue'
import QuizView from './views/QuizView.vue'
import ReportView from './views/ReportView.vue'
import LoginView from './views/LoginView.vue'
import MeView from './views/MeView.vue'
import AdminView from './views/AdminView.vue'
import { generateQuestions, bigtechGenerate, submitGrade, getPaper, meApi } from './api'
import { hintAsync } from './notify'

const route = useRoute()
const router = useRouter()

const routerReady = ref(false)
router.isReady().then(() => (routerReady.value = true))

/* ---------------------------------- 开局隐私 & 提示弹窗（首次访问弹一次，跨会话记住） ---------------------------------- */
const noticeVisible = ref(!localStorage.getItem('notice-seen'))
function closeNotice() {
  localStorage.setItem('notice-seen', '1')
  noticeVisible.value = false
}

/* ---------------------------------- 登录态 ---------------------------------- */
const username = ref(localStorage.getItem('username') || '')
const isLoggedIn = ref(!!localStorage.getItem('token'))
window.addEventListener('auth-changed', () => {
  username.value = localStorage.getItem('username') || ''
  isLoggedIn.value = !!localStorage.getItem('token')
})

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  localStorage.removeItem('admin')
  window.dispatchEvent(new Event('auth-changed'))
  reset()
  router.push('/login')
}

/* ---------------------------------- 主流程状态机 ---------------------------------- */
const step = ref('upload')
const challenge = ref(false)
const profile = ref(null)
const questions = ref([])
const report = ref(null)
const paperId = ref(null) // 当前试卷 id，供答题页作答落盘
const resumeId = ref(null) // 当前画像来源简历记录 id，随出题带给后端做级联删除关联
const processing = ref(false)
const processingText = ref('AI 处理中…')

const stepMap = { upload: 0, quiz: 1, report: 2 }
const stepIndex = computed(() => stepMap[step.value] ?? 0)

const onLoginPage = computed(() => route.name === 'login')
const onMePage = computed(() => route.name === 'me')
const onAdminPage = computed(() => route.name === 'admin')

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 轮询试卷直到出题完成 */
async function pollPaper(paperId) {
  for (let i = 0; i < 300; i++) {
    const res = await getPaper(paperId)
    const d = res.data || {}
    if (d.status === 'DONE') return d.questions || []
    if (d.status === 'FAILED') throw new Error('出题失败，请重试')
    await sleep(2000)
  }
  throw new Error('出题超时，请稍后重试')
}

/** 轮询作答详情直到判分完成 */
async function pollReport(submissionId) {
  for (let i = 0; i < 300; i++) {
    const res = await meApi.submissionDetail(submissionId)
    const d = res.data || {}
    if (d.status === 'DONE' && d.report) return d.report
    if (d.status === 'FAILED') throw new Error(d.errorMsg || '判分失败，请重试')
    await sleep(3000)
  }
  throw new Error('判分超时，请稍后重试')
}

function onParsed(p) {
  profile.value = p
  questions.value = []
  report.value = null
}

async function onGenerate(role, majorDomain) {
  processing.value = true
  processingText.value = challenge.value ? 'AI 组卷中（大厂挑战）…' : 'AI 组卷中…'
  hintAsync(processingText.value + ' 约需 1 分钟，请耐心等待…')
  try {
    const res = challenge.value
      ? await bigtechGenerate(profile.value, resumeId.value)
      : await generateQuestions(profile.value, role, resumeId.value, majorDomain)
    paperId.value = res.data.paperId
    questions.value = await pollPaper(res.data.paperId)
    step.value = 'quiz'
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '生成题目失败，请重试')
  } finally {
    processing.value = false
  }
}

async function onSubmit(answers) {
  processing.value = true
  processingText.value = 'AI 判分中，请稍候…'
  hintAsync(processingText.value + ' 约需 1 分钟，请耐心等待…')
  try {
    const res = await submitGrade(answers)
    const reportData = await pollReport(res.data.submissionId)
    report.value = reportData
    step.value = 'report'
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '评卷失败，请重试')
  } finally {
    processing.value = false
  }
}

function reset() {
  profile.value = null
  questions.value = []
  report.value = null
  paperId.value = null
  resumeId.value = null
  step.value = 'upload'
}

/** 从个人主页返回：清空主流程状态，回到首页（上传页） */
function onHome() {
  reset()
  router.push('/')
}

/** 简历已提交异步解析：留在上传页轮询进度，完成后直接展示画像，不再甩去个人主页 */
async function onSubmitted(recordId) {
  if (!recordId) {
    // 兜底：拿不到 recordId 时退回旧行为（去个人主页看进度）
    router.push('/me')
    return
  }
  questions.value = []
  report.value = null
  processing.value = true
  processingText.value = 'AI 解析简历中…'
  hintAsync('AI 解析简历中，约需 1 分钟，请耐心等待…')
  try {
    const result = await pollResumeProfile(recordId)
    if (result.ok) {
      profile.value = result.profile
      resumeId.value = recordId
      ElMessage.success('简历解析完成，可以开始出题了')
    } else {
      ElMessage.error(result.error)
    }
  } catch (e) {
    ElMessage.error('解析失败，请重试')
  } finally {
    processing.value = false
  }
}

/** 轮询简历解析记录，直到 DONE（返回画像）或 FAILED / 超时 */
async function pollResumeProfile(recordId) {
  for (let i = 0; i < 200; i++) {
    const res = await meApi.resumes()
    const rec = (res.data.items || []).find((r) => r.id === recordId)
    if (rec) {
      if (rec.status === 'DONE' && rec.profile) return { ok: true, profile: rec.profile }
      if (rec.status === 'FAILED') return { ok: false, error: rec.errorMsg || '简历解析失败' }
    }
    await sleep(3000)
  }
  return { ok: false, error: '解析超时，请到个人主页查看进度' }
}

/* ---------------------------------- 个人主页联动 ---------------------------------- */
async function onReuseProfile(p, rid) {
  challenge.value = false
  step.value = 'upload'
  profile.value = p
  resumeId.value = rid ?? null // 记住画像来源简历，出题时带给后端
  questions.value = []
  report.value = null
  router.push('/')
  await onGenerate()
}

/** 用历史试卷复用出题 → 轮询后进入答题 */
async function onReusePaper(data) {
  challenge.value = false
  report.value = null
  step.value = 'upload'
  router.push('/')
  processing.value = true
  processingText.value = 'AI 组卷中…'
  hintAsync('AI 组卷中，约需 1 分钟，请耐心等待…')
  try {
    paperId.value = data.paperId
    questions.value = await pollPaper(data.paperId)
    step.value = 'quiz'
  } catch (e) {
    ElMessage.error(e.message || '重新出题失败')
  } finally {
    processing.value = false
  }
}

/** 从出题记录直接去答这张卷（拉取已有题目） */
async function onAnswerPaper(pid) {
  challenge.value = false
  report.value = null
  step.value = 'upload'
  router.push('/')
  processing.value = true
  processingText.value = '加载试卷中…'
  hintAsync('试卷加载中，约需 1 分钟，请耐心等待…')
  try {
    paperId.value = pid
    questions.value = await pollPaper(pid)
    step.value = 'quiz'
  } catch (e) {
    ElMessage.error(e.response?.data?.message || e.message || '加载试卷失败')
  } finally {
    processing.value = false
  }
}
</script>

<template>
  <div v-if="routerReady" class="app">
    <!-- 顶栏（登录页 / 管理后台不显示） -->
    <header v-if="!onLoginPage && !onAdminPage" class="top">
      <div class="logo" @click="router.push('/')" title="回到首页">🎯 面试模拟器</div>
      <div class="slogan">简历 → AI 出题 → 要点判分 → 错题讲解</div>
      <div class="top-right">
        <template v-if="isLoggedIn">
          <span class="user-name">{{ username }}</span>
          <el-button text @click="router.push('/me')">个人主页</el-button>
          <el-button text type="danger" @click="logout">退出登录</el-button>
        </template>
      </div>
    </header>

    <!-- 登录 / 注册页 -->
    <LoginView v-if="onLoginPage" />

    <!-- 管理后台（仅通过路径 /admin 访问） -->
    <AdminView v-else-if="onAdminPage" />

    <!-- 个人主页 -->
    <MeView v-else-if="onMePage" @use-profile="onReuseProfile" @reuse-paper="onReusePaper" @answer-paper="onAnswerPaper" @home="onHome" />

    <!-- 主流程：upload → quiz → report 状态机 -->
    <template v-else>
      <el-steps :active="stepIndex" align-center finish-status="success" class="steps">
        <el-step title="上传简历" />
        <el-step title="答题" />
        <el-step title="评分报告" />
      </el-steps>

      <main class="main" v-loading="processing" :element-loading-text="processingText">
        <UploadView v-if="step === 'upload'" :profile="profile" :processing="processing" :bigtech="challenge"
          @parsed="onParsed" @generate="onGenerate" @mode="(v) => (challenge = v)" @submitted="onSubmitted" />
        <QuizView v-else-if="step === 'quiz'" :questions="questions" :paper-id="paperId" @submit="onSubmit" />
        <ReportView v-else-if="step === 'report'" :report="report" @reset="reset" />
      </main>
    </template>

    <!-- 备案信息（登录页 / 管理后台不显示） -->
    <footer v-if="!onLoginPage && !onAdminPage" class="footer">
      <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">粤ICP备2026127488号</a>
    </footer>

    <!-- 开局隐私 & 提示弹窗（首次访问即弹出，跨会话只弹一次；管理后台不弹） -->
    <el-dialog v-if="!onAdminPage" v-model="noticeVisible" title="👋 欢迎使用 AI 面试模拟器" width="580px"
      :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false" align-center>
      <div class="notice">
        <div class="privacy">
          <div class="privacy-title">🔒 隐私与数据，请放心</div>
          <p>我们<b>不收集</b>你的手机号、邮箱等联系方式；<b>不存储</b>简历原件（PDF / Word / 图片）——上传后仅提取结构化的技能画像用于出题。</p>
          <p>你可以随时在<b>个人主页 → 解析记录</b>删除任意一条解析记录，数据随即清除。</p>
        </div>
        <p>这是个人开发的 <b>Demo</b>，免费给大家试用，功能与题库仍在持续完善，<b>欢迎多提意见</b>。</p>
        <p>反馈请关注小红书号：<b class="wx">95183612128</b>（欢迎私信）</p>
        <p>无论你是 <b>计算机</b> 相关专业，还是 <b>其他专业</b>，在这里都能放心练：计算机方向会从海量题库里<b>精选好题、再结合你的项目定制</b>；其他专业也别担心——AI 会照着你的简历帮你出题，练习一样专业、一样有针对性。</p>
        <p>已支持岗位：<b>Java 后端</b>、<b>软件测试</b>、<b>前端开发</b>、<b>AI 应用开发</b>。</p>
        <p>首页还有 <b>「大厂挑战」</b>：AI Agent 大厂真题，4 题 × 25 分，题干标注公司。</p>
        <p>后续将陆续支持 <b>Go 后端</b>、<b>运维</b> 等岗位。</p>
      </div>
      <template #footer>
        <el-button type="primary" size="large" @click="closeNotice">我知道了，开始体验</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style>
.app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.footer {
  margin-top: auto;
  padding: 24px 16px 32px;
  text-align: center;
  font-size: 13px;
  color: var(--c-text-3);
}
.footer a {
  color: var(--c-text-3);
  text-decoration: none;
}
.footer a:hover {
  color: var(--c-text-2);
  text-decoration: underline;
}
.top {
  position: sticky;
  top: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 40px;
  background: var(--c-glass);
  backdrop-filter: saturate(180%) blur(20px);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}
.logo {
  font-size: 21px;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: var(--c-text);
  cursor: pointer;
  user-select: none;
  transition: opacity 0.2s ease;
}
.logo:hover {
  opacity: 0.65;
}
.slogan {
  font-size: 14px;
  color: var(--c-text-3);
}
.top-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.user-name {
  font-size: 15px;
  color: var(--c-text-2);
  margin-right: 4px;
}
.steps {
  max-width: 900px;
  margin: 36px auto 28px;
}
.steps .el-step__title {
  white-space: nowrap;
}
.main {
  padding: 0 24px 56px;
}
.notice p {
  margin: 0 0 12px;
  font-size: 15px;
  line-height: 1.75;
  color: var(--c-text-2);
}
.notice p:last-child {
  margin-bottom: 0;
}
.privacy {
  margin-bottom: 16px;
  padding: 14px 16px;
  border-radius: var(--radius-lg);
  background: rgba(0, 113, 227, 0.06);
  border: 1px solid rgba(0, 113, 227, 0.16);
}
.privacy .privacy-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--c-primary);
  margin-bottom: 8px;
}
.privacy p {
  margin-bottom: 6px;
  font-size: 14px;
  color: var(--c-text-2);
}
.privacy p:last-child {
  margin-bottom: 0;
}
.notice b {
  color: var(--c-text);
}
.notice .wx {
  display: inline-block;
  padding: 2px 12px;
  border-radius: var(--radius-pill);
  background: rgba(0, 113, 227, 0.1);
  color: var(--c-primary);
  letter-spacing: 0.02em;
}
@media (max-width: 640px) {
  .top {
    padding: 12px 18px;
  }
  .slogan {
    display: none;
  }
}
@media (max-width: 640px) {
  .logo { font-size: 18px; }
  .user-name { display: none; }
  .steps { margin: 22px auto 16px; padding: 0 6px; }
  .steps .el-step__title { font-size: 12px; }
  .main { padding: 0 14px 40px; }
}
</style>