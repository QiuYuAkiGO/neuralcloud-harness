package neuralcloud.controlplane.app;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

record MemoryConfig(
        URI endpoint,
        String characterId,
        String token,
        boolean scopedRecall,
        int apiVersion,
        String grantToken,
        int hotIndexTokens,
        String historyProfile) {
    private static final Set<String> FIELDS = Set.of("endpoint", "characterId", "accessTokenEnv");

    static MemoryConfig load(ConfigSource source) throws IOException {
        var properties = source.readProperties();
        var fields = new HashSet<>(properties.stringPropertyNames());
        fields.remove("recallMode");
        fields.remove("apiVersion");
        fields.remove("hotIndexTokens");
        fields.remove("historyProfile");
        fields.remove("grantAccessTokenEnv");
        String version = properties.getProperty("apiVersion", "1");
        if (!Set.of("1", "2").contains(version))
            throw new IllegalArgumentException("记忆 apiVersion 须为 1 或 2");
        if (!fields.equals(FIELDS)
                || properties.values().stream().anyMatch(value -> value.toString().isBlank())) {
            throw new IllegalArgumentException("记忆配置须包含以下非空字段 " + FIELDS);
        }
        var endpoint = URI.create(properties.getProperty("endpoint"));
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() < 1 || endpoint.getPort() > 65535 || !"/".equals(endpoint.getRawPath())
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("记忆 endpoint 须为 http://127.0.0.1:端口/");
        }
        String character = properties.getProperty("characterId");
        if (!character.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("记忆 characterId 须为 1 至 128 位字母、数字、下划线或连字符");
        }
        String keyEnvironment = properties.getProperty("accessTokenEnv");
        String token = System.getenv(keyEnvironment);
        if (token == null || !token.matches("[!-~]{32,256}")) {
            throw new IllegalArgumentException("记忆令牌环境变量须为 32 至 256 位可见 ASCII 字符 " + keyEnvironment);
        }
        String mode = properties.getProperty("recallMode", "recent");
        if (!Set.of("recent", "scoped").contains(mode)) {
            throw new IllegalArgumentException("记忆 recallMode 须为 recent 或 scoped");
        }
        String grant = null;
        if (properties.containsKey("grantAccessTokenEnv")) {
            grant = System.getenv(properties.getProperty("grantAccessTokenEnv"));
            if (!version.equals("2")
                    || !mode.equals("scoped")
                    || grant == null
                    || !grant.matches("[!-~]{32,256}")
                    || grant.equals(token))
                throw new IllegalArgumentException("渠道授权需要 v2 scoped 和独立的 grantAccessTokenEnv");
        }
        int hotTokens = Integer.parseInt(properties.getProperty("hotIndexTokens", "0"));
        if (hotTokens < 0
                || hotTokens > 2048
                || (hotTokens > 0 && !(version.equals("2") && mode.equals("scoped"))))
            throw new IllegalArgumentException("hotIndexTokens 须为 0–2048，启用时须配置 v2 scoped");
        String profile = properties.getProperty("historyProfile", "QUICK");
        if (!(profile.equals("QUICK") || profile.equals("THINKING") && version.equals("2") && mode.equals("scoped")))
            throw new IllegalArgumentException("historyProfile 须为 QUICK，或在 v2 scoped 下使用 THINKING");
        return new MemoryConfig(
                endpoint,
                character,
                token,
                "scoped".equals(mode),
                Integer.parseInt(version),
                grant,
                hotTokens,
                profile);
    }
}
