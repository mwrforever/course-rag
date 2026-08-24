import { dirname } from "path";
import { fileURLToPath } from "url";
import { FlatCompat } from "@eslint/eslintrc";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const compat = new FlatCompat({
  baseDirectory: __dirname,
});

const eslintConfig = [
  {
    // 全局忽略：构建产物、依赖与覆盖率报告不参与 lint
    ignores: ["node_modules/**", ".next/**", "out/**", "build/**", "coverage/**", "next-env.d.ts"],
  },
  ...compat.extends("next/core-web-vitals", "next/typescript"),
  {
    rules: {
      // 生产代码禁用 console：日志统一走封装，避免散落的调试输出进入主干
      "no-console": "error",
      // any 零容忍：类型缺口必须显式建模（后端契约字段一律声明显式类型）
      "@typescript-eslint/no-explicit-any": "error",
    },
  },
];

export default eslintConfig;
