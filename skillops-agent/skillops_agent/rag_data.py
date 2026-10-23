"""SkillOps 수정안 생성에 넣을 RAG 데이터를 만든다.

LLM 이 수정안을 엉뚱하게 내는 주된 이유는 시스템 사실을 몰라서다. 도구가 실제로 돌려주는 값, 값마다
만드는 skill 과 쓰는 skill, 처리 순서 규칙을 사람이 쓰지 않고 주문 Agent 코드와 실행 기록과 게시본
SKILL.md 에서 자동으로 만든다. 판단 기준, 업무 결과 규칙, 장애 사례는 사람이 쓴
skillops-agent/rag/ 의 문서를 읽는다. 항목 모양은 팀 RAG 실험(TSG: 도구 계약 + skill 이름 조회 +
판단 기준)의 설계를 따른다.

만드는 항목(kind)은 다섯 가지다.
- tool_contract: skill 마다 1건. 허용 도구·인자·보상 도구는 ToolCatalog.java, 도구가 실제로 돌려주는
  값은 게시본으로 성공한 실행 기록의 TOOL_RESULT
- contract_map: 1건. 게시본 frontmatter 의 inputs/outputs 와 OrderFlowPolicy.INITIAL_INPUT_KEYS
- policy: policy-flow 는 게시본 steps·depends_on 과 OrderFlowPolicy 에서 자동, 나머지는
  skillops-agent/rag/policies/*.md
- guideline: skillops-agent/rag/guides/*.md 마다 1건
- case: skillops-agent/rag/cases.json 의 사례마다 1건. 처음에는 order-agent/docs/scenarios 의
  S2·S3·S4·S6 으로 만들었다

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
# 사람이 쓰는 문서와 생성 결과를 두는 폴더다.
RAG_DIR = REPO_ROOT / "skillops-agent" / "rag"
GUIDES_DIR = RAG_DIR / "guides"
POLICIES_DIR = RAG_DIR / "policies"
CASES_PATH = RAG_DIR / "cases.json"
DATA_PATH = RAG_DIR / "rag_data.json"
# 사실 데이터를 읽는 주문 Agent 코드다.
_HARNESS = REPO_ROOT / "order-agent/backend/src/main/java/com/capstone4/orderagent/harness"
FLOW_POLICY_PATH = _HARNESS / "OrderFlowPolicy.java"
TOOL_CATALOG_PATH = _HARNESS / "ToolCatalog.java"
# 결과 파일 형식 버전이다. 칸 이름이나 뜻을 바꾸면 올린다. 2 에서 TSG 설계의 칸 이름으로 바꿨다.
SCHEMA_VERSION = 2
# 도구 계약을 어기면 나는 오류 종류다. 조회 함수가 오류 종류로 항목을 고를 때 쓴다.
CONTRACT_ERRORS = ["MISSING_SKILL_OUTPUT", "CONTRACT_VIOLATION"]
FLOW_ERRORS = ["CONTRACT_VIOLATION", "INCOMPLETE_FLOW", "INVALID_FLOW"]
# 처리 순서 규칙을 붙이는 기준 skill 이다. 이 skill 이 바뀌었을 때만 policy-flow 를 넣는다.
FLOW_SKILL = "order-flow"
# ToolCatalog.java 의 도구 설명에서 인자 목록을 읽는 표시다. 도구는 모두 order_id 를 받는다.
_ARGS_MARK = "Arguments:"
_DEFAULT_ARGS = ["order_id"]


# ── 주문 Agent 코드 읽기 ─────────────────────────────────────────────────────────


def read_tool_catalog(java_text: str) -> dict:
    """ToolCatalog.java 에서 skill 별 허용 도구, 인자, 설명과 보상 도구를 읽는다.

    BINDINGS 의 new Binding("skill", "tool", ProcessingStepType.X, "설명", ...) 을 읽는다. 인자는
    설명의 'Arguments: a, b' 부분이고, 없으면 모든 도구가 받는 order_id 만 둔다. 보상
    도구(RELEASE_TOOL)는 도구 이름 앞부분이 같은 skill 에 붙인다(inventory.release →
    inventory.reserve 를 쓰는 inventory-check).
    모양이 바뀌어 읽지 못하면 틀린 값으로 데이터를 만들지 않도록 ValueError 를 던진다.
    """
    bindings = re.findall(
        r'new Binding\(\s*"([^"]+)",\s*"([^"]+)",\s*ProcessingStepType\.\w+,\s*"([^"]*)"', java_text
    )
    release = re.search(r'RELEASE_TOOL\s*=\s*"([^"]+)"', java_text)
    if not bindings:
        raise ValueError(f"ToolCatalog BINDINGS 를 읽지 못했습니다: {TOOL_CATALOG_PATH}")
    catalog = {}
    for skill, tool, description in bindings:
        args = _DEFAULT_ARGS
        if _ARGS_MARK in description:
            listed = description.split(_ARGS_MARK, 1)[1].strip().rstrip(".")
            args = [a.strip() for a in re.split(r",\s*(?![^\[]*\])", listed) if a.strip()]
        catalog[skill] = {"tool": tool, "tool_args": args, "description": description,
                          "compensation_tool": None}
    if release:
        prefix = release.group(1).split(".")[0]
        for entry in catalog.values():
            if entry["tool"].split(".")[0] == prefix:
                entry["compensation_tool"] = release.group(1)
    return catalog


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


def traced_tools(trace: dict) -> dict:
    """실행 기록에서 skill 별로 실제로 부른 도구와 돌려받은 값의 키를 모은다.

    TOOL_REQUEST 의 data.tool, TOOL_RESULT 의 data.result(context 가 있으면 그 안) 키를 쓴다.
    """
    found: dict[str, dict] = {}
    for event in trace.get("events", []):
        skill, data = event.get("skill_id"), event.get("data") or {}
        if not skill:
            continue
        if event.get("kind") == "TOOL_REQUEST":
            found.setdefault(skill, {})["tool"] = data.get("tool")
        elif event.get("kind") == "TOOL_RESULT":
            result = data.get("result") or {}
            found.setdefault(skill, {})["produces"] = sorted((result.get("context", result)).keys())
    return found


# ── 항목 만들기 ───────────────────────────────────────────────────────────────────


def tool_contracts(catalog: dict, trace: dict, version: dict) -> tuple[list[dict], list[str]]:
    """ToolCatalog 와 실행 기록을 합쳐 skill 별 도구 계약과 대조 경고를 만든다.

    허용 도구·인자·보상 도구는 catalog, 도구가 실제로 돌려준 값은 trace 에서 가져온다. catalog 에는
    있는데 실행 기록에 없는 skill, 또는 기록의 도구가 catalog 와 다른 skill 은 경고로 남긴다.
    version 은 generated_at, bundle_version 을 담는다.
    """
    traced = traced_tools(trace)
    warnings = []
    items = []
    for skill in sorted(catalog):
        entry, seen = catalog[skill], traced.get(skill)
        produces = (seen or {}).get("produces", [])
        if not seen:
            warnings.append(f"{skill}: 실행 기록에 도구 호출이 없어 반환 값을 알 수 없습니다")
        elif seen.get("tool") != entry["tool"]:
            warnings.append(f"{skill}: 실행 기록의 도구 {seen.get('tool')} 가 ToolCatalog 의 "
                            f"{entry['tool']} 와 다릅니다")
        compensation = entry["compensation_tool"]
        text = (
            f"{skill} skill 은 {entry['tool']} 도구만 쓴다. 도구가 실제로 돌려주는 값: "
            f"{', '.join(produces)}. 이 밖의 값은 도구가 만들지 않으므로 outputs 에 "
            f"선언하면 실행 중 MISSING_SKILL_OUTPUT 으로 실패한다. 선언만 바꿔서는 새 값을 "
            f"만들 수 없고 도구 구현을 바꿔야 한다."
        )
        if compensation:
            text += (f" 결제가 거절되거나 결제 전에 오류가 나면 서버가 {compensation} 으로 "
                     f"예약을 되돌린다.")
        items.append({
            "id": f"tool-{skill}",
            "kind": "tool_contract",
            "skill": skill,
            "tool": entry["tool"],
            "tool_args": entry["tool_args"],
            "produces": produces,
            "compensation_tool": compensation,
            "failure_if_violated": "MISSING_SKILL_OUTPUT",
            "skills": [skill],
            "fields": produces,
            "error_categories": CONTRACT_ERRORS,
            "source": [str(TOOL_CATALOG_PATH.relative_to(REPO_ROOT)), "trace:TOOL_RESULT"],
            "source_execution_id": trace.get("execution_id"),
            **version,
            "text": text,
        })
    for skill in sorted(set(traced) - set(catalog)):
        warnings.append(f"{skill}: 실행 기록에는 있는데 ToolCatalog 에 없습니다")
    return items, warnings


def contract_map(skills: list[dict], initial_inputs: list[str], version: dict) -> dict:
    """게시본 skill 목록으로 값마다 만드는 skill 과 쓰는 skill 을 정리한 계약 지도를 만든다.

    skills 는 skillops-agent/skillops_agent/order_agent.py 의 list_skills 응답 items 다. 값은 어떤
    skill 의 outputs 에 있거나 처음 주문 입력이면 공급된 것으로 본다. 한 값을 여러 skill 이 내보내면
    producer 에는 skill_id 순으로 첫 skill 을 둔다.
    """
    edges = []
    for key in sorted({k for s in skills for k in s.get("outputs", [])}):
        producers = sorted(s["skill_id"] for s in skills if key in s.get("outputs", []))
        consumers = sorted(s["skill_id"] for s in skills if key in s.get("inputs", []))
        edges.append({"key": key, "producer": producers[0], "consumers": consumers})
    lines = [
        f"- {e['key']}: {e['producer']} → {', '.join(e['consumers']) or '(쓰는 skill 없음)'}"
        for e in edges
    ]
    return {
        "id": "contract-map",
        "kind": "contract_map",
        "initial_inputs": initial_inputs,
        "edges": edges,
        "skills": ["*"],
        "fields": sorted({e["key"] for e in edges} | set(initial_inputs)),
        "error_categories": CONTRACT_ERRORS,
        "source": ["skills/*/SKILL.md frontmatter", "OrderFlowPolicy.INITIAL_INPUT_KEYS"],
        **version,
        "text": (
            "[정상 계약 지도: 게시본 기준] 처음 주문 입력: " + ", ".join(initial_inputs) + "\n"
            + "\n".join(lines)
            + "\n어떤 skill 도 만들지 않는 값을 inputs 에 요구하면 정적 검사에서 "
            "CONTRACT_VIOLATION(missing_inputs)으로 실행 전에 막힌다. "
            "오류가 난 skill(입력을 못 받은 쪽)과 "
            "고칠 skill(값을 안 만들거나 없는 값을 요구한 쪽)은 다를 수 있다."
        ),
    }


def flow_policy(skills: list[dict], flow_constants: dict, version: dict) -> dict:
    """게시본 steps·depends_on 과 OrderFlowPolicy 상수로 처리 순서 규칙(policy-flow)을 만든다.

    선행 단계 규칙은 skill 마다 depends_on 에서 하나씩 만든다. shipping-reserve 의 depends_on 이
    payment 이므로 '결제 전 배송 금지' 도 여기서 나온다.
    """
    flow = next((s for s in skills if s["skill_id"] == FLOW_SKILL), {})
    steps = flow.get("steps", [])
    rules = [{"rule": "정상 steps", "value": steps}]
    for skill in sorted(skills, key=lambda s: s["skill_id"]):
        for dep in skill.get("depends_on", []):
            rules.append({"rule": "선행 단계",
                          "value": f"{skill['skill_id']} 는 {dep} 가 끝난 뒤에만",
                          "violation": "CONTRACT_VIOLATION"})
    rules.append({"rule": "필수 마지막 단계", "value": flow_constants["required_final_step"],
                  "violation": "INCOMPLETE_FLOW"})
    rules.append({"rule": "최대 단계 수", "value": flow_constants["max_flow_steps"],
                  "violation": "INVALID_FLOW"})
    return {
        "id": "policy-flow",
        "kind": "policy",
        "applies_to_skills": [FLOW_SKILL],
        "rules": rules,
        "skills": [FLOW_SKILL],
        "fields": [],
        "error_categories": FLOW_ERRORS,
        "source": [str(FLOW_POLICY_PATH.relative_to(REPO_ROOT)), "skills/order-flow/SKILL.md steps",
                   "skills/*/SKILL.md depends_on"],
        **version,
        "text": " ".join(_rule_sentence(r) for r in rules),
    }


def _rule_sentence(rule: dict) -> str:
    value = rule["value"]
    shown = f"[{', '.join(value)}]" if isinstance(value, list) else str(value)
    tail = f" (어기면 {rule['violation']})" if rule.get("violation") else ""
    return f"{rule['rule']}: {shown}{tail}."


_FRONT = re.compile(r"\A---\n(.*?)\n---\n(.*)\Z", re.S)
_LIST = re.compile(r"^\[(.*)\]$")


def _read_markdown(path: Path) -> tuple[dict, str]:
    """frontmatter 가 있는 문서를 (메타데이터, 한 줄로 이은 본문) 으로 읽는다."""
    match = _FRONT.match(path.read_text(encoding="utf-8"))
    if not match:
        raise ValueError(f"문서에 frontmatter 가 없습니다: {path}")
    meta = {}
    for line in match.group(1).splitlines():
        key, _, value = line.partition(":")
        value = value.strip()
        listed = _LIST.match(value)
        if listed:
            value = [v.strip() for v in listed.group(1).split(",") if v.strip()]
        meta[key.strip()] = value
    return meta, " ".join(match.group(2).split())


def load_guides(guides_dir: Path = GUIDES_DIR) -> list[dict]:
    """판단 기준 문서(*.md)를 읽어 guideline 항목으로 만든다.

    frontmatter 의 id, applies_to_errors, owner, version 을 쓴다. 판단 기준은 사실이 아니라 운영
    합의라서 사람이 쓰고, 바꿀 때 version 을 올린다.
    """
    items = []
    for path in sorted(guides_dir.glob("*.md")):
        meta, body = _read_markdown(path)
        items.append({
            "id": meta["id"],
            "kind": "guideline",
            "applies_to_errors": meta.get("applies_to_errors", []),
            "owner": meta.get("owner"),
            "version": meta.get("version"),
            "skills": ["*"],
            "fields": [],
            "error_categories": meta.get("applies_to_errors", []),
            "source": [str(path.relative_to(REPO_ROOT))],
            "text": body,
        })
    return items


def load_policies(policies_dir: Path = POLICIES_DIR) -> list[dict]:
    """사람이 쓰는 업무 규칙 문서(*.md)를 읽어 policy 항목으로 만든다.

    frontmatter 의 id, applies_to_skills, source, version 을 쓴다. 정상 거절(REJECTED/HOLD)처럼 코드
    한 곳에서 뽑기 어려운 업무 결과 규칙을 둔다.
    """
    items = []
    for path in sorted(policies_dir.glob("*.md")):
        meta, body = _read_markdown(path)
        skills = meta.get("applies_to_skills", [])
        source = meta.get("source", [])
        items.append({
            "id": meta["id"],
            "kind": "policy",
            "applies_to_skills": skills,
            "version": meta.get("version"),
            "skills": skills,
            "fields": [],
            "error_categories": [],
            "source": [str(path.relative_to(REPO_ROOT)),
                       *(source if isinstance(source, list) else [source])],
            "text": body,
        })
    return items


def load_cases(path: Path = CASES_PATH) -> list[dict]:
    """장애 사례(cases.json)를 읽어 case 항목으로 만든다.

    사례마다 symptom(error_category, phase, failed_skill, missing_inputs), change, root_skill,
    correct_fix, anti_patterns, outcome, source 를 둔다. 조회 함수가 오류 종류와 skill 로 거른 뒤
    최대 1~2건만 넣으므로, 메타데이터에 원인 skill 과 실패 skill 을 함께 둔다.
    """
    items = []
    for case in json.loads(path.read_text(encoding="utf-8")):
        symptom, change = case["symptom"], case["change"]
        skills = sorted({case["root_skill"], symptom["failed_skill"]})
        text = (
            f"[사례 {case['id']}] 증상: {symptom['failed_skill']} 에서 {symptom['error_category']}"
            f"({symptom.get('phase')}), 누락 입력 {symptom.get('missing_inputs') or '없음'}. "
            f"변경: {change['skill']} 의 {change['field']} {change['before']} → {change['after']}. "
            f"원인 skill: {case['root_skill']}. 올바른 수정: {case['correct_fix']}. "
            f"피해야 할 수정: {'; '.join(case['anti_patterns'])}."
        )
        items.append({
            **case,
            "kind": "case",
            "skills": skills,
            "fields": sorted(set(symptom.get("missing_inputs") or [])),
            "error_categories": [symptom["error_category"]],
            "text": text,
        })
    return items


def build(trace: dict, snapshot: dict, skills: list[dict], flow_constants: dict, catalog: dict,
          guides: list[dict], policies: list[dict], cases: list[dict]) -> tuple[dict, list[str]]:
    """모든 항목을 모아 결과 파일 하나로 만든다. (결과, 도구 계약 대조 경고) 를 돌려준다.

    snapshot 은 trace 가 돈 게시본 manifest 다. bundle_version 은
    '<release>@<snapshot hash 앞 12자>' 로 두어 게시본이 바뀌면 값도 바뀐다.
    """
    meta = snapshot.get("metadata") or {}
    generated_at = datetime.now(UTC).isoformat(timespec="seconds")
    version = {"generated_at": generated_at,
               "bundle_version": f"{meta.get('release')}@{(snapshot.get('hash') or '')[:12]}"}
    contracts, warnings = tool_contracts(catalog, trace, version)
    items = [
        *contracts,
        contract_map(skills, flow_constants["initial_inputs"], version),
        flow_policy(skills, flow_constants, version),
        *policies,
        *guides,
        *cases,
    ]
    return {
        "schema_version": SCHEMA_VERSION,
        "generated_at": generated_at,
        "bundle": {
            "snapshot_id": snapshot.get("snapshot_id"),
            "snapshot_hash": snapshot.get("hash"),
            "release": meta.get("release"),
            "source": meta.get("source"),
            "bundle_version": version["bundle_version"],
        },
        "source_execution_id": trace.get("execution_id"),
        "items": items,
    }, warnings


def write(data: dict, path: Path = DATA_PATH) -> Path:
    """결과를 사람이 읽기 좋게 들여 쓴 UTF-8 JSON 으로 저장한다."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path
