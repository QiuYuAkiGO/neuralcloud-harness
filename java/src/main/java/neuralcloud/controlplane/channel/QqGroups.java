package neuralcloud.controlplane.channel;

import com.google.gson.*;
import neuralcloud.controlplane.console.AuditLog;
import neuralcloud.controlplane.memory.ObserveQueue;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** OneBot 已认证群列表和本机操作者的接收策略。新群从 OFF 开始，策略以版本号做并发控制。 */
public final class QqGroups {
    public enum Mode {
        OFF,
        OBSERVE,
        OBSERVE_REPLY
    }

    public record Policy(Mode policy, long revision) {}

    private record Room(String name, int members, int capacity) {}

    private static final Policy DISABLED = new Policy(Mode.OFF, 0);
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private final Path file;
    private final long account;
    private final AuditLog audit;
    private final ObserveQueue observation;
    private Map<Long, Policy> policies = Map.of();
    private Map<Long, Room> rooms = Map.of();
    private boolean connected;
    private boolean epochsPending;
    private String refreshed;

    /** 策略文件按账号绑定，已存在但无法解析的配置会阻止启动。 */
    public QqGroups(Path file, long account, AuditLog audit) throws IOException {
        this(file, account, audit, null);
    }

    public QqGroups(Path file, long account, AuditLog audit, ObserveQueue observation)
            throws IOException {
        this.observation = observation;
        this.file = file.toAbsolutePath();
        this.account = account;
        this.audit = audit;
        if (Files.exists(this.file)) {
            var doc = JSON.fromJson(Files.readString(this.file), JsonObject.class);
            if (!doc.keySet().equals(Set.of("account", "policies"))
                    || !Long.toString(account).equals(doc.get("account").getAsString()))
                throw new IOException("QQ 策略文件账号或字段不符");
            var saved = new HashMap<Long, Policy>();
            for (var e : doc.getAsJsonObject("policies").entrySet()) {
                if (!e.getKey().matches("[1-9][0-9]{4,15}")) throw new IOException("QQ 策略文件群号无效");
                var p = decode(e.getValue());
                if (p.revision() < 1) throw new IOException("QQ 策略文件版本无效");
                saved.put(Long.valueOf(e.getKey()), p);
            }
            policies = Map.copyOf(saved);
        }
        if (observation != null) {
            var enabled = observedVersions(policies);
            observation.prepareEpochs(account, enabled);
            observation.applyEpochs(account, enabled);
        }
    }

    private static Map<Long, Long> observedVersions(Map<Long, Policy> policies) {
        var versions = new HashMap<Long, Long>();
        policies.forEach(
                (room, p) -> {
                    if (p.policy() == Mode.OBSERVE) versions.put(room, p.revision());
                });
        return versions;
    }

    /** HTTP 与磁盘使用同一个策略契约，禁止数字字符串、未知枚举与省略版本。 */
    public static Policy decode(JsonElement value) {
        if (!(value instanceof JsonObject doc)
                || !doc.keySet().equals(Set.of("policy", "revision"))
                || !(doc.get("policy") instanceof JsonPrimitive mode)
                || !mode.isString()
                || !(doc.get("revision") instanceof JsonPrimitive rev)
                || !rev.isNumber()
                || !rev.getAsString().matches("0|[1-9][0-9]{0,17}"))
            throw new IllegalArgumentException("policy 或 revision 无效");
        return new Policy(Mode.valueOf(mode.getAsString()), rev.getAsLong());
    }

    /** 以完整成功的 get_group_list 替换接入名单。退群时撤销原策略，重新入群须手工启用。 */
    synchronized void roster(JsonArray list) throws IOException {
        var next = new LinkedHashMap<Long, Room>();
        for (var element : list) {
            if (!(element instanceof JsonObject room)
                    || !(room.get("group_name") instanceof JsonPrimitive name)
                    || !name.isString()) throw new IOException("get_group_list 缺少 group_name");
            long id = integer(room, "group_id");
            long count = integer(room, "member_count"),
                    capacity = integer(room, "max_member_count");
            if (id < 10000
                    || id > 9999999999999999L
                    || count > Integer.MAX_VALUE
                    || capacity > Integer.MAX_VALUE
                    || next.put(id, new Room(name.getAsString(), (int) count, (int) capacity))
                            != null) throw new IOException("get_group_list 群号或成员数无效");
        }
        var updated = new HashMap<>(policies);
        policies.forEach(
                (id, p) -> {
                    if (!next.containsKey(id) && p.policy() != Mode.OFF)
                        updated.put(id, new Policy(Mode.OFF, p.revision() + 1));
                });
        if (!updated.equals(policies)) persist(updated);
        else finishPolicyChange();
        rooms = next;
        connected = true;
        refreshed = Instant.now().toString();
        if (observation != null) observation.wake();
    }

    private static long integer(JsonObject room, String key) throws IOException {
        if (!(room.get(key) instanceof JsonPrimitive n)
                || !n.isNumber()
                || !n.getAsString().matches("0|[1-9][0-9]{0,15}"))
            throw new IOException("get_group_list 字段无效 " + key);
        return n.getAsLong();
    }

    /** 断线保留最后可见名单供面板查阅，渠道停止受理该连接的群事件。 */
    synchronized void disconnected() {
        connected = false;
        if (observation != null) observation.wake();
    }

    public enum Availability {
        CURRENT,
        DISCONNECTED,
        RETIRED
    }

    /** 临时断线不撤销已持久化的版本。只有完整名单或策略修改可确认旧批次退役。 */
    public synchronized Availability availability(long room, long revision) {
        if (!new Policy(Mode.OBSERVE, revision).equals(policies.get(room)))
            return Availability.RETIRED;
        return connected && rooms.containsKey(room)
                ? Availability.CURRENT
                : Availability.DISCONNECTED;
    }

    synchronized Policy current(long group) {
        return connected && rooms.containsKey(group)
                ? policies.getOrDefault(group, DISABLED)
                : DISABLED;
    }

    /** 私聊验权只查询本账号当前正在观察的群；停收的群立即退出候选集合。 */
    synchronized List<Long> observed() {
        return rooms.keySet().stream().filter(id -> current(id).policy() != Mode.OFF).toList();
    }

    /** 比较面板所见版本后原子写入新策略；保存失败时继续使用此前已持久化的版本。 */
    public synchronized Policy change(long group, Policy cmd) throws IOException {
        if (!connected || !rooms.containsKey(group))
            throw new NoSuchElementException("群不在已连接的 QQ 名单中");
        finishPolicyChange();
        var old = current(group);
        if (old.revision() != cmd.revision())
            throw new ConcurrentModificationException("群策略已更新，请刷新");
        if (old.policy() == cmd.policy()) return old;
        var saved = new Policy(cmd.policy(), old.revision() + 1);
        var next = new HashMap<>(policies);
        next.put(group, saved);
        persist(next);
        return saved;
    }

    private void persist(Map<Long, Policy> next) throws IOException {
        var enabled = observedVersions(next);
        if (observation != null) observation.prepareEpochs(account, enabled);
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "qq-policies-", ".tmp");
        try {
            Files.writeString(
                    temp, JSON.toJson(Map.of("account", Long.toString(account), "policies", next)));
            try (var ch = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(
                    temp,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            policies = Map.copyOf(next);
            epochsPending = observation != null;
            finishPolicyChange();
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** 文件改名后若 SQLite 暂时不可写，下一次名单刷新或管理请求继续落实文件中的生效版本。 */
    private void finishPolicyChange() throws IOException {
        if (epochsPending) {
            observation.applyEpochs(account, observedVersions(policies));
            epochsPending = false;
        }
    }

    /** 群流量来自持久审计。仅统计已受理的消息与 OneBot 确认的送达，断线/未启用不计入。 */
    public synchronized Map<String, Object> view() throws IOException {
        finishPolicyChange();
        var groups = new ArrayList<Map<String, Object>>();
        for (var entry : rooms.entrySet()) {
            long id = entry.getKey();
            var room = entry.getValue();
            String chain = "qq-group-" + account + "-" + id;
            var p = policies.getOrDefault(id, DISABLED);
            long received = 0, delivered = 0;
            String lastReceived = "", lastDelivered = "";
            for (var event : audit.events(chain)) {
                if (event.type().equals("qq_message")) {
                    received++;
                    lastReceived = event.recordedAt();
                }
                if (event.type().equals("qq_delivery")) {
                    delivered++;
                    lastDelivered = event.recordedAt();
                }
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("id", Long.toString(id));
            row.put("name", room.name());
            row.put("members", room.members());
            row.put("capacity", room.capacity());
            row.put("policy", p.policy());
            row.put("revision", p.revision());
            row.put("received", received);
            row.put("delivered", delivered);
            row.put("lastReceived", lastReceived);
            row.put("lastDelivered", lastDelivered);
            row.put("chain", chain);
            row.put(
                    "observation",
                    p.policy() == Mode.OBSERVE && observation != null
                            ? observation.status(account, id, p.revision(), connected)
                            : new ObserveQueue.Status(0, "DISABLED", "", "", "", ""));
            groups.add(row);
        }
        return Map.of(
                "enabled",
                true,
                "connected",
                connected,
                "account",
                Long.toString(account),
                "refreshed",
                refreshed == null ? "" : refreshed,
                "groups",
                groups);
    }
}
