// 明/暗主题纯函数模块（零 antd/浏览器强依赖，可被 node:test 直接 import）。
// 约定：默认 light；localStorage 记住手动选择（key THEME_STORAGE_KEY）；
// CSS 侧由 html[data-theme] 切换 styles.css 里的 --ops-* 变量。

export const THEME_STORAGE_KEY = 'redis-ops-theme'

export const normalizeTheme = value => (value === 'dark' ? 'dark' : 'light')

export const readStoredTheme = (storage = globalThis.localStorage) => {
  try { return normalizeTheme(storage?.getItem(THEME_STORAGE_KEY)) } catch { return 'light' }
}

export const persistTheme = (mode, storage = globalThis.localStorage) => {
  try { storage?.setItem(THEME_STORAGE_KEY, normalizeTheme(mode)) } catch { /* 隐私模式等场景静默降级 */ }
}

export const applyTheme = (mode, doc = globalThis.document) => {
  doc?.documentElement?.setAttribute('data-theme', normalizeTheme(mode))
}

// antd 主色：暗板用 antd 暗算法默认蓝 #1668dc（组件生态会围绕它生成链接/focus 色）；
// 该值与 styles.css 里 --ops-primary/--ops-primary-rgb/--ops-metric-fill 三处需同步修改。
export const themeToken = mode => ({ colorPrimary: mode === 'dark' ? '#1668dc' : '#1677ff', borderRadius: 6 })

export const toggleTheme = mode => (mode === 'dark' ? 'light' : 'dark')
