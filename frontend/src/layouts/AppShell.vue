<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter, RouterLink, RouterView } from 'vue-router'
import { useI18n } from 'vue-i18n'
import {
  Box,
  Calendar,
  Close,
  DataAnalysis,
  Document,
  Fold,
  Goods,
  Grid,
  Histogram,
  List,
  Menu as MenuIcon,
  OfficeBuilding,
  Setting,
  SwitchButton,
  User,
  UserFilled,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const collapsed = ref(false)
const mobileOpen = ref(false)

const primaryNav = [
  { to: '/dashboard', key: 'nav.dashboard', icon: Grid },
  { to: '/products', key: 'nav.products', icon: Goods },
  { to: '/inventory', key: 'nav.inventory', icon: Box },
  { to: '/orders', key: 'nav.orders', icon: Document },
  { to: '/events', key: 'nav.events', icon: Calendar },
  { to: '/reports', key: 'nav.reports', icon: DataAnalysis },
]
const visiblePrimaryNav = computed(() =>
  auth.isAdmin ? primaryNav.filter((item) => item.to === '/reports') : primaryNav,
)
const adminNav = [
  { to: '/admin/tenants', key: 'nav.tenants', icon: OfficeBuilding },
  { to: '/admin/users', key: 'nav.users', icon: UserFilled },
  { to: '/admin/audit', key: 'nav.audit', icon: List },
  { to: '/admin/data', key: 'nav.globalData', icon: Histogram },
]
const pageTitle = computed(() => (route.meta.titleKey ? t(route.meta.titleKey) : t('app.name')))
const initials = computed(() =>
  (auth.user?.displayName || auth.user?.email || 'IA')
    .split(/\s+/)
    .map((part) => part[0])
    .join('')
    .slice(0, 2)
    .toUpperCase(),
)

watch(
  () => route.fullPath,
  () => {
    mobileOpen.value = false
  },
)
watch(mobileOpen, (open) => document.body.classList.toggle('nav-open', open))
onBeforeUnmount(() => document.body.classList.remove('nav-open'))

async function signOut() {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <div class="app-shell" :class="{ 'is-collapsed': collapsed }">
    <button
      class="mobile-menu"
      type="button"
      :aria-label="t('nav.openMenu')"
      aria-controls="primary-navigation"
      :aria-expanded="mobileOpen"
      @click="mobileOpen = true"
    >
      <MenuIcon />
    </button>
    <button
      v-if="mobileOpen"
      class="nav-scrim"
      type="button"
      :aria-label="t('common.close')"
      @click="mobileOpen = false"
    />
    <aside id="primary-navigation" class="side-nav" :class="{ 'is-mobile-open': mobileOpen }">
      <div class="brand-lockup">
        <div class="brand-mark">
          <span /><b>{{ t('app.shortName') }}</b>
        </div>
        <div class="brand-copy">
          <strong>{{ t('app.name') }}</strong
          ><small>{{ t('app.workspace') }}</small>
        </div>
        <button
          class="drawer-close"
          type="button"
          :aria-label="t('common.close')"
          @click="mobileOpen = false"
        >
          <Close />
        </button>
      </div>

      <nav class="nav-groups">
        <section>
          <p class="nav-label">{{ t('nav.operations') }}</p>
          <RouterLink
            v-for="item in visiblePrimaryNav"
            :key="item.to"
            :to="item.to"
            class="nav-item"
          >
            <component :is="item.icon" /><span>{{ t(item.key) }}</span>
          </RouterLink>
        </section>
        <section v-if="auth.isAdmin">
          <p class="nav-label">{{ t('nav.management') }}</p>
          <RouterLink v-for="item in adminNav" :key="item.to" :to="item.to" class="nav-item">
            <component :is="item.icon" /><span>{{ t(item.key) }}</span>
          </RouterLink>
        </section>
      </nav>

      <div class="side-nav__bottom">
        <RouterLink to="/profile" class="user-chip">
          <span class="user-chip__avatar">{{ initials }}</span>
          <span class="user-chip__copy"
            ><strong>{{ auth.user?.displayName }}</strong
            ><small>{{ auth.user?.tenant?.name || auth.user?.role }}</small></span
          >
          <Setting />
        </RouterLink>
        <button class="nav-item sign-out" type="button" @click="signOut">
          <SwitchButton /><span>{{ t('auth.signOut') }}</span>
        </button>
        <button
          class="collapse-control"
          type="button"
          :aria-label="t(collapsed ? 'nav.expand' : 'nav.collapse')"
          @click="collapsed = !collapsed"
        >
          <Fold /><span>{{ t(collapsed ? 'nav.expand' : 'nav.collapse') }}</span>
        </button>
      </div>
    </aside>

    <main class="workspace">
      <div class="workspace__bar">
        <span>{{ pageTitle }}</span
        ><span class="workspace__tenant"
          ><User />{{ auth.user?.tenant?.name || auth.user?.email }}</span
        >
      </div>
      <div class="workspace__content"><RouterView /></div>
    </main>
  </div>
</template>
