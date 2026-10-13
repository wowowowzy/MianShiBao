<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { parseResume, learningPlan } from '../api'
import { notifyParsing, EST } from '../notify'

const props = defineProps({
  profile: Object,     // 解析出的能力画像
  processing: Boolean, // AI 解析中
  bigtech: Boolean,    // 是否大厂挑战模式
})

const emit = defineEmits(['parsed', 'generate', 'mode', 'submitted'])

const uploading = ref(false)
const fileList = ref([])
const uploadKey = ref(0)

// 大方向 / 岗位：上传文件后弹窗选择，再带参数调后端解析
const majorDomain = ref('计算机')
const role = ref('Java 后端')
const pickVisible = ref(false)
const pendingFile = ref(null)

// 大厂挑战固定 AI 应用开发
watch(() => props.bigtech, (v) => {
  role.value = v ? 'AI 应用开发' : 'Java 后端'
})

// 其他专业没有 IT 目标岗位：选「其他」时岗位置空；切回计算机则恢复默认
watch(majorDomain, (v) => {
  if (v === '其他') {
    role.value = ''
  } else if (!role.value) {
    role.value = 'Java 后端'
  }
})

// 大厂挑战说明弹窗
const bigtechCompanies = ['字节跳动', '小红书', '阿里巴巴', '腾讯', '百度', '华为', '蚂蚁集团', '美团', '快手', '商汤科技']
const challengeNoticeVisible = ref(false)
function onChallengeClick() {
  if (props.bigtech) return
  challengeNoticeVisible.value = true
}
function confirmChallenge() {
  challengeNoticeVisible.value = false
  emit('mode', true)
}

function onFileChange(file) {
  pendingFile.value = file.raw
  fileList.value = []
  pickVisible.value = true
}

function cancelPick() {
  pickVisible.value = false
  pendingFile.value = null
  uploadKey.value++
}

async function confirmPick() {
  if (!pendingFile.value) {
    pickVisible.value = false
    return
  }
  pickVisible.value = false
  await doParse()
}

async function doParse() {
  uploading.value = true
  try {
    const res = await parseResume(pendingFile.value, { majorDomain: majorDomain.value, role: role.value })
    notifyParsing(EST.parse)
    ElMessage.success('已提交解析，正在后台处理')
    // 将 recordId 传给 App，以便留在上传页轮询进度
    emit('submitted', res.data.recordId)
  } catch (e) {
    ElMessage.error(e.response?.data?.message || parseErr(e) || '提交失败')
  } finally {
    uploading.value = false
    pendingFile.value = null
    fileList.value = []
    uploadKey.value++
  }
}

function parseErr(e) {
  const d = e.response?.data
  if (typeof d === 'string') return d
  return d?.message
}

const levelColor = { 精通: 'danger', 熟悉: 'success', 了解: 'info' }

/* ---------------------------------- 学习路线 ---------------------------------- */
const planVisible = ref(false)
const planLoading = ref(false)
const plan = ref(null)

async function loadPlan() {
  if (!props.profile) return
  planVisible.value = true
  planLoading.value = true
  plan.value = null
  try {
    const res = await learningPlan(props.profile)
    plan.value = res.data
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '学习路线生成失败，请重试')
    planVisible.value = false
  } finally {
    planLoading.value = false
  }
}
</script>

<template>
  <div class="upload-view">
    <!-- 模式切换 -->
    <div class="mode-row">
      <button class="mode-btn" :class="{ active: !props.bigtech }" @click="emit('mode', false)">常规面试</button>
      <button class="mode-btn challenge" :class="{ active: props.bigtech }" @click="onChallengeClick">🔥 大厂挑战</button>
      <span v-if="props.bigtech" class="mode-hint">AI Agent 大厂真题 · 4 题 × 25 分 · 题干标注公司</span>
    </div>

    <el-dialog v-model="challengeNoticeVisible" title="🔥 大厂挑战" width="540px" align-center>
      <div class="challenge-notice">
        <p>大厂挑战目前<b>仅支持 AI 应用开发岗位</b>。</p>
        <p>涵盖公司：</p>
        <div class="companies">
          <el-tag v-for="c in bigtechCompanies" :key="c" type="danger" effect="plain" class="company-tag">{{ c }}</el-tag>
        </div>
        <p class="challenge-hint">将使用上述公司 AI Agent / AI 应用方向的真题出 <b>4 道题</b>，每题 <b>25 分</b>，题干会标注公司。</p>
      </div>
      <template #footer>
        <el-button @click="challengeNoticeVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmChallenge">开始大厂挑战</el-button>
      </template>
    </el-dialog>

    <el-upload :key="uploadKey" drag :auto-upload="false" accept=".pdf,.doc,.docx,.png,.jpg,.jpeg,.txt"
      :show-file-list="false" :disabled="uploading || props.processing" :on-change="onFileChange">
      <div class="upload-inner">
        <div class="upload-icon">📄</div>
        <div class="upload-main">{{ uploading || props.processing ? 'AI 解析中…' : '点击或拖拽上传简历' }}</div>
        <div class="upload-sub">支持 PDF / Word / 图片（扫描件走 OCR）/ TXT</div>
      </div>
    </el-upload>

    <template v-if="props.profile">
      <el-card class="profile-card" shadow="never">
        <template #header>
          <div class="profile-head">
            <span class="profile-title">能力画像</span>
            <el-tag type="warning">{{ props.profile.targetRole || '未识别岗位' }}</el-tag>
            <el-tag v-if="props.profile.yearsExperience !== undefined">{{ props.profile.yearsExperience }} 年经验</el-tag>
          </div>
        </template>
        <el-descriptions :column="2" size="small">
          <el-descriptions-item label="姓名">{{ props.profile.name || '—' }}</el-descriptions-item>
          <el-descriptions-item label="教育">{{ props.profile.school || '—' }} {{ props.profile.degree || '' }}</el-descriptions-item>
        </el-descriptions>
        <div class="skills">
          <span v-for="s in props.profile.skills" :key="s.name" class="skill-chip">
            <el-tag :type="levelColor[s.level] || 'primary'" effect="plain" class="skill-tag"
              :title="(s.keywords || []).join('、')">
              {{ s.name }}
            </el-tag>
            <span class="skill-level">{{ s.level }}</span>
          </span>
        </div>
        <div class="projects" v-if="(props.profile.projects || []).length">
          <div class="projects-label">项目经历（{{ props.profile.projects.length }} 个）</div>
          <div v-for="p in props.profile.projects" :key="p.name" class="project-item">
            <b>{{ p.name }}</b>
            <span class="project-role">[{{ p.role }}]</span>
            <el-tag v-for="t in p.techStack" :key="t" size="small" class="tech-tag">{{ t }}</el-tag>
          </div>
        </div>
        <el-button type="primary" class="gen-btn" @click="emit('generate', role, majorDomain)" :loading="props.processing">
          {{ props.bigtech ? '开始大厂挑战 →' : '开始生成面试题 →' }}
        </el-button>
        <el-button v-if="!props.bigtech" class="plan-btn" :loading="planLoading" @click="loadPlan">
          🎓 获取学习路线
        </el-button>
      </el-card>
    </template>

    <!-- 上传后选择大方向与岗位 -->
    <el-dialog v-model="pickVisible" title="请选择大方向与目标岗位" width="520px"
      :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false" align-center>
      <div class="pick-body">
        <div class="pick-row">
          <span class="pick-label">大方向</span>
          <el-select v-model="majorDomain" class="pick-select">
            <el-option label="计算机" value="计算机" />
            <el-option label="其他" value="其他" />
          </el-select>
        </div>
        <div class="pick-row">
          <span class="pick-label">目标岗位</span>
          <el-select v-if="!props.bigtech && majorDomain !== '其他'" v-model="role" class="pick-select">
            <el-option label="Java 后端" value="Java 后端" />
            <el-option label="软件测试" value="软件测试" />
            <el-option label="前端开发" value="前端开发" />
            <el-option label="AI 应用开发" value="AI 应用开发" />
          </el-select>
          <el-tag v-else-if="props.bigtech" type="danger" effect="plain" class="pick-select">AI 应用开发（大厂挑战）</el-tag>
          <span v-else class="pick-role-placeholder">其他专业不选岗位，AI 按简历出题</span>
        </div>
        <div class="pick-hint">
          选 <b>计算机</b> 方向：从精选题库出题，再结合你的项目定制场景题，练习更贴近真实面试；
          选 <b>其他</b> 专业也不用担心——AI 会照着你的简历帮你出题，一样有针对性的练习。
          岗位支持 <b>Java 后端、软件测试、前端开发、AI 应用开发</b>；大厂挑战使用 AI Agent 大厂真题库。
        </div>
      </div>
      <template #footer>
        <el-button @click="cancelPick">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="confirmPick">开始解析</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="planVisible" title="🎓 学习路线" width="780px" top="6vh">
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
        <el-empty v-else-if="!planLoading" description="暂无学习路线，请先上传简历解析" />
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.upload-view { max-width: 960px; margin: 0 auto; }
.mode-row { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }
.mode-btn { padding: 10px 22px; border-radius: var(--radius-pill); border: 1px solid var(--c-line); background: #fff; color: var(--c-text-2); font-size: 15px; font-weight: 600; cursor: pointer; transition: all 0.2s ease; }
.mode-btn:hover { border-color: var(--c-primary); color: var(--c-primary); }
.mode-btn.active { background: linear-gradient(180deg, var(--c-primary-2), var(--c-primary)); border-color: transparent; color: #fff; box-shadow: var(--shadow-brand); }
.mode-btn.challenge.active { background: linear-gradient(180deg, #ff8a3d, #ff5a1f); box-shadow: 0 8px 20px rgba(255, 90, 31, 0.3); }
.mode-hint { font-size: 13px; color: var(--c-text-3); }
.upload-inner { padding: 48px 0; }
.upload-icon { font-size: 60px; }
.upload-main { margin: 12px 0; font-size: 24px; font-weight: 700; letter-spacing: -0.02em; color: var(--c-text); }
.upload-sub { font-size: 15px; color: var(--c-text-3); }
.profile-card { margin-top: 32px; }
.profile-head { display: flex; align-items: center; gap: 12px; }
.profile-title { font-size: 18px; font-weight: 700; letter-spacing: -0.01em; margin-right: 4px; }
.skills { margin-top: 16px; display: flex; flex-wrap: wrap; gap: 10px; }
.skill-chip { display: inline-flex; align-items: center; gap: 6px; padding: 4px 12px 4px 8px; background: var(--c-bg-2); border-radius: var(--radius-pill); }
.skill-level { font-size: 12px; color: var(--c-text-3); }
.projects-label { margin: 20px 0 10px; font-size: 15px; font-weight: 600; color: var(--c-text-2); }
.project-item { margin-bottom: 10px; font-size: 15px; color: var(--c-text); }
.project-role { margin: 0 8px 0 6px; color: var(--c-text-3); }
.tech-tag { margin-right: 4px; }
.gen-btn { width: 100%; height: 54px; margin-top: 26px; font-size: 17px; font-weight: 600; }
.plan-btn { width: 100%; height: 50px; margin-top: 12px; font-size: 15px; font-weight: 600; background: #fff; border: 1px solid var(--c-line); }
.pick-body { display: flex; flex-direction: column; gap: 18px; }
.pick-row { display: flex; align-items: center; gap: 16px; }
.pick-label { width: 72px; font-size: 15px; font-weight: 600; color: var(--c-text-2); }
.pick-select { flex: 1; }
.pick-role-placeholder { flex: 1; color: var(--c-text-3); font-size: 14px; }
.pick-hint { padding: 12px 16px; font-size: 13px; line-height: 1.7; color: var(--c-text-2); background: var(--c-bg-2); border-radius: var(--radius-md); }
.pick-hint b { color: var(--c-text); }
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
@media (max-width: 640px) {
  .mode-row { flex-wrap: wrap; gap: 8px; }
  .mode-hint { width: 100%; }
  .upload-inner { padding: 32px 12px; }
  .upload-icon { font-size: 48px; }
  .upload-main { font-size: 19px; }
  .gen-btn { height: 50px; font-size: 16px; }
  .profile-head { flex-wrap: wrap; }
  .pick-row { flex-direction: column; align-items: flex-start; gap: 8px; }
  .pick-label { width: auto; }
  .pick-select { width: 100%; }
  .phase { padding: 14px 16px; }
}
.challenge-notice p { margin: 0 0 10px; font-size: 15px; line-height: 1.7; color: var(--c-text-2); }
.challenge-notice b { color: var(--c-text); }
.companies { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.company-tag { margin: 0; }
.challenge-hint { padding: 12px 16px; background: var(--c-bg-2); border-radius: var(--radius-md); font-size: 13px !important; }
</style>