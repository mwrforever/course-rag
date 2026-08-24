import type { NextConfig } from "next";

// C 端 Next 配置：dev 同源代理后端 + MinIO 图片域名白名单
const nextConfig: NextConfig = {
  async rewrites() {
    // dev 同源代理后端，规避 CORS 与 cookie domain（设计文档 §3.3：baseURL /api/v1 统一走本代理转发 8080）
    return [{ source: "/api/v1/:path*", destination: "http://localhost:8080/api/v1/:path*" }];
  },
  images: {
    // 课程封面图来自本地 MinIO（9000 端口），供 next/image 优化使用
    remotePatterns: [{ protocol: "http", hostname: "localhost", port: "9000" }],
  },
};

export default nextConfig;
