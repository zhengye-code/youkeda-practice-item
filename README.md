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