/**
 * 网络请求统一封装：wx.request / wx.uploadFile → Promise。
 * - 自动携带登录 token（Authorization: Bearer xxx）
 * - 401 统一清理本地登录态并跳登录页
 * - 非 2xx 解析后端 {message} 错误体，拒绝为 { message, statusCode }
 */
const { BASE_URL } = require('./config')

function getToken() {
  return wx.getStorageSync('token')
}

function clearSession() {
  wx.removeStorageSync('token')
  wx.removeStorageSync('username')
  wx.removeStorageSync('admin')
}

function parseErr(data) {
  if (data && typeof data === 'object') {
    if (data.message) return data.message
    if (data.msg) return data.msg
  }
  if (typeof data === 'string' && data) return data
  return '请求失败'
}

function handleUnauthorized() {
  clearSession()
  wx.reLaunch({ url: '/pages/login/login' })
}

function request(options) {
  return new Promise((resolve, reject) => {
    const header = Object.assign({}, options.header || {})
    if (options.contentType) header['Content-Type'] = options.contentType
    const t = getToken()
    if (t) header.Authorization = 'Bearer ' + t
    wx.request({
      url: BASE_URL + options.url,
      method: options.method || 'GET',
      data: options.data,
      header,
      timeout: options.timeout || 180000,
      success(res) {
        const st = res.statusCode
        if (st >= 200 && st < 300) {
          resolve(res.data)
          return
        }
        if (st === 401) {
          handleUnauthorized()
        }
        reject({ message: parseErr(res.data), statusCode: st })
      },
      fail(err) {
        reject({ message: err.errMsg || '网络请求失败', statusCode: 0 })
      },
    })
  })
}

function upload(url, filePath, formData) {
  return new Promise((resolve, reject) => {
    const header = {}
    const t = getToken()
    if (t) header.Authorization = 'Bearer ' + t
    wx.uploadFile({
      url: BASE_URL + url,
      filePath,
      name: 'file',
      formData,
      header,
      timeout: 180000,
      success(res) {
        const st = res.statusCode
        let body = res.data
        try {
          body = JSON.parse(res.data)
        } catch (e) { /* 非 JSON 则原样透出 */ }
        if (st >= 200 && st < 300) {
          resolve(body)
          return
        }
        if (st === 401) {
          handleUnauthorized()
        }
        reject({ message: parseErr(body), statusCode: st })
      },
      fail(err) {
        reject({ message: err.errMsg || '上传失败', statusCode: 0 })
      },
    })
  })
}

module.exports = { request, upload }