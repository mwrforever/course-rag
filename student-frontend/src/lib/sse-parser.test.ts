/**
 * SSE 流式解析器测试（核心 100%：对话流式消费链路的解析地基）
 *
 * 帧格式以后端实证为准（MemoryStreamBridge + ChatStreamEntry，SseEmitter 序列化）：
 * - 命名事件：`id:<seq>\nevent:<name>\ndata:<原始JSON>\n\n`（data 不加引号）
 * - 心跳保活：注释行 `:heartbeat\n\n`（SseEmitter.event().comment() 产生）
 * - id 行每条事件可有可无；data 可多行（按规范拼接 a\nb）
 *
 * 用例组与 Task 10 brief Step 1 的 7 组一一对应，另补边界：空帧 / \r\n 行尾 /
 * 连续多帧一 chunk / 事件名任意字符串 / 无冒号行与未知字段忽略 / 无 event 行默认 message。
 */
import { describe, expect, it } from "vitest";
import { createSseParser, type SseHandlers } from "./sse-parser";

/** 记录解析回调的收件桶：断言触发序列、参数内容与心跳次数 */
function createSink() {
  // 事件数组与心跳计数均是基本类型/引用值，回调必须经同一个对象引用写入，
  // 返回时不得展开拷贝（展开会复制计数快照，回调自增不可见）
  const sink = {
    events: [] as Array<{ name: string; data: string; id: string | null }>,
    heartbeats: 0,
    handlers: {} as SseHandlers,
  };
  sink.handlers = {
    onEvent(name, data, id) {
      sink.events.push({ name, data, id });
    },
    onHeartbeat() {
      sink.heartbeats += 1;
    },
  };
  return sink;
}

describe("createSseParser 帧解析", () => {
  it("用例1 完整帧：id/event/data 三行 + 空行 → 一次 onEvent", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed('id:1\nevent:metadata\ndata:{"studentId":"9"}\n\n');
    expect(sink.events).toEqual([{ name: "metadata", data: '{"studentId":"9"}', id: "1" }]);
    expect(sink.heartbeats).toBe(0);
  });

  it("用例2 跨 chunk 残包：拆在两段的帧合并后只触发一次", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed("id:2\nevent:del");
    feed('ta\ndata:{"text":"a"}\n\n');
    expect(sink.events).toEqual([{ name: "delta", data: '{"text":"a"}', id: "2" }]);
    expect(sink.heartbeats).toBe(0);
  });

  it("用例3 注释行 :heartbeat → onHeartbeat（非 onEvent），支持跨 chunk 残包", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed(":heartbeat\n\n");
    expect(sink.heartbeats).toBe(1);
    expect(sink.events).toEqual([]);
    // 心跳帧本身也可被拆成多个 chunk 喂入
    feed(":heart");
    feed("beat\n\n");
    expect(sink.heartbeats).toBe(2);
    expect(sink.events).toEqual([]);
  });

  it("用例4 无 id 行的帧 → onEvent 的 id 参数为 null", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed("event:delta\ndata:x\n\n");
    expect(sink.events).toEqual([{ name: "delta", data: "x", id: null }]);
  });

  it("用例5 data 多行拼接：data:a + data:b → a\\nb（末尾以空行终止）", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed("event:delta\ndata:a\ndata:b\n\n");
    expect(sink.events).toEqual([{ name: "delta", data: "a\nb", id: null }]);
  });

  it("用例6 半帧（无空行终止）不触发任何回调，补上空行后才派发", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed("id:3\nevent:delta\ndata:partial");
    expect(sink.events).toEqual([]);
    expect(sink.heartbeats).toBe(0);
    feed("\n\n");
    expect(sink.events).toEqual([{ name: "delta", data: "partial", id: "3" }]);
  });

  it("用例7 data 原始 JSON 串不加引号原样透传", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    const payload = '{"runId":"8","status":"COMPLETED","messageId":"7"}';
    feed(`id:1\nevent:end\ndata:${payload}\n\n`);
    expect(sink.events).toEqual([{ name: "end", data: payload, id: "1" }]);
  });

  it("边界 空帧与纯 id 帧：无 data 无注释，不触发任何回调", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed("\n\n");
    feed("id:5\n\n");
    expect(sink.events).toEqual([]);
    expect(sink.heartbeats).toBe(0);
  });

  it("边界 \\r\\n 行尾兼容（含跨 chunk 拆开的 CRLF）", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed("id:9\r");
    feed("\nevent:delta\r\ndata:x\r\n\r\n");
    expect(sink.events).toEqual([{ name: "delta", data: "x", id: "9" }]);
  });

  it("边界 一个 chunk 内连续多帧依次触发，帧间状态互不污染", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed("id:1\nevent:thinking\ndata:t\n\nid:2\nevent:delta\ndata:d\n\n");
    expect(sink.events).toEqual([
      { name: "thinking", data: "t", id: "1" },
      { name: "delta", data: "d", id: "2" },
    ]);
  });

  it("边界 事件名任意字符串原样透传", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed("event:any name here 42\ndata:x\n\n");
    expect(sink.events).toEqual([{ name: "any name here 42", data: "x", id: null }]);
  });

  it("边界 无冒号行与未知字段行忽略，不影响其所在帧", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed("garbage\nevent:delta\nretry:1000\ndata:x\n\n");
    expect(sink.events).toEqual([{ name: "delta", data: "x", id: null }]);
  });

  it("边界 无 event 行按规范默认消息类型 message；data: 空值帧照常触发", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed("data:x\n\n");
    feed("data:\n\n");
    expect(sink.events).toEqual([
      { name: "message", data: "x", id: null },
      { name: "message", data: "", id: null },
    ]);
  });

  it("边界 事件帧内含注释行：注释忽略，仍只触发一次事件而非心跳", () => {
    const sink = createSink();
    const feed = createSseParser(sink.handlers);
    feed(":comment\nevent:delta\n:ignore\ndata:y\n\n");
    expect(sink.events).toEqual([{ name: "delta", data: "y", id: null }]);
    expect(sink.heartbeats).toBe(0);
  });
});
