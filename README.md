# youkeda-practice-item

> 微信 iLink Bot + 智谱 AI 智能机器人示例项目：支持**持续对话、图片识别与生成、语音收发（ASR/TTS）、天气查询**等能力。

基于 `wechat-ilink-sdk`（微信 iLink Bot SDK）+ 智谱开放平台 API + 和风天气 API 构建。

---

## 📁 项目结构

```
youkeda/
├── pom.xml                          # Maven 工程（Java 25）
├── tools/                           # 内置 SILK 语音编码器（silk_encoder.exe）
└── src/main/
    ├── java/
    │   ├── runClient.java           # 主入口：扫码登录/恢复、监听器注册、阻塞保活
    │   ├── AskAIService.java        # 智谱 chat/completions（支持 Function Calling）
    │   ├── ContextAnalyzer.java     # 微信消息 → AI 上下文解析
    │   ├── ConversationHandler.java # 对话编排：多轮历史、图片/语音处理、发送回复
    │   ├── ResumeStore.java         # SDK 恢复上下文（ResumeContext）持久化
    │   ├── config/Config.java       # 配置加载（UTF-8 properties）
    │   ├── functionalCalling/
    │   │   ├── WeatherTool.java     # get_weather 工具（和风天气）
    │   │   ├── ImageTool.java       # generate_image 工具（CogView 文生图）
    │   │   ├── VoiceTool.java       # generate_speech 工具（TTS）
    │   │   └── weather/WindDirection.java
    │   └── services/
    │       ├── ImageService.java    # 图片下载/识别(GLM-4V)/生成/发送
    │       └── VoiceService.java    # ASR/TTS/SILK 转码
    └── resources/
        ├── config.properties        # AI/天气等全部配置
        └── tools/silk_encoder.exe   # classpath 内置 SILK 编码器
```

---

## 一、项目使用方法

### 1. 环境要求

| 依赖 | 说明 |
|---|---|
| JDK 25+ | 项目使用 `void main()` 隐式类语法（Maven target 25） |
| Maven 3.6+ | 构建工具 |
| 微信 iLink Bot 账号 | 需登录 iLink 平台获取 bot 权限 |
| 智谱 AI API Key | `https://open.bigmodel.cn` 控制台创建 |
| 和风天气 Token（可选） | 天气查询工具需要，`https://dev.qweather.com` |

### 2. 配置

编辑 `youkeda/src/main/resources/config.properties`，按需填写（详见下文"二、配置文件说明"）。

### 3. 编译与运行

```bash
cd youkeda
mvn clean compile
# 运行主程序（IDE 中运行 runClient，或打包后运行）
java runClient
```

首次运行会打印**二维码**，用微信扫码登录；之后自动保存 `resume.json`，**重启免扫码**（通过 SDK `ResumeContext` 恢复）。

### 4. 功能体验

| 用户操作 | Bot 行为 |
|---|---|
| 发任意文本 | 调用智谱 AI 对话回复 |
| 发图片 | SDK 下载 → GLM-4V 识别 → 图片内容进入上下文后回复 |
| 发语音 | SDK 下载 → 服务端转写/ASR → 语音内容进入上下文后回复 |
| 说"北京天气" | AI 调用 `get_weather` 工具 → 和风天气 → 回复天气 |
| 说"画一只猫" | AI 调用 `generate_image` → CogView 生图 → 发送图片 |
| 说"用语音回复" | AI 调用 `generate_speech` → TTS → SILK 转码 → 发送语音 |

---

## 二、配置文件说明（config.properties）

| 属性 | 作用 | 说明 |
|---|---|---|
| `ai.url` | 智谱对话接口地址 | chat/completions 端点 |
| `ai.token` | 智谱 API Key | **必填**，Bearer 认证 |
| `ai.model` | 对话模型名 | **函数调用需 `glm-4.7` 及以上**；默认 `glm-4-flash`（仅文本） |
| `ai.connectTimeoutMs` | 连接超时（毫秒） | 默认 15000 |
| `ai.socketTimeoutMs` | 读取超时（毫秒） | 默认 180000，大模型处理长上下文可能较慢 |
| `ai.vision.url` | 图片识别接口 | GLM-4V（chat/completions） |
| `ai.imageGen.url` | 文生图接口 | CogView |
| `ai.asr.url` / `ai.asr.model` | 语音转文本接口/模型 | `glm-asr-2512` |
| `ai.tts.url` / `ai.tts.model` / `ai.tts.voice` | 文本转语音接口/模型/音色 | 模型 `glm-tts`，音色如 `tongtong` |
| `ai.systemPrompt` | AI 系统提示词前缀 | 引导 AI 结合对话记录回答，支持 `\n` 换行 |
| `qweather.host` / `qweather.token` | 和风天气专属 Host / JWT Token | `get_weather` 工具使用 |

> 配置文件按 **UTF-8** 加载（`Config` 使用 `InputStreamReader(UTF_8)`），中文可直接填写。

---

## 三、具体调用链

### 1. 启动 & 登录/恢复链

```
runClient.main
  ├─ ResumeStore.load()                        # 读取本地 resume.json（若有）
  ├─ ILinkClient.builder().resumeContext(ctx)  # SDK 恢复登录态、游标、会话 contextToken
  ├─ client.isLoggedIn()? ──是──▶ 免扫码，心跳自动启动
  │                     └──否──▶ executeLogin() → 打印二维码 → getLoginFuture().get()
  ├─ ResumeStore.save(client)                  # 登录成功后持久化
  └─ CountDownLatch.await()                    # 阻塞保活（Ctrl+C 退出）
```

### 2. 消息处理链（持续对话）

```
SDK HeartbeatService（每 5s）→ pollAndDispatchMessages → getupdates 拉新消息
  → onMessage 监听器（synchronized 串行）
    → ConversationHandler.handleIncomingMessage
      ├─ 过滤 bot 自身消息
      ├─ 图片消息 → ImageService 下载 → GLM-4V 识别 → 追加 [图片内容]
      ├─ 语音消息 → SDK 下载 → VoiceItem.text / ASR → 追加 [语音内容]
      ├─ 加入该用户多轮历史（chatHistories，上限 20 条）
      ├─ ContextAnalyzer.analyze(历史) → AI 上下文文本
      └─ AskAIService.askAI(client, userId, 上下文) → AI 回答
          └─ sendAIResponse：发文本 / [图片]标记→CogView 生图 / [语音]标记→TTS
  → ResumeStore.save(client)                   # 持久化最新上下文
```

### 3. Function Calling 链（AI 调工具）

```
用户："北京今天天气怎么样？"
  AskAIService.askAI 请求携带 tools=[get_weather, generate_image, generate_speech]
  → AI 返回 tool_calls(get_weather, {"city":"北京"})
  → executeToolCall 分发 → WeatherTool.execute
      ├─ 和风 /geo/v2/city/lookup 城市搜索 → 经纬度
      └─ /weather/v1/current|daily 实时+预报 → 精简 JSON
  → 结果作为 role=tool 消息返回 → 再次请求
  → AI 基于天气数据组织最终回复
```

### 4. 图片生成链

```
用户："画一只橘猫" → AI tool_calls(generate_image, {"prompt":"橘猫"})
  → ImageTool.execute → ImageService.generateImage（CogView）→ 图片 URL
  → downloadImageFromUrl → SDK sendImage 发送给用户
  → 返回 {"success":true} 给 AI → AI 最终回复确认
```

### 5. 语音收发链

```
用户发语音 → SDK downloadVoiceFromMessageItem（silk）→ VoiceItem.text/ASR 转文本 → 上下文

AI 发语音（用户发语音或回复含 [语音]）：
  → VoiceService.synthesize(text)（智谱 TTS → wav，校验 RIFF/WAVE 头）
  → VoiceService.wavToSilk(wav)（内置 silk_encoder.exe，classpath 提取 → SILK）
  → SDK sendVoice(userId, silk, "reply.silk", 时长, 采样率)（encodeType=6 SILK）
```

---

## 四、重要方法或代码块

### 1. `AskAIService.askAI(ILinkClient, String userId, String aiContext)` —— AI 对话 + 函数调用核心

```java
for (int round = 0; round < 2; round++) {
    // 请求体携带 tools=[get_weather, generate_image, generate_speech]
    JsonNode message = ...root.path("choices").path(0).path("message");
    if (message.has("tool_calls")) {
        // 执行工具，把结果作为 role=tool 消息追加，进入第二轮
        messages.add(message);
        for (tc : tool_calls) messages.add(toolMsg(executeToolCall(client, userId, tc)));
        continue;
    }
    return message.path("content").asText("");   // 无工具调用 → 直接返回回答
}
```

### 2. `ConversationHandler.handleIncomingMessage` —— 对话编排总入口

维护每用户多轮历史（`chatHistories`），图片/语音消息分别走 `enrichImageMessage` / `transcribeVoiceMessage`（**先收集后统一 addAll，避免 ConcurrentModificationException**）。

### 3. `VoiceService.wavToSilk(byte[] wav)` —— 微信语音格式转换（关键）

```java
// 编码器候选：①classpath 内置 resources/tools/silk_encoder.exe → 提取到临时目录
//            ②项目 tools/ 目录   ③系统 PATH
// 微信语音必须 SILK 编码（encodeType=6），TTS 返回 wav 需先转换
```

### 4. `ContextAnalyzer.analyze(List<WeixinMessage>)` —— 消息 → AI 上下文

把多轮历史解析为 JSON 转义的对话文本，供 AI 理解完整上下文（含图片/语音描述）。

### 5. Function Calling 工具（`functionalCalling` 包）

- `WeatherTool.defineTool()/execute()` —— 查天气
- `ImageTool.defineTool()/execute()` —— 生成图片
- `VoiceTool.defineTool()/execute()` —— 生成语音
- 均由 `AskAIService.executeToolCall` 按名称分发。

### 6. `ResumeStore.save/load` —— SDK 上下文持久化

登录成功/消息处理/关闭时导出 `ResumeContext` 到 `resume.json`，重启后 `builder.resumeContext()` 无缝恢复（免扫码）。

---

## 五、注意事项

1. **模型选择**：`ai.model` 需为支持工具调用的模型（`glm-4.7` 及以上）；默认 `glm-4-flash` 只适合纯文本对话，函数调用不生效。
2. **账号配额**：图片生成（CogView）、语音（TTS/ASR）多为付费能力，余额不足时接口返回 `code 1113`，日志会明确打印错误内容。
3. **语音格式**：微信语音必须 **SILK 编码**，TTS 返回 wav 后由内置 `silk_encoder.exe` 转换；若编码器缺失，日志会提示放置到 `tools/`。
4. **和风天气**：`qweather.token` 需使用和风新版专属 Host 的 JWT Token；未配置时天气工具返回错误。
5. **`resume.json` 含登录凭证**：属于敏感信息，注意保密；建议加入 `.gitignore`。
6. **上下文历史**：单用户对话历史上限 `MAX_HISTORY = 20` 条，过长会被裁剪。
7. **并发安全**：`onMessage` 通过 `synchronized(lock)` 串行处理，避免多线程并发调用 AI / contextToken 竞争。
8. **日志定位**：所有 API 错误响应、异常均打印完整堆栈与响应体，出错时可从日志直接定位（账号/模型/网络/配置问题）。
