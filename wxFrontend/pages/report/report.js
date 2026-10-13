const { STORAGE } = require('../../utils/config')

Page({
  data: {
    report: null,
    pct: 0,
    scoreColor: '#67c23a',
    expanded: {},
    items: [],
    fromMe: false,
  },

  onLoad(options) {
    const token = wx.getStorageSync(STORAGE.TOKEN)
    if (!token) { wx.reLaunch({ url: '/pages/login/login' }); return }
    // from=me：从「我的」查看历史报告进来 → 底部按钮显示「返回我的」且不清本地缓存
    this.setData({ fromMe: !!(options && options.from === 'me') })
    const report = wx.getStorageSync(STORAGE.REPORT)
    if (!report) { this.setData({}); return }
    const totalFull = report.totalFullScore || 1
    const pct = Math.round((report.totalScore / totalFull) * 100 || 0)
    const scoreColor = pct >= 80 ? '#67c23a' : pct >= 50 ? '#e6a23c' : '#f56c6c'
    const items = (report.items || []).map((it, i) => {
      const hit = (it.points || []).filter((p) => p.hit).length
      return Object.assign({}, it, {
        index: i,
        _diffLabel: { BASIC: '基础', INTERMEDIATE: '进阶', ADVANCED: '深入' }[it.difficulty] || it.difficulty,
        _diffColor: { BASIC: 'basic', INTERMEDIATE: 'inter', ADVANCED: 'adv' }[it.difficulty] || 'info',
        _scoreClass: it.score >= it.fullScore && it.fullScore > 0 ? 'full' : (it.score === 0 ? 'zero' : 'mid'),
        _hitCount: hit,
      })
    })
    this.setData({ report, pct, scoreColor, items })
  },

  toggle(e) {
    const i = e.currentTarget.dataset.i
    const expanded = Object.assign({}, this.data.expanded)
    expanded[i] = !expanded[i]
    this.setData({ expanded })
  },

  restart() {
    // 从「我的」看历史报告进来：只回首页，不清本地答案/报告缓存
    if (this.data.fromMe) {
      wx.switchTab({ url: '/pages/index/index' })
      return
    }
    wx.removeStorageSync(STORAGE.REPORT)
    wx.removeStorageSync(STORAGE.PAPER_ID)
    wx.removeStorageSync(STORAGE.QUESTIONS)
    wx.switchTab({ url: '/pages/index/index' })
  },
})