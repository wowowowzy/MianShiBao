const { meApi, reuseApi, getPaper } = require('../../utils/api')
const { STORAGE } = require('../../utils/config')
const { poll } = require('../../utils/poll')

function toast(text) {
  wx.showToast({ title: text, icon: 'none', duration: 2500 })
}

Page({
  data: {
    username: '',
    displayName: '',
    stats: { resumes: 0, papers: 0, submissions: 0 },
    activeTab: 'resumes',
    resumes: [],
    submissions: [],
    papers: [],
    loading: false,
    reusingPaperId: null,

    resumeVisible: false,
    curResume: null,

    planVisible: false,
    planLoading: false,
    plan: null,
  },

  onLoad() {
    const token = wx.getStorageSync(STORAGE.TOKEN)
    if (!token) { wx.reLaunch({ url: '/pages/login/login' }); return }
  },

  onShow() {
    if (wx.getStorageSync(STORAGE.TOKEN)) {
      this.loadAll()
    }
  },

  onUnload() {
    if (this._timer) clearTimeout(this._timer)
  },

  onHide() {
    if (this._timer) clearTimeout(this._timer)
  },

  loadAll(silent) {
    if (!silent) this.setData({ loading: true })
    meApi.me()
      .catch(() => null)
      .then((me) => {
        return Promise.all([
          Promise.resolve(me),
          meApi.resumes(),
          meApi.papers(),
          meApi.submissions(),
        ])
      })
      .then(([me, resu, pap, sub]) => {
        const resumes = (resu.items || []).map((r) => Object.assign({}, r, { _time: this.fmt(r.createdAt) }))
        const submissions = (sub.items || []).map((s) => Object.assign({}, s, {
          _time: this.fmt(s.createdAt),
          _pctText: this.pctText(s.pct),
          _pctClass: this.pctClass(s.pct),
        }))
        const papers = (pap.items || []).map((p) => Object.assign({}, p, { _time: this.fmt(p.createdAt) }))
        // 昵称优先展示，未设置则回退到用户名
        const nickname = (me && me.nickname) || wx.getStorageSync(STORAGE.NICKNAME) || ''
        if (nickname) wx.setStorageSync(STORAGE.NICKNAME, nickname)
        const username = (me && me.username) || wx.getStorageSync(STORAGE.USERNAME) || ''
        this.setData({
          username,
          displayName: nickname || username || '微信用户',
          stats: {
            resumes: resu.count || 0,
            papers: pap.count || 0,
            submissions: sub.count || 0,
          },
          resumes,
          papers,
          submissions,
        })
        this.schedulePoll()
      })
      .catch((e) => toast((e && e.message) || '加载个人数据失败'))
      .finally(() => this.setData({ loading: false }))
  },

  schedulePoll() {
    const hasProcessing =
      this.data.resumes.some((r) => r.status === 'PROCESSING') ||
      this.data.submissions.some((s) => s.status === 'PROCESSING') ||
      this.data.papers.some((p) => p.status === 'PROCESSING')
    if (this._timer) clearTimeout(this._timer)
    if (hasProcessing) this._timer = setTimeout(() => this.loadAll(true), 5000)
  },

  onTab(e) {
    this.setData({ activeTab: e.currentTarget.dataset.tab })
  },

  fmt(ts) {
    if (!ts) return ''
    return String(ts).replace('T', ' ').replace(/\.\d+$/, '').slice(0, 16)
  },

  /* ---------- 解析记录 ---------- */
  viewSkills(e) {
    const r = e.currentTarget.dataset.r
    this.setData({ curResume: r, resumeVisible: true })
  },
  closeResume() {
    this.setData({ resumeVisible: false })
  },

  /** 简历画像弹层内「用这份简历出题」 */
  useCurrent() {
    this.closeResume()
    this.useProfile()
  },

  useProfile(e) {
    const r = e && e.currentTarget && e.currentTarget.dataset ? e.currentTarget.dataset.r : this.data.curResume
    if (!r) return
    if (!r.profile) { toast('该简历缺少画像，无法直接出题，请重新上传'); return }
    wx.setStorageSync(STORAGE.PROFILE, r.profile)
    wx.setStorageSync(STORAGE.RESUME_ID, r.id)
    wx.setStorageSync(STORAGE.PENDING_GENERATE, '1')
    wx.switchTab({ url: '/pages/index/index' })
  },

  removeResume(e) {
    const r = e.currentTarget.dataset.r
    wx.showModal({
      title: '删除解析记录',
      content: '删除后该份简历的解析记录、学习路线及由它生成的试卷与报告都将被永久清除，且不可恢复。确定删除吗？',
      confirmText: '确认删除',
      confirmColor: '#f56c6c',
      success: (res) => {
        if (!res.confirm) return
        meApi.deleteResume(r.id)
          .then(() => {
            this.setData({ resumes: this.data.resumes.filter((x) => x.id !== r.id) })
            this.setData({ 'stats.resumes': this.data.resumes.length })
            toast('解析记录已删除')
          })
          .catch((err) => toast((err && err.message) || '删除失败，请重试'))
      },
    })
  },

  openPlan(e) {
    const r = e.currentTarget.dataset.r
    this.setData({ planVisible: true, planLoading: true, plan: null })
    meApi.genResumePlan(r.id)
      .catch((err) => { throw new Error((err && err.message) || '学习路线生成失败') })
      .then(() => poll(
        () => meApi.resumePlan(r.id),
        (d) => d.status === 'DONE' && d.plan,
        200,
        3000,
        '学习路线生成超时，请稍后重试',
      ))
      .then((d) => this.setData({ plan: d.plan, planLoading: false }))
      .catch((err) => {
        this.setData({ planLoading: false, planVisible: false })
        toast((err && err.message) || '学习路线生成失败')
      })
  },
  closePlan() {
    this.setData({ planVisible: false })
  },

  goHome() {
    wx.switchTab({ url: '/pages/index/index' })
  },

  /* ---------- 作答记录 ---------- */
  viewSubmission(e) {
    const s = e.currentTarget.dataset.s
    wx.showLoading({ title: '加载报告…', mask: true })
    poll(
      () => meApi.submissionDetail(s.id),
      (d) => d.status === 'DONE' && d.report,
      300,
      3000,
      '报告加载超时，请稍后重试',
    )
      .then((d) => {
        wx.setStorageSync(STORAGE.REPORT, d.report)
        wx.hideLoading()
        wx.navigateTo({ url: '/pages/report/report?from=me' })
      })
      .catch((err) => {
        wx.hideLoading()
        toast((err && err.message) || '加载评分报告失败')
      })
  },

  /* ---------- 出题记录 ---------- */
  answerPaper(e) {
    const p = e.currentTarget.dataset.p
    wx.setStorageSync(STORAGE.PAPER_ID, p.id)
    wx.removeStorageSync(STORAGE.QUESTIONS)
    if (p.status === 'DONE') {
      wx.navigateTo({ url: '/pages/quiz/quiz' })
    } else if (p.status === 'PROCESSING') {
      wx.navigateTo({ url: '/pages/quiz/quiz' })
    } else {
      toast('该试卷尚未完成出题')
    }
  },

  reusePaper(e) {
    const p = e.currentTarget.dataset.p
    if (this.data.reusingPaperId === p.id) return
    this.setData({ reusingPaperId: p.id })
    wx.showLoading({ title: 'AI 组卷中…（约1分钟）', mask: true })
    reuseApi(p.id)
      .then((body) => {
        wx.setStorageSync(STORAGE.PAPER_ID, body.paperId)
        return poll(() => getPaper(body.paperId), (pp) => pp.status === 'DONE', 300, 2000, '重新出题超时，请稍后重试')
      })
      .then((pp) => {
        wx.setStorageSync(STORAGE.QUESTIONS, pp.questions || [])
        wx.hideLoading()
        this.setData({ reusingPaperId: null })
        wx.navigateTo({ url: '/pages/quiz/quiz' })
      })
      .catch((err) => {
        wx.hideLoading()
        this.setData({ reusingPaperId: null })
        toast((err && err.message) || '重新出题失败，请重试')
      })
  },

  pctClass(pct) {
    if (pct == null) return 'low'
    return pct >= 80 ? 'good' : pct >= 50 ? 'mid' : 'low'
  },
  pctText(pct) {
    return pct == null ? '—' : Math.round(Number(pct)) + '%'
  },
})