import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useViewStore } from '@/stores/view'

/**
 * 导航结构：总览 / 业务（含个体数据）/ 治理。
 *  统计面只保留总览看板（/overview）：增长、财务、质检运营、违规图谱、质量、合规六个统计页已整体下线，
 *  旧地址由文件末尾的 catch-all 兜底回总览。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    component: () => import('@/views/login/index.vue'),
  },
  {
    path: '/',
    component: () => import('@/layouts/AdminLayout.vue'),
    redirect: '/overview',
    children: [
      {
        path: 'overview',
        name: 'overview',
        component: () => import('@/views/overview/index.vue'),
        meta: { title: '总览看板' },
      },
      {
        path: 'recordings',
        name: 'recordings',
        component: () => import('@/views/recordings/index.vue'),
        meta: { title: '录音资产' },
      },
      {
        path: 'rules',
        name: 'rules',
        component: () => import('@/views/rules/index.vue'),
        meta: { title: '规则库' },
      },
      {
        path: 'legal-clauses',
        name: 'legal-clauses',
        component: () => import('@/views/legal-clauses/index.vue'),
        meta: { title: '条款库' },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/settings/index.vue'),
        meta: { title: '策略设置' },
      },
      {
        path: 'agreements',
        name: 'agreements',
        component: () => import('@/views/agreements/index.vue'),
        meta: { title: '协议配置' },
      },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('@/views/audit/index.vue'),
        meta: { title: '权限与审计' },
      },
      {
        path: 'accounts',
        name: 'accounts',
        component: () => import('@/views/accounts/index.vue'),
        meta: { title: '账号管理' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/overview' },
]

const router = createRouter({
  // 用 Vite 注入的 BASE_URL（= vite.config.ts 里的 base），
  // 部署到 /admin/ 子路径时路由才会跟着走；改路径只需动 vite.config.ts 一处
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

router.beforeEach((to) => {
  const userStore = useUserStore()
  if (to.path === '/login') {
    return true
  }
  if (!userStore.token) {
    return '/login'
  }
  // 锁定规则放在守卫里，直接改 URL 或刷新也绕不过去 ——
  // 否则「投资人视图已锁定个体数据」只是一句界面上的话。
  const viewStore = useViewStore()
  if (to.name && viewStore.isLocked(String(to.name))) {
    return '/overview'
  }
  return true
})

export default router
