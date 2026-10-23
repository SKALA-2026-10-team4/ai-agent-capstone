"""평가셋 파일 하나로 호출(트리거) 진단 그래프를 실제 LLM 으로 돌려 보고서를 찍는다.

OPENAI_API_KEY 가 필요하고 유료 호출이 일어난다. skillops-agent 폴더에서 다음처럼 실행한다.

    uv run scripts/run_trigger.py evalsets/doc-assistant.sample.json
    uv run scripts/run_trigger.py evalsets/doc-assistant.sample.json --repeat 1 --out report.json
    uv run scripts/run_trigger.py <평가셋>.json --skills-dir ../skills   # 실제 SKILL.md 로 검사
    uv run scripts/run_trigger.py evalsets/order-flow.seed.json --generate-only \
        --out evalsets/order-flow.generated.json   # 질의만 만들고 멈춤(사람 검토용 초안)

평가셋 형식은 skillops-agent/evalsets/doc-assistant.sample.json 을 본다. skill 목록은 --skills-dir,
평가셋의 skills_dir, 평가셋의 skills 순서로 정한다(skill_quality.loader.resolve_skills).
"""

import argparse
import asyncio
import json
import os
import sys
import uuid
from pathlib import Path

# 스크립트를 직접 실행해도 skillops_agent 패키지를 import 하도록 skillops-agent 폴더를 경로에
# 넣는다.
sys.path.insert(0, str(Path(__file__).parent.parent))

from skillops_agent.config import RECURSION_LIMIT  # noqa: E402
from skillops_agent.skill_quality.loader import resolve_cases, resolve_skills  # noqa: E402
from skillops_agent.trigger_graph import trigger_graph  # noqa: E402
from skillops_agent.trigger_nodes import generate_eval_set  # noqa: E402


def _skills_dir_of(data: dict, eval_set: Path, override: Path | None) -> Path | None:
    """실제로 읽은 SKILL.md 폴더 경로다. 생성한 평가셋에 skills_dir 로 다시 적는다."""
    if override:
        return override.resolve()
    if data.get("skills_dir"):
        return (eval_set.parent / data["skills_dir"]).resolve()
    return None


async def generate_only(state: dict, data: dict, args) -> int:
    """평가 질의만 만들어 평가셋 JSON 으로 저장한다. 진단 그래프는 돌리지 않는다."""
    result = await generate_eval_set(state)
    for f in result["findings"]:
        if f["status"] == "FAIL":
            print(f"[rule {f['severity']}] {f['target']}: {f['id']} {f['reason']}")
    if result["error"] or not result["cases"]:
        print(f"\n중단: {result['error'] or result['status']}")
        return 1

    for c in result["cases"]:
        mark = "O" if c["should_trigger"] else "X"
        print(f"[{mark}] {c['expected_skill']:<18} {c['query']}")
    for r in result["remapped"]:
        print(f"[정답 고침] {r['query']} : {r['from']} → {r['to']}")
    for d in result["dropped"]:
        print(f"[버림] {d.get('query')} ({d['reason']})")

    out = args.out or args.eval_set.with_name(f"{state['target_skill']}.generated.json")
    eval_set = {
        "_comment": "LLM 이 만든 초안이다. 사람이 훑어보고 틀린 질의를 고친 뒤 평가셋으로 쓴다.",
        "target_skill": state["target_skill"],
        "seed_queries": state["seed_queries"],
        "cases": result["cases"],
    }
    skills_dir = _skills_dir_of(data, args.eval_set, args.skills_dir)
    if skills_dir:
        eval_set["skills_dir"] = os.path.relpath(skills_dir, out.resolve().parent)
    else:
        eval_set["skills"] = data.get("skills", [])
    out.write_text(json.dumps(eval_set, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\n질의 {len(result['cases'])}개를 {out} 에 저장했습니다.")
    return 0


async def main() -> int:
    """평가셋을 읽어 그래프를 돌리고 진행 메시지와 보고서를 찍는다."""
    parser = argparse.ArgumentParser()
    parser.add_argument("eval_set", type=Path)
    parser.add_argument("--repeat", type=int, default=None, help="질의마다 묻는 횟수")
    parser.add_argument("--out", type=Path, default=None, help="보고서를 저장할 JSON 경로")
    parser.add_argument(
        "--skills-dir", type=Path, default=None,
        help="SKILL.md 폴더(<이름>/SKILL.md). 원문을 검사한다",
    )
    parser.add_argument(
        "--generate-only", action="store_true",
        help="평가 질의만 만들어 --out(기본 <대상>.generated.json)에 저장하고 멈춘다",
    )
    args = parser.parse_args()

    data = json.loads(args.eval_set.read_text(encoding="utf-8"))
    state = {
        "target_skill": data["target_skill"],
        "skills": resolve_skills(data, args.eval_set.parent, args.skills_dir),
        "cases": resolve_cases(data, args.eval_set.parent),
        "seed_queries": data.get("seed_queries", []),
        "selector": data.get("selector", "chat"),
    }
    if args.repeat:
        state["repeat"] = args.repeat
    if args.generate_only:
        state["cases"] = []  # 있는 질의는 무시하고 새로 만든다
        return await generate_only(state, data, args)

    result = await trigger_graph.ainvoke(
        state,
        config={
            "configurable": {"thread_id": str(uuid.uuid4())},
            "recursion_limit": RECURSION_LIMIT,
        },
    )

    for event in result.get("events", []):
        print(f"[{event['node']}] {event['msg']}")
    if result.get("error"):
        print(f"\nERROR: {result['error']}")
        return 1
    # 규칙 검사 결과를 먼저 짧게 보여 준다.
    for f in result["report"]["quality"]["findings"]:
        print(f"[rule {f['severity']}] {f['target']}: {f['id']} {f['reason']}")

    text = json.dumps(result["report"], ensure_ascii=False, indent=2)
    print("\n" + text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
