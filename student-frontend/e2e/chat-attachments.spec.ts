import { test, expect, type Page } from "@playwright/test";
import { mockApi, login, mockChatStream, frame } from "./helpers/sse-route";

/**
 * 附件 E2E（Task 12 扩容改版 + 反馈；原 attachments-feedback.spec 更名对齐任务口径）
 *
 * - 扩容形态：附件 chips 渲染进输入卡内顶部（attachment-area），border-t 与输入行分隔
 * - 预览弹窗：chip 点击 → 图片 Zoom 大图 / pdf iframe；Esc 关闭
 * - 附件超限：第 11 个文件即拒（无 POST attachments 请求）
 * - 反馈：end 携带 messageId → 点赞 → POST /student/feedbacks 请求体断言
 */

/** 附件上传成功 mock：按 multipart 内 filename 数量返回配对记录（objectKey 语义） */
async function mockAttachmentUpload(page: Page) {
  await page.route("**/api/v1/student/chat/attachments", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    const count =
      route
        .request()
        .postData()
        ?.match(/filename=/g)?.length ?? 1;
    const records = Array.from({ length: count }, (_, i) => ({
      type: "image",
      url: `obj/e2e-${i}`,
      name: `图-${i}.png`,
      size: "1024",
    }));
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ code: 0, message: "success", data: records }),
    });
  });
}

test.describe("附件与反馈", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
  });

  test("扩容形态：附件 chips 内嵌输入卡顶部，border-t 与输入行分隔（Task 12）", async ({
    page,
  }) => {
    await mockAttachmentUpload(page);
    await login(page, "/");
    await page.goto("/chat");
    // 选择一张图片 → 上传完成 chip 出现在输入卡内的附件区
    await page
      .locator('input[type="file"]')
      .setInputFiles([
        { name: "课堂截图.png", mimeType: "image/png", buffer: Buffer.from("fake-png") },
      ]);
    await expect(page.getByTestId("attachment-chip")).toBeVisible();
    // 附件区位于输入卡内且在输入行之前（图一扩容形态）
    const area = page.getByTestId("attachment-area");
    const row = page.getByTestId("chat-input-row");
    await expect(area).toBeVisible();
    const cardBox = await page.getByTestId("chat-input-card").boundingBox();
    const areaBox = await area.boundingBox();
    const rowBox = await row.boundingBox();
    expect(cardBox && areaBox && areaBox.y >= cardBox.y).toBeTruthy();
    expect(areaBox && rowBox && areaBox.y < rowBox.y).toBeTruthy();
    // 分隔线：附件区存在时输入行挂 border-t
    await expect(row).toHaveClass(/border-t/);
    // 移除钮保留：点击后附件区收起、分隔线消失
    await page.getByRole("button", { name: /移除附件/ }).click();
    await expect(page.getByTestId("attachment-area")).toHaveCount(0);
    await expect(row).not.toHaveClass(/border-t/);
  });

  test("预览弹窗：图片 chip 点击打开 Zoom 大图，Esc 关闭（Task 12）", async ({ page }) => {
    await mockAttachmentUpload(page);
    await login(page, "/");
    await page.goto("/chat");
    await page
      .locator('input[type="file"]')
      .setInputFiles([
        { name: "课堂截图.png", mimeType: "image/png", buffer: Buffer.from("fake-png") },
      ]);
    await expect(page.getByTestId("attachment-chip")).toBeVisible();
    // chip 主体点击 → 预览弹窗（portal）：图片大图（blob src）可见
    await page.getByRole("button", { name: /预览附件：课堂截图\.png/ }).click();
    await expect(page.getByRole("dialog", { name: /预览附件：课堂截图\.png/ })).toBeVisible();
    await expect(page.getByTestId("attachment-preview-image")).toBeVisible();
    // Esc 关闭
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });

  test("预览弹窗：pdf chip 点击内嵌 iframe 预览（Task 12）", async ({ page }) => {
    await page.route("**/api/v1/student/chat/attachments", async (route) => {
      if (route.request().method() !== "POST") return route.fallback();
      return route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          code: 0,
          message: "success",
          data: [{ type: "document", url: "obj/e2e-pdf", name: "讲义.pdf", size: "2048" }],
        }),
      });
    });
    await login(page, "/");
    await page.goto("/chat");
    await page
      .locator('input[type="file"]')
      .setInputFiles([
        { name: "讲义.pdf", mimeType: "application/pdf", buffer: Buffer.from("%PDF-1.4") },
      ]);
    await expect(page.getByTestId("attachment-chip")).toBeVisible();
    await page.getByRole("button", { name: /预览附件：讲义\.pdf/ }).click();
    // pdf 分支：iframe 承载 blob URL
    const pdfFrame = page.getByTestId("attachment-preview-pdf");
    await expect(pdfFrame).toBeVisible();
    await expect(pdfFrame).toHaveAttribute("src", /^blob:/);
  });

  test("附件数量超限（第 11 个）前端即拒，无上传请求", async ({ page }) => {
    let uploadCalled = 0;
    await page.route("**/api/v1/student/chat/attachments", (route) => {
      uploadCalled += 1;
      return route.fallback();
    });
    // 10 个合法小文件 + 第 11 个触发数量上限
    const files = Array.from({ length: 10 }, (_, i) => ({
      name: `note-${i + 1}.txt`,
      mimeType: "text/plain",
      buffer: Buffer.from("hello"),
    }));
    await login(page, "/");
    await page.goto("/chat");
    // 文档 file input（accept=.pdf,.doc,.docx,.txt,.md）
    const docInput = page.locator('input[type="file"][accept*=".txt"]');
    await docInput.setInputFiles(files);
    // 第 11 个：拒绝提示（数量上限文案，attachment-chips validateAttachments）
    await docInput.setInputFiles([
      ...files,
      { name: "extra.txt", mimeType: "text/plain", buffer: Buffer.from("x") },
    ]);
    await expect(page.getByText("一次最多上传 10 个文件")).toBeVisible();
    // 第一次选中（10 个合法文件）即触发 1 次上传；第 11 个被前端拒绝后不新增请求
    expect(uploadCalled).toBe(1);
  });

  test("反馈：点赞后请求体含 sessionId/messageId/isLiked", async ({ page }) => {
    await mockChatStream(
      page,
      frame("metadata", { runId: "9001", sessionId: "77", model: "qwen3.8-max" }, 1) +
        frame(
          "sources",
          { sources: [{ chunkId: "101", docTitle: "讲义", headingPath: "1", score: 0.8 }] },
          2,
        ) +
        frame("delta", { text: "回答内容" }, 3) +
        frame("end", { runId: "9001", status: "COMPLETED", messageId: "5001" }, 4),
    );
    let feedbackBody: unknown = null;
    await page.route("**/api/v1/student/feedbacks", async (route) => {
      feedbackBody = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ code: 0, message: "success", data: null }),
      });
    });
    await login(page, "/");
    await page.goto("/chat");
    await page.getByPlaceholder("输入你的问题，Enter 发送，Shift+Enter 换行").fill("提问");
    await page.getByRole("button", { name: "发送" }).click();
    await expect(page.getByRole("button", { name: "有用" })).toBeVisible();
    await page.getByRole("button", { name: "有用" }).click();
    await expect.poll(() => feedbackBody).not.toBeNull();
    // 请求体契约：{sessionId, messageId, isLiked, intentType}（sources 出现 → knowledge_question）
    expect(feedbackBody).toMatchObject({
      sessionId: "77",
      messageId: "5001",
      isLiked: true,
      intentType: "knowledge_question",
    });
  });
});
