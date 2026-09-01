"use client";

/**
 * 登录页视图 —— 左右分栏沉浸式（设计稿二完整还原，中文化 + 移除第三方登录）
 *
 * 结构：左表单（返回条 + 衬线标题 + 登录/注册双 Tab 指示器 + 双面板切换动画 +
 * 底部 toast）｜右影像（vIn 缩放入场 + 上浮引语 + 徽记 + 鼠标视差）。
 * 业务接线：Tab 支持 ?tab=register 直达注册（shareable），成功后按 ?next 回跳；
 * 视差仅在 pointer:fine 生效；reduced-motion 全静态。
 */
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { LoginPanel } from "@/components/auth/login-panel";
import { RegisterPanel } from "@/components/auth/register-panel";
import { useAuth } from "@/lib/auth-context";

/** 表单面板入场延迟阶梯（秒，fade-up 逐层错峰） */
const FADE_STEPS = [0.05, 0.12, 0.2, 0.26, 0.34] as const;

/** 底部流程 toast 文案容器 */
type Toast = { text: string } | null;

/**
 * 登录页主视图
 */
export function LoginView() {
  const router = useRouter();
  const searchParams = useSearchParams();
  // 初始态受 URL 控制：?tab=register 打开即注册面板
  const [isSignIn, setIsSignIn] = useState(true);
  const [toast, setToast] = useState<Toast>(null);
  const toastTimerRef = useRef<number | null>(null);
  // 自动续期回跳已尝试标记（防重复跳转与用户在表单输入时被跳走）
  const autoResumeRef = useRef(false);
  // 登录态（AuthProvider 根布局挂载：挂载静默续期——localStorage 有 RT 即恢复登录态）
  const { isAuthenticated, isLoading } = useAuth();

  useEffect(() => {
    if (searchParams.get("tab") === "register") {
      setIsSignIn(false);
    }
  }, [searchParams]);

  // 2026-08-30 登录态保持修复：middleware 查 commerce_token（AT）或 c_rt_live 任一
  // cookie 存在即放行，仅两者皆无（真匿名/清空凭据）才重定向 /login。本页静默续期回跳
  // 覆盖该兜底路径——AuthProvider 挂载后用 localStorage 的 RT 静默续期恢复登录态，
  // 检测续期完成且已登录 → 自动回跳 ?next，用户无感续期，不被强制手动重新登录。
  useEffect(() => {
    if (autoResumeRef.current || isLoading || !isAuthenticated) {
      return;
    }
    autoResumeRef.current = true;
    const raw = searchParams.get("next");
    router.replace(raw && raw.startsWith("/") && !raw.startsWith("//") ? raw : "/");
  }, [isAuthenticated, isLoading, router, searchParams]);

  /** 切换 Tab 并同步 URL（replace 不产生历史噪音） */
  function switchTab(toSignIn: boolean) {
    setIsSignIn(toSignIn);
    const nextUrl = new URL(window.location.href);
    if (toSignIn) {
      nextUrl.searchParams.delete("tab");
    } else {
      nextUrl.searchParams.set("tab", "register");
    }
    window.history.replaceState(null, "", nextUrl);
  }

  /** 展示底部 toast（3.2s 自动消失，同设计稿时序） */
  function showToast(text: string) {
    setToast({ text });
    if (toastTimerRef.current != null) {
      clearTimeout(toastTimerRef.current);
    }
    toastTimerRef.current = window.setTimeout(() => setToast(null), 3200);
  }

  /** 登录成功：回跳目标取 ?next（仅放行站内相对路径，防开放重定向）；非法值收敛回首页 */
  function handleLoginSuccess() {
    const raw = searchParams.get("next");
    showToast("登录成功，正在带你回到学习现场…");
    router.push(raw && raw.startsWith("/") && !raw.startsWith("//") ? raw : "/");
  }

  /**
   * 注册成功：凭据已由 api 层落存（AT 内存 + RT localStorage），
   * 但 AuthProvider 用户态需经「挂载静默续期」建立——整页导航触发完整挂载
   * （与 E2E RT 注入建立登录态同一条链路，避免客户端上下文与存储不一致）
   */
  function handleRegisterSuccess() {
    showToast("账户创建成功——欢迎来到问渠学堂");
    window.location.assign("/");
  }

  return (
    <div className="grid min-h-screen lg:grid-cols-[1fr_46vw]">
      {/* ===== 左：表单 ===== */}
      <div className="flex flex-col px-[clamp(24px,6vw,110px)]">
        {/* 返回条 */}
        <div className="grid grid-cols-[1fr_auto_1fr] items-center pt-7 pb-2.5">
          <Link
            href="/"
            className="inline-flex items-center gap-2 text-[10.5px] tracking-[0.16em] text-muted uppercase transition-colors hover:text-ink"
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              aria-hidden
              className="size-4 transition-transform duration-300 group-hover:-translate-x-1 hover:-translate-x-0.5"
            >
              <path d="M20 12H4M11 5l-7 7 7 7" />
            </svg>
            返回首页
          </Link>
          <Link
            href="/"
            className="font-serif-display text-xl tracking-[0.22em] uppercase fade-up"
            style={{ ["--d" as string]: `${FADE_STEPS[1]}s` }}
          >
            问渠学堂
          </Link>
          <span />
        </div>

        <div className="mx-auto flex w-full max-w-[480px] flex-1 flex-col justify-center py-9 pb-[60px]">
          <p
            className="text-accent-italic fade-up text-[28px]"
            style={{ ["--d" as string]: `${FADE_STEPS[2]}s` }}
          >
            {isSignIn ? "欢迎回来" : "初次见面"}
          </p>
          <h1
            className="font-serif-display fade-up mt-2 mb-8 text-[clamp(30px,3.2vw,42px)] leading-tight font-medium"
            style={{ ["--d" as string]: `${FADE_STEPS[3]}s` }}
          >
            {isSignIn ? (
              <>
                登录你的
                <br />
                问渠学堂账户
              </>
            ) : (
              <>
                创建账户，
                <br />
                开始有据可答的学习
              </>
            )}
          </h1>

          {/* 双 Tab + 滑动指示器 */}
          <div
            className={`relative flex border-b border-border fade-up ${isSignIn ? "" : ""}`}
            style={{ ["--d" as string]: `${FADE_STEPS[4]}s` }}
            role="tablist"
            aria-label="登录或注册"
          >
            <button
              type="button"
              role="tab"
              aria-selected={isSignIn}
              data-testid="tab-signin"
              onClick={() => switchTab(true)}
              className={`flex-1 pt-4 pb-[17px] font-serif-display text-[21px] transition-colors ${isSignIn ? "text-ink" : "text-muted hover:text-text"}`}
            >
              登录
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={!isSignIn}
              data-testid="tab-register"
              onClick={() => switchTab(false)}
              className={`flex-1 pt-4 pb-[17px] font-serif-display text-[21px] transition-colors ${!isSignIn ? "text-ink" : "text-muted hover:text-text"}`}
            >
              创建账户
            </button>
            <span
              aria-hidden
              className="absolute bottom-[-1px] left-0 h-0.5 w-1/2 bg-ink transition-transform duration-500 ease-out"
              style={{ transform: isSignIn ? "translateX(0)" : "translateX(100%)" }}
              data-testid="tab-indicator"
            />
          </div>

          <div className="relative overflow-hidden">
            <div
              className={`${isSignIn ? "block" : "hidden"} pt-8`}
              style={
                isSignIn ? { animation: "pane-in .55s cubic-bezier(.22,.61,.36,1)" } : undefined
              }
              key="pane-in-block"
            >
              <LoginPanel onSuccess={handleLoginSuccess} />
            </div>
            {!isSignIn ? (
              <div
                className="pt-8"
                style={{ animation: "pane-in .55s cubic-bezier(.22,.61,.36,1)" }}
                key="pane-up-block"
              >
                <RegisterPanel onSuccess={handleRegisterSuccess} />
              </div>
            ) : null}
          </div>
        </div>
      </div>

      {/* ===== 右：影像 + 引语（lg 及以上展示） ===== */}
      <LoginVisual />

      {/* 底部 toast */}
      {toast ? (
        <div
          role="status"
          data-testid="auth-toast"
          className="fixed bottom-[30px] left-1/2 z-50 -translate-x-1/2 rounded-full bg-ink px-7 py-[15px] text-xs tracking-[0.12em] text-bg uppercase shadow-xl"
          style={{ animation: "up-fade .45s cubic-bezier(.22,.61,.36,1)" }}
        >
          {toast.text}
        </div>
      ) : null}
    </div>
  );
}

/**
 * 右侧影像区（鼠标视差仅 pointer:fine；reduced-motion 静态）
 */
function LoginVisual() {
  const imgRef = useRef<HTMLImageElement>(null);

  useEffect(() => {
    if (
      !window.matchMedia("(pointer:fine)").matches ||
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
    ) {
      return;
    }
    let vx = 0;
    let vy = 0;
    let tx = 0;
    let ty = 0;
    let rafId = 0;
    const onMove = (event: MouseEvent) => {
      tx = (event.clientX / window.innerWidth - 0.5) * 18;
      ty = (event.clientY / window.innerHeight - 0.5) * 12;
    };
    const loop = () => {
      vx += (tx - vx) * 0.06;
      vy += (ty - vy) * 0.06;
      if (imgRef.current) {
        imgRef.current.style.transform = `translate3d(${vx.toFixed(1)}px, ${vy.toFixed(1)}px, 0)`;
      }
      rafId = requestAnimationFrame(loop);
    };
    window.addEventListener("mousemove", onMove);
    rafId = requestAnimationFrame(loop);
    return () => {
      window.removeEventListener("mousemove", onMove);
      cancelAnimationFrame(rafId);
    };
  }, []);

  return (
    <div
      className="relative hidden overflow-hidden bg-surface-deep lg:block"
      data-testid="login-visual"
    >
      <img
        ref={imgRef}
        src="/images/login-visual.jpg"
        alt=""
        className="absolute -inset-[6%] h-[112%] w-[112%] object-cover brightness-90 will-change-transform"
        style={{
          filter: "sepia(.08) brightness(.9)",
          animation: "v-in 1.6s cubic-bezier(.22,.61,.36,1) both",
        }}
      />
      <div
        className="absolute inset-0"
        style={{ background: "linear-gradient(rgb(18 12 8 / 28%), rgb(18 12 8 / 62%) 80%)" }}
      />

      <div className="absolute inset-0 z-[2] flex flex-col justify-between p-[clamp(28px,4vw,60px)] text-bg">
        <div />
        <div className="mt-auto">
          <p
            className="text-script"
            style={{
              fontSize: "clamp(30px,2.8vw,42px)",
              animation: "up-fade 1s .5s cubic-bezier(.22,.61,.36,1) both",
            }}
          >
            旧学之蕴，新知之源
          </p>
          <h2
            className="font-serif-display mt-3 max-w-[520px] text-[clamp(32px,3.4vw,52px)] leading-[1.12] font-medium"
            style={{ animation: "up-fade 1s .65s cubic-bezier(.22,.61,.36,1) both" }}
          >
            每一位求学者，都值得被认真回答。
          </h2>
          <div
            className="mt-11 flex items-center gap-4"
            style={{ animation: "up-fade 1s .8s cubic-bezier(.22,.61,.36,1) both" }}
          >
            <svg
              viewBox="0 0 140 160"
              fill="none"
              stroke="currentColor"
              aria-hidden
              className="w-[52px] shrink-0 text-cream-200"
            >
              <path
                d="M70 6 L130 24 V84 c0 40 -28 62 -60 74 C38 146 10 124 10 84 V24 Z"
                strokeWidth="3"
              />
              <text
                x="70"
                y="92"
                textAnchor="middle"
                fontSize="58"
                fill="currentColor"
                stroke="none"
                style={{ fontFamily: "var(--font-display)" }}
              >
                问
              </text>
            </svg>
            <small className="text-[9px] leading-relaxed tracking-[0.24em] text-bg/70 uppercase">
              问渠学堂 Wenqu Academy · Est. 2026
              <br />
              为有源头活水来
            </small>
          </div>
        </div>
      </div>
    </div>
  );
}
