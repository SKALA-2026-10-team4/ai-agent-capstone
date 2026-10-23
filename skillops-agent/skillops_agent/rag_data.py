"""SkillOps 수정안 생성에 넣을 RAG 데이터를 만든다.

LLM 이 수정안을 엉뚱하게 내는 주된 이유는 시스템 사실을 몰라서다. 도구가 실제로 돌려주는 값, 값마다
만드는 skill 과 쓰는 skill, 처리 순서 규칙을 사람이 쓰지 않고 실행 기록과 게시본 SKILL.md 와 주문
Agent 코드에서 자동으로 만든다. 판단 기준은 운영자가 쓴 skillops-agent/rag/guides/*.md 를 읽는다.

만드는 항목(kind)은 네 가지다.
- tool_contract: skill 마다 1건. 성공한 실행 기록의 TOOL_REQUEST(도구·인자)와
  TOOL_RESULT(실제 반환 키)
- contract_map: 1건. 게시본 frontmatter 의 inputs/outputs 와 OrderFlowPolicy.INITIAL_INPUT_KEYS
- flow_policy: 1건. 게시본 order-flow 의 steps, 각 skill 의 depends_on, OrderFlowPolicy 의
  필수 마지막 단계와 최대 단계 수
- guideline: 판단 기준 문서마다 1건

항목마다 skills, fields, error_categories 메타데이터를 붙여 조회 함수가 skill 이름과 오류 종류로
정확히 꺼낼 수 있게 한다. 이 모듈은 주문 Agent 를 부르지 않는 순수 함수만 둔다. 조회와 저장은
skillops-agent/scripts/build_rag_data.py 가 한다. 결과 파일은 DATA_PATH 다.
"""

import json
import re
from datetime import UTC, datetime
from pathlib import Path

# 저장소 루트(skillops-agent/skillops_agent/rag_data.py 에서 두 단계 위)다.
REPO_ROOT = Path(__file__).resolve().parents[2]
# 판단 기준 문서와 생성 결과를 두는 폴더다.
RAG_DIR = REPO_ROOT / "skillops-agent" / "rag"
GUIDES_DIR = RAG_DIR / "guides"
DATA_PATH = RAG_DIR / "rag_data.json"
# 처음 주문 입력, 필수 마지막 단계, 최대 단계 수를 읽는 주문 Agent 코드다.
FLOW_POLICY_PATH = (
    REPO_ROOT
    / "order-agent/backend/src/main/java/com/capstone4/orderagent/harness"
    / "OrderFlowPolicy.java"
)
# 결과 파일 형식 버전이다. 칸 이름이나 뜻을 바꾸면 올린다.
SCHEMA_VERSION = 1
# 도구 계약을 어기면 나는 오류 종류다. 조회 함수가 오류 종류로 항목을 고를 때 쓴다.
CONTRACT_ERRORS = ["MISSING_SKILL_OUTPUT", "CONTRACT_VIOLATION"]
FLOW_ERRORS = ["CONTRACT_VIOLATION", "INCOMPLETE_FLOW", "INVALID_FLOW"]
# 처리 순서 규칙을 붙이는 기준 skill 이다. 이 skill 이 바뀌었을 때만 flow_policy 를 넣는다.
FLOW_SKILL = "order-flow"


def tool_contracts(trace: dict) -> list[dict]:
    """성공한 실행 기록 하나에서 skill 별 도구 계약을 만든다.

    TOOL_REQUEST 의 data.tool 과 data.arguments 키로 도구와 인자를, TOOL_RESULT 의 data.result
    (context 가 있으면 그 안)의 키로 도구가 실제로 돌려준 값을 정한다. 실행에서 실제로 부른 도구만
    나오므로, 성공한 실행(completion=COMPLETED)을 넘겨야 모든 단계가 들어간다.
    """
    found: dict[str, dict] = {}
    for event in trace.get("events", []):
        skill, data = event.get("skill_id"), event.get("data") or {}
        if not skill:
            continue
        if event.get("kind") == "TOOL_REQUEST":
            slot = found.setdefault(skill, {})
            slot["tool"] = data.get("tool")
            slot["tool_args"] = sorted((data.get("arguments") or {}).keys())
        elif event.get("kind") == "TOOL_RESULT":
            result = data.get("result") or {}
            found.setdefault(skill, {})["produces"] = sorted((result.get("context", result)).keys())
    items = []
    for skill in sorted(found):
        contract = found[skill]
        produces = contract.get("produces", [])
        items.append({
            "id": f"tool-{skill}",
            "kind": "tool_contract",
            "skills": [skill],
            "fields": produces,
            "error_categories": CONTRACT_ERRORS,
            "tool": contract.get("tool"),
            "tool_args": contract.get("tool_args", []),
            "produces": produces,
            "text": (
                f"{skill} skill 은 {contract.get('tool')} 도구만 쓴다. 도구가 실제로 돌려주는 값: "
                f"{', '.join(produces)}. 이 밖의 값은 도구가 만들지 않으므로 outputs 에 "
                f"선언하면 실행 중 MISSING_SKILL_OUTPUT 으로 실패한다. 선언만 바꿔서는 새 값을 "
                f"만들 수 없고 도구 구현을 바꿔야 한다."
            ),
            "source": {"type": "trace", "execution_id": trace.get("execution_id"),
                       "events": ["TOOL_REQUEST", "TOOL_RESULT"]},
        })
    return items


def contract_map(skills: list[dict], initial_inputs: list[str]) -> dict:
    """게시본 skill 목록으로 값마다 만드는 skill 과 쓰는 skill 을 정리한 계약 지도를 만든다.

    skills 는 skillops-agent/skillops_agent/order_agent.py 의 list_skills 응답 items 다. 값은 어떤
    skill 의 outputs 에 있거나 처음 주문 입력이면 공급된 것으로 본다.
    """
    edges = []
    for key in sorted({k for s in skills for k in s.get("outputs", [])}):
        producers = sorted(s["skill_id"] for s in skills if key in s.get("outputs", []))
        consumers = sorted(s["skill_id"] for s in skills if key in s.get("inputs", []))
        edges.append({"key": key, "producers": producers, "consumers": consumers})
    lines = [
        f"- {e['key']}: {', '.join(e['producers'])} → "
        f"{', '.join(e['consumers']) or '(쓰는 skill 없음)'}"
        for e in edges
    ]
    return {
        "id": "contract-map",
        "kind": "contract_map",
        "skills": ["*"],
        "fields": sorted({e["key"] for e in edges} | set(initial_inputs)),
        "error_categories": CONTRACT_ERRORS,
        "initial_inputs": initial_inputs,
        "edges": edges,
        "text": (
            "[정상 계약 지도: 게시본 기준] 처음 주문 입력: " + ", ".join(initial_inputs) + "\n"
            + "\n".join(lines)
            + "\n어떤 skill 도 만들지 않는 값을 inputs 에 요구하면 정적 검사에서 "
            "CONTRACT_VIOLATION(missing_inputs)으로 실행 전에 막힌다. "
            "오류가 난 skill(입력을 못 받은 쪽)과 "
            "고칠 skill(값을 안 만들거나 없는 값을 요구한 쪽)은 다를 수 있다."
        ),
        "source": {"type": "skill_frontmatter", "fields": ["inputs", "outputs"],
                   "initial_inputs": "OrderFlowPolicy.INITIAL_INPUT_KEYS"},
    }


def flow_policy(skills: list[dict], required_final_step: str, max_flow_steps: int) -> dict:
    """게시본 order-flow 의 steps 와 각 skill 의 depends_on 으로 처리 순서 규칙을 만든다."""
    flow = next((s for s in skills if s["skill_id"] == FLOW_SKILL), {})
    steps = flow.get("steps", [])
    deps = [f"{s['skill_id']} 는 {', '.join(s['depends_on'])} 가 끝난 뒤에만 실행된다"
            for s in skills if s.get("depends_on")]
    return {
        "id": "policy-flow",
        "kind": "flow_policy",
        "skills": [FLOW_SKILL],
        "fields": [],
        "error_categories": FLOW_ERRORS,
        "steps": steps,
        "required_final_step": required_final_step,
        "max_flow_steps": max_flow_steps,
        "text": (
            f"정상 steps: [{', '.join(steps)}]. 선행 단계: " + "; ".join(deps) + ". "
            f"필수 마지막 단계: {required_final_step} (빠지면 INCOMPLETE_FLOW). "
            f"최대 단계 수: {max_flow_steps} (넘으면 INVALID_FLOW)."
        ),
        "source": {"type": "skill_frontmatter", "fields": ["steps", "depends_on"],
                   "constants": "OrderFlowPolicy.REQUIRED_FINAL_STEP, MAX_FLOW_STEPS"},
    }


def read_flow_policy_constants(java_text: str) -> dict:
    """OrderFlowPolicy.java 에서 처음 주문 입력, 필수 마지막 단계, 최대 단계 수를 읽는다.

    상수 이름이나 모양이 바뀌어 읽지 못하면 틀린 값으로 데이터를 만들지 않도록 ValueError 를 던진다.
    """
    keys = re.search(r"INITIAL_INPUT_KEYS\s*=\s*List\.of\(([^)]*)\)", java_text)
    final = re.search(r'REQUIRED_FINAL_STEP\s*=\s*"([^"]+)"', java_text)
    limit = re.search(r"MAX_FLOW_STEPS\s*=\s*(\d+)", java_text)
    if not (keys and final and limit):
        raise ValueError(f"OrderFlowPolicy 상수를 읽지 못했습니다: {FLOW_POLICY_PATH}")
    return {
        "initial_inputs": re.findall(r'"([^"]+)"', keys.group(1)),
        "required_final_step": final.group(1),
        "max_flow_steps": int(limit.group(1)),
    }


_FRONT = re.compile(r"\A---\n(.*?)\n---\n(.*)\Z", re.S)
_LIST = re.compile(r"^\[(.*)\]$")


def load_guides(guides_dir: Path = GUIDES_DIR) -> list[dict]:
    """판단 기준 문서(*.md)를 읽어 guideline 항목으로 만든다.

    문서 맨 위 frontmatter 의 id, applies_to_errors, owner, version 을 메타데이터로 쓰고
    본문을 text 로 쓴다. 판단 기준은 사실이 아니라 운영 합의라서 사람이 쓰고, 바꿀 때 version 을
    올린다.
    """
    items = []
    for path in sorted(guides_dir.glob("*.md")):
        match = _FRONT.match(path.read_text(encoding="utf-8"))
        if not match:
            raise ValueError(f"판단 기준 문서에 frontmatter 가 없습니다: {path}")
        meta = {}
        for line in match.group(1).splitlines():
            key, _, value = line.partition(":")
            value = value.strip()
            listed = _LIST.match(value)
            if listed:
                value = [v.strip() for v in listed.group(1).split(",") if v.strip()]
            meta[key.strip()] = value
        items.append({
            "id": meta["id"],
            "kind": "guideline",
            "skills": ["*"],
            "fields": [],
            "error_categories": meta.get("applies_to_errors", []),
            "owner": meta.get("owner"),
            "version": meta.get("version"),
            "text": " ".join(match.group(2).split()),
            "source": {"type": "guide", "path": str(path.relative_to(REPO_ROOT))},
        })
    return items


def build(trace: dict, snapshot: dict, skills: list[dict], flow_constants: dict,
          guides: list[dict]) -> dict:
    """모든 항목을 모아 결과 파일 하나로 만든다. snapshot 은 trace 가 돈 게시본 manifest 다."""
    items = [
        *tool_contracts(trace),
        contract_map(skills, flow_constants["initial_inputs"]),
        flow_policy(
            skills, flow_constants["required_final_step"], flow_constants["max_flow_steps"]
        ),
        *guides,
    ]
    return {
        "schema_version": SCHEMA_VERSION,
        "generated_at": datetime.now(UTC).isoformat(timespec="seconds"),
        "bundle": {
            "snapshot_id": snapshot.get("snapshot_id"),
            "snapshot_hash": snapshot.get("hash"),
            "release": (snapshot.get("metadata") or {}).get("release"),
            "source": (snapshot.get("metadata") or {}).get("source"),
        },
        "source_execution_id": trace.get("execution_id"),
        "items": items,
    }


def write(data: dict, path: Path = DATA_PATH) -> Path:
    """결과를 사람이 읽기 좋게 들여 쓴 UTF-8 JSON 으로 저장한다."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path
