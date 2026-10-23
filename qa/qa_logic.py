"""평가셋 실행기의 판정 로직이다. 서버를 부르지 않는 순수 함수만 둔다.

qa/run_qa.py 의 check 와 eval 이 이 모듈로 케이스 파일(qa/cases/*.yaml)을 읽고, 수정본을 만들고,
주문 Agent 반응과 SkillOps 제안서를 정답표와 비교하고, 지표를 계산한다. 판정 규칙은 qa/README.md 의
R1~R5 이고 지표 정의는 QA 문서(SKILLOPS_QA_평가기준_테스트시나리오) 3장을 따른다. 서버를 부르는
코드와 나눈 이유는 판정 규칙을 서버 없이 qa/tests/test_qa_logic.py 로 고정하기 위해서다. 규칙을
바꾸면 그 테스트와 README 의 규칙 표를 함께 고친다.
"""

import difflib
import re
import statistics

# 케이스 파일에 꼭 있어야 하는 칸이다.
REQUIRED_FIELDS = ("id", "group", "title", "set", "run")
# run 칸에 올 수 있는 값이다. auto 는 규칙 모드에서 자동으로 돌리고, openai 는 주문 Agent 를 AI
# 모드로 띄운 때만 돌리며, manual 은 사람이 절차를 따라 확인한다.
RUN_KINDS = {"auto", "openai", "manual"}
SETS = {"dev", "hidden"}
TRIGGERS = {"skill_saved", "diagnosis_only"}
# 표준 주문(scenarios/order-agent/golden)을 SkillOps 가 돌리는 순서다. 사전 검증은 이 순서에서 처음
# 실패한 주문으로 진단을 이어 간다(skillops-agent/skillops_agent/nodes.py 의 _preflight).
GOLDEN_IDS = ("g-01", "g-02", "g-03")
# "문제가 있다"고 알린 것으로 세는 진단 결론이다. ERROR 는 멈춘 것이라 탐지로 세지 않는다.
DETECTED_STATUSES = {"VALIDATION_FAILED", "EVALUATED", "INTENT_VIOLATION"}
# 진단이 끝나지 않은 상태다. eval 은 이 상태가 아닐 때까지 기다린다.
ACTIVE_STATUSES = {"QUEUED", "RUNNING"}


class CaseError(ValueError):
    """케이스 파일이 형식에 맞지 않거나 수정을 적용할 수 없을 때 던지는 예외다."""


class PatchError(ValueError):
    """unified diff 를 원문에 적용할 수 없을 때 던지는 예외다."""


# ── 케이스 읽기 ──────────────────────────────────────────────────────────────


def load_case(data: dict, source: str) -> dict:
    """YAML 하나를 읽은 dict 를 검사하고 기본값을 채운 케이스로 돌려준다.

    필수 칸과 run, set, trigger 값을 보고, 자동 케이스에는 정답(answer.status 또는
    answer.save_rejected)이, 수동 케이스에는 절차(steps)와 기대 결과(expected)가 있어야 한다. 빠진
    선택 칸은 기본값으로 채워 qa/run_qa.py 가 칸이 있는지 다시 묻지 않게 한다. 오류 문구에는
    source(파일 이름)를 붙인다.
    """
    if not isinstance(data, dict):
        raise CaseError(f"{source}: 케이스는 YAML 객체여야 합니다")
    for name in REQUIRED_FIELDS:
        if not data.get(name):
            raise CaseError(f"{source}: {name} 칸이 없습니다")
    if data["run"] not in RUN_KINDS:
        raise CaseError(f"{source}: run 은 {sorted(RUN_KINDS)} 가운데 하나여야 합니다")
    if data["set"] not in SETS:
        raise CaseError(f"{source}: set 은 {sorted(SETS)} 가운데 하나여야 합니다")
    case = dict(data)
    case["source"] = source
    case.setdefault("base", "baseline")
    case.setdefault("trigger", "skill_saved")
    if case["trigger"] not in TRIGGERS:
        raise CaseError(f"{source}: trigger 는 {sorted(TRIGGERS)} 가운데 하나여야 합니다")
    case["change"] = [{"cause": True, **c} for c in case.get("change") or []]
    case["intent"] = {"text": "", "checks": [], **(case.get("intent") or {})}
    case["order_agent"] = case.get("order_agent") or {}
    case["fixes"] = [{"failure": None, "policy": None, **f} for f in case.get("fixes") or []]
    answer = {"revert_allowed": False, **(case.get("answer") or {})}
    case["answer"] = answer
    if case["run"] == "manual":
        if not case.get("steps") or not case.get("expected"):
            raise CaseError(f"{source}: 수동 케이스에는 steps 와 expected 가 있어야 합니다")
        return case
    if not answer.get("status") and not answer.get("save_rejected"):
        raise CaseError(f"{source}: answer.status 나 answer.save_rejected 가 있어야 합니다")
    return case


def validate_case_ids(cases: list[dict]) -> None:
    """케이스 ID 가 겹치면 CaseError 를 던진다. 숨긴 셋을 함께 읽을 때 같은 ID 가 섞이는 것을
    막는다.
    """
    seen: dict[str, str] = {}
    for case in cases:
        if case["id"] in seen:
            raise CaseError(
                f"케이스 ID {case['id']} 가 {seen[case['id']]} 와 {case['source']} 에 함께 있습니다"
            )
        seen[case["id"]] = case["source"]


# ── 수정본 만들기 ────────────────────────────────────────────────────────────


def apply_changes(texts: dict[str, str], changes: list[dict]) -> dict[str, str]:
    """파일 원문 묶음에 바꾸기(file, from, to)를 차례로 적용한 새 묶음을 돌려준다.

    from 은 그 파일에 정확히 한 번 나와야 한다. 없거나 두 번 이상 나오면 어느 자리를 바꿀지 정할 수
    없으므로 CaseError 를 던진다. 원래 묶음은 바꾸지 않는다. 담당자 수정(change)과 정답표의
    안(fixes[].change)을 만들 때 모두 이 함수를 쓴다.
    """
    result = dict(texts)
    for change in changes:
        path, old, new = change["file"], change["from"], change["to"]
        if path not in result:
            raise CaseError(f"{path} 파일이 기준 snapshot 에 없습니다")
        count = result[path].count(old)
        if count == 0:
            raise CaseError(f"{path} 에서 '{old}' 를 찾지 못했습니다")
        if count > 1:
            raise CaseError(f"{path} 에 '{old}' 가 두 번 이상 있어 바꿀 자리를 정할 수 없습니다")
        result[path] = result[path].replace(old, new)
    return result


def changed_files(changes: list[dict]) -> list[str]:
    """바꾸기 목록이 건드리는 파일 경로를 처음 나온 순서대로 겹치지 않게 돌려준다."""
    return list(dict.fromkeys(change["file"] for change in changes))


# ── patch 만들기와 적용 ──────────────────────────────────────────────────────

# '@@ -시작,길이 +시작,길이 @@' 머리줄이다. 길이는 1 이면 생략된다.
_HUNK_HEADER = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


def unified_patch(path: str, before: str, after: str) -> str:
    """before 를 after 로 바꾸는 unified diff 를 만든다. 차이가 없으면 빈 글이다.

    줄 단위(splitlines)로 비교하고 머리줄은 a/<path>, b/<path> 다.
    skillops-agent/skillops_agent/contracts.py 의 unified_patch 와 같은 모양이라 service 의
    apply_patch(service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java)가 그대로
    받는다. qa/run_qa.py 의 check 가 담당자 수정 되돌리기와 정답표의 안을 연습 공간에 넣을 때 쓴다.
    """
    lines = difflib.unified_diff(
        before.splitlines(),
        after.splitlines(),
        fromfile=f"a/{path}",
        tofile=f"b/{path}",
        lineterm="",
    )
    patch = "\n".join(lines)
    return patch + "\n" if patch else ""


def apply_unified_patch(text: str, patch: str) -> str:
    """unified diff 를 원문에 적용한 글을 돌려준다. 문맥 줄이 원문과 다르면 PatchError 를 던진다.

    eval 이 SkillOps 제안서 후보의 patch 를 수정본 원문에 적용해 결과 파일을 만들 때 쓴다. 그 결과를
    기준본과 비교해야 되돌리기인지 알 수 있다(R1). 원문 끝 줄바꿈은 그대로 둔다.
    """
    lines = text.splitlines()
    out: list[str] = []
    pos = 0
    body = patch.splitlines()
    i = 0
    while i < len(body):
        line = body[i]
        match = _HUNK_HEADER.match(line)
        if not match:
            if line.startswith(("--- ", "+++ ")) or not line:
                i += 1
                continue
            raise PatchError(f"읽을 수 없는 patch 줄입니다: {line!r}")
        old_start, old_len = int(match[1]), int(match[2] or "1")
        start = old_start - 1 if old_len else old_start
        if start < pos or start > len(lines):
            raise PatchError("patch 덩어리 위치가 원문과 맞지 않습니다")
        out.extend(lines[pos:start])
        pos = start
        i += 1
        while i < len(body) and not body[i].startswith("@@"):
            kind, content = (body[i][:1], body[i][1:]) if body[i] else (" ", "")
            if kind in (" ", "-"):
                if pos >= len(lines) or lines[pos] != content:
                    raise PatchError(f"patch 문맥이 원문과 다릅니다: {content!r}")
                if kind == " ":
                    out.append(content)
                pos += 1
            elif kind == "+":
                out.append(content)
            elif kind != "\\":
                raise PatchError(f"읽을 수 없는 patch 줄입니다: {body[i]!r}")
            i += 1
    out.extend(lines[pos:])
    return "\n".join(out) + ("\n" if text.endswith("\n") else "")


# ── 되돌리기 판정(R1) ────────────────────────────────────────────────────────


def reverted_files(base: dict[str, str], result: dict[str, str], files: list[str]) -> list[str]:
    """담당자가 바꾼 파일(files) 가운데 결과가 기준본과 같아진 파일을 돌려준다.

    한 파일이라도 기준본으로 돌아갔으면 그 부분의 담당자 의도를 지운 것이므로 되돌리기로 본다(R1).
    끝 줄바꿈 차이는 무시하도록 줄 목록으로 비교한다.
    """
    return [
        f for f in files if f in result and result[f].splitlines() == base.get(f, "").splitlines()
    ]


def fix_verdict(tests_pass: bool, reverted: bool, revert_allowed: bool, policy: str | None) -> bool:
    """정답표 기준으로 안 하나가 합격인지 정한다.

    시험(표준 주문 3개, 재현 주문, 의도 조건)을 모두 통과하고(R2), 되돌리기가 아니거나 그 케이스가
    되돌리기를 허용하고(R1), 팀이 정한 불합격 사유(policy, 예: R3 입력 삭제)가 없어야 합격이다.
    """
    return bool(tests_pass and (not reverted or revert_allowed) and not policy)


# ── 주문 Agent 반응 ──────────────────────────────────────────────────────────


def execution_uuid(run_id: str) -> str:
    """run_scenarios 의 run_id(r_ 뒤 32자리 16진수)나 UUID 를 하이픈 UUID 로 바꾼다.

    주문 Agent 의 GET /api/executions/{id}/trace 와 재현 주문 번호(f-<UUID>)가 하이픈 UUID 를
    받는다.
    """
    value = run_id[2:] if run_id.startswith("r_") else run_id
    hexes = value.replace("-", "").lower()
    if not re.fullmatch(r"[0-9a-f]{32}", hexes):
        raise CaseError(f"실행 번호 {run_id} 를 읽을 수 없습니다")
    return f"{hexes[:8]}-{hexes[8:12]}-{hexes[12:16]}-{hexes[16:20]}-{hexes[20:]}"


def observe_golden(passed: bool, trace: dict | None, reasons: list[str]) -> dict:
    """표준 주문 한 번의 결과를 정답표의 order_agent 칸과 같은 모양으로 바꾼다.

    result 는 pass, error(실행이 오류로 끝남), check_failed(오류 없이 끝났지만 기대 결과나 확인
    조건에서 떨어짐) 가운데 하나다. error 면 첫 EXECUTION_ERROR 의 category, 지목 Skill, 빠진 값,
    먼저 끝나야 할 Skill 을 담는다. 이 구분이 SkillOps 사전 검증의 갈림길(진단 진행과 검증 실패)과
    같다.
    """
    if passed:
        return {"result": "pass"}
    trace = trace or {}
    if trace.get("completion") != "FAILED":
        return {"result": "check_failed", "reasons": list(reasons)}
    event = next((e for e in trace.get("events", []) if e.get("kind") == "EXECUTION_ERROR"), {})
    data = event.get("data") or {}
    return {
        "result": "error",
        "category": data.get("category"),
        "skill": data.get("skill_id") or event.get("skill_id"),
        "missing": list(data.get("missing_inputs") or []),
        "deps": list(data.get("missing_dependencies") or []),
        "reasons": list(reasons),
    }


def match_expectation(expected: dict, observed: dict) -> list[str]:
    """주문 Agent 기대 반응(케이스의 order_agent 한 칸)과 관찰한 반응을 비교해 어긋난 점을 돌려준다.

    케이스에 적은 칸만 비교한다. skill 칸에 null 을 적으면 지목된 Skill 이 없어야 한다는 뜻이다.
    빠진 값과 먼저 끝나야 할 Skill 은 순서를 보지 않는 집합으로, reason 은 떨어진 이유 글 안의 부분
    문자열로 비교한다.
    """
    problems = []
    if expected.get("result") != observed.get("result"):
        return [
            f"result 기대 {expected.get('result')}, 실제 {observed.get('result')} "
            f"{observed.get('reasons', '')}"
        ]
    if "category" in expected and expected["category"] != observed.get("category"):
        problems.append(f"category 기대 {expected['category']}, 실제 {observed.get('category')}")
    if "skill" in expected and expected["skill"] != observed.get("skill"):
        problems.append(f"skill 기대 {expected['skill']}, 실제 {observed.get('skill')}")
    for key in ("missing", "deps"):
        if key in expected and set(expected[key]) != set(observed.get(key) or []):
            problems.append(f"{key} 기대 {expected[key]}, 실제 {observed.get(key)}")
    if "reason" in expected and not any(
        expected["reason"] in r for r in observed.get("reasons", [])
    ):
        problems.append(
            f"떨어진 이유에 '{expected['reason']}' 가 없습니다: {observed.get('reasons')}"
        )
    return problems


def preflight_status(observations: list[dict]) -> str:
    """표준 주문 결과(GOLDEN_IDS 순서)로 SkillOps 사전 검증의 갈림길을 정한다.

    모두 통과면 VALIDATED, 처음 실패한 주문이 오류로 끝났으면 DIAGNOSE(진단 진행), 오류 없이 결과만
    다르면 VALIDATION_FAILED 다. skillops-agent/skillops_agent/nodes.py 의 _preflight 와 같은
    규칙이며, 정답표의 answer.status 가 이 갈림길과 맞는지 check 가 확인한다.
    """
    failed = next((o for o in observations if o["result"] != "pass"), None)
    if failed is None:
        return "VALIDATED"
    return "DIAGNOSE" if failed["result"] == "error" else "VALIDATION_FAILED"


def check_fix(fix: dict, observed: dict) -> list[str]:
    """정답표의 안 하나에 적힌 판정과 check 가 실제로 돌린 결과를 비교해 어긋난 점을 돌려준다.

    observed 는 tests_pass(시험 통과), reverted(되돌리기인지), failing(떨어진 이유 목록),
    revert_allowed 를 담는다. tests_pass 가 다르거나, 계산한 합격 여부(fix_verdict)가 정답표의 valid
    와 다르거나, 정답표가 적은 실패 이유(failure)가 실제 이유에 없으면 어긋남으로 본다.
    """
    problems = []
    name = fix.get("name", "?")
    if bool(fix.get("tests_pass")) != bool(observed["tests_pass"]):
        problems.append(
            f"'{name}' 시험 통과 기대 {fix.get('tests_pass')}, 실제 {observed['tests_pass']} "
            f"{observed['failing'][:2]}"
        )
    computed = fix_verdict(
        observed["tests_pass"], observed["reverted"], observed["revert_allowed"], fix.get("policy")
    )
    if bool(fix.get("valid")) != computed:
        problems.append(f"'{name}' valid 기대 {fix.get('valid')}, 계산 {computed}")
    if fix.get("failure") and not any(fix["failure"] in r for r in observed["failing"]):
        problems.append(
            f"'{name}' 떨어진 이유에 '{fix['failure']}' 가 없습니다: {observed['failing'][:2]}"
        )
    return problems


# ── eval 채점 ────────────────────────────────────────────────────────────────


def candidate_texts(cand_texts: dict[str, str], candidate: dict) -> dict[str, str]:
    """SkillOps 후보의 patch 를 수정본 원문에 적용한 파일 묶음을 돌려준다. 적용할 수 없으면
    PatchError 다.
    """
    result = dict(cand_texts)
    for patch in candidate.get("patches") or []:
        path = f"skills/{patch['skill']}/SKILL.md"
        if path not in result:
            raise PatchError(f"후보가 없는 파일 {path} 를 고쳤습니다")
        result[path] = apply_unified_patch(result[path], patch["patch"])
    return result


def _same_texts(a: dict[str, str], b: dict[str, str]) -> bool:
    """두 파일 묶음이 줄 단위로 같은지 본다. 끝 줄바꿈 차이는 무시한다."""
    return a.keys() == b.keys() and all(a[k].splitlines() == b[k].splitlines() for k in a)


def grade_eval(
    case: dict,
    status: str | None,
    proposal: dict | None,
    valid_nos: list[int],
    base_texts: dict[str, str],
    cand_texts: dict[str, str],
    save_rejected: str | None = None,
) -> dict:
    """SkillOps 진단 결과 하나를 정답표로 채점한다.

    결론(diagnosis_status), 제안서 상태, 원인 Skill 을 정답과 비교하고, 합격(valid_nos)으로 나온
    후보마다 결과 파일을 만들어 되돌리기(R1)인지, 정답표가 불합격으로 정한 안인지, 정답표에 없는 새
    안인지 본다. 고칠 수 없다고 정한 케이스에 새 합격안이 나오면 정답표와 시스템 가운데 누가
    틀렸는지 다시 확인해야 하므로 CONFLICT 로 둔다(R5). 돌려주는 dict 는 summarize 가 지표를 셀 때
    읽는다.
    """
    answer = case["answer"]
    reasons: list[str] = []
    conflicts: list[str] = []
    graded = {
        "status": status,
        "detected": status in DETECTED_STATUSES,
        "root_cause_ok": None,
        "presented_fix": False,
        "revert_proposals": 0,
        "valid_candidates": 0,
        "proposal_status": (proposal or {}).get("status"),
        "root_cause": ((proposal or {}).get("root_cause") or {}).get("skill"),
    }
    if answer.get("save_rejected"):
        if save_rejected != answer["save_rejected"]:
            reasons.append(
                f"저장이 {answer['save_rejected']} 로 거부되어야 하는데 "
                f"결과는 {save_rejected or status}"
            )
        shown = f"SAVE_REJECTED({save_rejected})" if save_rejected else status
        return {
            **graded,
            "status": shown,
            "verdict": "FAIL" if reasons else "PASS",
            "reasons": reasons,
        }
    if status not in answer["status"]:
        reasons.append(f"결론 {status} (정답 {answer['status']})")
    if status == "EVALUATED" and proposal is not None:
        if answer.get("proposal") and proposal.get("status") not in answer["proposal"]:
            reasons.append(f"제안서 {proposal.get('status')} (정답 {answer['proposal']})")
        found = graded["root_cause"]
        if answer.get("root_cause_all"):
            graded["root_cause_ok"] = set(answer["root_cause_all"]) <= {found}
        elif answer.get("root_cause"):
            graded["root_cause_ok"] = found in answer["root_cause"]
        if graded["root_cause_ok"] is False:
            expected = answer.get("root_cause_all") or answer.get("root_cause")
            reasons.append(f"원인 {found} (정답 {expected})")
        owner_files = changed_files(case["change"])
        known = [(fix, apply_changes(cand_texts, fix["change"])) for fix in case["fixes"]]
        for cand in proposal.get("candidates") or []:
            if cand.get("candidate_no") not in valid_nos:
                continue
            graded["valid_candidates"] += 1
            no = cand.get("candidate_no")
            try:
                result = candidate_texts(cand_texts, cand)
            except PatchError as exc:
                reasons.append(f"후보 {no} 의 patch 를 적용할 수 없습니다: {exc}")
                continue
            reverted = reverted_files(base_texts, result, owner_files)
            matched = next((fix for fix, texts in known if _same_texts(texts, result)), None)
            if reverted and not answer["revert_allowed"]:
                graded["revert_proposals"] += 1
                reasons.append(f"되돌리기를 합격안으로 냈습니다(후보 {no}, {', '.join(reverted)})")
            elif matched is not None and not matched.get("valid"):
                why = matched.get("policy") or "정답표 불합격"
                reasons.append(
                    f"정답표가 불합격으로 정한 '{matched['name']}'을 합격안으로 냈습니다"
                    f"(후보 {no}, {why})"
                )
            elif matched is None and not reverted and answer.get("fixable") is False:
                conflicts.append(
                    f"고칠 수 없다고 정한 케이스에 새 합격안(후보 {no})이 나왔습니다. "
                    "재검증이 필요합니다(R5)"
                )
            else:
                graded["presented_fix"] = True
    verdict = "FAIL" if reasons else ("CONFLICT" if conflicts else "PASS")
    return {**graded, "verdict": verdict, "reasons": reasons + conflicts}


# ── 지표 ─────────────────────────────────────────────────────────────────────


def _rate(num: int, den: int) -> dict:
    """분자와 분모를 함께 남긴 비율이다. 분모가 0 이면 값은 None 이다."""
    return {"value": (num / den) if den else None, "num": num, "den": den}


def summarize(results: list[dict]) -> dict:
    """eval 결과 목록으로 QA 문서 3장의 지표를 계산한다. 값마다 분자와 분모를 함께 남긴다.

    results 의 원소는 {case, grade, seconds} 다. 문제없는 수정(정답이 VALIDATED 하나)은 오탐률의
    분모가 되고, 나머지 자동 케이스는 탐지율의 분모가 된다. 원인 특정은 진단까지 가지 않으면 실패로
    센다(QA 문서 7장 3번). '유효한 안 없음' 정답률은 정답 결론이 EVALUATED 하나인 고칠 수 없는
    케이스에서, 진단을 끝까지 하고 no_valid_fix 로 끝낸 경우만 정답으로 센다. 숨긴 주문이 있어야
    재는 거짓 합격률은 여기서 계산하지 않는다.
    """

    def answer(r):
        return r["case"]["answer"]

    def g(r):
        return r["grade"]

    graded = [r for r in results if not answer(r).get("save_rejected")]
    clean = [r for r in graded if answer(r).get("status") == ["VALIDATED"]]
    breaking = [r for r in graded if answer(r).get("status") != ["VALIDATED"]]
    rooted = [r for r in graded if answer(r).get("root_cause") or answer(r).get("root_cause_all")]
    fixable = [r for r in graded if answer(r).get("fixable") is True]
    unfixable = [
        r
        for r in graded
        if answer(r).get("fixable") is False and answer(r).get("status") == ["EVALUATED"]
    ]
    seconds = sorted(r["seconds"] for r in results if r.get("seconds") is not None)
    return {
        "pass_rate": _rate(sum(g(r)["verdict"] == "PASS" for r in results), len(results)),
        "detection": _rate(sum(bool(g(r).get("detected")) for r in breaking), len(breaking)),
        "false_alarm": _rate(sum(g(r).get("status") != "VALIDATED" for r in clean), len(clean)),
        "root_cause": _rate(sum(g(r).get("root_cause_ok") is True for r in rooted), len(rooted)),
        "fix_presented": _rate(sum(bool(g(r).get("presented_fix")) for r in fixable), len(fixable)),
        "no_fix_correct": _rate(
            sum(
                g(r).get("status") == "EVALUATED" and g(r).get("proposal_status") == "no_valid_fix"
                for r in unfixable
            ),
            len(unfixable),
        ),
        "revert_rate": _rate(
            sum(g(r).get("revert_proposals", 0) for r in results),
            sum(g(r).get("valid_candidates", 0) for r in results),
        ),
        "completion": _rate(
            sum(g(r).get("status") not in ("ERROR", "TIMEOUT") for r in graded), len(graded)
        ),
        "seconds_p50": statistics.median(seconds) if seconds else None,
        "seconds_p95": seconds[min(len(seconds) - 1, int(len(seconds) * 0.95))]
        if seconds
        else None,
    }
