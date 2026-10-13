/** 通用工具：sleep 与轮询 */
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * 轮询器：condition(结果, 第几轮) 返回 true 即结束；maxRounds 轮后抛错。
 * fetcher 返回响应体，尝试将 body.message 抛给上层。
 */
async function poll(fetcher, condition, maxRounds = 300, interval = 2000, label = '处理超时，请稍后重试') {
  for (let i = 0; i < maxRounds; i++) {
    const body = await fetcher()
    if (condition(body)) return body
    if (body && body.status === 'FAILED') {
      throw new Error((body && (body.errorMsg || body.message)) || '处理失败，请重试')
    }
    await sleep(interval)
  }
  throw new Error(label)
}

module.exports = { sleep, poll }