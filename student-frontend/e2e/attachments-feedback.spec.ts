import { test, expect } from "@playwright/test";
import { mockApi, login, mockChatStream, frame } from "./helpers/sse-route";

/**
 * 附件与反馈 E2E（整合 spec §3.2 附件/反馈 组）
 *
 * - 附件超限：第 11 个文件即拒（无 POST attachments 请求）
 * - 反馈：end 携带 messageId → 点赞 → POST /student/feedbacks 请求体断言
 */

test.describe("附件与反馈", () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page);
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
