"""평가셋 파일 하나로 호출(트리거) 진단 그래프를 실제 LLM 으로 돌려 보고서를 찍는다.

OPENAI_API_KEY 가 필요하고 유료 호출이 일어난다. skillops-agent 폴더에서 다음처럼 실행한다.

    uv run scripts/run_trigger.py evalsets/doc-assistant.sample.json
    uv run scripts/run_trigger.py evalsets/doc-assistant.sample.json --repeat 1 --out report.json
    uv run scripts/run_trigger.py <평가셋>.json --skills-dir ../skills   # 실제 SKILL.md 로 검사

평가셋 형식은 skillops-agent/evalsets/doc-assistant.sample.json 을 본다. skill 목록은 --skills-dir,
평가셋의 skills_dir, 평가셋의 skills 순서로 정한다(skill_quality.loader.resolve_skills).
"""

import argparse
import asyncio
import json
import sys
import uuid
from pathlib import Path

# 스크립트를 직접 실행해도 skillops_agent 패키지를 import 하도록 skillops-agent 폴더를 경로에
# 넣는다.
sys.path.insert(0, str(Path(__file__).parent.parent))

from skillops_agent.config import RECURSION_LIMIT  # noqa: E402
from skillops_agent.skill_quality.loader import resolve_skills  # noqa: E402
from skillops_agent.trigger_graph import trigger_graph  # noqa: E402


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
    args = parser.parse_args()

    data = json.loads(args.eval_set.read_text(encoding="utf-8"))
    state = {
        "target_skill": data["target_skill"],
        "skills": resolve_skills(data, args.eval_set.parent, args.skills_dir),
        "cases": data.get("cases", []),
        "seed_queries": data.get("seed_queries", []),
    }
    if args.repeat:
        state["repeat"] = args.repeat

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
