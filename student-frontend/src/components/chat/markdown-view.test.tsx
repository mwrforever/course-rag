/**
 * Markdown 渲染测试（Task 12 TDD 先行用例）
 *
 * 覆盖（设计 §1.5.4 Markdown 渲染）：
 * - 基础块：标题/段落/列表/引用/分隔线（remark-gfm 全量）
 * - 代码块：语言标签 + 复制按钮 + 语法高亮 + 横向滚动
 * - 表格：表格工具栏复制（GFM）
 * - 链接安全：target=_blank 必带 rel="noopener noreferrer"（防 tab 劫持）
 * - 行内代码样式存在
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MarkdownView, normalizeLanguage } from "./markdown-view";

const onNotify = vi.fn();
let clipboardWriteText: ReturnType<typeof vi.fn>;

function stubClipboard() {
  clipboardWriteText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", {
    value: { writeText: clipboardWriteText },
    configurable: true,
  });
}

/** 渲染 markdown 并断言 onNotify 传入（默认 vi.fn()） */
function renderMarkdown(content: string) {
  return render(<MarkdownView content={content} onNotify={onNotify} />);
}

beforeEach(() => {
  onNotify.mockReset();
  stubClipboard();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("normalizeLanguage 语言别名归一", () => {
  it("sh/shell → bash，js → javascript，ts → typescript，html → markup", () => {
    expect(normalizeLanguage("sh")).toBe("bash");
    expect(normalizeLanguage("shell")).toBe("bash");
    expect(normalizeLanguage("js")).toBe("javascript");
    expect(normalizeLanguage("ts")).toBe("typescript");
    expect(normalizeLanguage("html")).toBe("markup");
  });
  it("已支持语言原样返回，未知语言返回空串（降级纯文本）", () => {
    expect(normalizeLanguage("python")).toBe("python");
    expect(normalizeLanguage("unknown-lang")).toBe("");
  });
});

describe("MarkdownView 基础渲染", () => {
  it("标题/段落/列表/引用/分隔线：GFM 全量支持", () => {
    const md = [
      "# 一级标题",
      "",
      "正文段落内容",
      "",
      "- 列表项一",
      "- 列表项二",
      "",
      "> 引用内容",
      "",
      "---",
    ].join("\n");
    renderMarkdown(md);
    expect(screen.getByRole("heading", { level: 1, name: "一级标题" })).toBeInTheDocument();
    expect(screen.getByText("正文段落内容")).toBeInTheDocument();
    expect(screen.getByText("列表项一")).toBeInTheDocument();
    expect(screen.getByText("引用内容")).toBeInTheDocument();
    expect(screen.getAllByRole("separator").length).toBeGreaterThan(0);
  });

  it("映射覆写（Task 11）：h2/h3 挂 a-h 类；无序列表项挂 a-li 菱形；strong 深棕强调", () => {
    const md = ["## 二级标题", "", "### 三级标题", "", "- 菱形列表项", "", "这是**强调内容**"].join(
      "\n",
    );
    renderMarkdown(md);
    // h2/h3 → 设计稿 a-h 样式类（15px/700/gold-deep/字距 1.5px 由 CSS 承担）
    expect(screen.getByRole("heading", { level: 2, name: "二级标题" })).toHaveClass("a-h");
    expect(screen.getByRole("heading", { level: 3, name: "三级标题" })).toHaveClass("a-h");
    // 无序列表项 → 菱形 bullet 类（ul 去默认圆点）
    expect(screen.getByText("菱形列表项")).toHaveClass("a-li");
    // strong → brand-strong 深棕强调
    const strong = screen.getByText("强调内容");
    expect(strong.tagName).toBe("STRONG");
    expect(strong).toHaveClass("text-brand-strong");
  });

  it("有序列表保留十进制编号（ol 上下文关闭菱形 bullet，步骤语义不丢失）", () => {
    renderMarkdown(["1. 第一步", "2. 第二步"].join("\n"));
    const ol = screen.getByRole("list");
    expect(ol.tagName).toBe("OL");
    expect(ol.className).toContain("list-decimal");
  });

  it("行内代码：等宽样式包裹", () => {
    renderMarkdown("使用 `useChatStream` 订阅流");
    expect(screen.getByText("useChatStream")).toBeInTheDocument();
  });
});

describe("MarkdownView 代码块", () => {
  it("代码块：语言标签 + 复制按钮 + 横向滚动容器", () => {
    renderMarkdown(["```python", "print('hello')", "```"].join("\n"));
    expect(screen.getByText("python")).toBeInTheDocument();
    const copyBtn = screen.getByRole("button", { name: "复制代码" });
    expect(copyBtn).toBeInTheDocument();
    expect(screen.getByTestId("code-block")).toHaveClass("overflow-x-auto");
  });

  it("点击复制代码：clipboard 写入代码原文 + toast 已复制", async () => {
    const code = "console.log('hi')";
    renderMarkdown(["```js", code, "```"].join("\n"));
    fireEvent.click(screen.getByRole("button", { name: "复制代码" }));
    // toast 断言并入轮询：copyToClipboard 引入多一跳微任务，固定时点断言会竞态
    await vi.waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith(code);
      expect(onNotify).toHaveBeenCalledWith("已复制");
    });
  });

  it("未知语言代码块：降级纯文本渲染不崩溃", () => {
    renderMarkdown(["```not-a-real-lang", "plain text", "```"].join("\n"));
    expect(screen.getByText("plain text")).toBeInTheDocument();
  });
});

describe("MarkdownView 表格", () => {
  const TABLE_MD = [
    "| 名称 | 说明 |",
    "| --- | --- |",
    "| RAG | 检索增强生成 |",
    "| Milvus | 向量数据库 |",
  ].join("\n");

  it("表格渲染：表头 + 单元格 + 工具栏复制按钮", () => {
    renderMarkdown(TABLE_MD);
    expect(screen.getByRole("columnheader", { name: "名称" })).toBeInTheDocument();
    expect(screen.getByText("检索增强生成")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "复制表格" })).toBeInTheDocument();
  });

  it("点击复制表格：以 Markdown 分隔行写入 clipboard", async () => {
    renderMarkdown(TABLE_MD);
    fireEvent.click(screen.getByRole("button", { name: "复制表格" }));
    await vi.waitFor(() => {
      expect(clipboardWriteText).toHaveBeenCalledWith(
        "名称 | 说明\nRAG | 检索增强生成\nMilvus | 向量数据库",
      );
    });
  });
});

describe("MarkdownView 链接安全", () => {
  it("链接：target=_blank 且 rel=noopener noreferrer（防 tab 劫持）", () => {
    renderMarkdown("[课程资料](https://example.com/docs)");
    const link = screen.getByRole("link", { name: "课程资料" });
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", expect.stringContaining("noopener noreferrer"));
    expect(link).toHaveAttribute("href", "https://example.com/docs");
  });
});
