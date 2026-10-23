"""정답이 정해지는 Skill 품질 검사를 코드로 한다. LLM 을 부르지 않는다.

세 가지를 검사한다.
- check_eval_set: 평가셋(skill 목록, 질의) 형식. 틀리면 LLM 을 부르기 전에 멈춘다.
- check_skill_md: SKILL.md 원문 형식(frontmatter, name, description, steps·depends_on 참조, TODO).
  원문이 주어진 skill 만 검사한다. inputs/outputs 계약 검사는 service 의 DependencyAnalyzer 가
  맡으므로 여기서 다시 구현하지 않는다.
- check_proposal: LLM 이 만든 description 수정안 형식(빈 값, 길이, 동일, 평가 질의 복사).

결과는 schemas.Finding 목록이다. 흐름을 막을지는 schemas.blockers 로 정한다.
"""

import re
from difflib import SequenceMatcher

import yaml

from .schemas import Finding, finding

# 질의 묶음이다. dev 는 진단·개선에 쓰고(LLM 이 봄), holdout 은 채점에만 쓴다(LLM 이 못 봄).
SPLITS = ("dev", "holdout")
# 어떤 skill 도 고르지 않은 선택 이름이다. trigger_state.NONE_SKILL 과 같다.
NONE_SKILL = "none"
# 선택 Agent 모드다. chat 은 자연어 요청, order-agent 는 실제 주문 Agent 처럼 주문 데이터를 받는다.
SELECTORS = ("chat", "order-agent")
# 주문 Agent 모드 질의의 order 에 있어야 하는 키다(scenarios/order-agent/golden 의 order).
ORDER_KEYS = ("customer", "items", "payment_method", "shipping")
# 토스 Rubric 과 Agent Skills 규칙의 형식 한도다.
NAME_MAX = 64
DESCRIPTION_MAX = 1024
BODY_MAX_LINES = 500
# 이 비율 이상으로 평가 질의와 겹치면 질의를 외운 description 으로 본다.
QUERY_COPY_RATIO = 0.8
# 복사 검사를 할 최소 질의 길이(공백·기호 뺀 글자 수)다. 너무 짧은 질의는 우연히 겹친다.
QUERY_COPY_MIN_LEN = 6

# docs/contracts/skill-frontmatter.md 의 frontmatter 키다.
ALLOWED_KEYS = {
    "name", "description", "inputs", "outputs", "depends_on", "owner", "version", "steps",
}
_NAME_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
_XML_RE = re.compile(r"<[A-Za-z/][^>]*>")
_TODO_RE = re.compile(r"\b(TODO|FIXME|TBD)\b|<placeholder>", re.IGNORECASE)
# 대표적인 비밀 값 모양이다. 찾은 값은 결과에 남기지 않는다.
_SECRET_RE = re.compile(
    r"sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|-----BEGIN [A-Z ]*PRIVATE KEY-----"
)


# ── 평가셋 ─────────────────────────────────────────────────────────────────────


def check_case(
    case, names: set[str], require_order: bool = False
) -> tuple[dict | None, list[Finding]]:
    """평가 질의 하나를 검사한다. 맞으면 정리한 질의를, 틀리면 None 과 FAIL 목록을 돌려준다.

    require_order 면(주문 Agent 선택 모드) 질의마다 주문 데이터 order 가 있어야 한다. 이때 query 는
    결과에서 질의를 가리키는 이름표로만 쓴다.
    """
    problems = []
    order = case.get("order") if isinstance(case, dict) else None
    if require_order and not _valid_order(order):
        problems.append(
            finding("rule.case_order", "FAIL", "BLOCKER",
                    "주문 Agent 모드 질의에 주문 데이터(order)가 없거나 형식이 틀립니다.",
                    f"order 에 {', '.join(ORDER_KEYS)} 를 넣으세요.")
        )
    query = case.get("query") if isinstance(case, dict) else None
    expected = case.get("expected_skill") if isinstance(case, dict) else None
    should = case.get("should_trigger") if isinstance(case, dict) else None
    if not isinstance(query, str) or not query.strip():
        problems.append(
            finding("rule.case_query", "FAIL", "BLOCKER", "질의(query)가 비어 있습니다.",
                    "query 에 사용자 요청 문장을 넣으세요.")
        )
    if expected != NONE_SKILL and expected not in names:
        problems.append(
            finding("rule.case_expected_skill", "FAIL", "BLOCKER",
                    f"expected_skill '{expected}' 이 skill 목록에 없습니다.",
                    "skills 에 있는 이름이나 none 을 쓰세요.")
        )
    if not isinstance(should, bool):
        problems.append(
            finding("rule.case_should_trigger", "FAIL", "BLOCKER",
                    "should_trigger 는 true 또는 false 여야 합니다.", "true/false 로 고치세요.")
        )
    elif expected == NONE_SKILL and not should:
        problems.append(
            finding("rule.case_none_needs_true", "FAIL", "BLOCKER",
                    "expected_skill none 은 should_trigger true 와만 쓸 수 있습니다.",
                    "아무 skill 도 불리면 안 되는 질의는 none + true 로 쓰세요.")
        )
    split = case.get("split") if isinstance(case, dict) else None
    if split is not None and split not in SPLITS:
        problems.append(
            finding("rule.case_split", "FAIL", "BLOCKER",
                    f"split '{split}' 는 지원하지 않습니다.",
                    "dev(진단·개선용) 또는 holdout(채점용)을 쓰거나 비워 두세요.")
        )
    if problems:
        return None, problems
    clean = {"query": query.strip(), "expected_skill": expected, "should_trigger": should}
    if split is not None:
        clean["split"] = split
    if order is not None:
        clean["order"] = order
    return clean, []


def _valid_order(order) -> bool:
    """주문 데이터가 scenarios/order-agent/golden 의 order 모양인지 본다."""
    return (
        isinstance(order, dict)
        and all(k in order for k in ORDER_KEYS)
        and isinstance(order["items"], list)
        and isinstance(order["shipping"], dict)
    )


def check_eval_set(
    target, skills, cases, seed_queries=None, repeat=None, selector="chat"
) -> tuple[list[Finding], dict]:
    """평가셋 전체를 검사한다. (FAIL 목록, 정리한 값 {skills, cases}) 를 돌려준다.

    FAIL 이 하나라도 있으면 정리한 값은 쓰지 않는다. 질의가 있는데 target 이 불려야 하는 질의가
    하나도 없으면 개선을 검증할 수 없으므로 막는다(trigger_metrics.judge 와 같은 기준).
    """
    problems: list[Finding] = []
    if not skills:
        problems.append(finding("rule.skills_present", "FAIL", "BLOCKER",
                                "skill 목록이 비어 있습니다.", "skills 에 skill 을 넣으세요."))
        return problems, {}

    names = [s.get("name") if isinstance(s, dict) else None for s in skills]
    if any(not n or not isinstance(n, str) for n in names):
        problems.append(finding("rule.skill_name_present", "FAIL", "BLOCKER",
                                "이름이 없는 skill 이 있습니다.",
                                "모든 skill 에 name 을 넣으세요."))
    dupes = sorted({n for n in names if n and names.count(n) > 1})
    if dupes:
        problems.append(finding("rule.skill_name_unique", "FAIL", "BLOCKER",
                                f"skill 이름이 중복됩니다: {', '.join(dupes)}",
                                "skill 이름을 하나씩만 쓰세요."))
    if NONE_SKILL in names:
        problems.append(finding("rule.skill_name_reserved", "FAIL", "BLOCKER",
                                f"'{NONE_SKILL}' 은 미선택용이라 skill 이름으로 쓸 수 없습니다.",
                                "다른 이름을 쓰세요."))
    if target not in names:
        problems.append(finding("rule.target_in_skills", "FAIL", "BLOCKER",
                                f"target_skill '{target}' 이 skill 목록에 없습니다.",
                                "target_skill 을 skills 의 이름 중 하나로 쓰세요."))
    if repeat is not None and (not isinstance(repeat, int) or repeat < 1):
        problems.append(finding("rule.repeat_positive", "FAIL", "BLOCKER",
                                "repeat 는 1 이상이어야 합니다.", "repeat 를 1 이상으로 쓰세요."))

    if selector not in SELECTORS:
        problems.append(finding("rule.selector", "FAIL", "BLOCKER",
                                f"selector '{selector}' 는 지원하지 않습니다.",
                                f"{' 또는 '.join(SELECTORS)} 를 쓰세요."))
    if selector == "order-agent" and not cases:
        # 주문 Agent 모드는 자연어 요청 문장으로 질의를 만들 수 없다.
        problems.append(finding("rule.order_agent_needs_cases", "FAIL", "BLOCKER",
                                "주문 Agent 모드에는 주문 데이터가 든 질의(cases)가 필요합니다.",
                                "golden_dir 이나 cases 에 주문을 넣으세요."))

    name_set = {n for n in names if isinstance(n, str)}
    clean_cases = []
    for case in cases or []:
        clean, case_problems = check_case(case, name_set, require_order=selector == "order-agent")
        problems.extend(case_problems)
        if clean:
            clean_cases.append(clean)

    if not cases and not seed_queries:
        problems.append(finding("rule.cases_or_seeds", "FAIL", "BLOCKER",
                                "평가 질의(cases)와 요청 문장(seed_queries)이 모두 없습니다.",
                                "cases 나 seed_queries 중 하나를 넣으세요."))
    elif cases and not has_positive(clean_cases, target):
        problems.append(finding("rule.target_has_positive", "FAIL", "BLOCKER",
                                f"{target} 이 불려야 하는 질의가 없어 개선을 검증할 수 없습니다.",
                                "expected_skill=대상, should_trigger=true 인 질의를 넣으세요."))
    elif any(c.get("split") == "holdout" for c in clean_cases):
        # holdout 을 쓰면 진단할 dev 와 채점할 holdout 양쪽에 불려야 하는 질의가 있어야 한다.
        for part in ("dev", "holdout"):
            subset = [c for c in clean_cases if (c.get("split") or "dev") == part]
            if not has_positive(subset, target):
                problems.append(finding(
                    f"rule.{part}_has_positive", "FAIL", "BLOCKER",
                    f"{part} 묶음에 {target} 이 불려야 하는 질의가 없습니다.",
                    f"{part} 묶음에 expected_skill=대상, should_trigger=true 인 질의를 넣으세요.",
                ))

    clean_skills = [
        {**s, "name": s["name"], "description": s.get("description") or ""}
        for s in skills
        if isinstance(s, dict) and isinstance(s.get("name"), str)
    ]
    return problems, {"skills": clean_skills, "cases": clean_cases}


def has_positive(cases: list[dict], target: str) -> bool:
    """target 이 불려야 하는 질의가 하나라도 있는지 본다."""
    return any(c["should_trigger"] and c["expected_skill"] == target for c in cases)


# ── SKILL.md ───────────────────────────────────────────────────────────────────


def parse_skill_md(text: str) -> tuple[dict | None, str, str | None]:
    """SKILL.md 를 (frontmatter dict, 본문, 오류 문구) 로 나눈다. 오류가 없으면 오류 문구는 None.

    여러 줄 YAML 목록(steps:\\n  - a)도 읽도록 PyYAML 을 쓴다.
    """
    if not isinstance(text, str) or not text.startswith("---"):
        return None, text or "", "frontmatter 가 '---' 로 시작하지 않습니다."
    end = text.find("\n---", 3)
    if end < 0:
        return None, text, "frontmatter 를 닫는 '---' 가 없습니다."
    try:
        meta = yaml.safe_load(text[3:end])
    except yaml.YAMLError as exc:
        return None, text, f"frontmatter YAML 을 읽을 수 없습니다: {type(exc).__name__}"
    if not isinstance(meta, dict):
        return None, text, "frontmatter 가 키-값 형식이 아닙니다."
    body = text[end + 4:].lstrip("\n")
    return meta, body, None


def check_skill_md(
    text: str, folder: str | None = None, known_skills: set[str] | None = None
) -> tuple[dict | None, list[Finding]]:
    """SKILL.md 원문 하나를 검사한다. (frontmatter, PASS/FAIL 목록) 을 돌려준다.

    folder 를 주면 name 과 폴더 이름이 같은지, known_skills 를 주면 steps·depends_on 이 실제
    skill 을 가리키는지도 본다.
    """
    meta, body, error = parse_skill_md(text)
    name = meta.get("name") if meta else None
    target = name if isinstance(name, str) else folder

    def add(id, ok, severity, reason, fix):
        results.append(
            finding(id, "PASS" if ok else "FAIL", severity, "" if ok else reason,
                    "" if ok else fix, target=target)
        )

    results: list[Finding] = []
    add("rule.frontmatter_parse", error is None, "BLOCKER", error or "",
        "frontmatter 를 '---' 사이의 YAML 로 쓰세요.")
    if meta is None:
        return None, results

    add("rule.name_format",
        isinstance(name, str) and bool(_NAME_RE.match(name)) and len(name) <= NAME_MAX,
        "BLOCKER", f"name '{name}' 이 kebab-case {NAME_MAX}자 이하가 아닙니다.",
        "소문자, 숫자, '-' 로 쓰세요.")
    if folder is not None:
        add("rule.name_matches_folder", name == folder, "BLOCKER",
            f"name '{name}' 과 폴더 이름 '{folder}' 이 다릅니다.", "둘을 같게 맞추세요.")

    description = meta.get("description")
    desc_ok = isinstance(description, str) and 1 <= len(description.strip()) <= DESCRIPTION_MAX
    add("rule.description_length", desc_ok, "BLOCKER",
        f"description 이 비었거나 {DESCRIPTION_MAX}자를 넘습니다.",
        f"1~{DESCRIPTION_MAX}자로 쓰세요.")
    add("rule.description_no_xml",
        not (isinstance(description, str) and _XML_RE.search(description)), "BLOCKER",
        "description 에 XML/HTML 태그가 있습니다.", "태그를 빼고 평문으로 쓰세요.")

    unknown = sorted(set(meta) - ALLOWED_KEYS)
    add("rule.allowed_keys", not unknown, "MAJOR",
        f"정해지지 않은 frontmatter 키가 있습니다: {', '.join(unknown)}",
        "docs/contracts/skill-frontmatter.md 의 키만 쓰세요.")

    if "steps" in meta:
        steps = meta.get("steps")
        add("rule.steps_not_empty", isinstance(steps, list) and len(steps) > 0, "BLOCKER",
            "steps 가 비어 있어 실행할 단계가 없습니다.", "steps 에 실행 순서를 넣으세요.")
    if known_skills is not None:
        refs = [r for key in ("steps", "depends_on") for r in (meta.get(key) or [])]
        missing = sorted({str(r) for r in refs if r not in known_skills})
        add("rule.references_exist", not missing, "BLOCKER",
            f"없는 skill 을 가리킵니다: {', '.join(missing)}", "실제 skill 이름으로 고치세요.")

    add("rule.no_secret", not _SECRET_RE.search(text), "BLOCKER",
        "비밀 값(API 키, 개인 키)으로 보이는 문자열이 있습니다.",
        "비밀 값을 지우고 환경 변수로 옮기세요.")
    add("rule.no_todo", not _TODO_RE.search(body), "MINOR",
        "본문에 TODO 같은 미완성 표시가 남아 있습니다.", "본문을 채우고 표시를 지우세요.")
    add("rule.body_length", body.count("\n") + 1 <= BODY_MAX_LINES, "MINOR",
        f"본문이 {BODY_MAX_LINES}줄을 넘습니다.", "자세한 내용은 별도 문서로 나누세요.")
    return meta, results


def entry_structure(metas: dict[str, dict]) -> dict:
    """frontmatter 의 steps 로 진입 skill 과 하위 단계를 나눈다.

    다른 skill 의 steps 에 들어 있는 skill 은 하위 단계라 사용자 요청에서 직접 불리면 안 되고, 그
    일은 steps 를 가진 진입 skill(예: order-flow)이 받는다. metas 는 {skill 이름: frontmatter}.
    steps 를 가진 skill 이 없으면 모든 skill 이 진입 skill 이다.
    """
    sub_steps = {}
    for name, meta in metas.items():
        for step in (meta or {}).get("steps") or []:
            if isinstance(step, str) and step != name:
                sub_steps.setdefault(step, name)
    entries = [name for name in metas if name not in sub_steps]
    return {"entries": entries, "sub_steps": sub_steps}


# ── 수정안 ─────────────────────────────────────────────────────────────────────


def _normalize(text: str) -> str:
    """공백과 기호를 빼고 소문자로 바꾼다. 문장 겹침 비교에 쓴다."""
    return re.sub(r"[\W_]+", "", text.lower())


def copied_queries(description: str, queries: list[str]) -> list[str]:
    """description 에 거의 그대로 들어간 평가 질의를 찾는다(가장 긴 공통 부분 / 질의 길이)."""
    desc = _normalize(description)
    found = []
    for query in queries:
        q = _normalize(query)
        if len(q) < QUERY_COPY_MIN_LEN:
            continue
        match = SequenceMatcher(None, q, desc, autojunk=False).find_longest_match(
            0, len(q), 0, len(desc)
        )
        if match.size / len(q) >= QUERY_COPY_RATIO:
            found.append(query)
    return found


def check_proposal(
    description, current: str, queries: list[str], target: str | None = None
) -> list[Finding]:
    """description 수정안을 검사한다. BLOCKER FAIL 이 있으면 선택 테스트 전에 버린다."""
    text = description.strip() if isinstance(description, str) else ""
    results: list[Finding] = []

    def add(id, ok, severity, reason, fix):
        results.append(
            finding(id, "PASS" if ok else "FAIL", severity, "" if ok else reason,
                    "" if ok else fix, target=target)
        )

    add("rule.proposal_not_empty", bool(text), "BLOCKER",
        "description 이 비어 있습니다.", "description 을 채우세요.")
    add("rule.description_length", len(text) <= DESCRIPTION_MAX, "BLOCKER",
        f"description 이 {DESCRIPTION_MAX}자를 넘습니다({len(text)}자).",
        f"{DESCRIPTION_MAX}자 이하로 줄이세요.")
    add("rule.proposal_changed", text != (current or "").strip(), "BLOCKER",
        "원래 description 과 같습니다.", "진단한 원인을 반영해 바꾸세요.")
    add("rule.description_no_xml", not _XML_RE.search(text), "BLOCKER",
        "description 에 XML/HTML 태그가 있습니다.", "태그를 빼고 평문으로 쓰세요.")
    copied = copied_queries(text, queries)
    add("rule.proposal_no_query_copy", not copied, "BLOCKER",
        "평가 질의를 그대로 옮겼습니다: " + ", ".join(f'"{q}"' for q in copied),
        "질의 문장 대신 공통된 상황과 목적을 일반화해서 쓰세요.")
    add("rule.proposal_single_line", "\n" not in text, "MINOR",
        "description 에 줄바꿈이 있습니다.", "한 줄 평문으로 쓰세요.")
    return results
