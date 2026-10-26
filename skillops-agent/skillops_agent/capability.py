"""담당자 수정이 어떤 업무 도구도 만들지 않는 값을 요구하는지 찾고 개발 요청서를 만든다.

docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11(개발 요청서)을 코드로 옮긴 순수 함수 모음이다.
네트워크를 부르지 않고 LLM 도 쓰지 않는다. 도구 목록(catalog)은 주문 Agent 의 GET /api/tools 를
skillops-agent/skillops_agent/order_agent.py 의 get_tool_catalog 가 검사해 넘긴 값이다.
모양은 {initial_input_keys: [...], tools: [{tool, skill, description, produces: [...]}]} 이다.

함수는 세 묶음이다.
- 빈틈 판정: may_need_tool, frontmatter_skills, find_gaps. 키 k 는 집합에 그대로 있거나 집합의 p 에
  대해 p + '.' 로 시작하면 덮인다(skill_rules.covers). 담당자가 기준본 대비 새로 넣은 키만 본다.
  바뀐 skill 의 새 inputs·rules 키가 initial_input_keys ∪ 모든 도구 produces 로 덮이지 않으면
  missing_value, 새 outputs 키를 어떤 도구의 produces 도 덮지 않으면 tool_output_missing 이다.
- 임시 대안: offending_changes, interim_patch, interim_summary. 빈틈 키를 들여온 변경 hunk 만 되돌린
  patch 와 운영자가 읽는 한 줄 설명이다.
- 개발 요청서: options, development_request, with_proof. 제안서의 development_request 칸이고 모양은
  docs/contracts/proposal.schema.json 이다.

skillops-agent/skillops_agent/nodes.py 의 fetch_evidence(빈틈 판정과 요청서 초안)와
proposal_payload(임시 대안 근거)가 부르고, skillops-agent/tests/test_capability.py 가 규칙을
고정한다.
"""

import re

from . import skill_rules
from .contracts import unified_patch

# 개발 요청서의 kind 값이다. service 의 ProposalValidator.NEEDS_TOOL 과 같다.
REQUEST_KIND = "needs_tool"
# 도구를 고치거나 더할 때 바꿀 주문 Agent 파일이다. 도구 표(ToolCatalog), 도구
# 실행(OrderToolService), 외부 연동(adapter/) 순서다.
CHANGE_POINTS = (
    "order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java",
    "order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java",
    "order-agent/backend/src/main/java/com/capstone4/orderagent/adapter/",
)
# new_tool 의 예시 이름 꼬리다. 키의 마지막 낱말 뒤에 붙인다(shipping.weather → weather.lookup).
LOOKUP_SUFFIX = ".lookup"
# new_tool 항목에 남기는 설명이다. 이름은 SkillOps 가 붙인 예시일 뿐이다(2.11).
EXAMPLE_NOTE = "suggested_name 은 SkillOps 가 붙인 예시 이름입니다"
# 빈틈을 보는 frontmatter 칸 순서다. gaps 항목의 field 값이기도 하다.
FIELDS = ("inputs", "rules", "outputs")
# hunk 본문에서 값 이름 모양(점으로 이은 낱말)을 찾는다. 넓게 잡아도 원문을 한 번 더 읽을 뿐이다.
_KEY_TOKEN = re.compile(r"(?<![\w.-])[A-Za-z_][\w-]*(?:\.[\w-]+)+")
# unified hunk 머리줄이다. 바뀐 뒤 쪽의 시작 줄과 줄 수를 읽는다.
_HEADER = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


# ── 빈틈 판정 ──────────────────────────────────────────────────────────────────


def _covered(key: str, names) -> bool:
    """key 가 names 가운데 하나로 덮이는지 본다(그대로 있거나 'p.' 로 시작)."""
    return any(skill_rules.covers(name, key) for name in names)


def produced_keys(catalog: dict) -> list[str]:
    """모든 도구가 내는 키를 도구 순서대로 겹치지 않게 모은다."""
    return list(dict.fromkeys(key for tool in catalog["tools"] for key in tool["produces"]))


def available_keys(catalog: dict) -> list[str]:
    """처음 입력 키와 모든 도구가 내는 키의 합집합이다. missing_value 판정의 기준이다."""
    return list(dict.fromkeys([*catalog["initial_input_keys"], *produced_keys(catalog)]))


def _hunk_body(diff: str) -> list[str]:
    """C hunk 의 본문 줄(문맥·지운 줄·더한 줄)만 남긴다. '---'·'+++'·'@@' 머리줄은 뺀다."""
    rows, started = [], False
    for row in (diff or "").splitlines():
        if _HEADER.match(row):
            started = True
            continue
        if started and not row.startswith("\\"):
            rows.append(row)
    return rows


def _tokens(rows: list[str]) -> list[str]:
    """줄 목록에서 값 이름 모양 낱말을 처음 나온 순서대로 모은다."""
    return list(dict.fromkeys(token for row in rows for token in _KEY_TOKEN.findall(row)))


def _body_change(change: dict) -> bool:
    """본문(body)만 바꾼 hunk 인지 본다. 본문에만 적은 행동 변경은 이번 탐지 대상이 아니다(2.11)."""
    return change.get("part") == "body"


def may_need_tool(catalog: dict, changes: list[dict]) -> bool:
    """frontmatter hunk 에 어떤 도구도 내지 않는 값 이름 모양 낱말이 하나라도 있는지 본다.

    원문을 읽기 전에 거르는 값싼 검사다. 담당자가 새로 넣은 키는 반드시 그 hunk 의 줄(더한 줄 또는
    문맥 줄)에 글자로 나오므로, 모든 낱말이 도구 produces 로 덮이면 빈틈이 있을 수 없다. 거짓이면
    원문을 읽지 않아 빈틈이 없는 진단의 도구 호출이 예전과 같다. 참이어도 빈틈이 있다는 뜻은 아니고
    find_gaps 가 원문으로 다시 판정한다.
    """
    produced = produced_keys(catalog)
    return any(
        not _covered(token, produced)
        for change in changes
        if not _body_change(change)
        for token in _tokens(_hunk_body(change.get("diff")))
    )


def frontmatter_skills(changes: list[dict]) -> list[str]:
    """본문만 바꾼 것이 아닌 hunk 의 skill 을 변경 순서대로 겹치지 않게 돌려준다."""
    skills = (c["skill"] for c in changes if c.get("skill") and not _body_change(c))
    return list(dict.fromkeys(skills))


def _declared(text: str) -> dict[str, list[str]]:
    """SKILL.md 원문 frontmatter 의 inputs, rules(when 의 값 이름), outputs 를 칸별로 읽는다."""
    meta = skill_rules.frontmatter(text)
    rule_keys = [
        skill_rules.condition_key(skill_rules.when_of(rule)) for rule in skill_rules.rules_of(meta)
    ]
    outputs = meta.get("outputs")
    return {
        "inputs": skill_rules.inputs_of(meta),
        "rules": list(dict.fromkeys(key for key in rule_keys if key)),
        "outputs": [v for v in outputs if isinstance(v, str)] if isinstance(outputs, list) else [],
    }


def find_gaps(catalog: dict, sources: dict[str, tuple[str, str]]) -> list[dict]:
    """담당자가 새로 넣은 키 가운데 어떤 도구로도 채울 수 없는 것을 gaps 항목으로 돌려준다.

    sources 는 {skill: (기준본 원문, 수정본 원문)} 이다. 칸마다 수정본에 있고 기준본의 같은 칸에
    없던 키만 본다(기존 키는 담당자가 넣은 것이 아니므로 빼고 본다). inputs·rules 키를 처음 입력
    키와 모든 도구 produces 가 덮지 못하면 missing_value, outputs 키를 어떤 도구의 produces 도 덮지
    못하면 tool_output_missing 이다. order.shipping.country 처럼 처음 입력 키 아래 경로는 덮인다.
    항목은 {key, needed_by, field, kind} 이고 skill 순서, 칸 순서(FIELDS), 선언 순서다.
    """
    available, produced = available_keys(catalog), produced_keys(catalog)
    gaps = []
    for skill, (before, after) in sources.items():
        old, new = _declared(before), _declared(after)
        for field in FIELDS:
            names, kind = (produced, "tool_output_missing") if field == "outputs" else (
                available, "missing_value"
            )
            for key in new[field]:
                gap = {"key": key, "needed_by": skill, "field": field, "kind": kind}
                if key not in old[field] and not _covered(key, names) and gap not in gaps:
                    gaps.append(gap)
    return gaps


# ── 임시 대안 ──────────────────────────────────────────────────────────────────


def offending_changes(changes: list[dict], gaps: list[dict]) -> list[dict]:
    """빈틈 키를 들여온 변경 hunk 다. 그 hunk 를 되돌리는 것이 임시 대안이다.

    빈틈이 난 skill 의 본문 아닌 hunk 가운데 더한 줄에 빈틈 키가 글자로 나오는 것이다. 하나도 없으면
    (키가 문맥 줄로만 옮겨진 경우 등) 그 skill 들의 본문 아닌 hunk 를 모두 쓴다. interim_patch 와
    skillops-agent/skillops_agent/nodes.py 의 proposal_payload(근거 실행 고르기)가 같은 결과를 쓴다.
    """
    needed = {g["needed_by"] for g in gaps}
    candidates = [c for c in changes if c.get("skill") in needed and not _body_change(c)]
    found = []
    for change in candidates:
        keys = {g["key"] for g in gaps if g["needed_by"] == change["skill"]}
        added = [row for row in _hunk_body(change.get("diff")) if row.startswith("+")]
        if keys & set(_tokens(added)):
            found.append(change)
    return found or candidates


def _revert(text: str, diffs: list[str]) -> str | None:
    """수정본 원문에서 C hunk 들만 되돌린 원문이다. hunk 가 원문과 맞지 않으면 None 이다.

    hunk 의 바뀐 뒤 쪽 시작 줄부터 문맥·더한 줄이 원문과 같아야 하고, 그 자리를 문맥·지운 줄로
    바꾼다. 줄 번호가 밀리지 않게 아래 hunk 부터 바꾼다.
    """
    lines = text.splitlines()
    edits = []
    for diff in diffs:
        header = next((m for m in map(_HEADER.match, (diff or "").splitlines()) if m), None)
        if header is None:
            return None
        start = int(header[1]) - (1 if header[2] != "0" else 0)
        body = [row or " " for row in _hunk_body(diff)]
        after = [row[1:] for row in body if row[0] in " +"]
        before = [row[1:] for row in body if row[0] in " -"]
        edits.append((start, after, before))
    for start, after, before in sorted(edits, key=lambda e: e[0], reverse=True):
        if start < 0 or lines[start:start + len(after)] != after:
            return None
        lines[start:start + len(after)] = before
    return "\n".join(lines) + ("\n" if text.endswith("\n") else "")


def interim_patch(
    changes: list[dict], gaps: list[dict], sources: dict[str, tuple[str, str]]
) -> str:
    """빈틈 키를 들여온 hunk(offending_changes)만 되돌리는 unified diff(a/·b/ 헤더)다.

    skill 마다 수정본 원문 기준으로 만든다. hunk 를 원문에 맞춰 되돌리지 못하면 그 skill 을 기준본
    원문으로 되돌린다. 만들 것이 없으면 빈 글이다. 여러 skill 이면 파일별 diff 를 잇는다.
    """
    offending = offending_changes(changes, gaps)
    patches = []
    for skill in dict.fromkeys(c["skill"] for c in offending):
        if skill not in sources:
            continue
        before, after = sources[skill]
        reverted = _revert(after, [c.get("diff") for c in offending if c["skill"] == skill])
        patches.append(unified_patch(skill, after, before if reverted is None else reverted))
    return "".join(patches)


_PATCH_FILE = re.compile(r"^\+\+\+ b/skills/([^/]+)/SKILL\.md$")
_PATCH_FIELD = re.compile(r"^([+-])(inputs|outputs):\s*\[(.*)\]\s*$")


def restored_keys(patch: str) -> dict[tuple[str, str], list[str]]:
    """임시 대안 patch 가 skill·칸마다 되살리는 키다(+ 줄에만 있고 - 줄에는 없는 키).

    출력 이름을 바꾼 수정의 임시 대안은 새 이름을 빼는 것이 아니라 원래 이름을 되살리는 것이므로,
    interim_summary 가 이 값으로 '빼면' 과 '원래 이름으로 되돌리면' 을 가른다. 한 줄 목록([a, b])만
    읽고, 여러 줄 목록은 읽지 않는다(그때는 되살리는 키가 없다고 본다).
    """
    rows: dict[tuple[str, str], dict[str, list[str]]] = {}
    skill = None
    for line in (patch or "").splitlines():
        found = _PATCH_FILE.match(line)
        if found:
            skill = found.group(1)
            continue
        field = _PATCH_FIELD.match(line)
        if skill and field:
            keys = [k.strip() for k in field.group(3).split(",") if k.strip()]
            sides = rows.setdefault((skill, field.group(2)), {"+": [], "-": []})
            sides[field.group(1)].extend(keys)
    return {
        place: added
        for place, sides in rows.items()
        if (added := [k for k in sides["+"] if k not in sides["-"]])
    }


def interim_summary(gaps: list[dict], proven: bool, patch: str = "") -> str:
    """임시 대안 한 줄 설명이다. proven 은 원인 실험이 그 되돌리기로 통과를 보였는지다.

    patch 가 같은 칸에서 기준본 키를 되살리면(출력 이름 변경 등) '원래 값으로 되돌리면', 아니면
    '빼면' 이라고 쓴다. 예: 'notify 의 inputs 에서 shipping.weather 를 빼면 지금 주문은 다시
    처리됩니다(의도 보류)', 'inventory-check 의 outputs 에서 inventory.is_reserved 를 원래 값
    inventory.reserved 로 되돌리면 …'.
    """
    groups: dict[tuple[str, str], list[str]] = {}
    for gap in gaps:
        keys = groups.setdefault((gap["needed_by"], gap["field"]), [])
        if gap["key"] not in keys:
            keys.append(gap["key"])
    restored = restored_keys(patch)
    # 근거가 있으면 '…를 빼면', 없으면 '…를 빼는 것이' 꼴로 잇는다.
    verb = ("빼면", "되돌리면") if proven else ("빼는", "되돌리는")
    parts = []
    for (skill, field), keys in groups.items():
        back = restored.get((skill, field))
        how = f"원래 값 {', '.join(back)} 로 {verb[1]}" if back else verb[0]
        parts.append(f"{skill} 의 {field} 에서 {', '.join(keys)} 를 {how}")
    target = ", ".join(parts)
    if proven:
        return f"{target} 지금 주문은 다시 처리됩니다(의도 보류)"
    return (
        f"{target} 것이 임시 대안이지만 다시 처리되는지는 원인 실험으로 확인하지 "
        "못했습니다(의도 보류)"
    )


# ── 개발 요청서 ────────────────────────────────────────────────────────────────


def _flow_order(catalog: dict, steps: list[str]) -> list[str]:
    """단계 실행 순서다. 의존성 그래프 steps 가 없으면 도구 목록의 skill 순서를 쓴다."""
    return list(steps) or list(dict.fromkeys(tool["skill"] for tool in catalog["tools"]))


def available_before(catalog: dict, skill: str, order: list[str]) -> list[str]:
    """skill 단계 앞에서 쓸 수 있는 키다. 처음 입력 키 뒤에 앞 단계 도구 produces 를 잇는다.

    skill 이 순서에 없으면 그 skill 에 묶인 도구를 뺀 모든 도구의 produces 를 쓴다.
    """
    position = {name: no for no, name in enumerate(order)}
    keys = list(catalog["initial_input_keys"])
    tools = sorted(catalog["tools"], key=lambda t: position.get(t["skill"], len(order)))
    for tool in tools:
        if skill in position:
            earlier = position.get(tool["skill"], len(order)) < position[skill]
        else:
            earlier = tool["skill"] != skill
        if earlier:
            keys.extend(tool["produces"])
    return list(dict.fromkeys(keys))


def _in_time(tool: dict, gap: dict, position: dict[str, int]) -> bool:
    """도구가 빈틈 값을 늦지 않게 낼 수 있는 자리인지 본다. 순서를 모르면 참이다.

    inputs·rules 의 값은 그 단계보다 앞 단계 도구가, outputs 의 값은 그 단계나 앞 단계 도구가 내야
    한다.
    """
    here, there = position.get(gap["needed_by"]), position.get(tool["skill"])
    if here is None or there is None:
        return True
    return there <= here if gap["field"] == "outputs" else there < here


def options(catalog: dict, gaps: list[dict], steps: list[str]) -> list[dict]:
    """개발 요청서의 방법 목록이다. 기존 도구 확장(extend_tool)들 뒤에 새 도구(new_tool)들을 둔다.

    extend_tool 은 빈틈 키와 같은 앞부분(첫 낱말 + '.', 예: shipping.)을 이미 내는 도구다. outputs
    빈틈이면 그 단계 자신의 도구도 넣는다. 그 값을 쓰는 단계보다 늦은 도구는 뺀다(_in_time). 같은
    도구는 add_produces 를 모아 한 항목이다. new_tool 은 (키, 단계)마다 하나이고 이름은 키의 마지막
    낱말 + LOOKUP_SUFFIX 인 예시 이름이다. place_before 는 그 값을 쓰는 단계, available_inputs 는 그
    단계 앞에서 쓸 수 있는 키(available_before), produces 는 그 키다.
    """
    order = _flow_order(catalog, steps)
    position = {name: no for no, name in enumerate(order)}
    extend: dict[str, dict] = {}
    new_tools: list[dict] = []
    for gap in gaps:
        prefix = gap["key"].split(".", 1)[0] + "."
        for tool in catalog["tools"]:
            shares = any(key.startswith(prefix) for key in tool["produces"])
            own = gap["field"] == "outputs" and tool["skill"] == gap["needed_by"]
            if not (shares or own) or not _in_time(tool, gap, position):
                continue
            option = extend.setdefault(
                tool["tool"],
                {"type": "extend_tool", "tool": tool["tool"], "skill": tool["skill"],
                 "add_produces": []},
            )
            if gap["key"] not in option["add_produces"]:
                option["add_produces"].append(gap["key"])
        if any(o["produces"] == [gap["key"]] and o["place_before"] == gap["needed_by"]
               for o in new_tools):
            continue
        new_tools.append(
            {
                "type": "new_tool",
                "suggested_name": gap["key"].rsplit(".", 1)[-1] + LOOKUP_SUFFIX,
                "place_before": gap["needed_by"],
                "available_inputs": available_before(catalog, gap["needed_by"], order),
                "produces": [gap["key"]],
                "note": EXAMPLE_NOTE,
            }
        )
    return [*extend.values(), *new_tools]


def request_title(gaps: list[dict]) -> str:
    """개발 요청서 제목이다.

    예: "주문 Agent 업무 도구에 'shipping.weather' 값을 만드는 도구 개발 필요".
    """
    keys = ", ".join(f"'{key}'" for key in dict.fromkeys(g["key"] for g in gaps))
    return f"주문 Agent 업무 도구에 {keys} 값을 만드는 도구 개발 필요"


def development_request(
    catalog: dict,
    gaps: list[dict],
    steps: list[str],
    patch: str,
    evidence_run_ids: list[str],
) -> dict:
    """제안서의 development_request 초안이다. 임시 대안의 근거 실행은 아직 비어 있다.

    칸 순서는 docs/contracts/proposal.schema.json 과 같다. patch 가 비면 임시 대안(interim)은 None
    이다. 근거 실행과 그에 맞는 설명은 원인 실험 뒤 with_proof 가 채운다. evidence_run_ids 는 수정본
    사전 검증 실행이다.
    """
    interim = None
    if patch:
        interim = {"summary": interim_summary(gaps, proven=False, patch=patch), "patch": patch,
                   "proof_run_ids": []}
    return {
        "kind": REQUEST_KIND,
        "title": request_title(gaps),
        "gaps": [dict(gap) for gap in gaps],
        "options": options(catalog, gaps, steps),
        "change_points": list(CHANGE_POINTS),
        "interim": interim,
        "evidence_run_ids": list(evidence_run_ids),
    }


def with_proof(request: dict, proof_run_ids: list[str]) -> dict:
    """초안에 임시 대안의 근거 실행을 넣은 새 요청서다. 근거가 있으면 설명을 확인된 문장으로 바꾼다.

    proposal_payload 가 원인 실험 뒤에 부른다.
    """
    interim = request.get("interim")
    if not interim:
        return dict(request)
    proof = list(proof_run_ids)
    return {
        **request,
        "interim": {
            **interim,
            "summary": interim_summary(
                request["gaps"], proven=bool(proof), patch=interim.get("patch", "")
            ),
            "proof_run_ids": proof,
        },
    }
