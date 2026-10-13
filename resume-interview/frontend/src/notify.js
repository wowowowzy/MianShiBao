import { ElNotification, ElMessage } from 'element-plus'

/**
 * 异步后台任务提示工具。
 * notifyAsync：右上角常驻小弹窗，告知任务已提交、预计等待时长（后台进行，可离开页面）。
 * hintAsync：全屏 loading 等待场景下的可消失提示条（一次性提示预计时长）。
 */

/** 各异步任务的预计时长文案（诚实、留缓冲） */
export const EST = {
  parse: '约 1 分钟',
  paper: '约 1 分钟',
  grade: '约 1 分钟',
  plan: '约 30 秒',
}

/** 右上角弹窗：后台进行、可离开页面 */
export function notifyAsync({ title, message, duration = 8000 }) {
  ElNotification({
    title: title || 'AI 处理中',
    message: message || '正在后台处理，请耐心等待…',
    duration,
    position: 'top-right',
  })
}

/** 全屏等待场景的可消失提示条 */
export function hintAsync(text, type = 'info') {
  ElMessage({ message: text, type, duration: 5000 })
}

/** 简历解析专用：提交后跳个人主页后台处理 */
export function notifyParsing(estimate = EST.parse) {
  notifyAsync({
    title: '📄 简历解析中',
    message: `已提交解析，${estimate}。请前往个人主页查看进度，解析完成后即可出题。`,
  })
}