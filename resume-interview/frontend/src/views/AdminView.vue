<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import { adminApi } from '../api'

const router = useRouter()

const loading = ref(false)
const totals = ref({})
const users = ref([])
const resumes = ref([])
const today = ref('')
const defaultQuota = ref(5)

const trendEl = ref(null)
const rankEl = ref(null)
let trendChart = null
let rankChart = null

const levelColor = { 精通: 'danger', 熟悉: 'success', 了解: 'info' }

function fmt(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return String(ts).replace('T', ' ').slice(0, 16)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function renderTrend(list) {
  if (!trendEl.value) return
  trendChart = trendChart || echarts.init(trendEl.value)
  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 44, right: 20, top: 30, bottom: 32 },
    xAxis: { type: 'category', boundaryGap: false, data: list.map((x) => x.day.slice(5)) },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{
      name: '使用次数',
      type: 'line',
      smooth: true,
      symbolSize: 7,
      data: list.map((x) => x.used),
      itemStyle: { color: '#0071e3' },
      lineStyle: { width: 3 },
      areaStyle: {
        color: {
          type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [{ offset: 0, color: 'rgba(10,132,255,0.35)' }, { offset: 1, color: 'rgba(10,132,255,0)' }],
        },
      },
    }],
  })
}

function renderRank(list) {
  if (!rankEl.value) return
  const top = [...list].sort((a, b) => b.todayUsed - a.todayUsed).slice(0, 10).reverse()
  rankChart = rankChart || echarts.init(rankEl.value)
  rankChart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 96, right: 24, top: 20, bottom: 24 },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: { type: 'category', data: top.map((u) => u.username) },
    series: [{
      name: '今日使用',
      type: 'bar',
      barWidth: 14,
      data: top.map((u) => u.todayUsed),
      itemStyle: { color: '#0a84ff', borderRadius: [0, 7, 7, 0] },
    }],
  })
}

async function loadAll() {
  loading.value = true
  try {
    const [ov, rs] = await Promise.all([adminApi.overview(), adminApi.resumes(200)])
    const d = ov.data || {}
    totals.value = d.totals || {}
    users.value = d.userUsage || []
    today.value = d.today || ''
    defaultQuota.value = d.defaultQuota ?? 5
    resumes.value = rs.data.items || []
    await nextTick()
    renderTrend(d.trend || [])
    renderRank(d.userUsage || [])
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '加载管理数据失败')
  } finally {
    loading.value = false
  }
}

/* ---------------------------------- 用户操作 ---------------------------------- */
async function toggleBan(u) {
  try {
    await adminApi.setBanned(u.id, !u.banned)
    ElMessage.success(u.banned ? '已解封' : '已封禁')
    loadAll()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

async function editQuota(u) {
  try {
    const { value } = await ElMessageBox.prompt(`设置「${u.username}」今日剩余使用次数`, '调整次数', {
      inputValue: String(u.quota),
      inputPattern: /^\d+$/,
      inputErrorMessage: '请输入非负整数',
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    })
    await adminApi.setQuota(u.id, Number(value))
    ElMessage.success('已更新')
    loadAll()
  } catch {
    /* cancelled */
  }
}

async function toggleAdmin(u) {
  try {
    await ElMessageBox.confirm(
      u.admin ? `取消「${u.username}」的管理员权限？` : `将「${u.username}」设为管理员？`,
      '权限变更', { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' })
  } catch {
    return
  }
  try {
    await adminApi.setAdmin(u.id, !u.admin)
    ElMessage.success('已更新')
    loadAll()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

/* ---------------------------------- 代注册 ---------------------------------- */
const createVisible = ref(false)
const createForm = ref({ username: '', password: '', admin: false })
function openCreate() {
  createForm.value = { username: '', password: '', admin: false }
  createVisible.value = true
}
async function submitCreate() {
  const f = createForm.value
  if (!f.username || !f.password) {
    ElMessage.warning('请填写用户名与密码')
    return
  }
  try {
    await adminApi.createUser(f)
    ElMessage.success('账号已创建')
    createVisible.value = false
    loadAll()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '创建失败')
  }
}

function onResize() {
  trendChart?.resize()
  rankChart?.resize()
}

onMounted(() => {
  loadAll()
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  trendChart?.dispose()
  rankChart?.dispose()
})
</script>

<template>
  <div class="admin-view" v-loading="loading">
    <div class="admin-head">
      <div>
        <div class="admin-title">管理后台</div>
        <div class="admin-sub">数据截至 {{ today }} · 普通用户每天 {{ defaultQuota }} 次，管理员不限</div>
      </div>
      <div class="head-actions">
        <el-button @click="loadAll">刷新</el-button>
        <el-button type="primary" @click="openCreate">代注册账号</el-button>
        <el-button @click="router.push('/')">返回主页</el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-num">{{ totals.users ?? '—' }}</div>
        <div class="stat-label">用户总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ totals.resumes ?? '—' }}</div>
        <div class="stat-label">简历解析</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ totals.papers ?? '—' }}</div>
        <div class="stat-label">生成试卷</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ totals.submissions ?? '—' }}</div>
        <div class="stat-label">完成作答</div>
      </div>
      <div class="stat-card danger">
        <div class="stat-num">{{ totals.banned ?? '—' }}</div>
        <div class="stat-label">封禁用户</div>
      </div>
    </div>

    <div class="chart-row">
      <el-card shadow="never" class="chart-card">
        <div class="card-title">近 14 天使用趋势</div>
        <div ref="trendEl" class="chart"></div>
      </el-card>
      <el-card shadow="never" class="chart-card">
        <div class="card-title">今日用户使用排行（TOP 10）</div>
        <div ref="rankEl" class="chart"></div>
      </el-card>
    </div>

    <el-card shadow="never" class="block">
      <div class="card-title">用户管理（{{ users.length }}）</div>
      <el-table :data="users" size="default" style="width: 100%">
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.admin" type="warning" effect="plain" size="small">管理员</el-tag>
            <el-tag v-else type="info" effect="plain" size="small">普通用户</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.banned" type="danger" effect="plain" size="small">已封禁</el-tag>
            <el-tag v-else type="success" effect="plain" size="small">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="今日剩余" width="100">
          <template #default="{ row }">
            <span :class="row.quota > 0 ? 'ok' : 'zero'">{{ row.admin ? '∞' : row.quota }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="todayUsed" label="今日已用" width="100" />
        <el-table-column label="注册时间" min-width="150">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button class="soft-btn" size="small" @click="editQuota(row)">调次数</el-button>
            <el-button class="soft-btn" size="small" @click="toggleAdmin(row)">
              {{ row.admin ? '取消管理员' : '设管理员' }}
            </el-button>
            <el-button :type="row.banned ? 'success' : 'danger'" size="small" plain @click="toggleBan(row)">
              {{ row.banned ? '解封' : '封禁' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="block">
      <div class="card-title">简历解析记录（{{ resumes.length }}）</div>
      <el-table :data="resumes" size="default" style="width: 100%">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="resume-detail" v-if="row.profile">
              <el-descriptions :column="3" size="small">
                <el-descriptions-item label="姓名">{{ row.profile.name || '—' }}</el-descriptions-item>
                <el-descriptions-item label="目标岗位">{{ row.profile.targetRole || '—' }}</el-descriptions-item>
                <el-descriptions-item label="经验">{{ row.profile.yearsExperience ?? '—' }} 年</el-descriptions-item>
              </el-descriptions>
              <div class="detail-label">技能（{{ (row.profile.skills || []).length }}）</div>
              <div class="skills">
                <el-tag v-for="s in row.profile.skills || []" :key="s.name" size="small"
                  :type="levelColor[s.level] || 'primary'" effect="plain" class="skill-tag">
                  {{ s.name }} · {{ s.level }}
                </el-tag>
              </div>
              <template v-if="(row.profile.projects || []).length">
                <div class="detail-label">项目经历</div>
                <div v-for="p in row.profile.projects" :key="p.name" class="proj">
                  <b>{{ p.name }}</b> <span class="proj-role">[{{ p.role }}]</span>
                  <el-tag v-for="t in p.techStack || []" :key="t" size="small" class="tech">{{ t }}</el-tag>
                  <div class="proj-desc">{{ p.description }}</div>
                </div>
              </template>
            </div>
            <el-empty v-else description="无画像数据" :image-size="60" />
          </template>
        </el-table-column>
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="username" label="用户" min-width="120" />
        <el-table-column prop="sourceType" label="来源" width="90" />
        <el-table-column prop="textLength" label="文本长度" width="100" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'DONE'" type="success" effect="plain" size="small">完成</el-tag>
            <el-tag v-else-if="row.status === 'PROCESSING'" type="primary" effect="plain" size="small">解析中</el-tag>
            <el-tag v-else type="danger" effect="plain" size="small">失败</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间" min-width="150">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="代注册账号" width="440px">
      <el-form label-position="top">
        <el-form-item label="用户名（3-20 位字母/数字/下划线）">
          <el-input v-model.trim="createForm.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item label="初始密码">
          <el-input v-model.trim="createForm.password" placeholder="密码" show-password />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="createForm.admin">创建为管理员</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-view { max-width: 1200px; margin: 0 auto; padding: 32px 20px 64px; }
.admin-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; margin-bottom: 22px; }
.admin-title { font-size: clamp(24px, 3vw, 32px); font-weight: 700; letter-spacing: -0.03em; }
.admin-sub { margin-top: 8px; font-size: 14px; color: var(--c-text-3); }
.head-actions { display: flex; gap: 10px; }
.stat-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 16px; margin-bottom: 20px; }
.stat-card { background: var(--c-surface); border-radius: var(--radius-lg); box-shadow: var(--shadow-2); padding: 22px 24px; }
.stat-num { font-size: 38px; font-weight: 700; letter-spacing: -0.04em; color: var(--c-primary); }
.stat-card.danger .stat-num { color: var(--c-danger); }
.stat-label { margin-top: 4px; font-size: 14px; color: var(--c-text-2); }
.chart-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 20px; }
.chart-card { border-radius: var(--radius-lg); }
.chart { height: 280px; }
.card-title { font-size: 16px; font-weight: 700; letter-spacing: -0.01em; margin-bottom: 12px; }
.block { border-radius: var(--radius-lg); margin-bottom: 20px; }
.soft-btn {
  background: rgba(0, 113, 227, 0.1) !important;
  border: none !important;
  color: var(--c-primary) !important;
  box-shadow: none !important;
}
.soft-btn:hover { background: rgba(0, 113, 227, 0.18) !important; }
.ok { color: var(--c-success); font-weight: 700; }
.zero { color: var(--c-danger); font-weight: 700; }
.resume-detail { padding: 6px 12px 12px; }
.detail-label { margin: 12px 0 8px; font-size: 14px; font-weight: 700; color: var(--c-text-2); }
.skills { display: flex; flex-wrap: wrap; gap: 8px; }
.skill-tag { margin: 0; }
.proj { margin-bottom: 8px; font-size: 14px; }
.proj-role { color: var(--c-text-3); margin: 0 8px; }
.tech { margin-right: 4px; }
.proj-desc { margin-top: 4px; color: var(--c-text-2); line-height: 1.6; }
@media (max-width: 900px) {
  .stat-row { grid-template-columns: repeat(2, 1fr); }
  .chart-row { grid-template-columns: 1fr; }
}
@media (max-width: 900px) {
  .admin-head { flex-direction: column; align-items: flex-start; }
  .head-actions { flex-wrap: wrap; }
}
@media (max-width: 560px) {
  .admin-view { padding: 20px 12px 48px; }
  .stat-num { font-size: 30px; }
  .chart { height: 220px; }
  .stat-row { gap: 12px; }
}
</style>