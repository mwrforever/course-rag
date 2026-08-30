/**
 * next.config SSE 流式契约测试（2026-08-30 流式链路修复新增）
 *
 * 背景：/api/v1/* 经 rewrites 同源代理到后端 8080，Next 默认开启的 compression
 * 中间件会把代理响应中的 text/event-stream 包进 gzip，SSE 几十字节小帧滞留 zlib
 * 缓冲直到流结束才一次性 flush——浏览器「长时间空白→最终一次性全量渲染」的根因
 * （docs/progress/2026-08-30-流式链路根因调研.md）。compress: false 关闭该中间件，
 * 本测试锁定该配置防止后续误开导致流式实时性回归（宪法 C.1.9：流式端点必须禁代理缓冲）。
 */
import { describe, expect, it } from "vitest";
import nextConfig from "../../next.config";

describe("next.config SSE 流式契约", () => {
  it("compress 必须为 false：rewrite 代理开启压缩会吞 SSE 小帧（防回归）", () => {
    // 若本断言失败，说明 Next 内置压缩被重新打开——SSE 经代理将退化为
    // 「流结束才一次性到达」，实时打字机效果丢失
    expect(nextConfig.compress).toBe(false);
  });
});
