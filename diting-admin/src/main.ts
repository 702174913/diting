import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'
// vue-sonner 的定位/层级/动效全在自己的样式表里：不导入时 Toaster 会落在 #app 末尾的普通流里，
// 后台又是整屏布局 + 弹窗 overflow hidden，toast 实际被挤到视口外 —— 看起来就是「点了没提示」。
// 放在 style.css 之后，让它的 [data-sonner-toaster] 规则稳定生效。
import 'vue-sonner/style.css'

createApp(App).use(createPinia()).use(router).mount('#app')
