import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import router from './router'
import './styles/main.css'

/**
 * B 端应用入口
 *
 * 装配 Pinia（状态）+ VueRouter（路由守卫）+ VueQuery（服务端状态缓存）。
 * QueryClient 默认配置：失败重试 1 次、30s staleTime，页面级轮询（ETL 5s）由各查询自行声明。
 */
const app = createApp(App)

// 服务端状态客户端：统一重试与过期策略，后续接口层直接使用
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
    },
  },
})

app.use(createPinia())
app.use(router)
app.use(VueQueryPlugin, { queryClient })
app.mount('#app')
