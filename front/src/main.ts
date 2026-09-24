import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import pinia from './store'

// Element Plus
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

// Tailwind & Global CSS
import './style.css'

// Theme Tokens & Components
import './theme'

// Global Components
import DictSelect from './components/DictSelect.vue'
import DictTag from './components/DictTag.vue'

const app = createApp(App)

// 注册所有 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 注册全局字典通用组件
app.component('DictSelect', DictSelect)
app.component('DictTag', DictTag)

app.use(pinia)
app.use(router)
app.use(ElementPlus)

app.mount('#app')
