"""공통 규칙 검사(skill_quality)를 LLM 없이 값만으로 검사한다."""

from pathlib import Path

import pytest

from skillops_agent import config
from skillops_agent.skill_quality import (
    blockers,
    check_eval_set,
    check_proposal,
    check_skill_md,
    failed,
    finding,
    grade,
    rule_checker,
)
from skillops_agent.skill_quality.loader import load_skill_dir, resolve_skills

SKILLS_DIR = Path(__file__).resolve().parents[2] / "skills"
EVALSETS = Path(__file__).resolve().parents[1] / "evalsets"

PAYMENT = """---
name: payment
description: 주문 금액을 결제한다.
inputs:     [order.id, order.total]
outputs:    [payment.id, payment.status]
depends_on: [inventory-check]
owner: 재무팀
version: 1
---
# 결제 절차
1. 결제 어댑터를 호출한다.
"""
KNOWN = {"payment", "inventory-check", "order-flow"}


def ids_failed(findings):
    return {f["id"] for f in failed(findings)}


# ── SKILL.md ───────────────────────────────────────────────────────────────────


def test_valid_skill_passes_every_rule():
    meta, findings = check_skill_md(PAYMENT, "payment", KNOWN)
    assert meta["name"] == "payment"
    assert failed(findings) == []
    assert all(f["kind"] == "rule" for f in findings)
    assert grade(findings) == "S"


@pytest.mark.parametrize(
    "text, rule",
    [
        ("name: payment\n", "rule.frontmatter_parse"),
        ("---\nname: payment\n", "rule.frontmatter_parse"),
        ("---\nname: [unclosed\n---\n", "rule.frontmatter_parse"),
    ],
)
def test_broken_frontmatter_is_blocker_and_stops_other_checks(text, rule):
    meta, findings = check_skill_md(text, "payment")
    assert meta is None
    assert [f["id"] for f in findings] == [rule]
    assert grade(findings) == "F"


def test_name_and_description_rules():
    text = PAYMENT.replace("name: payment", "name: Payment_Skill").replace(
        "description: 주문 금액을 결제한다.", "description: <b>결제</b>"
    )
    _, findings = check_skill_md(text, "payment")
    assert {"rule.name_format", "rule.name_matches_folder", "rule.description_no_xml"} <= (
        ids_failed(findings)
    )
    long_desc = PAYMENT.replace("주문 금액을 결제한다.", "가" * 1025)
    assert "rule.description_length" in ids_failed(check_skill_md(long_desc)[1])
    empty_desc = PAYMENT.replace("주문 금액을 결제한다.", "''")
    assert "rule.description_length" in ids_failed(check_skill_md(empty_desc)[1])


def test_multiline_steps_are_read_and_empty_steps_block():
    # #72 사례: 여러 줄 YAML 목록을 빈 목록으로 읽으면 안 된다.
    flow = (
        "---\nname: order-flow\ndescription: 주문 순서\n"
        "steps:\n  - payment\n  - inventory-check\n---\n본문\n"
    )
    meta, findings = check_skill_md(flow, "order-flow", KNOWN)
    assert meta["steps"] == ["payment", "inventory-check"]
    assert failed(findings) == []

    empty = "---\nname: order-flow\ndescription: 주문 순서\nsteps:\n---\n본문\n"
    assert "rule.steps_not_empty" in ids_failed(check_skill_md(empty, "order-flow")[1])


def test_references_must_exist():
    _, findings = check_skill_md(PAYMENT, "payment", {"payment"})
    assert "rule.references_exist" in ids_failed(findings)


def test_minor_and_major_rules_lower_grade_without_blocking():
    text = PAYMENT.replace("version: 1", "version: 1\nextra: x") + "\n<!-- TODO: 본문 -->\n"
    _, findings = check_skill_md(text, "payment", KNOWN)
    assert ids_failed(findings) == {"rule.allowed_keys", "rule.no_todo"}
    assert blockers(findings) == []
    assert grade(findings) == "A"


def test_secret_is_blocker_and_not_echoed():
    text = PAYMENT + "\nkey: sk-" + "a" * 30 + "\n"
    _, findings = check_skill_md(text, "payment", KNOWN)
    secret = next(f for f in findings if f["id"] == "rule.no_secret")
    assert secret["status"] == "FAIL" and secret["severity"] == "BLOCKER"
    assert "sk-" not in secret["reason"]


def test_repository_skills_have_no_blockers():
    # 실제 skills/ 6개는 형식이 맞고, 본문 TODO(W5) 만 MINOR 로 남아 있다.
    folders = sorted(p for p in SKILLS_DIR.iterdir() if (p / "SKILL.md").exists())
    names = {p.name for p in folders}
    assert len(folders) == 6
    for folder in folders:
        _, findings = check_skill_md((folder / "SKILL.md").read_text("utf-8"), folder.name, names)
        assert blockers(findings) == [], folder.name
        assert ids_failed(findings) <= {"rule.no_todo"}, folder.name


# ── 평가셋 ─────────────────────────────────────────────────────────────────────

SKILLS = [{"name": "a", "description": "A"}, {"name": "b", "description": "B"}]


def test_eval_set_ok():
    cases = [{"query": "a 해줘", "expected_skill": "a", "should_trigger": True}]
    problems, clean = check_eval_set("a", SKILLS, cases)
    assert problems == []
    assert clean["cases"] == cases


@pytest.mark.parametrize(
    "skills, cases, rule",
    [
        ([], [], "rule.skills_present"),
        (SKILLS + [{"name": "a"}], None, "rule.skill_name_unique"),
        (SKILLS + [{"name": "none"}], None, "rule.skill_name_reserved"),
        (SKILLS, [{"query": "b 해줘", "expected_skill": "b", "should_trigger": True}],
         "rule.target_has_positive"),
        (SKILLS, [{"query": " ", "expected_skill": "a", "should_trigger": True}],
         "rule.case_query"),
    ],
)
def test_eval_set_problems(skills, cases, rule):
    cases = cases if cases is not None else [
        {"query": "a 해줘", "expected_skill": "a", "should_trigger": True}
    ]
    problems, _ = check_eval_set("a", skills, cases)
    assert rule in {p["id"] for p in problems}


# ── 수정안 ─────────────────────────────────────────────────────────────────────

QUERIES = ["이번 주 보고서 정리해줘", "회의록 요약해줘"]


def test_proposal_rules():
    ok = check_proposal("주간 업무 보고서 초안을 쓸 때 사용한다.", "문서를 정리한다.", QUERIES)
    assert blockers(ok) == []

    copied = check_proposal("예: '이번 주 보고서 정리해줘' 요청에 쓴다.", "문서", QUERIES)
    assert "rule.proposal_no_query_copy" in ids_failed(copied)
    assert "이번 주 보고서 정리해줘" in next(
        f["reason"] for f in copied if f["id"] == "rule.proposal_no_query_copy"
    )

    assert "rule.proposal_changed" in ids_failed(check_proposal("문서", "문서", QUERIES))
    assert "rule.proposal_not_empty" in ids_failed(check_proposal("  ", "문서", QUERIES))
    assert "rule.description_length" in ids_failed(check_proposal("가" * 1025, "문서", QUERIES))

    # 줄바꿈은 MINOR 라 막지 않는다.
    multi = check_proposal("한 줄\n두 줄", "문서", QUERIES)
    assert ids_failed(multi) == {"rule.proposal_single_line"}
    assert blockers(multi) == []


def test_description_limit_matches_config():
    assert config.TRIGGER_DESCRIPTION_MAX == rule_checker.DESCRIPTION_MAX


# ── 등급 ───────────────────────────────────────────────────────────────────────


def major(n, kind="rule"):
    return [finding(f"x{i}", "FAIL", "MAJOR", kind=kind) for i in range(n)]


@pytest.mark.parametrize(
    "n, expected", [(0, "S"), (1, "A"), (2, "A"), (3, "B"), (4, "B"), (5, "C")]
)
def test_grade_by_major_count(n, expected):
    assert grade(major(n)) == expected


def test_semantic_blocker_does_not_make_f():
    semantic = [finding("s1", "FAIL", "BLOCKER", kind="semantic")]
    assert blockers(semantic) == []
    assert grade(semantic) == "A"
    assert grade([finding("r1", "FAIL", "BLOCKER")]) == "F"
    assert grade([finding("u", "UNVERIFIED", "BLOCKER")]) == "S"


# ── 원문 폴더 읽기 ─────────────────────────────────────────────────────────────


def test_load_skill_dir_reads_every_skill_md():
    skills = load_skill_dir(SKILLS_DIR)
    expected = sorted(p.parent.name for p in SKILLS_DIR.glob("*/SKILL.md"))
    assert [s["name"] for s in skills] == expected
    assert all(s["skill_md"].startswith("---") and s["folder"] == s["name"] for s in skills)


def test_load_skill_dir_rejects_missing_or_empty(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_skill_dir(tmp_path / "nope")
    with pytest.raises(FileNotFoundError):
        load_skill_dir(tmp_path)


def test_resolve_skills_prefers_override_then_skills_dir_then_inline(tmp_path):
    inline = [{"name": "a", "description": "A"}]
    assert resolve_skills({"skills": inline}, tmp_path) == inline
    from_file = resolve_skills({"skills_dir": "doc-assistant/skills", "skills": inline}, EVALSETS)
    assert "doc-assistant" in [s["name"] for s in from_file]
    overridden = resolve_skills({"skills_dir": "doc-assistant/skills"}, EVALSETS, SKILLS_DIR)
    assert "order-flow" in [s["name"] for s in overridden]


def test_sample_eval_set_skill_files_pass_rule_checks():
    skills = resolve_skills({"skills_dir": "doc-assistant/skills"}, EVALSETS)
    names = {s["name"] for s in skills}
    for s in skills:
        _, findings = check_skill_md(s["skill_md"], s["folder"], names)
        assert failed(findings) == [], s["name"]


# ── 진입 구조 ──────────────────────────────────────────────────────────────────


def test_entry_structure_from_steps():
    from skillops_agent.skill_quality import entry_structure

    metas = {
        "order-flow": {"steps": ["order-intake", "payment"]},
        "order-intake": {},
        "payment": {"depends_on": ["order-intake"]},
        "faq": {},
    }
    s = entry_structure(metas)
    assert s["entries"] == ["order-flow", "faq"]
    assert s["sub_steps"] == {"order-intake": "order-flow", "payment": "order-flow"}
    assert entry_structure({"a": {}, "b": {}}) == {"entries": ["a", "b"], "sub_steps": {}}


def test_repository_skills_have_order_flow_as_only_entry():
    from skillops_agent.skill_quality import entry_structure, parse_skill_md

    metas = {s["name"]: parse_skill_md(s["skill_md"])[0] for s in load_skill_dir(SKILLS_DIR)}
    assert entry_structure(metas)["entries"] == ["order-flow"]
