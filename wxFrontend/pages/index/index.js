const {
  parseResume,
  generateQuestions,
  bigtechGenerate,
  getPaper,
  learningPlan,
  meApi,
  setNickname,
} = require('../../utils/api')
const { STORAGE, ROLES, MAJOR_DOMAINS } = require('../../utils/config')
const { poll } = require('../../utils/poll')

function toast(text) {
  wx.showToast({ title: text, icon: 'none', duration: 2500 })
}

Page({
  data: {
    mode: 'normal',            // normal | bigtech
    profile: null,             // 能力画像
    resumeId: null,            // 当前画像来源简历记录 id
    processing: false,
    uploading: false,

    majorDomain: '计算机',
    role: 'Java 后端',
    roles: ROLES,
    majorDomains: MAJOR_DOMAINS,

    noticeVisible: false,
    planVisible: false,
    planLoading: false,
    plan: null,

    nicknameVisible: false,    // 首次登录设置昵称（选填）
    nicknameInput: '',
    nicknameSaving: false,
  },

  onLoad() {
    const token = wx.getStorageSync(STORAGE.TOKEN)
    if (!token) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.reloadProfile()
    if (!wx.getStorageSync(STORAGE.NOTICE_SEEN)) {
      this.setData({ noticeVisible: true })
    }
  },

  onShow() {
    if (!wx.getStorageSync(STORAGE.TOKEN)) return
    this.reloadProfile()
    // 首次登录弹昵称；但需等隐私说明先看完（排序：先隐私 → 后昵称），避免两个弹窗叠一起
    if (!wx.getStorageSync(STORAGE.NICKNAME_PROMPTED) && !this.data.noticeVisible) {
      this.setData({ nicknameVisible: true, nicknameInput: wx.getStorageSync(STORAGE.NICKNAME) || '' })
    }
    // 从个人主页「用这份简历出题」回来：带画像自动开始出题
    const pending = wx.getStorageSync(STORAGE.PENDING_GENERATE)
    wx.removeStorageSync(STORAGE.PENDING_GENERATE)
    if (pending && this.data.profile) {
      this.startGenerate()
    }
  },

  /* ---------- 界面 ---------- */
  closeNotice() {
    wx.setStorageSync(STORAGE.NOTICE_SEEN, '1')
    this.setData({ noticeVisible: false })
    // 隐私说明看完后再弹出昵称设置（选填，只弹一次）
    if (!wx.getStorageSync(STORAGE.NICKNAME_PROMPTED)) {
      this.setData({ nicknameVisible: true, nicknameInput: wx.getStorageSync(STORAGE.NICKNAME) || '' })
    }
  },

  toggleMode(e) {
    const mode = e.currentTarget.dataset.mode
    if (mode === this.data.mode) return
    this.reloadProfile()
    let role = mode === 'bigtech' ? 'AI 应用开发' : 'Java 后端'
    // 其他专业没有 IT 岗位，切到常规面试时仍保持岗位为空
    if (mode === 'normal' && this.data.majorDomain === '其他') role = null
    this.setData({ mode, role })
  },

  reloadProfile() {
    this.setData({ profile: wx.getStorageSync(STORAGE.PROFILE) || null,
                   resumeId: wx.getStorageSync(STORAGE.RESUME_ID) || null })
  },

  /** 更换简历：清空当前画像，回到上传区（个人主页记录不受影响） */
  changeResume() {
    this.setData({ profile: null, resumeId: null })
    wx.removeStorageSync(STORAGE.PROFILE)
    wx.removeStorageSync(STORAGE.RESUME_ID)
  },

  pickMajorDomain() {
    if (this.data.mode === 'bigtech') return // 大厂挑战岗位固定，不需选大方向
    wx.showActionSheet({
      itemList: this.data.majorDomains,
      success: (res) => {
        const majorDomain = this.data.majorDomains[res.tapIndex]
        const patch = { majorDomain }
        // 其他专业没有 IT 目标岗位，置空；切回计算机则恢复默认岗位
        if (majorDomain === '其他') {
          patch.role = null
        } else if (!this.data.role) {
          patch.role = 'Java 后端'
        }
        this.setData(patch)
      },
    })
  },

  pickRole() {
    if (this.data.mode === 'bigtech') return // 大厂挑战固定 AI 应用开发
    if (this.data.majorDomain === '其他') {
      toast('其他专业无需选择目标岗位，AI 会按你的简历直接出题')
      return
    }
    wx.showActionSheet({
      itemList: this.data.roles,
      success: (res) => this.setData({ role: this.data.roles[res.tapIndex] }),
    })
  },

  /* ---------- 上传简历 ---------- */
  chooseFile() {
    wx.chooseMessageFile({
      count: 1,
      type: 'all',
      extension: ['pdf', 'doc', 'docx', 'png', 'jpg', 'jpeg', 'txt'],
      success: (res) => {
        const f = res.tempFiles && res.tempFiles[0]
        if (f) this.doUpload(f.path)
      },
      fail: (err) => { if (!/cancel/.test(err.errMsg)) toast('选择文件失败') },
    })
  },

  chooseImage() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const f = res.tempFiles && res.tempFiles[0]
        if (f) this.doUpload(f.tempFilePath)
      },
      fail: (err) => { if (!/cancel/.test(err.errMsg)) toast('选择图片失败') },
    })
  },

  doUpload(filePath) {
    if (this.data.uploading || this.data.processing) return
    this.setData({ uploading: true })
    wx.showLoading({ title: '提交解析…', mask: true })
    parseResume(filePath, { majorDomain: this.data.majorDomain, role: this.data.role })
      .then((body) => {
        if (!body.recordId) throw new Error('提交失败，请重试')
        wx.showLoading({ title: 'AI 解析简历中…（约1分钟）', mask: true })
        return this.pollResume(body.recordId)
      })
      .then((profile) => {
        wx.hideLoading()
        const resumeId = profile._resumeId || this._currentResumeId
        // 去掉内部标记再落存储（避免污染发送给后端出题的画像对象）
        const clean = Object.assign({}, profile)
        delete clean._resumeId
        this.setData({ profile: clean, resumeId })
        wx.setStorageSync(STORAGE.PROFILE, clean)
        wx.setStorageSync(STORAGE.RESUME_ID, resumeId)
        wx.showToast({ title: '简历解析完成，可以出题了', icon: 'success' })
      })
      .catch((e) => {
        wx.hideLoading()
        toast((e && e.message) || '解析失败，请重试')
      })
      .finally(() => this.setData({ uploading: false }))
  },

  pollResume(recordId) {
    this._currentResumeId = recordId
    return poll(
      () => meApi.resumes(),
      (body) => {
        const rec = (body.items || []).find((r) => r.id === recordId)
        if (!rec) return false
        if (rec.status === 'DONE' && rec.profile) {
          rec.profile._resumeId = recordId
          return true
        }
        if (rec.status === 'FAILED') throw new Error(rec.errorMsg || '简历解析失败')
        return false
      },
      200,
      3000,
      '解析超时，请到个人主页查看进度',
    ).then((body) => (body.items || []).find((r) => r.id === recordId).profile)
  },

  /* ---------- 出题 ---------- */
  startGenerate() {
    const { profile, mode, role, resumeId } = this.data
    if (!profile) { toast('请先上传并解析简历'); return }
    if (this.data.processing) return
    this.setData({ processing: true })
    wx.showLoading({ title: (mode === 'bigtech' ? 'AI 组卷中（大厂挑战）…' : 'AI 组卷中…') + '（约1分钟）', mask: true })

    const req = mode === 'bigtech'
      ? bigtechGenerate(profile, resumeId)
      : generateQuestions(profile, mode === 'bigtech' ? 'AI 应用开发' : role, resumeId, this.data.majorDomain)

    req
      .then((body) => {
        wx.setStorageSync(STORAGE.PAPER_ID, body.paperId)
        return poll(
          () => getPaper(body.paperId),
          (p) => p.status === 'DONE',
          300,
          2000,
          '出题超时，请稍后重试',
        ).then((p) => wx.setStorageSync(STORAGE.QUESTIONS, p.questions || []))
      })
      .then(() => {
        wx.hideLoading()
        this.setData({ processing: false })
        wx.navigateTo({ url: '/pages/quiz/quiz' })
      })
      .catch((e) => {
        wx.hideLoading()
        this.setData({ processing: false })
        toast((e && e.message) || '生成题目失败，请重试')
      })
  },

  /* ---------- 学习路线 ---------- */
  openPlan() {
    const { profile } = this.data
    if (!profile) { toast('请先上传并解析简历'); return }
    this.setData({ planVisible: true, planLoading: true, plan: null })
    learningPlan(profile)
      .then((body) => this.setData({ plan: body, planLoading: false }))
      .catch((e) => {
        this.setData({ planLoading: false })
        toast((e && e.message) || '学习路线生成失败，请重试')
      })
  },
  closePlan() {
    this.setData({ planVisible: false })
  },

  goMe() {
    wx.switchTab({ url: '/pages/me/me' })
  },

  /* ---------- 设置昵称（首次登录，选填） ---------- */
  onNicknameInput(e) {
    this.setData({ nicknameInput: e.detail.value })
  },

  saveNickname() {
    if (this.data.nicknameSaving) return
    const name = (this.data.nicknameInput || '').trim()
    if (name.length > 20) { toast('昵称最多 20 个字符'); return }
    this.setData({ nicknameSaving: true })
    // 为空 = 跳过，不调接口
    if (!name) {
      wx.setStorageSync(STORAGE.NICKNAME_PROMPTED, '1')
      this.setData({ nicknameVisible: false, nicknameSaving: false, nicknameInput: '' })
      return
    }
    setNickname(name)
      .then(() => {
        wx.setStorageSync(STORAGE.NICKNAME, name)
        wx.setStorageSync(STORAGE.NICKNAME_PROMPTED, '1')
        this.setData({ nicknameVisible: false, nicknameSaving: false, nicknameInput: '' })
      })
      .catch((e) => {
        this.setData({ nicknameSaving: false })
        toast((e && e.message) || '昵称保存失败，可稍后到个人主页重试')
      })
  },

  skipNickname() {
    wx.setStorageSync(STORAGE.NICKNAME_PROMPTED, '1')
    this.setData({ nicknameVisible: false, nicknameInput: '' })
  },
})