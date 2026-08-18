# youkeda-practice-item

夏令营微信 iLink Bot 练习项目。

## 当前阶段

已完成项目骨架、老师提供的 `wechat-ilink-sdk` 源码接入、OpenAI 兼容大模型接入，以及微信文字、图片、语音和天气处理：

```text
二维码登录 → 接收微信文字 → 大模型生成回复 → 回复到微信
              接收微信图片 → 保存并回传 → 可选视觉模型理解
              接收微信语音 → 保存并回传 → 使用微信转写生成 AI 回复
              识别天气意图 → 提取城市 → 高德 API 返回真实天气
```

若设置了 `DEEPSEEK_API_KEY`，程序优先调用 DeepSeek 官方的 `deepseek-v4-flash`；否则回退到百炼 `qwen3.6-flash`。程序按微信用户在内存中保留最近 10 条上下文消息，重启后上下文会清空。

图片理解与文字模型相互独立。没有配置视觉模型时，文字聊天、图片接收保存和原图回传仍可正常使用。

## 环境要求

- JDK 21
- Maven 3.9+

## 项目结构

```text
.
├── bot-app/                         # 小组自己的机器人程序
├── vendor/wechat-ilink-sdk-java-main/ # 老师提供的微信 SDK 源码
└── pom.xml                          # Maven 多模块入口
```

## 首次编译并安装本地 SDK

在仓库根目录执行：

```bash
mvn clean install
```

## 安全设置 DeepSeek API Key（当前推荐）

在 DeepSeek 开放平台创建 API Key 并点击复制，然后在准备启动机器人的同一个终端执行：

```bash
export DEEPSEEK_API_KEY="$(pbpaste)"
pbcopy < /dev/null
```

程序默认使用：

```bash
export DEEPSEEK_BASE_URL="https://api.deepseek.com"
export DEEPSEEK_MODEL="deepseek-v4-flash"
```

上述两项已有默认值，通常不需要手动设置。API 调用需要 DeepSeek 账户具有可用余额或赠送余额。

## 阿里云百炼配置（备用）

API Key 只从环境变量 `DASHSCOPE_API_KEY` 读取，不得写入源码。请在准备启动机器人的同一个终端窗口执行：

程序读取 Key 时会自动移除复制过程中混入的空格和换行符，但不会在日志中输出 Key。

```bash
read -s "DASHSCOPE_API_KEY?请粘贴新的百炼 API Key（输入不会显示）: "
echo
export DASHSCOPE_API_KEY
```

如需临时切换模型，可选执行：

```bash
export BAILIAN_MODEL=qwen3.6-flash
```

这些设置只对当前终端会话有效，关闭该终端后不会继续保留。

## 高德天气配置

在高德开放平台创建“Web 服务”类型的 Key。程序只从环境变量
`AMAP_WEATHER_API_KEY` 读取，不会把 Key 写入代码或日志。

复制 Key 后，在启动机器人的同一个终端执行：

```bash
read -s "AMAP_WEATHER_API_KEY?请粘贴高德天气 Key（输入不会显示）: "
echo
export AMAP_WEATHER_API_KEY
```

可选设置默认城市。设置后，用户只问“今天天气怎么样”时也能查询：

```bash
export WEATHER_DEFAULT_CITY="杭州"
```

支持文字或语音查询，例如“北京今天天气怎么样”“上海明天气温多少”“广州后天会不会下雨”。

## 图片理解配置（可选）

当前 DeepSeek 文字模型不接收图片。若要让机器人描述或分析图片，需要另外配置一个支持 OpenAI 兼容图像输入的视觉模型。

推荐使用百炼的 `qwen3-vl-flash`。程序依次读取：

- `VISION_API_KEY`，未设置时尝试 `DASHSCOPE_API_KEY`；
- `VISION_BASE_URL`，未设置时尝试 `DASHSCOPE_BASE_URL`；
- `VISION_MODEL`，默认 `qwen3-vl-flash`。

使用独立视觉 Key 时，在同一个终端执行：

```bash
export VISION_API_KEY="$(pbpaste)"
pbcopy < /dev/null
export VISION_BASE_URL="https://你的业务空间域名/compatible-mode/v1"
export VISION_MODEL="qwen3-vl-flash"
```

不要把真实 Key 写进命令历史、源码或 README。更稳妥的方式是先执行 `read -s`，再导出变量：

```bash
read -s "VISION_API_KEY?请粘贴视觉模型 API Key（输入不会显示）: "
echo
export VISION_API_KEY
```

## 运行微信 AI 对话机器人

```bash
mvn -f bot-app/pom.xml compile exec:java
```

启动后，程序会在下面的位置生成登录二维码：

```text
bot-app/target/wechat-login-qr.png
```

使用微信扫描二维码登录，然后用刚才扫码的同一个微信账号进入新出现的机器人会话：

- 发送文字：测试大模型回复和连续对话。
- 发送图片：机器人会保存并回传原图；配置视觉模型后，还会发送图片理解结果。
- 发送语音：机器人会保存并回传原语音；若微信消息携带转写文本，则继续生成 AI 文字回复。

收到的图片和语音保存在 `bot-app/inbox/YYYY-MM-DD/`，该目录已被 Git 忽略。

微信入站语音通常为 SILK 格式。当前版本优先使用微信消息自带的转写文本；如果某条语音没有携带转写，程序会明确提示并保留原始语音，不会伪造识别结果。

每次重新扫码可能得到新的 `botId`，不要继续使用旧机器人的聊天窗口。

程序启动时会先实际调用一次所选模型验证 API Key。只有显示“连接测试通过”后才会生成微信登录二维码，避免在 Key 无效时重复扫码。

## 密钥安全

API Key 只能保存在本地环境变量或被 `.gitignore` 忽略的本地配置文件中，严禁写入 Java 源码、日志或提交到 GitHub。任何曾经发到聊天中的 Key 都必须在百炼控制台立即撤销并重新创建。
