# tools/ - SILK 语音编码器

微信 iLink 语音消息使用 **SILK 编码**（SDK sendVoice 默认 encodeType=6）。
智谱 TTS 返回 wav，发送前必须先用 SILK 编码器把 wav 转成 silk。

## 放置方式

把 Windows 版 SILK 编码器放到本目录，程序会自动优先加载：

- `silk_encoder.exe`（推荐，silk-v3-encoder 的 Windows 编译版）
- 或 `silk_v3_encoder.exe`

找不到内置文件时，程序会退而探测系统 PATH 中的 `silk_v3_encoder` / `silk_encoder`。

## 获取编码器

silk-v3-encoder（腾讯 Silk V3）没有官方 Maven/Java 版本，也暂无官方 Windows 预编译发布。
可用方式：

1. **源码编译**：https://github.com/kn007/silk-v3-encoder （Windows 需 MSYS2/MinGW）
2. **第三方预编译**：搜索 `silk_encoder.exe`（常见于 QQ 机器人/bot 框架项目的发布包）
3. **运行自动下载脚本**：`powershell -ExecutionPolicy Bypass -File tools/download-silk.ps1`
