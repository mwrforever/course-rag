/**
 * 工具结果抽屉测试（2026-08-30 工具结果侧栏展示）
 *
 * 覆盖：
 * - tool=null 不渲染；非 null 挂载
 * - 结果卡片渲染：数组逐元素成卡（标题优先取 title/courseName/name）、
 *   分页列表 courses[] 数组逐课程成卡、单对象单卡、标量单卡
 * - 中文字段标签映射（价格/分类/状态等）与超长值截断
 * - 空输出降级空态文案
 * - 原始 JSON 折叠（默认收起 → 点击展开完整原文）
 * - 关闭路径：关闭按钮 / Esc / 遮罩点击（三路径保留）
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ToolResultDrawer } from "./tool-result-drawer";

/** 工具节点工厂（output 可覆盖） */
function makeTool(output: unknown, toolName = "listCourses") {
  return { toolName, input: { keyword: "Java" }, output };
}

describe("ToolResultDrawer 挂载与空态", () => {
  it("tool=null 不渲染任何节点", () => {
    const { container } = render(<ToolResultDrawer tool={null} onClose={vi.fn()} />);
    expect(container.firstChild).toBeNull();
  });

  it("空输出：空态文案（该工具无返回数据）", () => {
    render(<ToolResultDrawer tool={makeTool(null)} onClose={vi.fn()} />);
    expect(screen.getByTestId("tool-drawer")).toBeInTheDocument();
    expect(screen.getByText("该工具无返回数据")).toBeInTheDocument();
  });

  it("空数组输出：同样空态文案", () => {
    render(<ToolResultDrawer tool={makeTool([])} onClose={vi.fn()} />);
    expect(screen.getByText("该工具无返回数据")).toBeInTheDocument();
  });
});

describe("ToolResultDrawer 结果卡片渲染", () => {
  it("头部：标题「查询到的课程资料」+ 工具名人话 + 调用参数摘要", () => {
    render(<ToolResultDrawer tool={makeTool({ total: 1 })} onClose={vi.fn()} />);
    expect(screen.getByText("查询到的课程资料")).toBeInTheDocument();
    expect(screen.getByTestId("tool-drawer-sub")).toHaveTextContent("查询课程列表");
    expect(screen.getByTestId("tool-drawer-sub")).toHaveTextContent('{"keyword":"Java"}');
  });

  it("分页列表 courses[]：逐课程成卡（标题取 title，字段中文标签逐行）", () => {
    render(
      <ToolResultDrawer
        tool={makeTool({
          page: 1,
          total: 1,
          courses: [
            {
              courseId: "101",
              title: "Java 进阶实战",
              category: "Backend",
              price: "¥199",
              difficulty: "Intermediate",
              status: "Open",
              nextStartDate: "2026-09-01",
            },
          ],
        })}
        onClose={vi.fn()}
      />,
    );
    const items = screen.getAllByTestId("tool-result-item");
    expect(items).toHaveLength(1);
    expect(screen.getByText("Java 进阶实战")).toBeInTheDocument();
    expect(screen.getByText(/价格: ¥199/)).toBeInTheDocument();
    expect(screen.getByText(/分类: Backend/)).toBeInTheDocument();
    expect(screen.getByText(/状态: Open/)).toBeInTheDocument();
    expect(screen.getByText(/下次开课: 2026-09-01/)).toBeInTheDocument();
  });

  it("数组输出：逐元素成卡（标题优先取 title/name；无标题字段回退「条目」）", () => {
    render(
      <ToolResultDrawer
        tool={makeTool([{ name: "讲师甲", title: "人工智能导论讲师" }, { category: "AI" }])}
        onClose={vi.fn()}
      />,
    );
    const items = screen.getAllByTestId("tool-result-item");
    expect(items).toHaveLength(2);
    // title 优先于 name（TITLE_KEYS 顺序）
    expect(screen.getByText("人工智能导论讲师")).toBeInTheDocument();
    // 无标题字段 → 「条目」兜底
    expect(screen.getByText("条目")).toBeInTheDocument();
    expect(screen.getByText(/分类: AI/)).toBeInTheDocument();
  });

  it("单对象输出：单卡展示字段（含嵌套对象 JSON 预览截断）", () => {
    render(
      <ToolResultDrawer
        tool={makeTool({
          courseId: "202",
          enrollmentUrl: "https://example.com/enroll/202",
          nextSchedule: { date: "2026-09-15", duration: "8周" },
        })}
        onClose={vi.fn()}
      />,
    );
    const items = screen.getAllByTestId("tool-result-item");
    expect(items).toHaveLength(1);
    expect(screen.getByText("条目")).toBeInTheDocument();
    expect(screen.getByText(/报名链接: https:\/\/example\.com/)).toBeInTheDocument();
    // 嵌套对象以 JSON 预览截断兜底
    expect(screen.getByText(/下期排期: \{/)).toBeInTheDocument();
  });

  it("标量输出：单卡「结果」直显", () => {
    render(<ToolResultDrawer tool={makeTool("找到 3 门课程")} onClose={vi.fn()} />);
    expect(screen.getByText("结果")).toBeInTheDocument();
    expect(screen.getByText("找到 3 门课程")).toBeInTheDocument();
  });
});

describe("ToolResultDrawer 原始 JSON 折叠", () => {
  it("默认收起；点击展开完整原文（mono pre），再点收起", () => {
    const output = { total: 2, courses: [{ title: "A" }, { title: "B" }] };
    render(<ToolResultDrawer tool={makeTool(output)} onClose={vi.fn()} />);
    expect(screen.queryByTestId("tool-drawer-raw")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("tool-drawer-raw-toggle"));
    const raw = screen.getByTestId("tool-drawer-raw");
    expect(raw).toHaveTextContent('"total": 2');
    expect(raw).toHaveTextContent('"title": "A"');
    fireEvent.click(screen.getByTestId("tool-drawer-raw-toggle"));
    expect(screen.queryByTestId("tool-drawer-raw")).not.toBeInTheDocument();
  });
});

describe("ToolResultDrawer 关闭路径", () => {
  it.each([
    ["关闭按钮", () => fireEvent.click(screen.getByRole("button", { name: "关闭工具结果抽屉" }))],
    ["Esc 键", () => fireEvent.keyDown(window, { key: "Escape" })],
    ["遮罩点击", () => fireEvent.click(screen.getByTestId("tool-drawer-overlay"))],
  ])("%s 触发 onClose", (_name, trigger) => {
    const onClose = vi.fn();
    render(<ToolResultDrawer tool={makeTool({ total: 1 })} onClose={onClose} />);
    trigger();
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
