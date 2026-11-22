package neuralcloud.controlplane.app;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

record MemoryConfig(URI endpoint, String characterId, String token, boolean scopedRecall) {
    private static final Set<String> FIELDS = Set.of("endpoint", "characterId", "accessTokenEnv");

    static MemoryConfig load(ConfigSource source) throws IOException {
        var properties = source.readProperties();
        var fields = new HashSet<>(properties.stringPropertyNames());
        fields.remove("recallMode");
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
        return new MemoryConfig(endpoint, character, token, "scoped".equals(mode));
    }
}
