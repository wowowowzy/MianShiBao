/**
 * 后端接口封装（与 web 端 src/api.js 契约保持一致）。
 * 所有方法返回 Promise，resolve 的是 HTTP body 本身（即 web 端 res.data）。
 */
const { request, upload } = require('./request')

function qs(obj) {
  const parts = []
  for (const k in obj) {
    const v = obj[k]
    if (v != null && v !== '') {
      parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v))
    }
  }
  return parts.length ? '?' + parts.join('&') : ''
}

/* ---------- 认证 ---------- */
/** 微信静默登录：{ code } → { token, username, admin, isNew } */
const wechatLogin = (code) => request({ url: '/auth/wechat-login', method: 'POST', data: { code } })

/* ---------- 简历解析 ---------- */
/** 上传简历 → { sourceType, recordId }，解析在后台进行，轮询 me/resumes 取画像 */
const parseResume = (filePath, meta) => upload('/resume/parse', filePath, meta)

/* ---------- 出题 ---------- */
/** 按画像组卷 → { paperId }；majorDomain 为专业大类（计算机/其他）：其他专业后端纯 LLM 出题不走 RAG */
const generateQuestions = (profile, role, resumeId, majorDomain) =>
  request({
    url: '/questions/generate' + qs({ maxQuestions: 5, role, resumeId, majorDomain }),
    method: 'POST',
    data: profile,
  })
/** 大厂挑战：4×25，AI 应用开发题库 */
const bigtechGenerate = (profile, resumeId) =>
  request({ url: '/questions/bigtech' + qs({ resumeId }), method: 'POST', data: profile })
/** 轮询试卷：{ status, questions }（DONE 后含答题视图） */
const getPaper = (paperId) => request({ url: '/questions/paper/' + paperId })
/** 复用旧卷重新出题 → { paperId } */
const reuseApi = (paperId) => request({ url: '/questions/reuse', method: 'POST', data: { paperId } })

/* ---------- 交卷 ---------- */
/** 交卷评分 → { submissionId }（异步，轮询 me/submissions/{id} 取报告） */
const submitGrade = (answers) => request({ url: '/submissions/grade', method: 'POST', data: { answers } })

/* ---------- 学习路线 ---------- */
/** 基于画像生成一次分阶段学习方案（同步，可能较慢） */
const learningPlan = (profile) =>
  request({ url: '/me/learning-plan', method: 'POST', data: { profile }, timeout: 200000 })

/* ---------- 个人中心 ---------- */
const meApi = {
  me: () => request({ url: '/me' }),
  resumes: () => request({ url: '/me/resumes' }),
  deleteResume: (id) => request({ url: '/me/resumes/' + id, method: 'DELETE' }),
  papers: () => request({ url: '/me/papers' }),
  submissions: () => request({ url: '/me/submissions' }),
  submissionDetail: (id) => request({ url: '/me/submissions/' + id }),
  genResumePlan: (id) => request({ url: '/me/resumes/' + id + '/learning-plan', method: 'POST' }),
  resumePlan: (id) => request({ url: '/me/resumes/' + id + '/learning-plan' }),
}
/** 设置当前用户昵称（微信昵称展示用） */
const setNickname = (nickname) => request({ url: '/me/nickname', method: 'POST', data: { nickname } })

module.exports = { wechatLogin, parseResume, generateQuestions, bigtechGenerate, getPaper, reuseApi, submitGrade, learningPlan, meApi, setNickname }