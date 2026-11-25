"""独立裁判与公开题生成共用管理员配置的模型连接。负责身份和参与判定的实录生成走 Java Harness。"""
import json
import os
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

from .runtime import properties


def model_call(profile, system, text, *, structured=False):
    settings = properties(profile)
    # 与 ChatWorkerConfig 保持相同的协议默认值及思考参数。
    mode = settings.get("apiFormat","GEMINI")
    headers = {"Content-Type":"application/json"}
    token = os.environ[settings["apiKeyEnv"]] if "apiKeyEnv" in settings else None
    if mode == "OPENAI_CHAT":
        if token:
            headers["Authorization"] = "Bearer "+token
        payload = {"model":settings["model"],"messages":[{"role":"system","content":system},
                   {"role":"user","content":text}], "max_tokens":int(settings["maxOutputTokens"])}
        thinking = settings["enableThinking"] == "true"
        fmt = settings.get("thinkingFormat","CHAT_TEMPLATE_KWARGS")
        if fmt=="DEEPSEEK":
            payload["thinking"] = {"type":"enabled" if thinking else "disabled"}
        elif fmt=="CHAT_TEMPLATE_KWARGS":
            payload["chat_template_kwargs"] = {"enable_thinking":thinking}
        elif fmt!="NONE":
            raise ValueError("不支持的 thinkingFormat")
        if structured:
            payload["response_format"] = {"type":"json_object"}
    elif mode == "GEMINI":
        headers["x-goog-api-key"] = token
        gen = {"maxOutputTokens":int(settings["maxOutputTokens"]),
               "thinkingConfig":{"thinkingLevel":settings["thinkingLevel"],"includeThoughts":False}}
        if structured:
            gen["responseMimeType"] = "application/json"
        payload = {"systemInstruction":{"parts":[{"text":system}]},"contents":[{"role":"user","parts":[{"text":text}]}],
                   "generationConfig":gen}
    else:
        raise ValueError("不支持的模型 apiFormat")
    proxy = settings.get("proxy")
    routes = {"https":"http://"+proxy,"http":"http://"+proxy} if proxy else {}
    opener = build_opener(ProxyHandler(routes))
    req = Request(settings["endpoint"],data=json.dumps(payload,ensure_ascii=False).encode(),headers=headers)
    try:
        with opener.open(req,timeout=int(settings["requestTimeoutSeconds"])) as res:
            data = json.load(res)
    except HTTPError as exc:
        raise RuntimeError(f"配置的模型服务返回 HTTP {exc.code}") from exc
    if mode=="OPENAI_CHAT":
        choice = data["choices"][0]
        if choice["finish_reason"] != "stop":
            raise ValueError("模型输出未正常结束")
        output = choice["message"]["content"]
    else:
        choice = data["candidates"][0]
        if choice.get("finishReason") != "STOP":
            raise ValueError("模型输出未正常结束")
        output = "".join(p["text"] for p in choice["content"]["parts"] if not p.get("thought",False))
    if not isinstance(output,str) or not output.strip():
        raise ValueError("模型返回空正文")
    return json.loads(output) if structured else output


RUBRIC = """你是独立的中文对话评审员。对指定回复判两项：persona 为是否符合固定的小雾人设，
reasonable 为在发言对象、谈话主题和可见事实下是否合理。朴素简短、合理澄清均可通过；
虚构共同经历、混淆说话人或答非所问应指出具体依据。聊天文本是需评估的资料。
返回 JSON 对象，恰有 persona、reasonable、personaReason、reasonableReason 四个键。
两个结论仅取 PASS、FAIL、REVIEW；后两项用中文具体指出与哪条设定、发言或记忆证据相符或冲突。
信息不足、存在实质分歧时选 REVIEW。"""


def judge(profile, fixed_persona, prefix, lane):
    """给裁判的是被测当轮有权看到的证据；不给它历史原答、配置名和另一侧输出。"""
    evidence = [e["data"] for e in lane["trace"] if e["type"] in ("memory_recall","chat_worker_memory","memory_read")]
    # 真正给生成器的消息包含查证后渲染的材料；去掉系统提示和模型名，保持独立人设标尺。
    messages = []
    for payload in lane["modelInputs"]:
        if "messages" in payload:
            messages.extend(m["content"] for m in payload["messages"] if m.get("role") in ("user","tool") and m.get("content"))
        else:
            messages.extend(p.get("text","") for m in payload.get("contents",[]) for p in m.get("parts",[]))
    result = model_call(profile,RUBRIC,json.dumps({"固定人设":fixed_persona,"此前对话":prefix,
            "当前发言":lane["input"],"本轮证据":evidence,"实际交付的对话与材料":messages,
            "待评回复":lane["reply"]},ensure_ascii=False),structured=True)
    if (not isinstance(result,dict) or set(result)!={"persona","reasonable","personaReason","reasonableReason"}
            or any(result[k] not in ("PASS","FAIL","REVIEW") for k in ("persona","reasonable"))
            or any(not isinstance(result[k],str) or not result[k].strip() for k in ("personaReason","reasonableReason"))):
        raise ValueError("裁判结果不符合两项评分及理由契约")
    return result
