package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import config.Config;
import kong.unirest.Unirest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 语音服务：调用智谱 ASR（语音转文本）与 TTS（文本转语音）接口。
 * <ul>
 *   <li>{@link #transcribe}：音频字节 → 文本（.silk 先经 ffmpeg 转码为 .wav，再调 ASR）</li>
 *   <li>{@link #synthesize}：文本 → wav 音频字节（供 SDK sendVoice 发送）</li>
 *   <li>音频下载使用 SDK {@code ILinkClient.downloadVoiceFromMessageItem}，发送使用 SDK {@code sendVoice}</li>
 * </ul>
 */
public class VoiceService {
    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    // ==================== ASR：语音转文本 ====================

    /**
     * 将音频转写为文本（调用智谱 glm-asr-2512）。
     *
     * @param audioBytes 音频原始字节（SDK 下载）
     * @param fileName   文件名（用于判断格式：.silk 会先经 ffmpeg 转码为 .wav）
     * @return 转录文本；失败时返回空字符串
     */
    public static String transcribe(byte[] audioBytes, String fileName) throws IOException {
        if (audioBytes == null || audioBytes.length == 0) {
            log.warn("语音转文本：音频字节为空");
            return "";
        }
        byte[] asrBytes = audioBytes;
        String uploadName = (fileName == null || fileName.isEmpty()) ? "voice.wav" : fileName;
        // ASR 仅支持 wav/mp3，silk 需先转码
        if (fileName != null && fileName.toLowerCase().endsWith(".silk")) {
            byte[] wav = silkToWav(audioBytes);
            if (wav != null && wav.length > 0) {
                asrBytes = wav;
                uploadName = "voice.wav";
            }
        }
        kong.unirest.HttpResponse<String> response = Unirest.post(Config.asrUrl())
                .connectTimeout(Config.connectTimeoutMs())
                .socketTimeout(Config.socketTimeoutMs())
                .header("Authorization", "Bearer " + Config.aiToken())
                .field("model", Config.asrModel())
                .field("stream", "false")
                .field("file", new ByteArrayInputStream(asrBytes), uploadName)
                .asString();
        try {
            JsonNode root = new ObjectMapper().readTree(response.getBody());
            return root.path("text").asText("");
        } catch (Exception e) {
            log.warn("解析 ASR 响应失败: {}", response.getBody());
            return "";
        }
    }

    /** 使用系统 ffmpeg 将 silk 音频转码为 wav（ASR 只接受 wav/mp3）。 */
    public static byte[] silkToWav(byte[] silkBytes) throws IOException {
        if (silkBytes == null || silkBytes.length == 0) {
            return new byte[0];
        }
        Path in = Files.createTempFile("voice_in", ".silk");
        Path out = Files.createTempFile("voice_out", ".wav");
        try {
            Files.write(in, silkBytes);
            Process p = new ProcessBuilder("ffmpeg", "-y", "-i", in.toString(), out.toString())
                    .redirectErrorStream(true)
                    .start();
            try {
                if (!p.waitFor(60, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("ffmpeg 转码超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("ffmpeg 转码被中断", e);
            }
            if (p.exitValue() != 0) {
                throw new IOException("ffmpeg 转码失败，退出码=" + p.exitValue());
            }
            return Files.readAllBytes(out);
        } finally {
            Files.deleteIfExists(in);
            Files.deleteIfExists(out);
        }
    }

    // ==================== TTS：文本转语音 ====================

    /** 将文本合成为 wav 音频字节（调用智谱 glm-tts），供 SDK sendVoice 发送。 */
    public static byte[] synthesize(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new byte[0];
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode body = mapper.createObjectNode();
            body.put("model", Config.ttsModel());
            body.put("input", text.trim());
            body.put("voice", Config.ttsVoice());
            body.put("response_format", "wav");
            byte[] audio = Unirest.post(Config.ttsUrl())
                    .connectTimeout(Config.connectTimeoutMs())
                    .socketTimeout(Config.socketTimeoutMs())
                    .header("Authorization", "Bearer " + Config.aiToken())
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .asBytes()
                    .getBody();
            // 校验返回是否为有效 wav（RIFF/WAVE）；错误响应（如余额不足 JSON）不应被当作音频返回
            if (!isWav(audio)) {
                String resp = audio == null ? "(null)"
                        : new String(audio, 0, Math.min(audio.length, 200), StandardCharsets.UTF_8);
                log.warn("TTS 未返回有效 wav，可能为错误响应: {}", resp);
                return new byte[0];
            }
            return audio;
        } catch (Exception e) {
            log.warn("TTS 生成语音失败: ", e);
            return new byte[0];
        }
    }

    /** 判断字节是否为标准 RIFF/WAVE 头（PCM wav）。 */
    private static boolean isWav(byte[] wav) {
        if (wav == null || wav.length < 44) {
            return false;
        }
        return wav[0] == 'R' && wav[1] == 'I' && wav[2] == 'F' && wav[3] == 'F'
                && wav[8] == 'W' && wav[9] == 'A' && wav[10] == 'V' && wav[11] == 'E';
    }

    /**
     * 将 wav（PCM）转码为微信语音格式 SILK。
     * <p>依赖外部 SILK 编码器（kn007/silk-v3-encoder，命令名 silk_v3_encoder / silk_encoder），
     * 未安装时将抛出 IOException。微信 iLink 语音消息使用 SILK 编码（encodeType=6），
     * 必须先用本方法转换后再调用 SDK sendVoice。</p>
     *
     * @param wavBytes TTS 生成的 wav 字节
     * @return SILK 编码的语音字节
     */
    public static byte[] wavToSilk(byte[] wavBytes) throws IOException {
        if (wavBytes == null || wavBytes.length < 44) {
            throw new IOException("无效的 wav 数据");
        }
        WavInfo info = parseWav(wavBytes);
        if (info == null) {
            log.error("wavToSilk：无法解析 wav 头，字节长度={}（可能为错误 JSON 或其他格式）", wavBytes.length);
            throw new IOException("无法解析 wav 头（请确认输入为有效 wav，而非错误 JSON 或其他格式）");
        }
        // 定位 data chunk，提取 PCM 数据
        int dataOffset = -1;
        for (int i = 12; i + 8 <= wavBytes.length; i++) {
            if (wavBytes[i] == 'd' && wavBytes[i + 1] == 'a'
                    && wavBytes[i + 2] == 't' && wavBytes[i + 3] == 'a') {
                dataOffset = i + 8;
                break;
            }
        }
        if (dataOffset < 0 || dataOffset >= wavBytes.length) {
            throw new IOException("wav 中未找到 data chunk");
        }
        byte[] pcm = Arrays.copyOfRange(wavBytes, dataOffset, wavBytes.length);
        Path pcmFile = Files.createTempFile("voice_in", ".pcm");
        Path silkFile = Files.createTempFile("voice_out", ".silk");
        try {
            Files.write(pcmFile, pcm);
            // 编码器候选：①classpath 内置资源 → ②项目 tools/ 目录 → ③系统 PATH
            List<String> encoderCommands = new ArrayList<>();
            Path extractedExe = null;
            try (InputStream in =
                    VoiceService.class.getClassLoader().getResourceAsStream("tools/silk_encoder.exe")) {
                if (in != null) {
                    extractedExe = Files.createTempFile("silk_encoder", ".exe");
                    Files.copy(in, extractedExe, StandardCopyOption.REPLACE_EXISTING);
                    encoderCommands.add(extractedExe.toString());
                }
            } catch (IOException e) {
                log.warn("提取内置 SILK 编码器失败: ", e);
            }
            if (encoderCommands.isEmpty()) {
                String[] builtinNames = {"silk_encoder.exe", "encoder.exe", "silk_v3_encoder.exe", "silk_v3_encoder", "silk_encoder"};
                for (String name : builtinNames) {
                    File builtin = new File("tools", name);
                    if (builtin.exists() && builtin.isFile()) {
                        encoderCommands.add(builtin.getAbsolutePath());
                    }
                }
            }
            if (encoderCommands.isEmpty()) {
                encoderCommands.addAll(
                        Arrays.asList("silk_v3_encoder", "silk_encoder", "silk_encoder.exe", "silk_v3_encoder.exe"));
            }
            String lastError =
                    "未找到可用的 SILK 编码器（请在项目 tools/ 目录放置 silk_encoder.exe，或安装 silk-v3-encoder 到 PATH）";
            for (String encoder : encoderCommands) {
                try {
                    Process p = new ProcessBuilder(encoder, pcmFile.toString(), silkFile.toString())
                            .redirectErrorStream(true)
                            .start();
                    if (!p.waitFor(60, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        continue;
                    }
                    if (p.exitValue() == 0 && Files.exists(silkFile) && Files.size(silkFile) > 0) {
                        return Files.readAllBytes(silkFile);
                    }
                } catch (IOException e) {
                    lastError = e.getMessage();
                }
            }
            throw new IOException("SILK 转码失败：" + lastError
                    + "。请在项目 tools/ 目录放置编码器，或安装 kn007/silk-v3-encoder 到 PATH");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SILK 转码被中断", e);
        } finally {
            Files.deleteIfExists(pcmFile);
            Files.deleteIfExists(silkFile);
        }
    }

    // ==================== wav 辅助 ====================

    /** 解析 wav 头，返回音频时长（毫秒）；解析失败返回 0。 */
    public static int wavDurationMs(byte[] wav) {
        WavInfo info = parseWav(wav);
        return info == null ? 0 : info.durationMs;
    }

    /** 解析 wav 头，返回采样率；解析失败返回 24000（TTS 默认）。 */
    public static int wavSampleRate(byte[] wav) {
        WavInfo info = parseWav(wav);
        return info == null ? 24000 : info.sampleRate;
    }

    /** 解析标准 RIFF/WAVE 头（PCM），提取采样率、声道、位深与 data 大小。 */
    private static WavInfo parseWav(byte[] wav) {
        if (wav == null || wav.length < 44) {
            return null;
        }
        if (wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F'
                || wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') {
            return null;
        }
        int channels = (wav[22] & 0xFF) | ((wav[23] & 0xFF) << 8);
        int sampleRate = (wav[24] & 0xFF) | ((wav[25] & 0xFF) << 8)
                | ((wav[26] & 0xFF) << 16) | ((wav[27] & 0xFF) << 24);
        int bitsPerSample = (wav[34] & 0xFF) | ((wav[35] & 0xFF) << 8);
        // 查找 data chunk
        int dataSize = 0;
        for (int i = 12; i + 8 <= wav.length; i++) {
            if (wav[i] == 'd' && wav[i + 1] == 'a' && wav[i + 2] == 't' && wav[i + 3] == 'a') {
                dataSize = (wav[i + 4] & 0xFF) | ((wav[i + 5] & 0xFF) << 8)
                        | ((wav[i + 6] & 0xFF) << 16) | ((wav[i + 7] & 0xFF) << 24);
                break;
            }
        }
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        if (byteRate <= 0 || dataSize <= 0) {
            return null;
        }
        WavInfo info = new WavInfo();
        info.sampleRate = sampleRate;
        info.durationMs = (int) (dataSize * 1000L / byteRate);
        return info;
    }

    private static final class WavInfo {
        int sampleRate;
        int durationMs;
    }
}
