#!/usr/bin/env python3
"""P0 synthetic Master baseline. Requires a local OpenAI-compatible /v1/completions server.

This intentionally measures raw completion, NOT chat-template instruction tuning.
No NeuralCloud state, memory database or production components are touched.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime, timezone
import json
from pathlib import Path
import time
from urllib.request import Request, urlopen


def load_cases(path: Path) -> list[dict]:
    cases = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    for case in cases:
        for field in ("id", "category", "context", "question", "expected"):
            if field not in case:
                raise ValueError(f"{case.get('id', '<missing id>')}: missing field {field}")
    ids = [case["id"] for case in cases]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate test-case IDs")
    return cases


def build_prompt(case: dict) -> str:
    return (
        "阅读事件日志，仅根据已给证据回答。"
        "从问题提供的选项中选择一个代码，只输出代码，不解释。\n\n"
        f"事件日志:\n{case['context']}\n\n"
        f"问题: {case['question']}\n"
        "答案代码:"
    )


def request_completion(endpoint: str, model: str, prompt: str, max_tokens: int, timeout: int) -> tuple[dict, float]:
    payload = {
        "model": model,
        "prompt": prompt,
        "temperature": 0,
        "max_tokens": max_tokens,
        "seed": 42,
    }
    request = Request(
        endpoint.rstrip("/") + "/completions",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    start = time.perf_counter()
    with urlopen(request, timeout=timeout) as response:
        obj = json.loads(response.read().decode("utf-8"))
    elapsed_ms = round((time.perf_counter() - start) * 1000, 1)
    return obj, elapsed_ms


def main() -> None:
    parser = argparse.ArgumentParser(description="Qwen3.5-9B-Base Master P0 raw-completion baseline")
    parser.add_argument("--endpoint", default="http://127.0.0.1:8000/v1", help="OpenAI-compatible base /v1 URL")
    parser.add_argument("--model", default="neuralcloud-master-base")
    parser.add_argument("--cases", type=Path, default=Path("workspace_assets/eval/master_baseline/cases_v0.jsonl"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--max-tokens", type=int, default=32)
    parser.add_argument("--model-revision", default="UNRECORDED", help="Record an exact weight revision/hash when known")
    args = parser.parse_args()

    cases = load_cases(args.cases)
    if not cases:
        raise ValueError("No baseline cases supplied")
    args.output.parent.mkdir(parents=True, exist_ok=True)

    by_category: dict[str, list[bool]] = defaultdict(list)
    metadata = {
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "model": args.model,
        "model_revision": args.model_revision,
        "endpoint": args.endpoint,
        "mode": "raw_completions",
        "temperature": 0,
        "seed": 42,
        "max_tokens": args.max_tokens,
        "cases": str(args.cases),
        "case_count": len(cases),
        "note": "strict first-line code accuracy is a proxy for format+task accuracy, not full semantic comprehension; no TTFT measurement",
    }
    args.output.with_suffix(args.output.suffix + ".manifest.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    with args.output.open("w", encoding="utf-8") as target:
        for case in cases:
            prompt = build_prompt(case)
            response, elapsed_ms = request_completion(
                args.endpoint, args.model, prompt, args.max_tokens, args.timeout
            )
            raw = response["choices"][0]["text"]
            first_line = raw.lstrip().splitlines()[0].strip().strip(" \"'\`") if raw.strip() else ""
            correct = first_line.casefold() == case["expected"].casefold()
            by_category[case["category"]].append(correct)
            result = {
                "id": case["id"],
                "category": case["category"],
                "expected": case["expected"],
                "prediction": first_line,
                "strict_correct": correct,
                "prompt": prompt,
                "raw_output": raw,
                "usage": response.get("usage"),
                "elapsed_ms": elapsed_ms,
                "finish_reason": response["choices"][0].get("finish_reason"),
            }
            target.write(json.dumps(result, ensure_ascii=False) + "\n")
            target.flush()
            print(f"{case['id']}: {'PASS' if correct else 'FAIL'} ({elapsed_ms}ms)")

    n = len(cases)
    successes = sum(sum(v) for v in by_category.values())
    summary = {
        "total": n,
        "strict_correct": successes,
        "strict_accuracy": round(successes / n, 4),
        "by_category": {
            category: {"passed": sum(results), "total": len(results)}
            for category, results in sorted(by_category.items())
        },
        "warning": "No semantic adjudication, long-context performance, TTFT, or training result is claimed.",
    }
    args.output.with_suffix(args.output.suffix + ".summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
