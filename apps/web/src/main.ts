import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { onSessionEnded } from './api/client'
import { useSessionStore } from './stores/session'
import './styles/tokens.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
onSessionEnded(() => useSessionStore(pinia).markSignedOut())
app.use(router)
app.mount('#app')
