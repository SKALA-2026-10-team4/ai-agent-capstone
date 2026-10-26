"""단계 skill frontmatter 의 rules·inputs 를 읽고 고쳐 결정적 encode_rule 후보와 판정을 만든다.

docs/plans/2026-10-26-behavior-edit-root-fix.md 2.2(실행 규칙 rules)와 2.6(후보 종류와 코드 판정)을
코드로 옮긴 순수 함수 모음이다. 네트워크를 부르지 않고 LLM 도 쓰지 않는다.

함수는 세 묶음이다.
- 읽기: frontmatter_text, frontmatter, rules_of, inputs_of, covers. SKILL.md 원문의 frontmatter 를
  PyYAML 로 읽는다.
- 결정적 후보: encode_rule_revisions 가 의도 expectations(담당자가 준 것 또는 Agent 가 의도 글로
  만든 것)로 rules 한 덩어리를 만들어 대상 단계 skill 원문에 넣는다. 대상은 최대 두 곳이다
  (docs/plans/2026-10-26-behavior-edit-root-fix.md 2.10 V3-6). ① 바뀐 skill(단계 skill 일 때) ② 흐름
  순서상 scope 값을 input 으로 받을 수 있는 가장 이른 단계. 둘이 같으면 하나다. 같은 when 의 규칙이
  이미 있으면 규칙은 그대로 두고 빠진 input 만 더한다. 원문은 고칠 칸(inputs, rules)만 줄 단위로
  바꿔 나머지 frontmatter·주석과 본문을 그대로 둔다.
- 후보 판정: is_revert 와 weakens_contract. 두 값은 요청에 담당자 의도가 있을 때만 불합격 사유다
  (적용 범위는 skillops-agent/skillops_agent/nodes.py 의 _intent_kept). weakens_contract 의 정의는
  service 의 ProposalJudge 와 같다(V3-7).

skillops-agent/skillops_agent/nodes.py 의 generate_candidates 가 부르고,
skillops-agent/tests/test_skill_rules.py 가 규칙을 고정한다.
"""

import json
import re

import yaml

from .contracts import condition_parts, literal_values

# rules 의 then 값이다. 기대 최종 상태(order.final_status)에 HOLD 가 있으면 hold, REJECTED 만 있으면
# reject 다(2.6). 둘 다 있으면 hold 를 고른다.
STOP_ACTIONS = {"HOLD": "hold", "REJECTED": "reject"}
# 의도 text 에서 사유 코드를 만들지 못했을 때 쓰는 기본 reason 이다.
DEFAULT_REASON = "OWNER_POLICY"
# 주문 Agent 와 service 가 받는 reason 형식이다(2.2).
_REASON = re.compile(r"^[A-Z][A-Z0-9_]{0,39}$")
# YAML 1.1 이 글자가 아닌 값(불리언, null)으로 읽는 낱말이다. 소문자로 비교한다. SnakeYAML 과 PyYAML
# 은 따옴표 없는 NO, ON 같은 reason 을 불리언으로 읽어 형식 오류가 되므로(공용 사례
# docs/contracts/examples/skill-rules-cases.json 의 invalid-reason-yaml-bool-*), 결정적 후보의
# reason 과 따옴표 없이 쓰는 값에 쓰지 않는다.
_YAML_WORDS = frozenset({"y", "n", "yes", "no", "on", "off", "true", "false", "null", "~"})
# 따옴표 없이 써도 YAML 이 같은 글자로 읽는 목록 항목 모양이다. 아니면 큰따옴표 글자로
# 쓴다(_scalar).
_PLAIN = re.compile(r"^[A-Za-z_][A-Za-z0-9_.\-]*$")
# 주문 입력으로 처음부터 문맥에 있는 값 이름이다. service 의 DependencyAnalyzer 와 주문 Agent 의
# OrderFlowPolicy.INITIAL_INPUT_KEYS 와 같다(docs/contracts/mcp-tools-input-output.md 의
# get_dependency_graph 절). order.shipping.country 처럼 하위 경로를 보는 조건은 order.shipping 을
# input 으로 선언하면 읽을 수 있다.
INITIAL_INPUT_KEYS = (
    "order.id",
    "order.items",
    "order.customer",
    "order.payment_method",
    "order.shipping",
)
# frontmatter 의 최상위 칸 줄이다. 들여 쓴 줄과 '- ' 로 시작하는 줄은 앞 칸에 딸린 줄이다.
_TOP_KEY = re.compile(r"^([A-Za-z_][\w.-]*)\s*:")
_ITEM = re.compile(r"^(\s*)- ")


# ── 읽기 ───────────────────────────────────────────────────────────────────────


def _split(text) -> tuple[list[str], str] | None:
    """'---' 로 시작하는 SKILL.md 를 (frontmatter 줄 목록, 닫는 '---' 부터의 나머지) 로 나눈다.

    모양이 다르면 None 이다. _join 이 거꾸로 붙인다.
    """
    if not isinstance(text, str) or not text.startswith("---\n"):
        return None
    end = text.find("\n---", 3)
    if end < 0:
        return None
    return text[4:end].split("\n"), text[end + 1:]


def _join(lines: list[str], rest: str) -> str:
    """_split 으로 나눈 두 부분을 다시 SKILL.md 원문으로 붙인다."""
    return "---\n" + "\n".join(lines) + "\n" + rest


def frontmatter_text(text) -> str | None:
    """SKILL.md 원문에서 두 '---' 사이의 frontmatter 글을 그대로 꺼낸다. 모양이 다르면 None 이다.

    YAML 로 읽지 않으므로 rules 처럼 읽기 어려운 칸도 원문 그대로 남는다.
    skillops-agent/skillops_agent/nodes.py 의 _infer_expectations 가 프롬프트에 넣는다.
    """
    parts = _split(text)
    return "\n".join(parts[0]) if parts else None


def frontmatter(text) -> dict:
    """SKILL.md 원문의 frontmatter 를 dict 로 읽는다. 읽을 수 없으면 빈 dict 다."""
    parts = _split(text)
    if parts is None:
        return {}
    try:
        meta = yaml.safe_load("\n".join(parts[0]))
    except yaml.YAMLError:
        return {}
    return meta if isinstance(meta, dict) else {}


def rules_of(meta: dict) -> list[dict]:
    """frontmatter 의 rules 가운데 Map 항목만 돌려준다. 칸이 없거나 목록이 아니면 빈 목록이다."""
    rules = meta.get("rules")
    return [rule for rule in rules if isinstance(rule, dict)] if isinstance(rules, list) else []


def _strings(values) -> list[str]:
    """목록이면 글자 항목만, 목록이 아니면 빈 목록이다."""
    return [value for value in values if isinstance(value, str)] if isinstance(values, list) else []


def inputs_of(meta: dict) -> list[str]:
    """frontmatter 의 inputs 가운데 글자 항목만 돌려준다."""
    return _strings(meta.get("inputs"))


def covers(input_name: str, key: str) -> bool:
    """선언 input 이 값 이름 key 를 읽게 해 주는지 본다.

    같거나 key 가 'input.' 으로 시작하면 참이다. 2.2 의 조건 키 규칙과 같다.
    """
    return key == input_name or key.startswith(input_name + ".")


def when_of(rule: dict) -> str | None:
    """규칙 항목의 when 이 글자면 앞뒤 공백을 뗀 글, 아니면 None 이다.

    service 의 ProposalJudge.whensOf 처럼 앞뒤 공백을 따지지 않고 비교하려고 쓴다. 형식이 틀린 규칙
    (when 이 없거나 목록 등)도 예외 없이 읽는다.
    """
    value = rule.get("when")
    return value.strip() if isinstance(value, str) else None


def condition_key(text) -> str | None:
    """읽을 수 있는 조건이면 왼쪽 값 이름, 아니면 None 이다."""
    parts = condition_parts(text)
    return parts[0] if parts else None


def input_for(key: str) -> str:
    """조건 키 key 를 읽으려면 선언할 input 이다.

    주문 입력 키가 덮으면 그 키, 아니면 key 그대로다.
    """
    return next((initial for initial in INITIAL_INPUT_KEYS if covers(initial, key)), key)


# ── 결정적 encode_rule 후보 ─────────────────────────────────────────────────────


def stop_action(checks: list[str]) -> str | None:
    """expectation 의 checks 가 기대하는 업무 종료를 rules 의 then 값으로 바꾼다.

    order.final_status 를 == 나 in 으로 보는 조건의 글자 값에서 HOLD 와 REJECTED 를 찾는다. HOLD 가
    있으면 hold, REJECTED 만 있으면 reject, 둘 다 없으면 None(규칙으로 옮길 종료가 없다)이다.
    """
    found = set()
    for check in checks:
        parts = condition_parts(check)
        if parts and parts[0] == "order.final_status" and parts[1] in ("==", "in"):
            found.update(value for value in literal_values(parts[2]) if value in STOP_ACTIONS)
    if "HOLD" in found:
        return STOP_ACTIONS["HOLD"]
    if "REJECTED" in found:
        return STOP_ACTIONS["REJECTED"]
    return None


def reason_code(text: str | None) -> str:
    """의도 text 의 영문 낱말로 reason 코드를 만든다. 만들지 못하면 DEFAULT_REASON 이다.

    낱말을 대문자로 바꿔 '_' 로 잇고 40자에서 자른다. 한글만 있는 text 는 DEFAULT_REASON 이 된다.
    만든 코드가 YAML 1.1 불리언·null 낱말(NO, ON, TRUE 등)이면 YAML 이 글자로 읽지 않으므로
    DEFAULT_REASON 을 쓴다(V3-6).
    """
    words = re.findall(r"[A-Za-z][A-Za-z0-9]*", text or "")
    code = "_".join(word.upper() for word in words)[:40].rstrip("_")
    if not _REASON.fullmatch(code) or code.lower() in _YAML_WORDS:
        return DEFAULT_REASON
    return code


def owner_rules(expectations: list[dict], reason: str) -> list[dict]:
    """의도 expectations(담당자가 준 것 또는 Agent 가 만든 것)에서 rules 항목을 만든다.

    scope 가 있고 읽을 수 있으며 checks 가 업무 종료(stop_action)를 기대하는 expectation 마다
    {when: scope, then, reason} 하나다. scope 가 없거나 종료를 기대하지 않는 expectation(예:
    선입금은 SHIPPED)은 규칙이 아니라 시험 기대로만 쓴다.
    """
    rules = []
    for expectation in expectations:
        scope = expectation.get("scope")
        action = stop_action(expectation.get("checks") or [])
        if scope and condition_key(scope) and action:
            rules.append({"when": scope, "then": action, "reason": reason})
    return rules


def _suppliable(key: str, step: str, steps: list[str], metas: dict[str, dict]) -> bool:
    """step 이 key 를 input 으로 받을 수 있는지 본다.

    이미 선언했거나, 선언할 input(input_for)이 주문 입력 키이거나 앞 단계가 내놓는 값이면 참이다.
    """
    if any(covers(name, key) for name in inputs_of(metas.get(step, {}))):
        return True
    name = input_for(key)
    if name in INITIAL_INPUT_KEYS:
        return True
    earlier = steps[: steps.index(step)]
    return any(name in _strings(metas.get(s, {}).get("outputs")) for s in earlier)


def rule_targets(
    changed_skill: str | None, steps: list[str], metas: dict[str, dict], keys: list[str]
) -> list[str]:
    """rules 를 넣을 단계 skill 을 최대 두 개 고른다(V3-6).

    ① 바뀐 skill 이 흐름의 단계 skill(steps 칸이 없는 skill)이면 그 skill ② 모든 조건 키를 input
    으로 받을 수 있는(_suppliable) 가장 이른 단계 순서다. 둘이 같으면 하나만 남는다. 바뀐 skill 이
    결제 뒤 단계라 규칙이 너무 늦게 걸리는 경우(B-02 의 shipping-reserve)에도 ② 가 결제 전
    단계(order-intake) 후보를 따로 만든다.
    """
    targets = []
    if changed_skill in steps and "steps" not in metas.get(changed_skill, {}):
        targets.append(changed_skill)
    earliest = next(
        (
            s
            for s in steps
            if "steps" not in metas.get(s, {})
            and all(_suppliable(k, s, steps, metas) for k in keys)
        ),
        None,
    )
    if earliest is not None and earliest not in targets:
        targets.append(earliest)
    return targets


def rule_target(
    changed_skill: str | None, steps: list[str], metas: dict[str, dict], keys: list[str]
) -> str | None:
    """rule_targets 의 첫 단계다. 넣을 단계가 없으면 None 이다."""
    targets = rule_targets(changed_skill, steps, metas, keys)
    return targets[0] if targets else None


def _block(lines: list[str], key: str) -> tuple[int, int] | None:
    """frontmatter 줄 목록에서 최상위 칸 key 가 차지하는 [시작, 끝) 줄 범위다. 없으면 None 이다."""
    for start, line in enumerate(lines):
        match = _TOP_KEY.match(line)
        if match and match.group(1) == key:
            end = start + 1
            while end < len(lines) and not _TOP_KEY.match(lines[end]):
                end += 1
            while end > start + 1 and not lines[end - 1].strip():
                end -= 1
            return start, end
    return None


def _scalar(value) -> str:
    """YAML 값 하나를 frontmatter 에 쓸 글로 만든다. 읽으면 같은 값이 되게 쓴다.

    None 은 null, bool 은 true·false, 숫자는 그대로다. 글자는 평범한 이름(_PLAIN)이고 YAML 1.1
    불리언·null 낱말이 아니면 따옴표 없이, 아니면 JSON 글자(YAML 큰따옴표 글자)로 쓴다. 목록이나 Map
    은 JSON(YAML 흐름식)으로 쓴다. _rule_lines 와 _with_inputs 가 부른다.
    """
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int | float):
        return str(value)
    if isinstance(value, str):
        if _PLAIN.fullmatch(value) and value.lower() not in _YAML_WORDS:
            return value
        return json.dumps(value, ensure_ascii=False)
    return json.dumps(value, ensure_ascii=False, default=str)


def _rule_lines(rules: list[dict], indent: str) -> list[str]:
    """rules 항목을 블록 목록 줄로 만든다. when 글자는 JSON 글자(YAML 큰따옴표 글자)로 쓴다.

    항목의 칸을 읽은 순서대로 모두 쓴다. 형식이 틀린 기존 규칙(then 이 없거나 모르는 칸이 있음)도
    예외 없이 그대로 옮기고, 칸이 하나도 없는 항목만 건너뛴다. 형식 오류는 apply_patch 의
    metadata_errors 가 알린다.
    """
    lines = []
    for rule in rules:
        for no, (key, value) in enumerate(rule.items()):
            prefix = f"{indent}- " if no == 0 else f"{indent}  "
            if key == "when" and isinstance(value, str):
                text = json.dumps(value, ensure_ascii=False)
            else:
                text = _scalar(value)
            lines.append(f"{prefix}{_scalar(key)}: {text}")
    return lines


# 한 줄 흐름식 목록 칸의 값 부분이다. ' [a, b]  # 주석' 을 (' [', 'a, b', ']  # 주석') 으로 나눈다.
_FLOW_VALUE = re.compile(r"^(\s*\[)(.*?)(\]\s*(?:#.*)?)$")


def _with_inputs(lines: list[str], meta: dict, keys: list[str]) -> list[str]:
    """조건 키를 읽을 input 이 없으면 inputs 칸 끝에 더한 줄 목록을 돌려준다.

    칸의 모양을 지킨다. 한 줄 흐름식 목록(inputs: [a, b])이면 닫는 ']' 앞에 더해 줄 끝 주석을
    남기고, 블록 목록(inputs: 아래 '- a' 줄)이면 같은 들여쓰기로 마지막 항목 뒤에 더해 다른 줄과
    주석을 그대로 둔다. 칸이 없으면 끝에 새로 쓰고, 그 밖의 모양(빈 칸, 여러 줄 흐름식 등)은 읽은
    항목과 새 항목으로 한 줄 흐름식 목록을 다시 쓴다. 더하는 이름은 _scalar 로 써서 YAML 특수 문자가
    든 이름도 글자로 읽힌다. 더할 것이 없으면 그대로 돌려준다.
    """
    inputs = inputs_of(meta)
    added = []
    for key in keys:
        if not any(covers(name, key) for name in inputs + added):
            added.append(input_for(key))
    if not added:
        return lines
    items = [_scalar(name) for name in added]
    span = _block(lines, "inputs")
    if span is None:
        return lines + ["inputs: [" + ", ".join(items) + "]"]
    start, end = span
    head, value = lines[start].split(":", 1)
    flow = _FLOW_VALUE.match(value)
    if flow and end == start + 1:
        inner = flow.group(2).rstrip()
        joined = (inner + ", " if inner.strip() else "") + ", ".join(items)
        return lines[:start] + [f"{head}:{flow.group(1)}{joined}{flow.group(3)}"] + lines[end:]
    item_rows = [no for no in range(start + 1, end) if _ITEM.match(lines[no])]
    if item_rows and not value.split("#", 1)[0].strip():
        indent = _ITEM.match(lines[item_rows[0]]).group(1)
        last = item_rows[-1] + 1
        return lines[:last] + [f"{indent}- {item}" for item in items] + lines[last:]
    line = "inputs: [" + ", ".join([_scalar(name) for name in inputs] + items) + "]"
    return lines[:start] + [line] + lines[end:]


def _with_rules(lines: list[str], meta: dict, rules: list[dict]) -> list[str]:
    """rules 칸에 항목을 더한 줄 목록을 돌려준다.

    칸이 없으면 frontmatter 끝에 새로 쓴다. 블록 목록이면 기존 줄을 그대로 두고 같은 들여쓰기로 뒤에
    붙인다. 빈 칸(rules:, rules: [])이나 흐름식 목록이면 읽은 항목과 새 항목으로 칸을 다시 쓴다.
    """
    span = _block(lines, "rules")
    if span is None:
        return lines + ["rules:"] + _rule_lines(rules, "  ")
    start, end = span
    items = [_ITEM.match(line) for line in lines[start + 1:end]]
    items = [match for match in items if match]
    if items and not lines[start].split(":", 1)[1].strip():
        return lines[:end] + _rule_lines(rules, items[0].group(1)) + lines[end:]
    rendered = ["rules:"] + _rule_lines(rules_of(meta) + rules, "  ")
    return lines[:start] + rendered + lines[end:]


def add_rules(text: str, rules: list[dict]) -> str | None:
    """단계 skill 원문에 rules 를 넣고 조건 키를 inputs 에 더한 원문을 돌려준다.

    같은 when(앞뒤 공백 무시)의 규칙이 이미 있으면 그 규칙은 그대로 두고 빠진 input 만 더한다.
    담당자가 rules 를 직접 넣고 조건 키를 inputs 에 넣지 않은 B-05 를 고치는 후보다(V3-6). flow
    skill(steps 칸이 있음)이거나 원문을 읽을 수 없거나 바꿀 것이 없으면 None 이다. 고친 원문을 다시
    읽어 모든 규칙과 input 이 들어갔고 원래 규칙이 남았는지 확인하고, 아니면 None 이다. 형식이 틀린
    기존 규칙이 있어도 예외를 내지 않는다.
    """
    parts = _split(text)
    meta = frontmatter(text)
    if parts is None or not meta or "steps" in meta:
        return None
    existing = {when for when in map(when_of, rules_of(meta)) if when}
    new_rules = [rule for rule in rules if rule["when"].strip() not in existing]
    keys = list(dict.fromkeys(k for k in (condition_key(r["when"]) for r in rules) if k))
    lines, rest = parts
    lines = _with_inputs(lines, meta, keys)
    if new_rules:
        lines = _with_rules(lines, meta, new_rules)
    revised = _join(lines, rest)
    if revised == text:
        return None
    check = frontmatter(revised)
    whens = set(map(when_of, rules_of(check)))
    readable = all(any(covers(name, key) for name in inputs_of(check)) for key in keys)
    wanted = existing | {rule["when"].strip() for rule in rules}
    if not readable or not wanted <= whens:
        return None
    return revised


def encode_rule_revisions(
    intent: dict, changed_skill: str | None, sources: dict[str, dict], steps: list[str]
) -> list[tuple[str, str]]:
    """의도로 결정적 encode_rule 후보의 (skill, 수정 후 전체 원문) 목록을 만든다. 최대 두 개다.

    intent 는 {text, expectations} 이고 expectations 는 담당자가 준 것이거나 Agent 가 의도 글로 만든
    것이다(어느 쪽이든 후보 검증이 실행으로 판정한다). sources 는 {skill: {content, version}}(후보
    snapshot 원문), steps 는 의존성 그래프의 실행 순서다. 대상 순서는 rule_targets 이고(V3-6),
    단계마다 add_rules 로 고친다. 규칙으로 옮길 expectation 이 없거나, 넣을 단계가 없거나, 바꿀 것이
    없으면 빈 목록이다. 2.6 의 결정적 후보 규칙을 따른다.
    """
    rules = owner_rules(intent.get("expectations") or [], reason_code(intent.get("text")))
    if not rules:
        return []
    metas = {skill: frontmatter(source["content"]) for skill, source in sources.items()}
    keys = [condition_key(rule["when"]) for rule in rules]
    revisions = []
    for target in rule_targets(changed_skill, [s for s in steps if s in sources], metas, keys):
        revised = add_rules(sources[target]["content"], rules)
        if revised:
            revisions.append((target, revised))
    return revisions


# ── 후보 판정 ──────────────────────────────────────────────────────────────────


def _same_text(left: str, right: str) -> bool:
    """줄 단위로 같은지 본다. patch 가 줄 단위라 마지막 줄바꿈만 다른 두 원문은 같은 파일이 된다."""
    return left.splitlines() == right.splitlines()


def is_revert(revised: dict[str, str], baseline: dict[str, str], owner_changed: set[str]) -> bool:
    """후보를 적용하면 담당자가 바꾼 파일 중 하나라도 기준본과 같아지는지 본다.

    revised 는 후보가 고친 {skill: 수정 후 원문}, baseline 은 담당자가 바꾼 skill 의 기준 snapshot
    원문이다. 후보가 고치지 않은 파일은 수정본 그대로라 기준본과 다르다.
    """
    return any(
        skill in owner_changed and skill in baseline and _same_text(text, baseline[skill])
        for skill, text in revised.items()
    )


def weakens_contract(
    revised: dict[str, str], current: dict[str, str], scope_keys: list[str]
) -> bool:
    """후보가 계약을 약하게 만드는지 본다. service·Agent·QA 가 같은 정의를 쓴다(V3-7).

    current 는 후보 snapshot 원문이다. 고친 skill 마다 다음 가운데 하나면 참이다.
    - 원래 rules 의 when(앞뒤 공백 무시)이 고친 뒤 rules 에 없다. 규칙을 지웠거나 when 을 바꾼
      것이다.
    - 지운 input 이 원래 rules 의 when 키나 의도 scope 키(scope_keys)를 덮었다. 다른 input 이 그
      키를 여전히 덮어도
      참이다(service/src/main/java/com/capstone4/skillops/proposal/ProposalJudge.java 의
      weakensContract 와 같다).
    then·reason 만 바꾼 규칙은 약화가 아니다. 형식이 틀린 규칙도 예외 없이 읽는다.
    """
    for skill, text in revised.items():
        before, after = frontmatter(current.get(skill, "")), frontmatter(text)
        before_whens = [when for when in map(when_of, rules_of(before)) if when]
        after_whens = set(map(when_of, rules_of(after)))
        if any(when not in after_whens for when in before_whens):
            return True
        used = {condition_key(when) for when in before_whens} | set(scope_keys)
        used.discard(None)
        after_inputs = set(inputs_of(after))
        removed = [name for name in inputs_of(before) if name not in after_inputs]
        if any(covers(name, key) for name in removed for key in used):
            return True
    return False
