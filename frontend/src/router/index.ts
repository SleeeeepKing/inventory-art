import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    titleKey?: string
    requiresAuth?: boolean
    guestOnly?: boolean
    adminOnly?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { guestOnly: true, titleKey: 'auth.signIn' } },
  {
    path: '/',
    component: () => import('@/layouts/AppShell.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { titleKey: 'nav.dashboard' } },
      { path: 'products', name: 'products', component: () => import('@/views/ProductsView.vue'), meta: { titleKey: 'nav.products' } },
      { path: 'inventory', name: 'inventory', component: () => import('@/views/InventoryView.vue'), meta: { titleKey: 'nav.inventory' } },
      { path: 'orders', name: 'orders', component: () => import('@/views/OrdersView.vue'), meta: { titleKey: 'nav.orders' } },
      { path: 'imports', name: 'imports', component: () => import('@/views/ImportsView.vue'), meta: { titleKey: 'nav.imports' } },
      { path: 'imports/new', name: 'import-new', component: () => import('@/views/ImportWizardView.vue'), meta: { titleKey: 'import.title' } },
      { path: 'reports', name: 'reports', component: () => import('@/views/ReportsView.vue'), meta: { titleKey: 'nav.reports' } },
      { path: 'profile', name: 'profile', component: () => import('@/views/ProfileView.vue'), meta: { titleKey: 'nav.profile' } },
      { path: 'admin/tenants', name: 'admin-tenants', component: () => import('@/views/admin/AdminTenantsView.vue'), meta: { titleKey: 'nav.tenants', adminOnly: true } },
      { path: 'admin/users', name: 'admin-users', component: () => import('@/views/admin/AdminUsersView.vue'), meta: { titleKey: 'nav.users', adminOnly: true } },
      { path: 'admin/audit', name: 'admin-audit', component: () => import('@/views/admin/AdminAuditView.vue'), meta: { titleKey: 'nav.audit', adminOnly: true } },
      { path: 'admin/data', name: 'admin-data', component: () => import('@/views/admin/AdminDataView.vue'), meta: { titleKey: 'nav.globalData', adminOnly: true } },
    ],
  },
  { path: '/:pathMatch(.*)*', component: () => import('@/views/NotFoundView.vue'), meta: { titleKey: 'errors.notFound' } },
]

export const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) await auth.initialize()
  if (to.meta.requiresAuth && !auth.isAuthenticated) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.guestOnly && auth.isAuthenticated) return { name: 'dashboard' }
  if (to.meta.adminOnly && !auth.isAdmin) return { name: 'dashboard' }
  return true
})
