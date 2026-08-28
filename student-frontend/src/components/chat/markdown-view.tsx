"use client";

/**
 * AI 回答 Markdown 渲染（设计 §1.5.4 Markdown 渲染）
 *
 * - react-markdown + remark-gfm：标题/段落/列表/引用/分隔线/任务清单/表格全量支持
 * - 代码块：语言标签 + 复制按钮 + 语法高亮（PrismLight 定集注册）+ 横向滚动
 *   语言别名归一（sh/shell→bash、js→javascript、ts→typescript、html→markup 等）
 * - 表格：sticky 工具栏（「表格」标签 + 复制按钮），复制为 | 分隔行
 * - 链接安全：target=_blank 必带 rel="noopener noreferrer"（防 tab 劫持）
 * - 行内代码：surface-2 底 + mono；用户消息不走本组件（纯文本渲染防 XSS，见 message-list）
 *
 * 流式契约：content 随 delta 累积变化，本组件全量重渲染（react-markdown 幂等，
 * 无需做增量 diff；打字光标由 message-list 独立挂载）。
 */
import { Copy } from "@phosphor-icons/react";
import { memo, type ReactNode } from "react";
import { PrismLight as SyntaxHighlighter } from "react-syntax-highlighter";
import bash from "react-syntax-highlighter/dist/esm/languages/prism/bash";
import css from "react-syntax-highlighter/dist/esm/languages/prism/css";
import go from "react-syntax-highlighter/dist/esm/languages/prism/go";
import java from "react-syntax-highlighter/dist/esm/languages/prism/java";
import javascript from "react-syntax-highlighter/dist/esm/languages/prism/javascript";
import json from "react-syntax-highlighter/dist/esm/languages/prism/json";
import jsx from "react-syntax-highlighter/dist/esm/languages/prism/jsx";
import markdown from "react-syntax-highlighter/dist/esm/languages/prism/markdown";
import markup from "react-syntax-highlighter/dist/esm/languages/prism/markup";
import python from "react-syntax-highlighter/dist/esm/languages/prism/python";
import sql from "react-syntax-highlighter/dist/esm/languages/prism/sql";
import tsx from "react-syntax-highlighter/dist/esm/languages/prism/tsx";
import typescript from "react-syntax-highlighter/dist/esm/languages/prism/typescript";
import oneLight from "react-syntax-highlighter/dist/esm/styles/prism/one-light";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

// 语法高亮定集注册：课程问答高频语言（模块级只注册一次，PrismLight 惰性加载）
SyntaxHighlighter.registerLanguage("bash", bash);
SyntaxHighlighter.registerLanguage("css", css);
SyntaxHighlighter.registerLanguage("go", go);
SyntaxHighlighter.registerLanguage("java", java);
SyntaxHighlighter.registerLanguage("javascript", javascript);
SyntaxHighlighter.registerLanguage("json", json);
SyntaxHighlighter.registerLanguage("jsx", jsx);
SyntaxHighlighter.registerLanguage("markdown", markdown);
SyntaxHighlighter.registerLanguage("markup", markup);
SyntaxHighlighter.registerLanguage("python", python);
SyntaxHighlighter.registerLanguage("sql", sql);
SyntaxHighlighter.registerLanguage("tsx", tsx);
SyntaxHighlighter.registerLanguage("typescript", typescript);

/** 已注册语言集合（normalizeLanguage 判定的唯一依据） */
const REGISTERED_LANGUAGES: ReadonlySet<string> = new Set([
  "bash",
  "css",
  "go",
  "java",
  "javascript",
  "json",
  "jsx",
  "markdown",
  "markup",
  "python",
  "sql",
  "tsx",
  "typescript",
]);

/** Markdown 渲染组件 props */
export interface MarkdownViewProps {
  /** Markdown 原文（AI 消息正文，流式累积） */
  content: string;
  /** 复制成功提示回调（页面 toast） */
  onNotify(message: string): void;
}

/** 语言别名归一表（fence 声明的别名 → 已注册语言） */
const LANGUAGE_ALIASES: Record<string, string> = {
  sh: "bash",
  shell: "bash",
  js: "javascript",
  ts: "typescript",
  html: "markup",
  xml: "markup",
  md: "markdown",
};

/**
 * 语言别名归一：返回已注册语言名；别名未收录或未注册返回空串
 * （空串时调用方降级纯文本渲染，避免 PrismLight 抛未知语言）
 *
 * @param language fence 语言标识
 * @returns 可注册语言名或空串
 */
export function normalizeLanguage(language: string): string {
  const name = (LANGUAGE_ALIASES[language] ?? language).toLowerCase();
  return REGISTERED_LANGUAGES.has(name) ? name : "";
}

/** 复制文本到剪贴板并提示（clipboard API 现代浏览器通用） */
async function copyText(text: string, onNotify: (message: string) => void) {
  await navigator.clipboard.writeText(text);
  onNotify("已复制");
}

/**
 * 表格复制文本：| 分隔行 + 换行分行（含表头；表格原样留档）
 *
 * @param table 表格 DOM 节点
 * @returns 可粘贴的文本行
 */
function tableToText(table: HTMLTableElement): string {
  return Array.from(table.querySelectorAll("tr"))
    .map((row) =>
      Array.from(row.querySelectorAll("th, td"))
        .map((cell) => cell.textContent ?? "")
        .join(" | "),
    )
    .join("\n");
}

/**
 * 代码块包装：语言标签 + 复制按钮 + 语法高亮 + 横向滚动
 *
 * @param language fence 语言标识（未知语言降级纯文本）
 * @param code 代码原文
 * @param onNotify 复制提示回调
 */
function CodeBlock({
  language,
  code,
  onNotify,
}: {
  language: string;
  code: string;
  onNotify: (message: string) => void;
}) {
  const normalized = normalizeLanguage(language);
  return (
    <div
      data-testid="code-block"
      className="overflow-x-auto rounded-xl border border-border bg-surface-2"
    >
      {/* 工具栏：语言标签 + 复制按钮 */}
      <div className="sticky left-0 flex items-center justify-between border-b border-border px-3 py-1.5">
        <span className="text-xs text-muted">{language}</span>
        <button
          type="button"
          aria-label="复制代码"
          onClick={() => void copyText(code, onNotify)}
          className="flex items-center gap-1 rounded-lg px-1.5 py-0.5 text-xs text-subtle transition-colors hover:bg-surface hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
        >
          <Copy size={12} aria-hidden />
          复制
        </button>
      </div>
      {normalized ? (
        <SyntaxHighlighter
          language={normalized}
          style={oneLight}
          customStyle={{
            margin: 0,
            background: "transparent",
            fontSize: "13px",
            lineHeight: "1.65",
          }}
        >
          {code}
        </SyntaxHighlighter>
      ) : (
        // 未知语言降级：纯文本保留（不丢内容）
        <pre className="px-3 py-2 font-mono text-[13px] leading-6 whitespace-pre-wrap break-all">
          {code}
        </pre>
      )}
    </div>
  );
}

/**
 * 表格包装：sticky 工具栏（标签 + 复制）包裹 GFM 表格，纵向横向均可滚动
 *
 * @param onNotify 复制提示回调
 */
function TableWrapper({
  children,
  onNotify,
}: {
  children: ReactNode;
  onNotify: (message: string) => void;
}) {
  return (
    <div data-testid="table-block" className="my-3 overflow-x-auto rounded-xl border border-border">
      <div className="sticky left-0 flex items-center justify-between border-b border-border bg-surface-2 px-3 py-1.5">
        <span className="text-xs text-muted">表格</span>
        <button
          type="button"
          aria-label="复制表格"
          onClick={(event) => {
            // 在事件处理内定位表格节点（children 挂载于同 wrapper 的兄弟节点）
            const table = event.currentTarget
              .closest("[data-testid='table-block']")
              ?.querySelector("table");
            if (table) {
              void copyText(tableToText(table), onNotify);
            }
          }}
          className="flex items-center gap-1 rounded-lg px-1.5 py-0.5 text-xs text-subtle transition-colors hover:bg-surface hover:text-text focus-visible:ring-2 focus-visible:ring-brand"
        >
          <Copy size={12} aria-hidden />
          复制
        </button>
      </div>
      {children}
    </div>
  );
}

/**
 * AI 回答 Markdown 渲染（memo 化 Task 14：content 与 onNotify 引用不变即跳过重渲染，
 * 历史行不随末条流式 delta 重复解析 Markdown）
 *
 * @param content Markdown 原文
 * @param onNotify 复制提示回调
 */
export const MarkdownView = memo(function MarkdownView({ content, onNotify }: MarkdownViewProps) {
  return (
    <div data-testid="markdown-view" className="text-[15px] leading-7 text-text">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          // 代码：fence（带 language- 类）→ CodeBlock；行内 → 等宽浅底
          code({ className, children }) {
            const match = /language-(\w+)/.exec(className ?? "");
            if (match) {
              return (
                <CodeBlock
                  language={match[1]}
                  code={String(children).replace(/\n$/, "")}
                  onNotify={onNotify}
                />
              );
            }
            return (
              <code className="rounded-md bg-surface-2 px-1.5 py-0.5 font-mono text-[13px] text-brand-strong">
                {children}
              </code>
            );
          },
          // 链接：新窗口打开必带 rel（防 tab 劫持，安全基线）
          a({ href, children }) {
            return (
              <a
                href={href}
                target="_blank"
                rel="noopener noreferrer"
                className="text-brand underline decoration-brand/40 underline-offset-2 hover:text-brand-strong"
              >
                {children}
              </a>
            );
          },
          // 表格：覆写必须自带 <table>（react-markdown 传给 table 组件的是 thead/tbody 子节点），
          // 外层 sticky 工具栏 + 复制
          table({ children }) {
            return (
              <TableWrapper onNotify={onNotify}>
                <table className="w-full border-collapse text-sm">{children}</table>
              </TableWrapper>
            );
          },
          // 标题层级：h2/h3 → 设计稿 a-h 样式（15px/700/gold-deep/字距 1.5px）；
          // h1 罕见（GFM 答案惯例从 h2 起），保留紧凑阶梯
          h1: ({ children }) => <h1 className="mt-5 mb-2 text-xl font-semibold">{children}</h1>,
          h2: ({ children }) => <h2 className="a-h mt-2 mb-1">{children}</h2>,
          h3: ({ children }) => <h3 className="a-h mt-2 mb-1">{children}</h3>,
          p: ({ children }) => <p className="my-2">{children}</p>,
          // 无序列表：菱形 bullet（a-li，去默认圆点）；有序列表保留十进制编号（步骤语义，
          // ol 下的 a-li 菱形由 CSS 作用域关闭）
          ul: ({ children }) => <ul className="my-2 list-none space-y-1 pl-0">{children}</ul>,
          ol: ({ children }) => <ol className="my-2 list-decimal space-y-1 pl-6">{children}</ol>,
          li: ({ children }) => <li className="a-li pl-1">{children}</li>,
          // strong 强调：brand-strong 深棕（Task 11 映射覆写）
          strong: ({ children }) => (
            <strong className="font-bold text-brand-strong">{children}</strong>
          ),
          blockquote: ({ children }) => (
            <blockquote className="my-2 border-l-2 border-brand/40 pl-3 text-muted">
              {children}
            </blockquote>
          ),
          hr: () => <hr className="my-4 border-border" />,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
});
