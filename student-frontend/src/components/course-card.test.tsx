/**
 * CourseCard 课程卡测试（Task 8 TDD 先行用例）
 *
 * 覆盖：字段渲染（标题/讲师/课时/星级/学习人数 + 课程跳转链接）、无封面学科渐变兜底
 * （category 映射与 null 默认渐变）、封面加载失败（onErrorCapture 捕获底层 img error 切换兜底）、
 * 标题 2 行截断 class、meta 行按需渲染（可空字段缺失时对应项省略）。
 */
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { CourseCard } from "./course-card";
import type { StudentCourse } from "@/lib/types";

/** 构造课程对象（部分字段可覆盖，模拟 J1 各字段形态） */
function makeCourse(overrides: Partial<StudentCourse> = {}): StudentCourse {
  return {
    id: "c-1",
    title: "数据结构与算法",
    coverImage: null,
    category: null,
    instructorName: "王老师",
    duration: "32",
    rating: 4.5,
    learningCount: 256,
    ...overrides,
  };
}

describe("CourseCard 课程卡", () => {
  it("渲染全部字段并指向课程跳转链接", () => {
    render(
      <CourseCard course={makeCourse({ coverImage: "http://localhost:9000/bucket/java.jpg" })} />,
    );
    const link = screen.getByRole("link", { name: /数据结构与算法/ });
    expect(link).toHaveAttribute("href", "/courses/c-1");
    expect(screen.getByRole("heading", { level: 3 })).toHaveTextContent("数据结构与算法");
    expect(screen.getByText("王老师")).toBeInTheDocument();
    expect(screen.getByText("32")).toBeInTheDocument();
    expect(screen.getByText("4.5")).toBeInTheDocument();
    expect(screen.getByText("256 人学习")).toBeInTheDocument();
    // 封面图渲染（next/image 渲染为 img，alt 承载课程名）
    expect(screen.getByAltText("数据结构与算法")).toBeInTheDocument();
  });

  it("无封面：category 映射低饱和渐变学科兜底", () => {
    const { rerender } = render(<CourseCard course={makeCourse({ category: "计算机科学" })} />);
    expect(screen.getByTestId("cover-fallback")).toHaveClass("from-sky-100");
    // 切换为未知 category：走默认渐变
    rerender(<CourseCard course={makeCourse({ category: null })} />);
    expect(screen.getByTestId("cover-fallback")).toHaveClass("from-brand-light");
  });

  it("标题 2 行截断 class 就位", () => {
    render(<CourseCard course={makeCourse()} />);
    expect(screen.getByRole("heading", { level: 3 })).toHaveClass("line-clamp-2");
  });

  it("meta 行按需渲染：可空字段缺失时省略对应项，学习人数恒展示", () => {
    render(
      <CourseCard course={makeCourse({ instructorName: null, duration: null, rating: null })} />,
    );
    expect(screen.queryByText("王老师")).not.toBeInTheDocument();
    expect(screen.queryByText("32")).not.toBeInTheDocument();
    expect(screen.queryByText("4.5")).not.toBeInTheDocument();
    expect(screen.getByText("256 人学习")).toBeInTheDocument();
  });

  it("星级评分保留一位小数展示", () => {
    render(<CourseCard course={makeCourse({ rating: 4 })} />);
    expect(screen.getByText("4.0")).toBeInTheDocument();
  });

  it("封面加载失败：回退学科渐变兜底", () => {
    render(
      <CourseCard course={makeCourse({ coverImage: "http://localhost:9000/bucket/x.jpg" })} />,
    );
    const cover = screen.getByAltText("数据结构与算法");
    // next/image 底层 img error 事件不冒泡，经捕获阶段被封面容器 onErrorCapture 接住
    fireEvent.error(cover);
    expect(screen.queryByAltText("数据结构与算法")).not.toBeInTheDocument();
    expect(screen.getByTestId("cover-fallback")).toBeInTheDocument();
  });
});
