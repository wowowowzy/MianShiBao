const { wechatLogin } = require('../../utils/api')
const { STORAGE } = require('../../utils/config')

Page({
  data: {
    loading: false,
    error: '',
  },

  onLoad() {
    // 已登录过直接进首页
    if (wx.getStorageSync(STORAGE.TOKEN)) {
      wx.switchTab({ url: '/pages/index/index' })
      return
    }
    this.doLogin()
  },

  /** 微信静默登录：wx.login 取 code → 后端换 openid/账号 → 落地 token */
  doLogin() {
    if (this.data.loading) return
    this.setData({ loading: true, error: '' })
    wx.login({
      success: (loginRes) => {
        if (!loginRes.code) {
          this.setData({ loading: false, error: '获取微信登录凭证失败，请重试' })
          return
        }
        wechatLogin(loginRes.code)
          .then((body) => {
            wx.setStorageSync(STORAGE.TOKEN, body.token)
            wx.setStorageSync(STORAGE.USERNAME, body.username || '微信用户')
            wx.setStorageSync(STORAGE.ADMIN, body.admin ? '1' : '0')
            if (body.nickname) wx.setStorageSync(STORAGE.NICKNAME, body.nickname)
            wx.switchTab({ url: '/pages/index/index' })
          })
          .catch((e) => {
            this.setData({ loading: false, error: (e && e.message) || '登录失败，请重试' })
          })
      },
      fail: () => {
        this.setData({ loading: false, error: '微信登录失败，请重试' })
      },
    })
  },
})