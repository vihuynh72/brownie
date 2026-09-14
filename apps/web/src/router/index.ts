import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'documents',
      component: () => import('@/views/DocumentListView.vue'),
    },
    {
      path: '/documents/new',
      name: 'new-document',
      component: () => import('@/views/NewDocumentView.vue'),
    },
    {
      path: '/templates/new',
      name: 'new-template',
      component: () => import('@/views/NewTemplateView.vue'),
    },
    {
      path: '/documents/:documentId',
      name: 'workspace',
      component: () => import('@/views/WorkspaceView.vue'),
      props: (route) => ({ documentId: Number(route.params.documentId) }),
    },
  ],
})

/**
 * Brownie has no in-app login form -- authentication is a full-page
 * redirect to the OIDC provider (see the repository README). A route
 * guard that redirected there automatically would fight a signed-out
 * visitor landing on any deep link; instead every view reads
 * sessionStore.status itself and renders its own signed-out prompt, and
 * this guard only makes sure identity has actually been loaded once
 * before a route tries to use it.
 */
router.beforeEach(async () => {
  const session = useSessionStore()
  if (session.status === 'unknown') {
    await session.loadIdentity()
  }
  return true
})

export default router
