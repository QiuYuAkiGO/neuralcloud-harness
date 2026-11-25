package neuralcloud.controlplane.router;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonToken;
import neuralcloud.controlplane.orchestration.Orchestrator;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

/** Router 的首期派单契约。模型只提交步骤，宿主在验收后绑定会话和工作流。 */
final class ChatPlan {
    static final String NAME = "submit_plan";
    private static final String WORKER = "CHAT_WORKER";
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final TypeToken<Map<String, JsonElement>> STEP = new TypeToken<>() {};
    private static final Map<String, Object> FIELDS = Map.of(
            "id", Map.of("type", "string", "minLength", 1),
            "worker", Map.of("type", "string", "enum", List.of(WORKER)),
            "text", Map.of("type", "string", "minLength", 1, "description", "给 Chat Worker 的一步纯文本指令"),
            "dependsOn", Map.of("type", "array", "items", Map.of("type", "string"), "maxItems", 0));
    static final Map<String, Object> TOOL = Map.of("type", "function", "function", Map.of(
            "name", NAME, "description", "把 Master 已批准的回复意图交给一个 Chat Worker", "strict", true,
            "parameters", Map.of("type", "object", "additionalProperties", false, "required", List.of("steps"),
                    "properties", Map.of("steps", Map.of("type", "array", "minItems", 1, "maxItems", 1,
                            "items", Map.of("type", "object", "required", List.of("id", "worker", "text", "dependsOn"),
                                    "additionalProperties", false, "properties", FIELDS))))));

    private ChatPlan() {}

    /** 按层读取恰好一个步骤，拒绝重复键、额外字段/步骤和类型转换，完整验收后才能交给编排器。 */
    static Orchestrator.Plan parse(String arguments, Orchestrator.Scope scope) throws IOException {
        String rule = "submit_plan.steps 须恰含一步，id、text 为非空字符串，worker 为 CHAT_WORKER，dependsOn 为空数组";
        try (var reader = JSON.newJsonReader(new StringReader(arguments))) {
            reader.beginObject();
            if (!"steps".equals(reader.nextName())) throw new IOException(rule);
            reader.beginArray();
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException(rule);
            var fields = JSON.fromJson(reader, STEP);
            reader.endArray();
            reader.endObject();
            if (reader.peek() == JsonToken.END_DOCUMENT && fields.keySet().equals(FIELDS.keySet())
                    && fields.get("id") instanceof JsonPrimitive id && id.isString() && !id.getAsString().isBlank()
                    && fields.get("text") instanceof JsonPrimitive text && text.isString() && !text.getAsString().isBlank()
                    && fields.get("worker") instanceof JsonPrimitive worker && worker.isString() && WORKER.equals(worker.getAsString())
                    && fields.get("dependsOn") instanceof JsonArray dependencies && dependencies.isEmpty()) {
                return new Orchestrator.Plan(scope, List.of(new Orchestrator.Step(
                        id.getAsString(), WORKER, text.getAsString(), List.of())));
            }
        } catch (JsonParseException | IllegalStateException | java.util.NoSuchElementException
                 | com.google.gson.stream.MalformedJsonException | java.io.EOFException error) {
            throw new IOException(rule, error);
        }
        throw new IOException(rule);
    }
}
