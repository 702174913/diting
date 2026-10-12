import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

/**
 * 运营视图追求颗粒度，投资人视图追求可信度 —— 同一口径，呈现层隔离。
 *  这里的 'ops' 是**视图模式**（运营视图），与已下线的「质检运营」统计页无关。
 */
export type ViewMode = 'ops' | 'ir'

export type Range = 'TODAY' | '30D' | 'QUARTER'

/**
 * 投资人视图下锁定的页面：与后端 @RequireRole 一一对应。
 * - recordings：/api/admin/recordings 是 {ADMIN, OPERATOR}，INVESTOR 一律 403
 * - audit：/api/admin/audit-logs 是 ADMIN only
 * - accounts：账号管理是 ADMIN only，且写操作与投资人视图无关
 * - agreements：协议正文可写，投资人视图只读看板，不给改配置的入口
 * - legal-clauses：法律条款读接口是 {ADMIN, OPERATOR}，INVESTOR 一律 403（同协议配置）
 * 历史反例（已随页面下线，记录在这里避免重犯）：quality 是纯聚合页、对 INVESTOR 开放却被锁上了；
 * 而含转写原文的 recordings 反而没锁。
 * 遗留不一致：rules / settings 的读接口同为 {ADMIN, OPERATOR}，却一直没锁（本页不动它）。
 */
const IR_LOCKED = ['recordings', 'audit', 'accounts', 'agreements', 'legal-clauses']

const MODE_KEY = 'diting_admin_mode'
const RANGE_KEY = 'diting_admin_range'

/**
 * 模式落到 localStorage。
 * 锁定规则必须在刷新和直接改 URL 时依然生效，否则「已锁定」只是一句界面上的话。
 * 首次进入时按登录角色决定：投资人账号直接进聚合脱敏视图。
 */
function initialMode(): ViewMode {
  const saved = localStorage.getItem(MODE_KEY)
  if (saved === 'ops' || saved === 'ir') {
    return saved
  }
  return localStorage.getItem('diting_admin_role') === 'INVESTOR' ? 'ir' : 'ops'
}

function initialRange(): Range {
  const saved = localStorage.getItem(RANGE_KEY)
  return saved === 'TODAY' || saved === 'QUARTER' ? saved : '30D'
}

export const useViewStore = defineStore('view', () => {
  const mode = ref<ViewMode>(initialMode())
  const range = ref<Range>(initialRange())

  const isInvestor = computed(() => mode.value === 'ir')
  const apiPeriod = computed(() => range.value === 'TODAY' ? 'today' : range.value === 'QUARTER' ? 'quarter' : '30d')

  function isLocked(view: string) {
    return isInvestor.value && IR_LOCKED.includes(view)
  }

  function setMode(value: ViewMode) {
    mode.value = value
    localStorage.setItem(MODE_KEY, value)
  }

  function setRange(value: Range) {
    range.value = value
    localStorage.setItem(RANGE_KEY, value)
  }

  return { mode, range, apiPeriod, isInvestor, isLocked, setMode, setRange }
})
