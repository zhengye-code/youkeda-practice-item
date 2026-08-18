# youkeda-practice-item

夏令营微信 iLink Bot 练习项目。

## 当前阶段

已完成项目骨架、老师提供的 `wechat-ilink-sdk` 源码接入、OpenAI 兼容大模型接入，以及微信图片收发测试：

```text
二维码登录 → 接收微信文字 → 大模型生成回复 → 回复到微信
              接收微信图片 → 保存到本地 → 原图回传微信
```

若设置了 `DEEPSEEK_API_KEY`，程序优先调用 DeepSeek 官方的 `deepseek-v4-flash`；否则回退到百炼 `qwen3.6-flash`。程序按微信用户在内存中保留最近 10 条上下文消息，重启后上下文会清空。

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
- 发送图片：机器人会把图片保存到 `bot-app/inbox/YYYY-MM-DD/`，然后将原图发回微信，用于验证图片接收和发送通道。

当前接入的 DeepSeek 文本模型不负责识别图片内容；本阶段的图片功能是客观的“接收、保存、回传”测试。后续若要回答图片中的问题，需要另接支持视觉输入的模型。

每次重新扫码可能得到新的 `botId`，不要继续使用旧机器人的聊天窗口。

程序启动时会先实际调用一次所选模型验证 API Key。只有显示“连接测试通过”后才会生成微信登录二维码，避免在 Key 无效时重复扫码。

## 密钥安全

API Key 只能保存在本地环境变量或被 `.gitignore` 忽略的本地配置文件中，严禁写入 Java 源码、日志或提交到 GitHub。任何曾经发到聊天中的 Key 都必须在百炼控制台立即撤销并重新创建。
