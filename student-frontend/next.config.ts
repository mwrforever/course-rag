import type { NextConfig } from "next";

// C 端 Next 配置：dev 同源代理后端 + MinIO 图片域名白名单
const nextConfig: NextConfig = {
  // 关闭 Next 内置 gzip 压缩中间件（compress 默认开启）：/api/v1/* 经 rewrites 代理到 8080 时，
  // compression 会把代理响应中的 text/event-stream 也包进 gzip，SSE 几十字节小帧全部滞留
  // zlib 缓冲（Z_NO_FLUSH）直到流结束才一次性 flush——浏览器表现为「长时间空白→最终一次性
  // 全量渲染」（2026-08-30 根因调研：docs/progress/2026-08-30-流式链路根因调研.md）。
  // 宪法 C.1.9 要求流式端点禁代理缓冲；后端 SSE 已同步补 Cache-Control: no-transform 兜底
  compress: false,
  async rewrites() {
    // dev 同源代理后端，规避 CORS 与 cookie domain（设计文档 §3.3：baseURL /api/v1 统一走本代理转发 8080）
    return [{ source: "/api/v1/:path*", destination: "http://localhost:8080/api/v1/:path*" }];
  },
  images: {
    // PERF-06：优化管道输出格式补 AVIF（默认仅 WebP；现代浏览器协商命中 AVIF 体积再降 ~30%）
    formats: ["image/avif", "image/webp"],
    // 课程封面图来自本地 MinIO（9000 端口），供 next/image 优化使用
    // （首页精选课程封面 PERF-06 同口径复用；生产域名接入时在此追加）
    remotePatterns: [{ protocol: "http", hostname: "localhost", port: "9000" }],
  },
};

export default nextConfig;
