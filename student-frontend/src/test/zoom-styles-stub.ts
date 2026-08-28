/**
 * 空模块桩：vitest 内替代 react-medium-image-zoom/dist/styles.css
 *
 * 背景：vitest 的 vite:css 管道会加载项目 PostCSS 配置（@tailwindcss/postcss 在
 * node 测试上下文不可实例化直接抛错），任何 .css 导入（含空桩）都会失败；
 * 经 vitest.config.mts resolve.alias 把该样式导入重定向到本空模块（别名命中
 * 先于扩展名解析，不进 css 管道），浏览器构建（Next.js）仍加载原样式不受影响。
 */
export {};
