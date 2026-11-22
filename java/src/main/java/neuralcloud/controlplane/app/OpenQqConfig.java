package neuralcloud.controlplane.app;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

final class OpenQqConfig {
    private static final Set<String> FIELDS = Set.of("endpoint", "accountId", "allowedUserId", "accessTokenEnv");

    record Settings(
            URI endpoint,
            long accountId,
            long allowedUserId,
            long allowedGroupId,
            String accessToken) {}

    private OpenQqConfig() {}

    static Settings load(ConfigSource source) throws IOException {
        var properties = source.readProperties();
        var fields = new java.util.HashSet<>(properties.stringPropertyNames());
        fields.remove("allowedGroupId");
        if (!fields.equals(FIELDS)
                || properties.values().stream().anyMatch(value -> value.toString().isBlank())) {
            throw new IllegalArgumentException("OpenQQ 配置须包含以下非空字段 " + FIELDS);
        }
        var endpoint = URI.create(properties.getProperty("endpoint"));
        if (!"ws".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() < 1 || endpoint.getPort() > 65535 || !"/".equals(endpoint.getRawPath())
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("OpenQQ endpoint 须为 ws://127.0.0.1:端口/");
        }
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
        if (group != null && !group.matches("[1-9][0-9]{4,11}"))
            throw new IllegalArgumentException("allowedGroupId 须为有效 QQ 群号");
        return new Settings(
                endpoint,
                Long.parseLong(account),
                Long.parseLong(user),
                group == null ? 0 : Long.parseLong(group),
                token);
    }
}
