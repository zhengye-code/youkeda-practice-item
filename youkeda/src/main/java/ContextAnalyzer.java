import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VideoItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 消息上下文解析器：把微信消息列表解析为可供聊天 AI API 使用的上下文文本。
 */
public class ContextAnalyzer {

    /**
     * 解析消息列表，生成 JSON 转义后的对话上下文文本，可直接拼入请求的 "content" 字段。
     *
     * @param messages 微信消息列表（可为多轮历史）
     * @return JSON 安全的对话上下文文本
     */
    public static String analyze(List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        int index = 0;
        for (WeixinMessage msg : messages) {
            if (msg == null) continue;
            index++;
            sb.append("第").append(index).append("条消息:\n");
            if (msg.getFrom_user_id() != null) {
                sb.append("  发送者: ").append(msg.getFrom_user_id()).append('\n');
            }
            if (msg.getTo_user_id() != null) {
                sb.append("  接收者: ").append(msg.getTo_user_id()).append('\n');
            }
            if (msg.getCreate_time_ms() != null) {
                sb.append("  时间: ").append(sdf.format(new Date(msg.getCreate_time_ms()))).append('\n');
            }
            List<MessageItem> items = msg.getItem_list();
            if (items == null || items.isEmpty()) {
                sb.append("  内容: (无消息内容)\n");
            } else {
                for (MessageItem item : items) {
                    sb.append("  内容: ").append(describeItem(item)).append('\n');
                }
            }
            sb.append("  ---\n");
        }
        return escapeJson(sb.toString().trim());
    }

    /** 将单条 MessageItem 描述为文本：文本消息取原文，媒体消息给类型与元信息。 */
    private static String describeItem(MessageItem item) {
        if (item == null) return "(null)";
        if (item.getText_item() != null && item.getText_item().getText() != null) {
            return item.getText_item().getText();
        }
        if (item.getImage_item() != null) {
            ImageItem img = item.getImage_item();
            StringBuilder d = new StringBuilder("[图片消息]");
            if (img.getUrl() != null) d.append(" url=").append(img.getUrl());
            if (img.getMid_size() != null) d.append(" 大小=").append(img.getMid_size()).append("B");
            return d.toString();
        }
        if (item.getFile_item() != null) {
            FileItem f = item.getFile_item();
            StringBuilder d = new StringBuilder("[文件消息]");
            if (f.getFile_name() != null) d.append(" 文件名=").append(f.getFile_name());
            if (f.getLen() != null) d.append(" 大小=").append(f.getLen()).append("B");
            if (f.getMd5() != null) d.append(" md5=").append(f.getMd5());
            return d.toString();
        }
        if (item.getVoice_item() != null) {
            VoiceItem v = item.getVoice_item();
            StringBuilder d = new StringBuilder("[语音消息]");
            if (v.getPlaytime() != null) d.append(" 时长=").append(v.getPlaytime()).append("ms");
            if (v.getText() != null && !v.getText().isEmpty()) d.append(" 转写文本=").append(v.getText());
            return d.toString();
        }
        if (item.getVideo_item() != null) {
            VideoItem vid = item.getVideo_item();
            StringBuilder d = new StringBuilder("[视频消息]");
            if (vid.getPlay_length() != null) d.append(" 时长=").append(vid.getPlay_length()).append("ms");
            if (vid.getVideo_md5() != null) d.append(" md5=").append(vid.getVideo_md5());
            return d.toString();
        }
        return "[未知消息类型 type=" + item.getType() + ']';
    }

    /** 对文本做 JSON 字符串转义，确保可直接放入 JSON 的 "content" 字段。 */
    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
