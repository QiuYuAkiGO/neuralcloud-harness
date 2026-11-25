"""只读的 Memory v2 快照与按水位生成的实验库。正式库的索引和旧版本绝不整库复制到模型进程。"""
import hashlib
import json
import re
import sqlite3
import uuid
from collections import defaultdict
from contextlib import closing
from pathlib import Path
from zipfile import ZipFile

QQ = re.compile(r"(group|c2c):onebot:([1-9]\d*):([1-9]\d*)\Z")


def audit_context(directory):
    """仅从同次运行、同一会话的因果链或观察回执取寻址信息；关键词和历史是否回复都不是证明。"""
    evidence = {}
    if directory is None:
        return evidence
    for path in sorted(Path(directory).glob("*.jsonl")):
        roots = {}
        with path.open(encoding="utf-8-sig") as lines:
            for line in lines:
                event = json.loads(line)
                run, chain = event["runId"], event["chain"]
                key = (run, chain, event["eventId"])
                data = event["data"]
                parent = roots.get((run, chain, event.get("causedBy")))
                if event["type"] == "qq_message" and "messageId" in data:
                    parent = (chain, str(data["messageId"]))
                if parent:
                    roots[key] = parent
                    if event["type"] == "qq_participation":
                        evidence[parent] = ("OBSERVE_REPLY", data["addressing"] == "DIRECT")
                if event["type"] == "observe_claimed":
                    for source in data["sources"]:
                        evidence[source["recordId"]] = ("OBSERVE", None)
    return evidence


class Snapshot:
    def __init__(self, path, audit_dir=None):
        self.path = Path(path).resolve(strict=True)
        wal = Path(str(self.path) + "-wal")
        if wal.exists() and wal.stat().st_size:
            raise ValueError("需要通过 SQLite backup 导出的独立一致性快照")
        with self.path.open("rb") as stream:
            self.digest = hashlib.file_digest(stream, "sha256").hexdigest()
        self.stat = self.signature()
        with closing(self.connect()) as db:
            # 格式 5、6 增加 Dreaming 档案与人物称呼，历史回放仍读取格式 4 已有的表。
            if db.execute("PRAGMA user_version").fetchone()[0] not in (4, 5, 6):
                raise ValueError("需要包含修订、授权和目录的 Memory v2 格式 4、5 或 6 快照")
            self.character, self.stream = db.execute("SELECT character_id,stream_id FROM meta").fetchone()
            self.watermark = db.execute("SELECT coalesce(max(seq),0) FROM changes").fetchone()[0]
        self.audit = audit_context(audit_dir)

    def signature(self):
        st = self.path.stat()
        return st.st_size, st.st_mtime_ns, st.st_ino

    def connect(self):
        if self.signature() != self.stat:
            raise ValueError("源快照已替换，请重新加载实验实例")
        db = sqlite3.connect(self.path.as_uri() + "?mode=ro&immutable=1", uri=True)
        db.row_factory = sqlite3.Row
        return db

    def message(self, row):
        body = json.loads(row["body"])
        channel = body.get("channel", {})
        kind, account, peer = QQ.fullmatch(row["conversation"]).groups()
        msg_id = channel.get("message_id")
        chain = f"qq-{'group' if kind == 'group' else 'private'}-{account}-{peer}"
        if kind == "c2c":
            policy, addressed = None, True
        else:
            policy, addressed = self.audit.get(row["id"], self.audit.get((chain, str(msg_id)), (None, None)))
        sender = channel.get("sender_id")
        # 老资料可能只有显示名。私聊会话的对方账号无法证明一条旧记录由谁发出。
        # 保留原始显示名供查阅，操作者可另外建立明确指定发件人的变体。
        return dict(id=row["id"], revision=row["revision"], seq=row["seq"], time=row["occurred"],
                    text=body["content"], speaker=body.get("speaker", ""), sender=sender,
                    messageId=msg_id, kind=row["kind"], policy=policy, addressed=addressed,
                    source=body.get("source_venue"), sourced=bool(body.get("source_refs")))

    def conversations(self, kind=None, query="", after=None, before=None):
        """目录只列当前可向本会话展示的 QQ 原话。搜索日期使用 UTC 的 ISO 日期。"""
        cond = ["r.kind IN ('observation','self_expression')", self.visible_sql()]
        params = []
        if query:
            cond.append("(instr(json_extract(v.body,'$.content'),?)>0 OR instr(r.conversation,?)>0)")
            params.extend([query, query])
        for op, value in ((">=", after), ("<=", before)):
            if value:
                cond.append("substr(v.occurred,1,10)" + op + "?")
                params.append(value)
        with closing(self.connect()) as db:
            rows = db.execute("SELECT r.conversation,count(*) n,max(v.occurred) last FROM records r "
                              "JOIN revisions v ON v.record_id=r.id AND v.revision=r.revision WHERE "
                              + " AND ".join(cond) + " GROUP BY r.conversation ORDER BY last DESC", params)
            result = []
            for row in rows:
                match = QQ.fullmatch(row["conversation"])
                if match:
                    label = "group" if match[1] == "group" else "private"
                    if kind in (None, "", label):
                        result.append(dict(id=row["conversation"],kind=label,count=row["n"],last=row["last"]))
            return result

    @staticmethod
    def visible_sql():
        # 展示本会话正文前，以导出时的在线版本和每个上游的可引用权复核派生来源。
        return """r.status='ONLINE' AND json_extract(v.body,'$.privacy') IN ('public','private')
        AND NOT EXISTS (SELECT 1 FROM evidence_dependencies d WHERE d.derived=r.id AND NOT EXISTS
            (SELECT 1 FROM audience_rights a JOIN records p ON p.id=a.id WHERE a.id=d.source
             AND a.revision=d.revision AND a.audience=r.conversation AND a.use='CITE' AND p.status='ONLINE'))"""

    def timeline(self, conversation):
        if not QQ.fullmatch(conversation):
            raise ValueError("conversation 须为 QQ 群聊或私聊标识")
        with closing(self.connect()) as db:
            rows = db.execute("SELECT r.*,v.seq,v.occurred,v.body FROM records r JOIN revisions v "
                              "ON v.record_id=r.id AND v.revision=r.revision WHERE r.conversation=? "
                              "AND r.kind IN ('observation','self_expression') AND "
                              + self.visible_sql() + " ORDER BY v.seq", (conversation,))
            return [self.message(row) for row in rows]

    def cut(self, conversation, target_record_id):
        """候选必须在当时已入账，且该精确版本截至导出仍生效。外部授权同时检查两端水位。"""
        timeline = self.timeline(conversation)
        target = next((x for x in timeline if x["id"] == target_record_id), None)
        if not target or target["kind"] != "observation":
            raise ValueError("target 须为该会话可见的入站发言")
        if target["revision"] != 1:
            raise ValueError("该发言的原版本已更正，无法按原始收信时刻重放")
        kind, account, peer = QQ.fullmatch(conversation).groups()
        with closing(self.connect()) as db:
            grants = [dict(r) for r in db.execute("""SELECT g.* FROM visibility_grants g WHERE g.target=?
                AND g.use='CITE' AND g.status='ACTIVE' AND EXISTS (SELECT 1 FROM receipts t
                JOIN changes c ON c.seq=t.seq WHERE t.grant_id=g.grant_id AND c.op='GRANT' AND c.seq<?)""",
                                                 (conversation,target["seq"]))]
            authorized = {(g["record_id"],g["revision"]) for g in grants}
            rows = db.execute("""SELECT r.*,v.seq,v.occurred,v.body FROM records r JOIN revisions v
                ON r.id=v.record_id AND v.revision=r.revision WHERE r.status='ONLINE' AND v.seq<?
                AND json_extract(v.body,'$.privacy') IN ('public','private') ORDER BY v.seq""", (target["seq"],))
            selected, cite = {}, set()
            for row in rows:
                key = (row["id"],row["revision"])
                body = json.loads(row["body"])
                identity, channel = body.get("identity",{}), body.get("channel",{})
                # 本人原话与身份只能由正式 Memory 的专用途径读取，实验库保留原会话归属。
                own = (kind == "c2c" and row["kind"] == "observation" and
                       (identity.get("account_id") == peer or
                        (channel.get("account_id") == account and channel.get("sender_id") == peer
                         and channel.get("type") in ("qq_group","qq_private","qq_group_notice"))))
                if row["conversation"] == conversation or key in authorized:
                    cite.add(key)
                elif not own:
                    continue
                selected[key] = dict(row)
            # 包括传递来源。上游未来才获得授权时，较早的派生项也不能留在该截点。
            denied = {row["derived"] for row in db.execute("SELECT * FROM evidence_dependencies")
                      if (row["source"],row["revision"]) not in cite}
        records = [r for r in selected.values() if r["id"] not in denied]
        ids = {r["id"] for r in records}
        prefix = [x for x in timeline if x["seq"] < target["seq"] and x["id"] in ids and not x["sourced"]]
        return dict(target=target, prefix=prefix, records=records, grants=[g for g in grants if g["record_id"] in ids],
                    conversation=conversation, watermark=target["seq"]-1, snapshot=self.digest)

    def materialize(self, cut, path, memory_jar):
        """在新文件内重建白名单账本与索引。旧回执、整库 FTS、历史卡片、v1 审计副本均不进入 JVM。"""
        if path.exists():
            raise FileExistsError(path)
        with ZipFile(memory_jar) as jar, closing(sqlite3.connect(path)) as dest, closing(self.connect()) as src:
            for schema in ("schema.sql","visibility.sql","catalog.sql"):
                dest.executescript(jar.read(schema).decode())
            dest.execute("INSERT INTO meta VALUES (?,?)", (self.character, str(uuid.uuid5(uuid.NAMESPACE_URL,
                         f"{self.digest}/{cut['conversation']}/{cut['watermark']}"))))
            ids = {row["id"] for row in cut["records"]}
            for r in cut["records"]:
                rid, rev, seq = r["id"],r["revision"],r["seq"]
                oldrow = src.execute("SELECT rowid FROM records WHERE id=?", (rid,)).fetchone()[0]
                dest.execute("INSERT INTO records VALUES(?,?,?,?,?,?,?)",
                             tuple(r[k] for k in ("id","conversation","owner","source","kind","revision","status")))
                newrow = dest.execute("SELECT last_insert_rowid()").fetchone()[0]
                dest.execute("INSERT INTO changes VALUES(?,?,?,?,?)",
                             src.execute("SELECT * FROM changes WHERE seq=?", (seq,)).fetchone())
                dest.execute("INSERT INTO revisions VALUES(?,?,?,?,?)",(rid,rev,seq,r["occurred"],r["body"]))
                for table, columns in (("search","terms"), ("cell_search","words,dates")):
                    terms = src.execute(f"SELECT {columns} FROM {table} WHERE rowid=?", (oldrow,)).fetchone()
                    if terms:
                        placeholders = ",".join("?" for _ in terms)
                        dest.execute(f"INSERT INTO {table}(rowid,{columns}) VALUES(?,{placeholders})",(newrow,*terms))
                claims = src.execute("SELECT * FROM claim_times WHERE record_id=? AND revision=?", (rid,rev))
                dest.executemany("INSERT INTO claim_times VALUES(?,?,?,?,?)",claims)
            for dep in src.execute("SELECT * FROM sources"):
                if dep["derived"] in ids and dep["source"] in ids:
                    dest.execute("INSERT INTO sources VALUES(?,?,?)", tuple(dep))
            dest.executemany("INSERT INTO visibility_grants VALUES(?,?,?,?,?,?,?,?,?,?)",
                             [tuple(g.values()) for g in cut["grants"]])
            dest.commit()
