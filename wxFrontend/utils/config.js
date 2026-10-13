/**
 * 全局配置
 * BASE_URL：后端 API 网关地址（小程序 request / uploadFile 的合法域名需在微信公众平台后台
 * 「开发 → 开发管理 → 开发设置 → 服务器域名」中添加：request 合法域名 https://wowowzy.art）
 */
module.exports = {
  BASE_URL: 'https://wowowzy.art/api',
  // BASE_URL: 'http://127.0.0.1:8080/api',
  // 本地缓存 key 集中管理，避免散落
  STORAGE: {
    TOKEN: 'token',
    USERNAME: 'username',
    ADMIN: 'admin',
    NICKNAME: 'nickname',
    NICKNAME_PROMPTED: 'nicknamePrompted',
    PROFILE: 'profile',
    RESUME_ID: 'resumeId',
    PAPER_ID: 'paperId',
    QUESTIONS: 'paperQuestions',
    REPORT: 'report',
    NOTICE_SEEN: 'notice-seen',
    PENDING_GENERATE: 'pendingGenerate',
  },
  // 岗位列表
  ROLES: ['Java 后端', '软件测试', '前端开发', 'AI 应用开发'],
  MAJOR_DOMAINS: ['计算机', '其他'],
}