"""实验状态独立于 Memory。配置和案例是版本化对象，评分历史与每侧运行按事务更新。"""
import json
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone


def now():
    return datetime.now(timezone.utc).isoformat()


class Store:
    def __init__(self, root):
        self.file = root/"experiments.sqlite"
        with self.transaction() as db:
            db.execute("CREATE TABLE IF NOT EXISTS objects(kind TEXT,id TEXT PRIMARY KEY,body TEXT NOT NULL)")
            db.execute("CREATE INDEX IF NOT EXISTS object_kind ON objects(kind)")
            db.execute("CREATE TABLE IF NOT EXISTS splits(origin TEXT PRIMARY KEY,split TEXT NOT NULL)")
            # 进程中断的任务不能当作成功，也不能隐式重放已经调用过的付费模型。
            for kind, ident, body in db.execute("""SELECT kind,id,body FROM objects WHERE kind IN ('run','benchmark')
                AND json_extract(body,'$.status') IN ('queued','running')""").fetchall():
                doc = json.loads(body)
                doc.update(status="failed",error="服务重启，原运行未完成")
                for lane in doc.get("lanes",[]):
                    if lane["status"] in ("queued","running"):
                        lane.update(status="failed",error=doc["error"])
                self.write(db,kind,ident,doc)

    @contextmanager
    def transaction(self):
        db = sqlite3.connect(self.file,timeout=20)
        try:
            db.execute("BEGIN IMMEDIATE")
            yield db
            db.commit()
        finally:
            db.close()

    @staticmethod
    def write(db, kind, ident, doc):
        db.execute("UPDATE objects SET body=? WHERE kind=? AND id=?",
                   (json.dumps(doc,ensure_ascii=False),kind,ident))

    @staticmethod
    def read(db, kind, ident):
        row = db.execute("SELECT body FROM objects WHERE kind=? AND id=?",(kind,ident)).fetchone()
        if row is None:
            raise KeyError(f"{kind} {ident}")
        return json.loads(row[0])

    def get(self,kind,ident):
        with self.transaction() as db:
            return self.read(db,kind,ident)

    def all(self,kind):
        with self.transaction() as db:
            return [json.loads(row[0]) for row in db.execute("SELECT body FROM objects WHERE kind=? ORDER BY rowid DESC",(kind,))]

    def recent_runs(self):
        """首页只读最近运行的元数据，完整推理请求与私聊正文按运行 ID 读取。"""
        with self.transaction() as db:
            rows = db.execute("""SELECT id,json_extract(body,'$.caseId'),json_extract(body,'$.status'),
                json_extract(body,'$.created') FROM objects WHERE kind='run' ORDER BY rowid DESC LIMIT 60""")
            return [dict(zip(("id","caseId","status","created"),row)) for row in rows]

    def add(self,kind,doc, *, split_key=None):
        with self.transaction() as db:
            if split_key:
                db.execute("INSERT OR IGNORE INTO splits VALUES(?,?)",(split_key,doc["split"]))
                if db.execute("SELECT split FROM splits WHERE origin=?",(split_key,)).fetchone()[0] != doc["split"]:
                    raise ValueError("同一原会话只能属于一个数据分集")
            return self.insert(db,kind,doc)

    @staticmethod
    def insert(db, kind, values):
        doc = {**values,"id":uuid.uuid4().hex,"created":now()}
        db.execute("INSERT INTO objects VALUES(?,?,?)",(kind,doc["id"],json.dumps(doc,ensure_ascii=False)))
        return doc

    def change(self,kind,ident,update):
        with self.transaction() as db:
            doc = self.read(db,kind,ident)
            update(doc)
            self.write(db,kind,ident,doc)
            return doc
