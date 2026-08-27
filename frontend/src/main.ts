import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import { vReveal } from './directives/reveal'
import router from './router'
import './styles/main.css'

/**
 * B 端应用入口
 *
 * 装配 Pinia（状态）+ VueRouter（路由守卫）+ VueQuery（服务端状态缓存）
 * + v-reveal 滚动入场指令（全局注册，供 N6~N8 视图直接使用）。
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
// v-reveal 滚动入场指令全局注册（directives/reveal.ts，含减少动效降级）
app.directive('reveal', vReveal)
app.mount('#app')
