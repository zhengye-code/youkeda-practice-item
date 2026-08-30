# youkeda-practice-item
基于 Spring Boot 4.1 + 千问大模型的微信Bot，
支持天气查询、实时计算、时间获取等工具调用能力，通过 Function Calling实现多轮链式工具调用。

src/main/java/com/claw/assistant/
├── ClawAssistantApplication.java       # Spring Boot 启动类
├── model/
│   └── IntentType.java                # 意图识别类型
├── service/
│   ├── BotService.java            # Bot服务接口
│   ├── LlmService.java          # 大模型服务接口
│   ├── TtsService.java              # 文字转语音服务接口
│   ├── WeatherService.java             #天气查询服务接口
│   └── impl/
│       ├── BotServiceImpl.java  #对接微信Bot服务
│       ├── IntentRecognizer.java   #意图识别
│       ├── LlmServiceImpl.java  #大模型处理
│       ├── WeatherServiceImpl.java    # 天气查询
│       ├── ToolExecute.java       # 调用工具函数
│       ├── ToolFunctions.java       # 工具函数 JSON Schema 注册
│       └── TtsServiceImpl.java        #文字转语音
└── config/
└── ...                            # 配置类

## 本地安全配置

API Key 不得写入 `application.properties` 或提交到 Git。启动前在当前终端设置：

```bash
export DASHSCOPE_API_KEY="你的百炼 Key"
export AMAP_WEATHER_API_KEY="你的高德 Web 服务 Key"
```

自动化测试会用 `bot.enabled=false` 禁止真实微信登录，避免测试时生成二维码或残留后台连接。需要临时禁止本地 Bot 启动时也可设置 `BOT_ENABLED=false`。

已经提交到 Git 历史的旧 Key 必须在对应平台撤销并重新创建；只删除仓库中的明文不能使旧 Key 失效。

## Function Calling 工具

- `get_weather`：查询城市天气；
- `get_current_time`：获取当前时间；
- `calculate`：执行四则运算。

`LlmServiceImpl.chatWithTools` 会把 JSON Schema 发给模型，执行模型返回的 `tool_calls`，再以 `role=tool` 回传结果，最多循环 5 轮。链式示例：先计算 `120 + 30` 得到 `150`，再把 `150` 作为下一步输入乘以 `2`，最终得到 `300`。

多工具协作分为两种：

- 串行：跨模型轮次执行，下一轮工具可以使用上一轮 `role=tool` 的返回结果；
- 并行：同一轮中互不依赖的多个 `tool_calls` 使用 Java 21 虚拟线程同时执行，最终按模型给出的原始顺序回传结果。

单个并行工具失败只会返回该工具的结构化错误，不会取消同一批次的其他工具。

## RAG、Skill 与消息路由

当前文本消息和语音转写文本统一经过以下路由：

```text
用户消息
→ 命中“学习复盘 / 今日复盘 / 复盘学习” → StudyReviewSkill → 直接回复固定复盘流程
→ 否则命中 RAG / Skill / Function Calling / 知识库 / 检索增强 → 本地 Markdown 关键词检索 → 增强 Prompt → LLM 回复
→ 否则 → 原有 chatWithTools → 工具调用或普通闲聊
```

本练习中的业务意义：

- Function Calling 工具执行天气、时间、计算等单个动作；
- RAG 为模型补充项目私有资料，并要求回答附来源；
- Skill 固化“学习复盘”这种多步骤业务流程，减少每次临时提示和流程漂移。

知识库示例位于 `src/main/resources/knowledge-base/rag-skill-notes.md`。极简检索器会读取 Markdown、按空行切块，并按英文关键词和中文二元词进行 Top-K 排序，不依赖向量数据库。

开启或关闭 RAG：

```bash
# 默认开启
export RAG_ENABLED=true

# 关闭后，同一个包含 RAG 关键词的问题会直接进入普通 LLM 路由
export RAG_ENABLED=false
```

可用 `RAG_TOP_K` 调整返回片段数量，也可用逗号分隔的 `RAG_KEYWORDS` 调整触发词。`MessageRouterTest` 使用同一个问题分别验证开启时走 `RAG`、关闭时走 `DIRECT_LLM`，无需真实 API Key。

## 校园 AI 学习助手：模块二

模块二位于 `src/main/java/com/claw/assistant/learning/`，只负责接收模块一已经识别出的学习意图并生成结构化 Markdown，不负责意图分类或结果校验。

支持的意图标签：

- `KNOWLEDGE_SUMMARY`：知识点整理，固定输出核心考点、公式、典型应用、易错点和自测清单；
- `STUDY_PLAN`：学习日程规划，按日期或阶段生成带优先级和完成标准的 Markdown 表格；
- `WRONG_ANSWER_ANALYSIS`：错题解析，输出错误原因、分步解法、结论和避坑要点。

调用接口：

```bash
curl -X POST http://localhost:8080/api/learning/content \
  -H 'Content-Type: application/json' \
  -d '{
    "sessionId": "student-001",
    "intent": "STUDY_PLAN",
    "content": "帮我制定下周物理复习计划，每晚可学习2小时"
  }'
```

继续使用相同的 `sessionId` 和 `intent` 发送“把力学放到第一天”等追问时，服务会携带最近几轮对话，支持修改已有结果。不同意图的上下文互相隔离。

清除某一类任务的上下文：

```bash
curl -X DELETE 'http://localhost:8080/api/learning/content/student-001?intent=STUDY_PLAN'
```

默认保留最近 4 轮、Prompt 最多 6000 字符、最大输出 1800 token，可分别通过 `LEARNING_CONTEXT_MAX_TURNS`、`LEARNING_CONTEXT_MAX_CHARS` 和 `LEARNING_OUTPUT_MAX_TOKENS` 调整。完全相同的连续请求会直接复用上一轮结果，不重复调用模型。模块三可以直接注入 `LearningContentService`，获取 `LearningContentResult.markdown()` 后执行校验与二次生成。

## 可定时、可断点续跑的学习长任务

`src/main/java/com/claw/assistant/agent/` 将一个高层学习目标自动拆成三个检查点：

1. 解析目标与约束；
2. 调用模块二生成结构化学习内容；
3. 整理最终 Markdown 成品。

创建一个北京时间 2026-08-28 20:00（UTC 为 12:00）执行的任务：

```bash
curl -X POST http://localhost:8080/api/agent/tasks \
  -H 'Content-Type: application/json' \
  -d '{
    "sessionId": "student-001",
    "intent": "STUDY_PLAN",
    "goal": "结合我的晚间作息，生成一周高等数学复习计划",
    "scheduledAt": "2026-08-28T12:00:00Z"
  }'
```

查询、立即执行、暂停和恢复：

```bash
curl http://localhost:8080/api/agent/tasks/{taskId}
curl -X POST http://localhost:8080/api/agent/tasks/{taskId}/run
curl -X POST http://localhost:8080/api/agent/tasks/{taskId}/pause
curl -X POST http://localhost:8080/api/agent/tasks/{taskId}/resume
```

调度器默认每秒检查到期任务，并使用 Java 21 虚拟线程执行，慢速模型调用不会阻塞后续扫描。同一个任务 ID 同时只会调度一次。每个步骤完成后都原子写入 `data/agent-tasks/{taskId}.json`；若应用在运行中退出，下次启动会把任务恢复到中断步骤，不重复执行已经完成的检查点。

结构化大模型请求和工具调用请求设置了 60 秒单次超时。超时会使当前步骤进入 `FAILED` 并保存检查点，后续可通过 `/resume` 从该步骤重新执行，避免任务无限等待。

可用 `AGENT_TASK_STORE_DIR` 修改检查点目录，用 `AGENT_TASK_POLL_INTERVAL_MS` 修改扫描间隔。运行数据默认不进入 Git。
