package neuralcloud.controlplane.dreaming;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** 从人物主称呼生成可移植的文件分量。归属按稳定 ID 核对，Markdown 标题使用完整原名。 */
final class DreamingNames {
    private static final Pattern DEVICE = Pattern.compile(
            "(?iu)(?:CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|(?:COM|LPT)[1-9¹²³]) *(?:\\..*)?");
    private static final Pattern OLD_NAME = Pattern.compile("(?i)dream-[a-z0-9_-]+-[a-f0-9]{64}");
    private static final String FORBIDDEN = "<>:\"/\\|?*%";
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /** 每个键都是完整人物指纹。短标记冲突时使用完整摘要，结果与服务器返回顺序无关。 */
    static Map<String, String> people(Map<String, String> subjects) throws IOException {
        var stems = new LinkedHashMap<String, String>();
        var counts = new HashMap<String, Integer>();
        var shortIds = new HashMap<String, Integer>();
        for (var person : subjects.entrySet()) {
            String stem = encode(person.getValue());
            stems.put(person.getKey(), stem);
            counts.merge(pathKey(stem), 1, Integer::sum);
            shortIds.merge(person.getKey().substring(0, 12), 1, Integer::sum);
        }
        var filenames = new HashSet<String>();
        for (var person : stems.entrySet()) {
            String stem = person.getValue();
            if (counts.get(pathKey(stem)) > 1 || utf8Size(stem) > 252) {
                // %~ 不会出现在逐字节的 %HH 转义中，避免与真实人名的后缀相撞。
                String shortId = person.getKey().substring(0, 12);
                String suffix = "%~" + (shortIds.get(shortId) == 1 ? shortId : person.getKey());
                stem = prefix(stem, 252 - suffix.length()) + suffix;
            }
            if (!filenames.add(pathKey(stem))) throw new IOException("Dreaming 人物文件名冲突");
            person.setValue(stem + ".md");
        }
        return stems;
    }

    private static String pathKey(String stem) {
        return Normalizer.normalize(stem.toUpperCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    /** 转义真实百分号、保留字符、结尾空格和点，并给设备名及旧文件命名空间让位。 */
    private static String encode(String subject) {
        boolean reserved = DEVICE.matcher(subject).matches() || OLD_NAME.matcher(subject).matches();
        int tail = subject.length();
        while (tail > 0 && (subject.charAt(tail - 1) == '.' || subject.charAt(tail - 1) == ' ')) tail--;
        var out = new StringBuilder();
        for (int at = 0; at < subject.length();) {
            int cp = subject.codePointAt(at);
            String letter = Character.toString(cp);
            if ((at == 0 && reserved) || at >= tail || Character.isISOControl(cp) || FORBIDDEN.indexOf(cp) >= 0) {
                for (byte value : letter.getBytes(StandardCharsets.UTF_8)) out.append('%').append(HEX.toHexDigits(value));
            } else out.append(letter);
            at += Character.charCount(cp);
        }
        return out.toString();
    }

    /** 保留完整码点和 %HH 单元，给扩展名与区分后缀留出文件系统的字节预算。 */
    private static String prefix(String stem, int limit) {
        int at = 0, bytes = 0;
        while (at < stem.length()) {
            int end = at + (stem.charAt(at) == '%' ? 3 : Character.charCount(stem.codePointAt(at)));
            bytes += utf8Size(stem.substring(at, end));
            if (bytes > limit) break;
            at = end;
        }
        return stem.substring(0, at);
    }

    private static int utf8Size(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 标记绑定实际发布的文件名，兼容文件系统对同一个可见名称的 Unicode 规范化。 */
    static String filenameDigest(String filename) {
        return digest(Normalizer.normalize(filename, Normalizer.Form.NFC));
    }

    /** 摘要与旧版本的 UTF-8 SHA-256 相同，供主题、迁移和必要的区分后缀共用。 */
    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
