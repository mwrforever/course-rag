import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'
import prettierConfig from 'eslint-config-prettier'
import globals from 'globals'

/**
 * B 端 ESLint 扁平配置（Flat Config）
 *
 * 组成：typescript-eslint 推荐规则 + eslint-plugin-vue flat/recommended
 * （.vue 文件由 vue-eslint-parser 解析，内部 TS 段交给 @typescript-eslint/parser）
 * + eslint-config-prettier 关闭与 Prettier 冲突的格式规则（格式化交给 prettier --check）。
 */
export default [
  {
    // 忽略构建产物与依赖目录
    ignores: ['node_modules/**', 'dist/**', 'coverage/**'],
  },
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        // .vue 的 <script lang="ts"> 段使用 TS 解析器
        parser: tseslint.parser,
      },
    },
  },
  {
    files: ['**/*.{ts,mts,tsx,vue}'],
    languageOptions: {
      globals: {
        ...globals.browser,
      },
    },
    rules: {
      // 生产代码禁用 console：日志统一走后续封装的日志能力，避免散落的调试输出进入主干
      'no-console': 'error',
      // any 零容忍：契约类型缺口必须显式建模
      '@typescript-eslint/no-explicit-any': 'error',
      // shadcn-vue 约定：ui 基础件（Button/Badge）与 App 根组件允许单字命名
      'vue/multi-word-component-names': 'off',
    },
  },
  {
    // 配置文件跑在 Node 环境
    files: ['vite.config.ts', 'vitest.config.ts', 'eslint.config.js'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },
  prettierConfig,
]
