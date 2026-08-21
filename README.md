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
