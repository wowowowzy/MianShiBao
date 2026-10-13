import { createRouter, createWebHashHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import MeView from '../views/MeView.vue'
import AdminView from '../views/AdminView.vue'

/*
 * 路由设计：
 *   '/'       主流程（上传→答题→报告）——状态机保留在 App.vue 内，本路由仅占位
 *   '/login'  登录 / 注册页
 *   '/me'     个人主页（解析 / 作答 / 出题记录）
 *   '/admin'  管理后台（仅管理员）
 * 使用 hash 模式，任意静态托管都无需服务端回退配置。
 */
const routes = [
  { path: '/', name: 'main', component: LoginView },
  { path: '/login', name: 'login', component: LoginView },
  { path: '/me', name: 'me', component: MeView },
  { path: '/admin', name: 'admin', component: AdminView, meta: { admin: true } },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

/** 本地校验 token 是否有效（解析 JWT exp，不验签）：过期或非法则清空登录态并返回 false */
function hasValidToken() {
  const token = localStorage.getItem('token')
  if (!token) return false
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    if (payload.exp && payload.exp * 1000 <= Date.now()) {
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      localStorage.removeItem('admin')
      window.dispatchEvent(new Event('auth-changed'))
      return false
    }
    return true
  } catch {
    // 解析失败视为非法 token
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('admin')
    return false
  }
}

// 未登录 → 一律回登录页；已登录访问登录页 → 回主流程；管理后台需管理员
router.beforeEach((to) => {
  const loggedIn = hasValidToken() // 过期 token 在此被主动清理，避免"看着已登录、一操作就被踢"
  if (!loggedIn && to.name !== 'login') return { name: 'login' }
  if (loggedIn && to.name === 'login') return { name: 'main' }
  if (to.meta?.admin && localStorage.getItem('admin') !== '1') return { name: 'main' }
})

export default router