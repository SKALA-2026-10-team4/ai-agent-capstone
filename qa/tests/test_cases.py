"""저장소의 실제 케이스 파일(qa/cases/*.yaml)이 서로 맞고 게시본 Skill 에 적용되는지 확인한다.

check 는 서버가 있어야 돌지만, 수정의 from 글이 게시본에 정확히 한 번 있는지, 주문 Agent 반응 표가
표준 주문 g-01~g-06 을 모두 적었는지, 정답표의 안이 판정 규칙(R1·R3)과 정답표의 규칙 위치
(answer.rule_skills)에 맞는지는 서버 없이 알 수 있다. 케이스를 고치거나 게시본 Skill
(order-agent/skills)이 바뀌면 이 테스트가 먼저 어긋남을 알린다. check 의 기준 snapshot 은 시연
fixture baseline 이고, 그 내용은 게시본 그대로다(order-agent/backend/src/main/java/com/capstone4/
orderagent/workspace/SnapshotService.java 의 demo).
"""

import json
import re
from pathlib import Path

import pytest
import yaml

from qa_logic import (
    GOLDEN_IDS,
    apply_changes,
    front_matter,
    load_case,
    rag_exclusions,
    reverted_files,
    rule_changes,
    scope_keys,
    validate_case_ids,
    weakened,
)

REPO = Path(__file__).resolve().parents[2]
CASES_DIR = REPO / "qa/cases"
SKILLS_DIR = REPO / "order-agent/skills"
GOLDEN_DIR = REPO / "scenarios/order-agent/golden"
RAG_CASES = REPO / "skillops-agent/rag/cases.json"

pytestmark = pytest.mark.skipif(not SKILLS_DIR.is_dir(), reason="게시본 Skill 폴더가 없습니다")


def published_texts() -> dict[str, str]:
    """게시본 Skill 을 snapshot 경로(skills/<이름>/SKILL.md)로 읽는다. 줄바꿈은 LF 로 맞춘다."""
    return {
        f"skills/{path.parent.name}/SKILL.md": path.read_bytes()
        .decode("utf-8")
        .replace("\r\n", "\n")
        for path in sorted(SKILLS_DIR.glob("*/SKILL.md"))
    }


def all_cases() -> list[dict]:
    cases = [
        load_case(yaml.safe_load(path.read_text(encoding="utf-8")), path.name)
        for path in sorted(CASES_DIR.glob("*.yaml"))
    ]
    validate_case_ids(cases)
    return cases


CASES = all_cases()
AUTO = [c for c in CASES if c["run"] != "manual" and not c["answer"].get("save_rejected")]


def ids(cases):
    return [c["id"] for c in cases]


@pytest.mark.parametrize("case", AUTO, ids=ids(AUTO))
def test_changes_and_fixes_apply_exactly_once(case):
    base = published_texts()
    cand = apply_changes(base, case["change"])  # from 이 없거나 두 번 이상이면 CaseError
    for fix in case["fixes"]:
        apply_changes(cand, fix["change"])


@pytest.mark.parametrize("case", AUTO, ids=ids(AUTO))
def test_order_agent_table_covers_every_golden_order(case):
    table = case["order_agent"]
    if case["run"] == "openai":
        assert not table  # AI 모드 케이스는 반응을 적지 않는다.
        return
    assert set(table) == set(GOLDEN_IDS), f"{case['id']} 의 order_agent 칸이 {sorted(table)} 입니다"
    for reaction in table.values():
        assert reaction["result"] in {"pass", "error", "check_failed"}


def test_golden_files_exist_for_every_golden_id():
    for golden in GOLDEN_IDS:
        data = json.loads((GOLDEN_DIR / f"{golden}.json").read_text(encoding="utf-8"))
        assert data["id"] == golden


INTENT = [c for c in AUTO if c["owner_intent"] is not None]


def test_behavior_cases_exist():
    assert ids(INTENT) == ["B-01", "B-02", "B-03", "B-04", "B-05"]


@pytest.mark.parametrize("case", INTENT, ids=ids(INTENT))
def test_behavior_case_fixes_follow_the_judging_rules(case):
    """정답표가 합격으로 적은 안은 되돌리기도 약화도 아니고, 규칙을 정답표의 위치에만 둔다.

    불합격으로 적은 안은 시험에서 떨어지거나(tests_pass false), 되돌리기거나, 약화여야 한다. 시험
    통과 여부 자체는 check 가 서버로 확인한다.
    """
    base = published_texts()
    cand = apply_changes(base, case["change"])
    owner_files = list(dict.fromkeys(c["file"] for c in case["change"]))
    keys = scope_keys(case["owner_intent"])
    allowed = case["answer"]["rule_skills"]
    for fix in case["fixes"]:
        target = apply_changes(cand, fix["change"])
        reverted = bool(reverted_files(base, target, owner_files))
        weak = weakened(cand, target, keys)
        if fix["valid"]:
            assert fix["tests_pass"], fix["name"]
            assert not reverted and not weak and not fix["policy"], fix["name"]
            assert set(rule_changes(cand, target)) <= set(allowed), fix["name"]
        else:
            assert not fix["tests_pass"] or reverted or weak or fix["policy"], fix["name"]


# 처음 주문에 들어 있는 값 이름이다. order-agent/backend/src/main/java/com/capstone4/orderagent/
# harness/OrderFlowPolicy.java 의 INITIAL_INPUT_KEYS 와 같다. 이 값은 어느 단계든 inputs 에 더할
# 수 있다.
INITIAL_INPUT_KEYS = (
    "order.id",
    "order.items",
    "order.customer",
    "order.payment_method",
    "order.shipping",
)


def _covers(name: str, key: str) -> bool:
    return key == name or key.startswith(name + ".")


def earliest_rule_skill(texts: dict[str, str], key: str) -> str | None:
    """flow 순서에서 key 를 조건으로 쓸 수 있는 가장 이른 단계다(계획 2.10 V3-6 의 두 번째 위치).

    처음 주문 값이면 첫 단계가 그 값을 inputs 에 더할 수 있고, 아니면 key 를 덮는 input 을 이미
    선언한 첫 단계다.
    """
    steps = front_matter(texts["skills/order-flow/SKILL.md"]).get("steps") or []
    if any(_covers(name, key) for name in INITIAL_INPUT_KEYS):
        return steps[0] if steps else None
    for step in steps:
        inputs = front_matter(texts.get(f"skills/{step}/SKILL.md", "")).get("inputs") or []
        if any(_covers(str(name), key) for name in inputs):
            return step
    return None


@pytest.mark.parametrize("case", INTENT, ids=ids(INTENT))
def test_behavior_case_rule_skills_accept_the_deterministic_placements(case):
    """SkillOps 의 결정적 encode_rule 후보 위치(최대 2곳, 계획 2.10 V3-6)를 정답표가 받아들인다.

    ① 담당자가 바꾼 Skill 은 answer.rule_skills 안에 있거나, 그 위치가 시험에서 떨어지는 까닭을
    불합격 안(tests_pass false)으로 적어 두어야 한다(B-02 의 배송 예약). ② scope 키를 조건으로 쓸
    수 있는 가장 이른 단계는 rule_skills 안에 있어야 한다. 그래야 LLM 없이 나온 합격안이 CONFLICT 가
    되지 않는다.
    """
    base = published_texts()
    cand = apply_changes(base, case["change"])
    allowed = case["answer"]["rule_skills"]
    for key in scope_keys(case["owner_intent"]):
        assert earliest_rule_skill(cand, key) in allowed, key
    failing_spots = {
        skill
        for fix in case["fixes"]
        if not fix["tests_pass"]
        for skill in rule_changes(cand, apply_changes(cand, fix["change"]))
    }
    for path in dict.fromkeys(c["file"] for c in case["change"]):
        owner_skill = path.split("/")[1]
        assert owner_skill in allowed or owner_skill in failing_spots, owner_skill


REASON = re.compile(r"[A-Z][A-Z0-9_]{0,39}")
OPERATORS = {"==", "!=", "in", ">", ">=", "<", "<="}


def rule_problems(texts: dict[str, str]) -> list[str]:
    """rules 가 계약(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.2)에 맞지 않는 곳을 돌려준다.

    키는 when·then·reason 셋, then 은 hold·reject, reason 은 대문자 코드, when 의 값 이름은 그
    Skill 의 선언 input i 와 같거나 i. 으로 시작해야 한다(RULE_KEY_NOT_INPUT).
    """
    problems = []
    for path, text in texts.items():
        meta = front_matter(text)
        for rule in meta.get("rules") or []:
            parts = str(rule.get("when", "")).split(maxsplit=2)
            key = parts[0] if parts else ""
            inputs = meta.get("inputs") or []
            if set(rule) != {"when", "then", "reason"} or rule["then"] not in {"hold", "reject"}:
                problems.append(f"{path}: 규칙 모양 {rule}")
            if not REASON.fullmatch(str(rule.get("reason"))):
                problems.append(f"{path}: reason {rule.get('reason')}")
            if len(parts) != 3 or parts[1] not in OPERATORS:
                problems.append(f"{path}: 조건 {rule.get('when')}")
            if not any(key == i or key.startswith(i + ".") for i in inputs):
                problems.append(f"{path}: {key} 는 선언 input 이 아닙니다")
    return problems


@pytest.mark.parametrize("case", INTENT, ids=ids(INTENT))
def test_behavior_case_valid_fixes_write_rules_the_order_agent_accepts(case):
    cand = apply_changes(published_texts(), case["change"])
    for fix in case["fixes"]:
        if fix["valid"]:
            assert rule_problems(apply_changes(cand, fix["change"])) == [], fix["name"]


def test_b05_owner_rule_uses_an_undeclared_key():
    case = next(c for c in INTENT if c["id"] == "B-05")
    problems = rule_problems(apply_changes(published_texts(), case["change"]))
    assert problems == ["skills/payment/SKILL.md: order.customer 는 선언 input 이 아닙니다"]


@pytest.mark.parametrize("case", INTENT, ids=ids(INTENT))
def test_behavior_case_answer_fields(case):
    answer = case["answer"]
    assert answer["fixable"] is True
    assert answer["root_cause_kind"] in {"change", "incomplete_edit"}
    assert not answer["revert_allowed"]
    assert any(f["valid"] for f in case["fixes"])
    assert any(f["name"].startswith("되돌리기") and not f["valid"] for f in case["fixes"])


def test_rag_cases_point_to_existing_qa_cases():
    rag = json.loads(RAG_CASES.read_text(encoding="utf-8"))
    known = set(ids(CASES))
    for case_id in rag_exclusions(rag):
        assert case_id in known, case_id
