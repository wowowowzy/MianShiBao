import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 180000 })

/* ---------------------------------- 全局拦截 ---------------------------------- */
// 请求：携带已登录 token
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// 响应：401（登录/注册接口除外）→ 清除本地登录态并跳转登录页
let redirectingToLogin = false // 并发 401 只跳一次，避免多个请求各自 push 造成反复跳转
http.interceptors.response.use(
  (res) => res,
  (err) => {
    const status = err.response?.status
    const url = err.config?.url || ''
    const isAuthReq = url.startsWith('/auth/')
    if (status === 401 && !isAuthReq) {
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      localStorage.removeItem('admin')
      window.dispatchEvent(new Event('auth-changed'))
      if (redirectingToLogin) return Promise.reject(err)
      redirectingToLogin = true
      // 动态引入 router，避免循环依赖
      import('./router/index.js')
        .then(({ default: router }) => {
          if (router.currentRoute.value.path !== '/login') router.push('/login')
        })
        .catch(() => {
          window.location.hash = '#/login'
        })
        .finally(() => {
          redirectingToLogin = false
        })
    }
    return Promise.reject(err)
  }
)

/* ---------------------------------- 现有接口（契约不变） ---------------------------------- */

/** 上传简历 → 结构化能力画像（meta: {majorDomain, role} 可选方向选择，随请求落库） */
export function parseResume(file, meta = {}) {
  const fd = new FormData()
  fd.append('file', file)
  if (meta.majorDomain) fd.append('majorDomain', meta.majorDomain)
  if (meta.role) fd.append('role', meta.role)
  return http.post('/resume/parse', fd)
}

/** 按能力画像组卷（答题视图，不含答案）；role 为岗位，resumeId 记录来源简历用于级联删除，
 * majorDomain 为专业大类（计算机/其他）：其他专业后端走纯 LLM 出题，不走 RAG 题库 */
export function generateQuestions(profile, role = 'Java 后端', resumeId = null, majorDomain = null) {
  const params = { maxQuestions: 5 }
  if (role) params.role = role
  if (resumeId) params.resumeId = resumeId
  if (majorDomain) params.majorDomain = majorDomain
  return http.post('/questions/generate', profile, { params })
}

/** 大厂挑战：只用大厂题库出 4 题（每题 25 分，题干带公司） */
export function bigtechGenerate(profile, resumeId = null) {
  const params = {}
  if (resumeId) params.resumeId = resumeId
  return http.post('/questions/bigtech', profile, { params })
}

/** 轮询试卷：{ status, questions }（DONE 后含作答视图，不含答案） */
export function getPaper(paperId) {
  return http.get(`/questions/paper/${paperId}`)
}

/** 交卷评分（要点判分） */
export function submitGrade(answers) {
  return http.post('/submissions/grade', { answers })
}

/* ---------------------------------- 认证 ---------------------------------- */

export const authApi = {
  /** 获取图形验证码 → { uuid, img } */
  captcha() {
    return http.get('/auth/captcha')
  },
  /** 注册 { username, password, uuid, captcha } → { id, username } */
  register(data) {
    return http.post('/auth/register', data)
  },
  /** 登录 { username, password, uuid, captcha } → { token, username, admin } */
  login(data) {
    return http.post('/auth/login', data)
  },
}

/* ---------------------------------- 个人中心 ---------------------------------- */

export const meApi = {
  /** 当前用户信息 → { username } */
  me() {
    return http.get('/me')
  },
  /** 解析记录 → { count, items:[{id, sourceType, textLength, profile, createdAt}] } */
  resumes() {
    return http.get('/me/resumes')
  },
  /** 删除一条简历解析记录（连同其学习路线），返回 { id, message } */
  deleteResume(id) {
    return http.delete(`/me/resumes/${id}`)
  },
  /** 出题（试卷）记录 → { count, items:[{id, questionCount, createdAt}] } */
  papers() {
    return http.get('/me/papers')
  },
  /** 作答记录 → { count, items:[{id, paperId, totalScore, fullScore, pct, createdAt}] } */
  submissions() {
    return http.get('/me/submissions')
  },
  /** 单次作答详情 → { id, status, report:{ totalScore, totalFullScore, items } } */
  submissionDetail(id) {
    return http.get(`/me/submissions/${id}`)
  },
  /** 触发（异步）生成某简历的学习路线 */
  genResumePlan(id) {
    return http.post(`/me/resumes/${id}/learning-plan`)
  },
  /** 查询学习路线状态/内容 → { status, plan, errorMsg } */
  resumePlan(id) {
    return http.get(`/me/resumes/${id}/learning-plan`)
  },
}

/* ---------------------------------- 复用 ---------------------------------- */

/** 基于历史试卷重新出题 → 同 generate：{ paperId, questions } */
export function reuseApi(paperId) {
  return http.post('/questions/reuse', { paperId })
}

/* ---------------------------------- 学习路线 ---------------------------------- */

/** 生成分阶段学习方案（一次 LLM 调用，输入能力画像 + 服务端自动拉近期答题弱项） */
export function learningPlan(profile) {
  return http.post('/me/learning-plan', { profile })
}

/* ---------------------------------- 管理后台 ---------------------------------- */

export const adminApi = {
  /** 大屏概览 → { totals, today, trend, userUsage, defaultQuota } */
  overview() {
    return http.get('/admin/overview')
  },
  /** 用户列表 → { count, items:[{id,username,admin,banned,quota,todayUsed,createdAt}] } */
  users() {
    return http.get('/admin/users')
  },
  /** 简历解析记录（含画像） */
  resumes(limit = 200) {
    return http.get('/admin/resumes', { params: { limit } })
  },
  /** 代注册账号 { username, password, admin } */
  createUser(data) {
    return http.post('/admin/users', data)
  },
  /** 封禁/解封 */
  setBanned(id, value) {
    return http.post(`/admin/users/${id}/ban`, { value })
  },
  /** 调整今日剩余次数 */
  setQuota(id, quota) {
    return http.post(`/admin/users/${id}/quota`, { quota })
  },
  /** 设为/取消管理员 */
  setAdmin(id, value) {
    return http.post(`/admin/users/${id}/admin`, { value })
  },
}