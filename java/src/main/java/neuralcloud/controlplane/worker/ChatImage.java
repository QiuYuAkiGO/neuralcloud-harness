package neuralcloud.controlplane.worker;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/** OneBot 图片回执的已验证像素文件。模型取得原图编码，审计只取得同一份字节的摘要。 */
public final class ChatImage {
    public static final int MAX_COUNT = 4;
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 20 * 1024 * 1024;
    private final String mimeType;
    private final String base64;
    private final int size;
    private final String sha256;

    private ChatImage(String mimeType, String base64, byte[] bytes) {
        this.mimeType = mimeType;
        this.base64 = base64;
        size = bytes.length;
        try {
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** 在外部数据边界核对实际字节数与文件签名，文件名、路径和 URL 均不能代替原图。 */
    public static ChatImage fromBase64(String value, int remaining) throws IOException {
        int limit = Math.min(MAX_BYTES, remaining);
        if (value.length() > 4 * ((limit + 2) / 3)) throw new IOException("QQ 图片超过本条消息的字节上限");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new IOException("get_image.base64 不是有效的图片编码", error);
        }
        if (bytes.length > limit) throw new IOException("QQ 图片超过本条消息的字节上限");
        String signature = HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, 12));
        String mime;
        if (signature.startsWith("89504e470d0a1a0a")) mime = "image/png";
        else if (signature.startsWith("ffd8ff")) mime = "image/jpeg";
        else if (signature.startsWith("474946383761") || signature.startsWith("474946383961")) mime = "image/gif";
        else if (signature.length() == 24 && signature.startsWith("52494646") && signature.endsWith("57454250")) mime = "image/webp";
        else throw new IOException("QQ 图片字节须为 JPEG、PNG、GIF 或 WebP");
        return new ChatImage(mime, value, bytes);
    }

    String mimeType() { return mimeType; }
    String base64() { return base64; }
    public int size() { return size; }

    /** 顺序由消息段决定，哈希定位实际交给视觉模型的文件。 */
    public Map<String, Object> evidence() {
        return Map.of("mimeType", mimeType, "bytes", size, "sha256", sha256);
    }
}
