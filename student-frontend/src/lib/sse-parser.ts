/**
 * SSE 流式解析器
 *
 * 将 fetch + ReadableStream 解码得到的任意分块文本喂入返回的管道函数，内部维护
 * 跨 chunk 缓冲与当前帧字段，按 SSE 协议（WHATWG server-sent-events）解析出完整帧后
 * 同步回调。纯函数无外部依赖，可独立单元测试（核心 100% 目标）。
 *
 * 后端帧格式实证（MemoryStreamBridge + ChatStreamEntry，SseEmitter 序列化）：
 * - 命名事件：`id:<seq>\nevent:<name>\ndata:<原始JSON>\n\n`（data 不加引号，
 *   Spring 序列化冒号后无空格）
 * - 心跳保活：注释行 `:heartbeat\n\n`（SseEmitter.event().comment("heartbeat") 产生）
 * - 事件名 11 种：metadata / thinking / thinking_end / delta / query_plan /
 *   tool_call / tool_result / sources / stage / error / end；id 行每条事件可有可无
 *   （2026-08-30 对齐设计稿：query_plan/stage 由消费侧忽略，解析器原样透传）
 *
 * 解析规则（对齐 SSE 规范）：
 * - 空行终止一帧：出现过 data 行 → onEvent（event: 缺省取规范默认 "message"）；
 *   无 data 但出现过注释行 → onHeartbeat（后端心跳帧即此类）；两者皆无 → 丢弃
 *   （空帧 / 纯 id 帧 / 纯未知字段帧）
 * - 多条 data 行按规范以换行拼接，派发时去掉末尾行间换行（data:a\ndata:b → "a\nb"）
 * - 字段值按规范剥离单个前导空格（": " 分隔可选——event:metadata 与 event: metadata 等价；
 *   BUG-26：中间层按规范写 `data: {...}` 带空格时不再产生 " metadata" 类错位事件名）
 * - data 值原样透传，不加引号、不做任何转义
 * - 注释行内容忽略；无冒号行与未知字段行忽略（retry 等后续扩展不影响帧解析）
 * - 行尾兼容 \n 与 \r\n；一行可能被任意切分跨多个 chunk，由残行缓冲兜底
 */
export interface SseHandlers {
  /** 一次完整事件帧派发：事件名、data 原文（无引号）、id（无 id 行为 null） */
  onEvent(name: string, data: string, id: string | null): void;
  /** 心跳保活帧派发（注释行 + 空行，后端保活连接用） */
  onHeartbeat(): void;
}

/**
 * 创建流式 SSE 解析器：喂入 ReadableStream 任意分块的字符串，内部缓冲处理跨 chunk 残包
 *
 * @param h 事件回调集合（onEvent / onHeartbeat），解析出完整帧时即时回调
 * @returns 管道函数：每次调用喂入一个文本分块；单线程顺序喂入即可保证正确性
 */
export function createSseParser(h: SseHandlers): (chunk: string) => void {
  // ── 解析器状态（闭包内累积，单线程顺序喂入无并发问题）──
  let pendingLine = ""; // 尚未遇到换行符的残行（跨 chunk 累积）
  let eventName = ""; // 当前帧 event: 字段值（空串 = 缺省 "message"）
  let data = ""; // 当前帧 data: 字段拼接缓冲（行间以 \n 分隔）
  let id: string | null = null; // 当前帧 id: 字段值（无 id 行为 null）
  let hasData = false; // 当前帧是否出现过 data 行（空值 data: 也算）
  let hasComment = false; // 当前帧是否出现过注释行

  /**
   * 处理一行（不含行尾符）：空行终止当前帧并派发；字段行累加进帧状态
   * @param line 去除 \n / \r\n 之后的单行文本
   */
  function processLine(line: string): void {
    if (line.length === 0) {
      // 空行 = 帧结束：按 SSE 规范派发并清空帧状态
      if (hasData) {
        // data 拼接缓冲末尾是行间分隔换行，派发前去掉（多 data 行 → "a\nb"）
        h.onEvent(eventName === "" ? "message" : eventName, data.slice(0, -1), id);
      } else if (hasComment) {
        // 无 data 仅含注释行 = 心跳保活帧（后端 :heartbeat\n\n）
        h.onHeartbeat();
      }
      // 空帧 / 纯 id 帧：无 data 无注释，不派发任何回调
      eventName = "";
      data = "";
      id = null;
      hasData = false;
      hasComment = false;
      return;
    }
    if (line.startsWith(":")) {
      // SSE 注释行（心跳帧 :heartbeat）：内容忽略，仅记录存在性供帧末判断
      hasComment = true;
      return;
    }
    const colon = line.indexOf(":");
    if (colon === -1) {
      // 无冒号的行按规范忽略（非法行不打断当前帧）
      return;
    }
    const field = line.slice(0, colon);
    // 规范：字段值剥离「单个」前导空格（": " 分隔可选；只剥一个，多空格属值内容）
    let value = line.slice(colon + 1);
    if (value.startsWith(" ")) {
      value = value.slice(1);
    }
    if (field === "data") {
      // data 行：值追加换行作为行间分隔（规范要求，派发时去掉末尾）
      data += value + "\n";
      hasData = true;
    } else if (field === "event") {
      eventName = value;
    } else if (field === "id") {
      id = value;
    }
    // 其余字段（retry 等）暂不消费，忽略即可
  }

  /**
   * 返回的管道函数：喂入文本分块，逐行扫描；行尾残片并入 pendingLine 等待下一 chunk
   * @param chunk ReadableStream 解码出的一个文本分块（任意切分点）
   */
  return (chunk: string): void => {
    let i = 0;
    while (i < chunk.length) {
      const nl = chunk.indexOf("\n", i);
      if (nl === -1) {
        // 本 chunk 剩余部分无换行：并入残行缓冲，等后续 chunk 补全后处理
        pendingLine += chunk.slice(i);
        return;
      }
      // 完整行 = 残行缓冲 + 本 chunk 片段；\r\n 行尾去掉 \r（跨 chunk 拆开的 \r 也在此兜住）
      let line = pendingLine + chunk.slice(i, nl);
      pendingLine = "";
      if (line.endsWith("\r")) {
        line = line.slice(0, -1);
      }
      processLine(line);
      i = nl + 1;
    }
  };
}
