package neuralcloud.controlplane.app;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/**
 * 常驻记忆服务（Resident Memory v1 / v2）的配置记录与启动边界校验器。
 * 字段说明：
 * - {@code endpoint}：记忆服务的本机回环 HTTP 根地址（强制限制在 {@code http://127.0.0.1:<port>/}）；
 * - {@code characterId}：角色标识，用于校验记忆库与当前启动角色一致；
 * - {@code token}：常规读写 Bearer 令牌；
 * - {@code scopedRecall}：是否启用按会话/受众隔离的分域召回（{@code recallMode=scoped}）；
 * - {@code apiVersion}：协议主版本（1 或 2）；
 * - {@code grantToken}：渠道原作者授权专用令牌（仅 v2 scoped 可启用，且必须与常规 {@code token} 分离）；
 * - {@code hotIndexTokens}：v2 受众热目录卡片的 token 预算（0 表示禁用，1~2048 表示启用）；
 * - {@code historyProfile}：历史检索深度档位（{@code QUICK} 或 v2 scoped 专属 {@code THINKING}）。
 */
record MemoryConfig(
        URI endpoint,
        String characterId,
        String token,
        boolean scopedRecall,
        int apiVersion,
        String grantToken,
        int hotIndexTokens,
        String historyProfile) {
    /** 必填核心配置键集合（其余可选键在校验前先从待检查集合中剥离）。 */
    private static final Set<String> FIELDS = Set.of("endpoint", "characterId", "accessTokenEnv");

    /**
     * 读取并校验记忆服务配置文件。
     * 除了格式与环境变量令牌长度校验外，还会强制校验跨字段组合约束（例如：原作者授权、热目录卡片、
     * {@code THINKING} 检索档位均依赖 Memory v2 的 {@code scoped} 模式，且授权令牌不能复用普通读写令牌）。
     */
    static MemoryConfig load(ConfigSource source) throws IOException {
        var properties = source.readProperties();
        // 先剔除允许出现的可选键，再检查剩余键集合是否与必填字段完全一致，防止遗漏或拼错字段名。
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
        // 记忆服务承载私密历史，硬性限定只能连接本机回环根路径。
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
        // 原作者授权令牌与常规访问令牌严格权限分离，防止普通读写凭证越权签发跨会话授权。
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
        // 热目录卡片与深度历史档位仅在 Memory v2 scoped 模式下受支持。
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
