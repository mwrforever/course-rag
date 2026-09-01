/**
 * CourseCard 渲染性能契约测试（PERF-07）
 *
 * 目标：props（course 引用 / purchased / priority）不变时，memo 化的 CourseCard
 * 在父级重渲染（如课程中心搜索框每键触发整页渲染）下跳过重渲染；
 * props 变化时恢复渲染（memo 不导致陈旧）。
 *
 * 手法：以渲染计数桩替换 next/image（卡片封面渲染即计数，等价于卡内渲染计数），
 * CourseCard 本体保持真实实现（其 memo 即被测对象）；独立成文件避免模块级
 * mock 影响真实 next/image 的行为测试。
 */
import { render } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

/** 封面渲染计数（vi.mock 工厂被提升，须经 vi.hoisted 共享引用） */
const counters = vi.hoisted(() => ({ image: 0 }));

vi.mock("next/image", () => ({
  default: function Image() {
    counters.image += 1;
    // eslint-disable-next-line @next/next/no-img-element -- 渲染计数桩模拟 next/image 底层 img，非真实图片加载
    return <img alt="" />;
  },
}));

import { CourseCard } from "./course-card";
import type { StudentCourse } from "@/lib/types";

/** 构造带封面的课程对象（封面存在才走 next/image 分支供计数） */
function makeCourse(): StudentCourse {
  return {
    id: "c-1",
    title: "数据结构与算法",
    coverImage: "http://localhost:9000/bucket/java.jpg",
    category: null,
    instructorName: "王老师",
    duration: "32",
    rating: 4.5,
    learningCount: 256,
    price: 299,
  };
}

beforeEach(() => {
  counters.image = 0;
});

describe("PERF-07：CourseCard memo 生效", () => {
  it("props 不变（course 同引用）时父级重渲染跳过卡片渲染", () => {
    const course = makeCourse();
    const { rerender } = render(<CourseCard course={course} />);
    expect(counters.image).toBe(1);

    // 模拟搜索框每键触发：messages/keyword 等无关状态变化 → 整页重渲染，
    // 卡片 props（course 引用来自 query data 稳定）不变
    rerender(<CourseCard course={course} />);
    rerender(<CourseCard course={course} />);
    expect(counters.image).toBe(1);
  });

  it("props 变化（purchased 翻转 / course 换引用）时恢复渲染（不陈旧）", () => {
    const course = makeCourse();
    const { rerender } = render(<CourseCard course={course} />);
    expect(counters.image).toBe(1);

    // purchased 翻转（登录后交叉标记已购）
    rerender(<CourseCard course={course} purchased />);
    expect(counters.image).toBe(2);

    // course 换引用（数据刷新产生新对象）
    rerender(<CourseCard course={{ ...course, title: "Java 从入门到进阶" }} purchased />);
    expect(counters.image).toBe(3);
  });
});
