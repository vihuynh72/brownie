import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { isInAppPath } from '@/auth/intent'

declare module 'vue-router' {
  interface RouteMeta {
    /** The route reads or writes workspace data, so a signed-out visitor is sent to the sign-in page first. */
    requiresSession?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
    },
    {
      path: '/signin',
      name: 'signin',
      component: () => import('@/views/SignInView.vue'),
    },
    {
      path: '/chat',
      name: 'chat',
      component: () => import('@/views/ChatView.vue'),
      meta: { requiresSession: true },
    },
    {
      path: '/trash',
      name: 'trash',
      component: () => import('@/views/TrashView.vue'),
      meta: { requiresSession: true },
    },
    {
      path: '/your-data',
      name: 'your-data',
      component: () => import('@/views/YourDataView.vue'),
      meta: { requiresSession: true },
    },
    {
      // Where Google sends a person back after its consent page, as well as the page itself.
      path: '/connections',
      name: 'connections',
      component: () => import('@/views/ConnectionsView.vue'),
      meta: { requiresSession: true },
    },
    {
      path: '/documents/new',
      name: 'new-document',
      component: () => import('@/views/NewDocumentView.vue'),
      meta: { requiresSession: true },
    },
    {
      path: '/templates/new',
      name: 'new-template',
      component: () => import('@/views/NewTemplateView.vue'),
      meta: { requiresSession: true },
    },
    {
      path: '/documents/:documentId',
      name: 'workspace',
      component: () => import('@/views/WorkspaceView.vue'),
      props: (route) => ({ documentId: Number(route.params.documentId) }),
      meta: { requiresSession: true },
    },
    {
      // A mistyped or stale link gets a page that says so and a way back, not a blank main region.
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
    },
  ],
})

/**
 * Brownie has no login form of its own -- signing in is a full-page
 * redirect to the identity provider -- so this guard does two things
 * rather than one.
 *
 * It first makes sure identity has actually been loaded once, because
 * every view and the sidebar read sessionStore.status. Then, for a route
 * that needs a workspace, it sends a signed-out visitor to the sign-in
 * page carrying where they were going, instead of letting the page mount
 * and fail its first request with a 401. A visitor whose identity request
 * failed outright is not redirected: that is not the same as being signed
 * out, and App.vue shows the failure with a way to retry.
 */
router.beforeEach(async (to) => {
  const session = useSessionStore()
  // "loading" too: the app shell starts the request as it first renders, which is before this runs on a page load.
  if (session.status === 'unknown' || session.status === 'loading') {
    await session.loadIdentity()
  }
  if (to.meta.requiresSession && session.status === 'anonymous') {
    return { name: 'signin', query: { next: to.fullPath } }
  }
  if (to.name === 'signin' && session.status === 'authenticated') {
    const next = to.query.next
    return isInAppPath(next) ? next : { name: 'home' }
  }
  return true
})

export default router
