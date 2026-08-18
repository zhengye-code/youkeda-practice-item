import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnDisconnectListener;
import com.github.wechat.ilink.sdk.core.listener.OnHeartbeatListener;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * 主入口：负责配置、创建客户端、注册监听器、登录/恢复与阻塞保活。
 * 具体职责已拆分到独立类：
 *  - AskAIService：智谱 AI 接口调用
 *  - ContextAnalyzer：消息 → AI 上下文解析
 *  - ConversationHandler：多轮对话历史维护与处理
 *  - ResumeStore：SDK 恢复上下文（ResumeContext）持久化
 */
public class runClient {
    static Logger log = LoggerFactory.getLogger("log");

    public static void main(String[] args) throws Exception {
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(35000)
                .readTimeoutMs(35000)
                .writeTimeoutMs(35000)
                .httpMaxRetries(3)
                .retryBaseDelayMs(1000)
                .retryMaxDelayMs(10000)
                .heartbeatEnabled(true)
                .heartbeatIntervalMs(5000)
                .channelVersion("1.0.0")
                .build();

        // 用数组持有 client，供 onMessage 监听器在登录后调用（lambda 在 build 之后才会被触发）
        final ILinkClient[] clientHolder = new ILinkClient[1];
        // 串行处理消息，避免多条消息并发调用 AI / 发送导致 contextToken 竞争
        final Object lock = new Object();
        // 对话处理器：维护每个用户的多轮对话历史
        final ConversationHandler conversationHandler = new ConversationHandler();

        // 使用 SDK 的上下文恢复机制：启动时从本地恢复登录态、消息游标与会话 contextToken
        ResumeContext restored = ResumeStore.load();

        clientHolder[0] = ILinkClient.builder()
                .config(config)
                .resumeContext(restored)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        IO.println("登录成功，botId = " + context.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        System.err.println("登录失败: " + throwable.getMessage());
                    }
                })
                // 持续对话：把收到的一批消息整体交给 AI 生成回答并回复
                .onMessage(messages -> {
                    synchronized (lock) {
                        try {
                            conversationHandler.handleIncomingMessage(clientHolder[0], messages);
                            // 处理完消息后持久化最新上下文（contextToken / 游标），供服务重启恢复
                            ResumeStore.save(clientHolder[0]);
                        } catch (Exception e) {
                            // 打印完整堆栈，便于定位消息处理失败的具体位置（如 NPE）
                            log.warn("处理消息失败: ", e);
                        }
                    }
                })
                .onHeartbeat(new OnHeartbeatListener() {
                    @Override
                    public void onHeartbeatSuccess() {
                        // 心跳成功：连接健康；SDK 每次心跳会顺带拉取一次消息（getupdates），
                        // 因此心跳正常也意味着消息轮询链路正常。
                        log.info("心跳正常，连接健康");
                    }

                    @Override
                    public void onHeartbeatFailure(Throwable cause) {
                        // 心跳失败：网络异常或登录态可能失效
                        log.warn("心跳异常: {}", cause == null ? "unknown" : cause.getMessage(), cause);
                    }
                })
                .onDisconnect(new OnDisconnectListener() {
                    @Override
                    public void onDisconnect(Throwable cause) {
                    }

                    @Override
                    public void onReconnectStart(int attempt) {
                    }

                    @Override
                    public void onReconnectSuccess() {
                    }

                    @Override
                    public void onReconnectFailed(Throwable cause) {
                    }
                })
                .build();

        ILinkClient client = clientHolder[0];
        try {
            if (client.isLoggedIn()) {
                // SDK 已通过 resumeContext 恢复登录态（无需扫码），心跳自动启动
                IO.println("已从本地上下文恢复登录，botId = " + client.getLoginContext().getBotId());
            } else {
                String qrCodeContent = client.executeLogin();
                IO.println("请扫码登录：");
                IO.println(qrCodeContent);

                client.getLoginFuture().get();
                IO.println("登录完成，botId = " + client.getLoginContext().getBotId());
                ResumeStore.save(client);
            }
            IO.println("持续对话已开启：SDK 心跳每 " + config.getHeartbeatIntervalMs()
                    + "ms 拉取一次消息，用户给 bot 发消息即可自动回复。按 Ctrl+C 退出。");

            // 消息拉取由 SDK 心跳（HeartbeatService）统一驱动；
            // 这里仅阻塞主线程保持客户端存活，避免 finally 中 close() 提前关闭客户端。
            new CountDownLatch(1).await();
        } finally {
            // 关闭前持久化最新上下文，保证下次启动可无缝恢复
            ResumeStore.save(client);
            client.close();
        }
    }
}
