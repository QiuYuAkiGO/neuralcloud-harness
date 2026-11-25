package neuralcloud.controlplane.app;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * OpenQQ（OneBot v11 WebSocket 适配渠道）的配置加载与安全边界校验器。
 * 启动前强制校验连接地址仅限本机回环、机器人账号与允许对话的用户 QQ 号互不相同、
 * 鉴权令牌符合最低强度要求，以及可选的白名单项目群号格式正确。
 */
final class OpenQqConfig {
    private static final Set<String> FIELDS = Set.of("endpoint", "accountId", "allowedUserId", "accessTokenEnv");

    /**
     * 已校验的 OpenQQ 渠道配置快照。
     * {@code allowedGroupId} 为 0 表示未启用群聊监听，仅允许与 {@code allowedUserId} 的私聊。
     */
    record Settings(
            URI endpoint,
            long accountId,
            long allowedUserId,
            long allowedGroupId,
            java.util.Optional<java.nio.file.Path> groupPoliciesFile,
            String accessToken) {}

    private OpenQqConfig() {}

    /** 读取并校验 OpenQQ 配置文件及环境变量中的 OneBot 访问令牌。 */
    static Settings load(ConfigSource source) throws IOException {
        var properties = source.readProperties();
        var fields = new java.util.HashSet<>(properties.stringPropertyNames());
        // allowedGroupId 为可选字段，先移除后再比对必填字段集合。
        fields.remove("allowedGroupId");
        fields.remove("groupPoliciesFile");
        if (!fields.equals(FIELDS)
                || properties.values().stream().anyMatch(value -> value.toString().isBlank())) {
            throw new IllegalArgumentException("OpenQQ 配置须包含以下非空字段 " + FIELDS);
        }
        // OneBot 正向 WebSocket 仅允许连接本机回环地址，防止明文令牌在公网传输。
        var endpoint = URI.create(properties.getProperty("endpoint"));
        if (!"ws".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() < 1 || endpoint.getPort() > 65535 || !"/".equals(endpoint.getRawPath())
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("OpenQQ endpoint 须为 ws://127.0.0.1:端口/");
        }
        // 禁止机器人账号与白名单用户相同，避免自发自收造成消息回环。
        String account = properties.getProperty("accountId");
        String user = properties.getProperty("allowedUserId");
        if (!account.matches("[1-9][0-9]{4,11}") || !user.matches("[1-9][0-9]{4,11}") || account.equals(user)) {
            throw new IllegalArgumentException("OpenQQ accountId 和 allowedUserId 须为不同的有效 QQ 号");
        }
        String tokenEnv = properties.getProperty("accessTokenEnv");
        String token = System.getenv(tokenEnv);
        if (token == null || !token.matches("[!-~]{16,256}")) {
            throw new IllegalArgumentException("OpenQQ 令牌环境变量须为 16 至 256 位可见 ASCII 字符 " + tokenEnv);
        }
        String group = properties.getProperty("allowedGroupId");
        var policies =
                java.util.Optional.ofNullable(properties.getProperty("groupPoliciesFile"))
                        .map(java.nio.file.Path::of);
        if (group != null && policies.isPresent())
            throw new IllegalArgumentException("allowedGroupId 与 groupPoliciesFile 只能配置一个");
        if (group != null && !group.matches("[1-9][0-9]{4,11}"))
            throw new IllegalArgumentException("allowedGroupId 须为有效 QQ 群号");
        return new Settings(
                endpoint,
                Long.parseLong(account),
                Long.parseLong(user),
                group == null ? 0 : Long.parseLong(group),
                policies,
                token);
    }
}
