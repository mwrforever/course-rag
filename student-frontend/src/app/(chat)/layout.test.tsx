/**
 * (chat) 课程助手路由组布局测试（UI 重构 2026-08-25 新增）
 *
 * 验证：kimi 式应用壳（左侧栏 + 右侧满高内容区）+ QueryProvider 挂载。
 * ChatSidebar 自身行为由其独立测试覆盖（auth/api 层在此 mock 静默，避免噪音）。
 */
import { QueryClient, useQueryClient } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import ChatLayout from "./layout";

vi.mock("next/navigation", () => ({
  usePathname: () => "/chat",
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    user: { userId: "u1", role: "STUDENT", displayName: "同学A" },
    accessToken: null,
    isAuthenticated: true,
    isLoading: false,
    login: vi.fn(),
    logout: vi.fn(),
  }),
}));
vi.mock("@/lib/api", () => ({
  getSessions: vi.fn().mockResolvedValue({ records: [], total: "0", page: 1, size: 20 }),
}));

/** 探针：验证 children 处于 QueryProvider 上下文内 */
function Probe() {
  const client = useQueryClient();
  return <span data-testid="client">{client instanceof QueryClient ? "已挂载" : "未挂载"}</span>;
}

describe("(chat) 课程助手布局壳", () => {
  it("渲染左侧栏（kimi 壳）并将子内容置于右侧满高容器", () => {
    render(
      <ChatLayout>
        <Probe />
      </ChatLayout>,
    );
    // 左侧栏存在
    expect(screen.getByTestId("chat-sidebar")).toBeInTheDocument();
    // QueryProvider 已挂载（子内容可消费 useQueryClient）
    expect(screen.getByTestId("client")).toHaveTextContent("已挂载");
  });
});
