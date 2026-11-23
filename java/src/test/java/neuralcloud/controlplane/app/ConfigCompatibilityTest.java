package neuralcloud.controlplane.app;

import neuralcloud.controlplane.master.Master;
import neuralcloud.controlplane.worker.ChatWorker;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ConfigCompatibilityTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void loadsCloudAndLocalMasterConfigs() throws IOException {
        Path cloud = write("master-cloud.properties", """
                endpoint=https://api.deepseek.com/chat/completions
                model=deepseek-v4-pro
                httpVersion=HTTP_1_1
                enableThinking=false
                thinkingFormat=DEEPSEEK
                apiKeyEnv=DEEPSEEK_API_KEY
                proxy=127.0.0.1:7890
                connectTimeoutSeconds=10
                requestTimeoutSeconds=60
                probePrompt=OK
                probeMaxTokens=16
                probeTemperature=0
                """);
        var cloudSettings = MasterConfig.load(ConfigSource.file(cloud), Map.of("DEEPSEEK_API_KEY", "sk-test")::get);
        assertEquals("sk-test", cloudSettings.apiKey());
        assertEquals(Master.ThinkingFormat.DEEPSEEK, cloudSettings.thinkingFormat());
        assertEquals(7890, cloudSettings.proxy().getPort());
        assertTrue(cloudSettings.usesChat());

        assertThrows(IllegalArgumentException.class,
                () -> MasterConfig.load(ConfigSource.file(cloud), k -> null));

        Path local = write("master-local.properties", """
                endpoint=http://127.0.0.1:18081/v1/chat/completions
                model=qwen3.8-27b
                httpVersion=HTTP_1_1
                enableThinking=false
                connectTimeoutSeconds=5
                requestTimeoutSeconds=30
                probePrompt=1, 2, 3,
                probeMaxTokens=8
                probeTemperature=0
                """);
        var localSettings = MasterConfig.load(ConfigSource.file(local), k -> null);
        assertNull(localSettings.apiKey());
        assertNull(localSettings.proxy());
        assertEquals(Master.ThinkingFormat.CHAT_TEMPLATE_KWARGS, localSettings.thinkingFormat());

        Path badCompletion = write("master-bad.properties", """
                endpoint=http://127.0.0.1:8000/v1/completions
                model=base
                httpVersion=HTTP_1_1
                thinkingFormat=DEEPSEEK
                connectTimeoutSeconds=5
                requestTimeoutSeconds=30
                probePrompt=1
                probeMaxTokens=8
                probeTemperature=0
                """);
        assertThrows(IllegalArgumentException.class,
                () -> MasterConfig.load(ConfigSource.file(badCompletion), k -> null));
    }

    @Test
    public void loadsCloudAndLocalChatWorkerConfigs() throws IOException {
        Path directGemini = write("chat-gemini-direct.properties", """
                endpoint=https://generativelanguage.googleapis.com/v1beta/models/gemini-4-hydrogen:generateContent
                apiKeyEnv=GEMINI_API_KEY
                httpVersion=HTTP_2
                connectTimeoutSeconds=10
                requestTimeoutSeconds=30
                maxOutputTokens=1024
                thinkingLevel=LOW
                replyPreferences=classpath:/config/chat-worker-misty-reply-preferences.txt
                """);
        var gemini = ChatWorkerConfig.load(ConfigSource.file(directGemini));
        assertEquals(ChatWorker.ApiFormat.GEMINI, gemini.apiFormat());
        assertNull(gemini.proxy());
        assertNull(gemini.model());
        assertEquals("gem-key", gemini.apiKey(Map.of("GEMINI_API_KEY", "gem-key")::get));

        Path cloudDeepSeek = write("chat-deepseek.properties", """
                apiFormat=OPENAI_CHAT
                endpoint=https://api.deepseek.com/chat/completions
                model=deepseek-flash
                apiKeyEnv=DEEPSEEK_API_KEY
                httpVersion=HTTP_1_1
                enableThinking=false
                thinkingFormat=DEEPSEEK
                connectTimeoutSeconds=10
                requestTimeoutSeconds=60
                maxOutputTokens=1024
                replyPreferences=classpath:/config/chat-worker-misty-reply-preferences.txt
                """);
        var ds = ChatWorkerConfig.load(ConfigSource.file(cloudDeepSeek));
        assertEquals(ChatWorker.ApiFormat.OPENAI_CHAT, ds.apiFormat());
        assertEquals("deepseek-flash", ds.model());
        assertEquals(Master.ThinkingFormat.DEEPSEEK, ds.thinkingFormat());
        assertEquals("sk-ds", ds.apiKey(Map.of("DEEPSEEK_API_KEY", "sk-ds")::get));

        Path localOpenAi = write("chat-local.properties", """
                apiFormat=OPENAI_CHAT
                endpoint=http://127.0.0.1:18081/v1/chat/completions
                model=local-qwen
                httpVersion=HTTP_1_1
                enableThinking=false
                connectTimeoutSeconds=5
                requestTimeoutSeconds=30
                maxOutputTokens=512
                replyPreferences=classpath:/config/chat-worker-misty-reply-preferences.txt
                """);
        var local = ChatWorkerConfig.load(ConfigSource.file(localOpenAi));
        assertNull(local.apiKey(k -> null));
        assertEquals(Master.ThinkingFormat.CHAT_TEMPLATE_KWARGS, local.thinkingFormat());

        Path mixed = write("chat-invalid.properties", """
                apiFormat=OPENAI_CHAT
                endpoint=http://127.0.0.1:18081/v1/chat/completions
                model=local-qwen
                httpVersion=HTTP_1_1
                enableThinking=false
                thinkingLevel=LOW
                connectTimeoutSeconds=5
                requestTimeoutSeconds=30
                maxOutputTokens=512
                replyPreferences=classpath:/config/chat-worker-misty-reply-preferences.txt
                """);
        assertThrows(IllegalArgumentException.class, () -> ChatWorkerConfig.load(ConfigSource.file(mixed)));
    }

    @Test
    public void loadsCloudAndLocalTaskWorkerAndConsoleConfigs() throws IOException {
        Path localTask = write("task-local.properties", """
                endpoint=http://127.0.0.1:18081/v1/chat/completions
                model=local-task
                thinkingFormat=CHAT_TEMPLATE_KWARGS
                connectTimeout=PT5S
                requestTimeout=PT20S
                """);
        var task = TaskWorkerConfig.load(ConfigSource.file(localTask), k -> null, "Linux");
        assertNull(task.apiKey());
        assertEquals(HttpClient.Version.HTTP_1_1, task.httpVersion());
        assertEquals(Master.ThinkingFormat.CHAT_TEMPLATE_KWARGS, task.thinkingFormat());

        Path cloudConsole = write("console-cloud.properties", """
                listenHost=127.0.0.1
                listenPort=18900
                auditDirectory=java/console-audit
                chatWorkerConfig=classpath:/config/chat-worker-gemini.properties
                chatWorkerRolePrompt=classpath:/config/chat-worker-misty-prompt.txt
                routerConfig=classpath:/config/router.properties
                routerRolePrompt=classpath:/config/router-role-prompt.txt
                masterConfig=classpath:/config/master.properties
                masterMaxTokens=256
                masterTemperature=0
                gpuMetrics=NONE
                metricsIntervalSeconds=10
                metricsTimeoutSeconds=5
                """);
        var console = ConsoleConfig.load(ConfigSource.file(cloudConsole));
        assertEquals(4096, console.masterEpoch().triggerBytes());
        String configText = Files.readString(cloudConsole);
        var epoch = write("console-epoch.properties", configText + "masterEpochWorkingBytes=32\nmasterEpochTriggerBytes=64\n");
        assertEquals(32, ConsoleConfig.load(ConfigSource.file(epoch)).masterEpoch().workingBytes());
        Files.writeString(epoch, configText + "masterEpochWorkingBytes=32\n");
        assertThrows(IllegalArgumentException.class, () -> ConsoleConfig.load(ConfigSource.file(epoch)));
        Files.writeString(epoch, configText + "masterEpochWorkingBytes=64\nmasterEpochTriggerBytes=64\n");
        assertThrows(IllegalArgumentException.class, () -> ConsoleConfig.load(ConfigSource.file(epoch)));
        assertTrue(console.routerMetrics().isEmpty());
        assertTrue(console.masterMetrics().isEmpty());

        Path halfMetrics = write("console-half.properties", """
                listenHost=127.0.0.1
                listenPort=18900
                auditDirectory=java/console-audit
                chatWorkerConfig=classpath:/config/chat-worker-gemini.properties
                chatWorkerRolePrompt=classpath:/config/chat-worker-misty-prompt.txt
                routerConfig=classpath:/config/router.properties
                routerRolePrompt=classpath:/config/router-role-prompt.txt
                routerMetrics=http://127.0.0.1:18082/metrics
                masterConfig=classpath:/config/master.properties
                masterMaxTokens=256
                masterTemperature=0
                gpuMetrics=NONE
                metricsIntervalSeconds=10
                metricsTimeoutSeconds=5
                """);
        assertThrows(IllegalArgumentException.class, () -> ConsoleConfig.load(ConfigSource.file(halfMetrics)));
    }

    private Path write(String name, String content) throws IOException {
        Path file = folder.newFile(name).toPath();
        Files.writeString(file, content);
        return file;
    }
}
