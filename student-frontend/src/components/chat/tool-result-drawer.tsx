"use client";

/**
 * 工具结果抽屉（2026-08-30 工具结果侧栏展示，对齐设计稿「查询工具同样如此」）
 *
 * 查询工具（CourseApiTool）的执行结果经 TOOL_RESULT 事件到达（SSE 与历史行同构），
 * 完成态工具步骤点击后在右侧抽屉结构化展示：
 * - 深色头部条：标题「查询到的课程资料」+ 工具名人话 + 调用参数摘要 + 关闭按钮
 * - 结果卡片列表：数组元素（如课程列表 courses[]）逐元素一张卡——标题优先取
 *   title/courseName/name 等字段，其余标量字段按中文标签逐行展示（嵌套对象/数组
 *   以 JSON 预览截断兜底）；对象（详情/报名）单卡展示
 * - 底部「查看原始 JSON」折叠：完整输出原文（mono 可滚动），便于排查
 * - 交互：Esc / 遮罩点击 / 关闭按钮三种关闭路径（与召回抽屉同构）
 */
import { Wrench, X } from "@phosphor-icons/react";
import { useEffect, useState } from "react";
import { toolNameLabel } from "./op-step";

/** 工具结果抽屉 props */
export interface ToolResultDrawerProps {
  /** 工具节点（null = 抽屉关闭，不渲染） */
  tool: { toolName: string; input: unknown; output: unknown } | null;
  /** 关闭回调（关闭按钮 / Esc / 点击遮罩触发） */
  onClose: () => void;
}

/** 标题字段优先序（对象中取首个非空字符串作卡片标题） */
const TITLE_KEYS = ["title", "courseName", "name", "courseTitle", "keyword"];

/** 字段中文标签映射（未收录键原样回退，保证不空白） */
const FIELD_LABELS: Record<string, string> = {
  courseId: "课程ID",
  title: "标题",
  category: "分类",
  price: "价格",
  discount: "优惠",
  difficulty: "难度",
  status: "状态",
  nextStartDate: "下次开课",
  tags: "标签",
  courseName: "课程名",
  courseTitle: "课程名",
  name: "名称",
  keyword: "关键词",
  introContent: "课程简介",
  syllabusContent: "教学大纲",
  instructorContent: "讲师介绍",
  faqContent: "常见问题",
  enrollmentUrl: "报名链接",
  nextSchedule: "下期排期",
  instructor: "讲师",
  schedules: "排期",
  duration: "时长",
  totalLessons: "课时",
  schedule: "上课节奏",
  bio: "简介",
  page: "页码",
  pageSize: "每页条数",
  total: "总条数",
};

/** 结果卡片（标题 + 字段行列表） */
interface ResultCard {
  title: string;
  lines: string[];
}

/** 字段值文本长度上限（超长截断，卡片保持可扫读） */
const VALUE_MAX_LENGTH = 60;

/** 取对象标题字段（标题优先序中首个非空字符串；无则 null） */
function pickTitle(value: Record<string, unknown>): string | null {
  for (const key of TITLE_KEYS) {
    const v = value[key];
    if (typeof v === "string" && v.trim()) {
      return v.trim();
    }
  }
  return null;
}

/** 字段值 → 展示文本（标量直取；对象/数组以 JSON 预览截断兜底；null/undefined 空串） */
function valueText(value: unknown): string {
  if (value === null || value === undefined) {
    return "";
  }
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    const text = String(value);
    return text.length > VALUE_MAX_LENGTH ? `${text.slice(0, VALUE_MAX_LENGTH)}…` : text;
  }
  let json: string;
  try {
    json = JSON.stringify(value);
  } catch {
    json = String(value);
  }
  return json.length > VALUE_MAX_LENGTH ? `${json.slice(0, VALUE_MAX_LENGTH)}…` : json;
}

/** 对象 → 卡片（标题取标题字段，其余标量字段按中文标签逐行；标题字段与嵌套大对象不进行） */
function objectToCard(value: Record<string, unknown>): ResultCard {
  const lines: string[] = [];
  for (const [key, v] of Object.entries(value)) {
    if (TITLE_KEYS.includes(key)) {
      continue;
    }
    const text = valueText(v);
    if (!text) {
      continue;
    }
    const label = FIELD_LABELS[key] ?? key;
    lines.push(`${label}: ${text}`);
  }
  return { title: pickTitle(value) ?? "条目", lines };
}

/** 工具输出 → 结果卡片列表（数组逐元素成卡；对象取 courses[] 数组或单卡；标量单卡） */
function toResultCards(output: unknown): ResultCard[] {
  if (output === null || output === undefined) {
    return [];
  }
  if (Array.isArray(output)) {
    return output.length === 0
      ? []
      : output.map((item, index) =>
          item !== null && typeof item === "object"
            ? objectToCard(item as Record<string, unknown>)
            : { title: `条目 ${index + 1}`, lines: [valueText(item)] },
        );
  }
  if (typeof output === "object") {
    const obj = output as Record<string, unknown>;
    // 分页课程列表：courses 数组逐课程成卡（设计稿「查询到的课程资料」文件卡语义）
    if (Array.isArray(obj["courses"]) && (obj["courses"] as unknown[]).length > 0) {
      return (obj["courses"] as unknown[]).map((course) =>
        objectToCard(course as Record<string, unknown>),
      );
    }
    return [objectToCard(obj)];
  }
  return [{ title: "结果", lines: [valueText(output)] }];
}

/**
 * 工具结果抽屉（tool 非 null 时挂载）
 *
 * @param tool 工具节点（toolName/input/output；input 为模型调用参数、output 为工具返回）
 * @param onClose 关闭回调
 */
export function ToolResultDrawer({ tool, onClose }: ToolResultDrawerProps) {
  // 原始 JSON 折叠开关（默认收起；点击展开查看完整输出）
  const [rawOpen, setRawOpen] = useState(false);

  // Esc 关闭监听（抽屉开启期间挂载；hook 无条件调用，内部按 tool 判空）
  useEffect(() => {
    if (tool === null) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [tool, onClose]);

  if (tool === null) {
    return null;
  }

  const cards = toResultCards(tool.output);
  const label = toolNameLabel(tool.toolName);

  return (
    <div className="fixed inset-0 z-50">
      {/* 遮罩：点击空白关闭（仅 opacity 动画） */}
      <div
        data-testid="tool-drawer-overlay"
        aria-hidden
        onClick={onClose}
        className="absolute inset-0 animate-overlay-in bg-overlay motion-reduce:animate-none"
      />
      {/* 抽屉面板：右侧 440px 滑入（与召回抽屉同构） */}
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="工具查询结果"
        data-testid="tool-drawer"
        className="absolute top-0 right-0 flex h-full w-full max-w-[440px] animate-drawer-in flex-col bg-surface shadow-xl motion-reduce:animate-none"
      >
        {/* 浅色头部条（2026-08-30 对齐设计稿：与召回抽屉同构，无深色底） */}
        <header className="flex shrink-0 items-center gap-3 border-b border-border bg-surface px-5 py-4">
          <span className="grid size-9 shrink-0 place-items-center rounded-full bg-brand-soft text-brand-strong">
            <Wrench size={16} aria-hidden />
          </span>
          <div className="min-w-0 flex-1">
            <h3 className="font-display text-base font-semibold tracking-wide">查询到的课程资料</h3>
            <p className="truncate text-xs text-muted" data-testid="tool-drawer-sub">
              {label} · {valueText(tool.input) || "无入参"}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭工具结果抽屉"
            className="grid size-8 shrink-0 place-items-center rounded-full text-muted transition-colors hover:bg-brand-soft hover:text-brand-strong focus-visible:ring-2 focus-visible:ring-brand"
          >
            <X size={15} aria-hidden />
          </button>
        </header>
        {/* 结果卡片列表（错峰进场；空输出降级空态） */}
        <div className="flex-1 overflow-y-auto p-4">
          {cards.length === 0 ? (
            <p className="px-2 py-8 text-center text-sm text-subtle">该工具无返回数据</p>
          ) : (
            <ol className="space-y-3" data-testid="tool-drawer-list">
              {cards.map((card, index) => (
                <li
                  key={`${card.title}-${index}`}
                  data-testid="tool-result-item"
                  style={{ animationDelay: `${index * 85}ms` }}
                  className="chunk-card"
                >
                  {/* 卡片标题（课程名/条目名） */}
                  <p className="text-[13.5px] leading-relaxed font-bold text-text">{card.title}</p>
                  {/* 字段行（中文标签 + 值，逐行可扫读） */}
                  {card.lines.length > 0 ? (
                    <ul className="mt-2 space-y-1">
                      {card.lines.map((line, lineIndex) => (
                        <li
                          key={lineIndex}
                          className="text-xs leading-6 break-all whitespace-pre-wrap text-muted"
                        >
                          {line}
                        </li>
                      ))}
                    </ul>
                  ) : null}
                </li>
              ))}
            </ol>
          )}
          {/* 原始 JSON 折叠（完整输出原文，排查用） */}
          <button
            type="button"
            data-testid="tool-drawer-raw-toggle"
            onClick={() => setRawOpen((prev) => !prev)}
            className="mt-4 w-full rounded-lg border border-line px-3 py-2 text-left text-xs text-muted transition-colors hover:border-brand hover:text-brand-strong"
          >
            {rawOpen ? "▲ 收起原始 JSON" : "▼ 查看原始 JSON"}
          </button>
          {rawOpen ? (
            <pre
              data-testid="tool-drawer-raw"
              className="mt-2 max-h-52 overflow-auto rounded-lg border border-line bg-cream/40 p-3 font-mono text-[11px] leading-5 whitespace-pre-wrap break-all text-muted"
            >
              {JSON.stringify(tool.output, null, 2)}
            </pre>
          ) : null}
        </div>
      </aside>
    </div>
  );
}
