const { getPaper, submitGrade, meApi } = require('../../utils/api')
const { STORAGE } = require('../../utils/config')
const { poll } = require('../../utils/poll')

function toast(text) {
  wx.showToast({ title: text, icon: 'none', duration: 2500 })
}

Page({
  data: {
    paperId: null,
    questions: [],
    answers: {},
    loading: true,
    progress: 0,
  },

  onLoad() {
    const token = wx.getStorageSync(STORAGE.TOKEN)
    if (!token) { wx.reLaunch({ url: '/pages/login/login' }); return }
    const paperId = wx.getStorageSync(STORAGE.PAPER_ID)
    if (!paperId) {
      toast('缺少试卷，请返回重新出题')
      this.setData({ loading: false, questions: [] })
      return
    }
    this.setData({ paperId, answers: this.loadSaved(paperId) })
    this.loadQuestions(paperId)
  },

  storageKey(paperId) {
    return `quizAnswers-${paperId}`
  },

  loadSaved(paperId) {
    try {
      return JSON.parse(wx.getStorageSync(this.storageKey(paperId)) || '{}')
    } catch (e) { return {} }
  },

  persist() {
    try {
      wx.setStorageSync(this.storageKey(this.data.paperId), JSON.stringify(this.data.answers))
    } catch (e) { /* 存储满则忽略 */ }
  },

  clearSaved() {
    wx.removeStorageSync(this.storageKey(this.data.paperId))
  },

  keyOf(q) {
    return q._key || ((q.store || 'bank') + '-' + q.id)
  },

  loadQuestions(paperId) {
    const load = (qs) => {
      const decorated = qs.map((q) => {
        q._key = (q.store || 'bank') + '-' + q.id
        q._diffLabel = { BASIC: '基础', INTERMEDIATE: '进阶', ADVANCED: '深入' }[q.difficulty] || q.difficulty
        q._diffColor = { BASIC: 'basic', INTERMEDIATE: 'inter', ADVANCED: 'adv' }[q.difficulty] || 'info'
        q._storeLabel = { project: '项目实战', bigtech: '大厂真题' }[q.store] || q.skill || '题库'
        return q
      })
      this.setData({ questions: decorated, loading: false })
      this.updateProgress()
    }
    const stored = wx.getStorageSync(STORAGE.QUESTIONS)
    if (stored && stored.length) {
      load(stored)
      return
    }
    poll(() => getPaper(paperId), (p) => p.status === 'DONE', 300, 2000, '试卷加载超时，请稍后重试')
      .then((p) => {
        wx.setStorageSync(STORAGE.QUESTIONS, p.questions || [])
        load(p.questions || [])
      })
      .catch((e) => {
        this.setData({ loading: false })
        toast((e && e.message) || '加载试卷失败')
      })
  },

  onInput(e) {
    const q = e.currentTarget.dataset.q
    if (!q) return
    const key = this.keyOf(q)
    const answers = Object.assign({}, this.data.answers)
    answers[key] = e.detail.value
    this.setData({ answers })
    this.persist()
    this.updateProgress()
  },

  updateProgress() {
    const done = Object.keys(this.data.answers)
      .filter((k) => (this.data.answers[k] || '').trim()).length
    const total = this.data.questions.length || 1
    this.setData({ progress: Math.round((done / total) * 100) })
  },

  submit() {
    const unanswered = this.data.questions.filter((q) => !(this.data.answers[this.keyOf(q)] || '').trim()).length
    if (unanswered > 0) {
      wx.showModal({
        title: '交卷确认',
        content: `还有 ${unanswered} 题未作答，未作答的题将按 0 分计。确定交卷吗？`,
        confirmText: '确定交卷',
        confirmColor: '#f56c6c',
        success: (r) => { if (r.confirm) this.doSubmit() },
      })
    } else {
      this.doSubmit()
    }
  },

  doSubmit() {
    const list = this.data.questions.map((q) => ({
      questionId: q.id,
      store: q.store || 'bank',
      fullScore: q.fullScore || 20,
      answerText: this.data.answers[this.keyOf(q)] || '',
    }))
    wx.showLoading({ title: 'AI 判分中…（约1分钟）', mask: true })
    submitGrade(list)
      .then((body) => {
        wx.setStorageSync(STORAGE.PAPER_ID, body.paperId || this.data.paperId)
        return poll(
          () => meApi.submissionDetail(body.submissionId),
          (d) => d.status === 'DONE' && d.report,
          300,
          3000,
          '判分超时，请稍后重试',
        )
      })
      .then((d) => {
        wx.setStorageSync(STORAGE.REPORT, d.report)
        this.clearSaved()
        wx.hideLoading()
        wx.redirectTo({ url: '/pages/report/report' })
      })
      .catch((e) => {
        wx.hideLoading()
        toast((e && e.message) || '评卷失败，请重试')
      })
  },

  backHome() {
    wx.switchTab({ url: '/pages/index/index' })
  },
})