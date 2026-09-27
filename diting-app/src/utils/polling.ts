/**
 * 轮询控制器：保证同一页面只有一个定时器，离开页面立刻销毁，终态自动停止。
 * 这类「重复定时器」在真机上表现为重复请求与耗电，必须由结构保证而不是靠调用方自觉。
 */
export interface PollingOptions {
  /** 轮询间隔（毫秒） */
  intervalMs: number
  /** 拉一次数据；返回 true 表示可以继续轮询 */
  run: () => Promise<boolean>
  onError?: (error: unknown) => void
}

export function createPolling(options: PollingOptions) {
  let timer: ReturnType<typeof setInterval> | null = null
  let inFlight = false
  let generation = 0

  function stop() {
    generation++
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function isRunning() {
    return timer !== null
  }

  async function tick() {
    if (inFlight) {
      return
    }
    inFlight = true
    const requestedGeneration = generation
    try {
      const keepGoing = await options.run()
      if (!keepGoing && requestedGeneration === generation) {
        stop()
      }
    }
    catch (error) {
      if (requestedGeneration === generation) {
        options.onError?.(error)
      }
    }
    finally {
      inFlight = false
      // start() 可能在上一轮网络请求未返回时换了定时器；新轮须马上补一次请求。
      if (requestedGeneration !== generation && timer !== null) {
        void tick()
      }
    }
  }

  function start() {
    stop()
    timer = setInterval(tick, options.intervalMs)
    void tick()
  }

  return { start, stop, isRunning, tick }
}
