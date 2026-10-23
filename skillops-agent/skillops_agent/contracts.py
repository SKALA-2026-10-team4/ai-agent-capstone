"""C(service) MCP 도구와 주문 Agent HTTP 조회의 계약을 검사하고 변환하는 도우미 모음이다.

LLM 은 제안만 하고 판정은 C 실행 결과로만 하므로, B 가 받은 응답이 요청한 snapshot, workspace,
시나리오의 것인지 하나씩 대조하고 어긋나면 ContractError 로 진단을 멈춘다. 잘못된 증거로 원인을
확정하거나 후보를 합격시키지 않으려는 것이다. 이 모듈은 네트워크를 부르지 않는 순수 함수만 둔다.

함수는 다음 묶음으로 나뉜다.
- 실행 기록을 읽는 함수는 execution_uuid, event_data, error_event, context_keys, tools_called 다.
- 의도 확인 조건은 두 가지로 다룬다. check_problem 은 보내기 전에 조건을 검사하고,
  CHECK_FAILURE_PREFIX 는 실행 결과의 실패 사유에서 확인 조건 실패를 알아본다.
- snapshot 과 diff 를 검사하는 함수는 file_hashes, skill_file_path, skill_of_file,
  check_manifest, check_skill_listing, check_diff_evidence 다.
- run_scenarios 결과를 다루는 함수는 validate_report, replay_scenario_id, run_ids_of,
  failures_of, all_passed 다.
- patch 를 만드는 함수는 reverse_hunk(원인 실험)와 unified_patch(수정 후보)다.
- patch_contract_ok는 patch 응답의 입력·선행 단계 오류 목록을 검사한다.

skillops-agent/skillops_agent/nodes.py 가 대부분을 쓴다. skillops-agent/skillops_agent/tools.py 와
skillops-agent/skillops_agent/stub_tools.py 는 ContractError 와 execution_uuid 를,
skillops-agent/skillops_agent/main.py 는 execution_uuid 를 쓴다. 오류 문구는 진단 기록의 error 에
그대로 남고 skillops-agent/tests/test_flow_characterization.py 와
skillops-agent/tests/test_contract_helpers.py 가 글자 그대로 단언한다.
"""

from __future__ import annotations

import difflib
import re
from uuid import UUID


class ContractError(ValueError):
    """C 나 주문 Agent 응답이 계약과 다르거나 진단의 전제가 깨졌을 때 던지는 오류다.

    skillops-agent/skillops_agent/graph.py 의 guarded 가 잡아 'ContractError: <메시지>' 를 error 로
    남기고 진단을 ERROR 로 끝낸다. skillops-agent/skillops_agent/tools.py 의 MCPToolError 가 이
    클래스를 상속하므로 MCP 도구 오류도 같은 길로 간다. ValueError 를 상속하므로
    skillops-agent/skillops_agent/main.py 의 요청 검증 안에서 던지면 Pydantic 이 422 응답으로
    바꾼다.
    """

    pass


def execution_uuid(value: str) -> str:
    """실행 ID 를 하이픈이 있는 UUID 글로 맞춘다.

    실행 별칭 'r_' + UUID hex 도 받아 r_ 를 떼고 읽는다. UUID 로 읽을 수 없으면 ContractError 를
    던진다. skillops-agent/skillops_agent/nodes.py 의 _fix_snapshots 는 snapshot ID 가 UUID 인지
    볼 때도 이 함수를 쓰므로 그때도 오류 문구는 execution_id 를 말한다.
    nodes.py 말고도 skillops-agent/skillops_agent/tools.py 의 get_trace,
    skillops-agent/skillops_agent/stub_tools.py, skillops-agent/skillops_agent/main.py 의
    DiagnoseRequest 검증, skillops-agent/scripts/c2_real_judge.py 가 부른다.
    """
    try:
        raw = value[2:] if value.startswith("r_") else value
        return str(UUID(raw))
    except (ValueError, AttributeError, TypeError) as exc:
        raise ContractError("execution_id must be a UUID or r_ + UUID hex") from exc


def event_data(event: dict) -> dict:
    """trace 이벤트의 data 객체를 꺼낸다.

    data 가 dict 가 아니면 ContractError 를 던져 모양이 다른 기록으로 판정하지 않게 한다.
    context_keys, tools_called 와 skillops-agent/skillops_agent/nodes.py 의 _reproduce_failure,
    _failure_of 가 부른다.
    """
    value = event.get("data")
    if not isinstance(value, dict):
        raise ContractError("trace event.data must be an object")
    return value


def error_event(trace: dict) -> dict:
    """실행 기록의 첫 EXECUTION_ERROR 이벤트다. 없으면 None 이 아니라 빈 dict 를 돌려준다.

    빈 dict 라서 부르는 쪽이 .get 으로 바로 읽을 수 있다. event_data 에 넘기면 data 가 없어
    ContractError 가 난다. skillops-agent/skillops_agent/nodes.py 의 _reproduce_failure(오류
    category)와 _failure_of(제안서의 failure)가 부른다.
    """
    return next((e for e in trace.get("events", []) if e.get("kind") == "EXECUTION_ERROR"), {})


def context_keys(trace: dict) -> list[str]:
    """실행 기록에서 단계가 받은 값과 내놓은 값의 이름을 모아 정렬한다.

    SKILL_STARTED 의 data.input 과 SKILL_FINISHED 의 data.output 의 키를 모은다. C 가 확인 조건을
    볼 때 쓰는 문맥과 같은 범위다
    (service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 의 contextOf).
    C 는 문맥에 없는 값 이름을 null 로 읽으므로 이 목록에 없는 이름을 보는 조건은 check_problem 이
    버린다. 모든 이벤트의 data 를 event_data 로 읽으므로 data 가 객체가 아닌 이벤트가 하나라도
    있으면 ContractError 가 난다. skillops-agent/skillops_agent/nodes.py 의 _known_values 가 부른다.
    """
    keys = set()
    for event in trace.get("events", []):
        data = event_data(event)
        if event.get("kind") == "SKILL_STARTED":
            keys.update((data.get("input") or {}).keys())
        elif event.get("kind") == "SKILL_FINISHED":
            keys.update((data.get("output") or {}).keys())
    return sorted(keys)


# C 의 service/src/main/java/com/capstone4/skillops/scenario/Check.java 와 같은 문법이다.
# 한 줄은 '<값 이름> (==|!=|in) <값>' 모양이다. 값은 '글자', true, false, null, 숫자이고, in 뒤에만
# [..] 목록이 온다. 두 정규식은 Check.java 의 LINE, NUMBER 와 같다. C 쪽 문법을 바꾸면 이 두
# 정규식과 _is_literal, _split_outside_quotes 를 함께 고친다.
_CHECK_LINE = re.compile(r"^\s*(\S+)\s+(==|!=|in)\s+(.+?)\s*$")
_NUMBER = re.compile(r"-?\d+(\.\d+)?")


def _is_literal(raw: str) -> bool:
    """값 하나가 C 가 읽는 리터럴('글자', true, false, null, 숫자)인지 본다.

    service/src/main/java/com/capstone4/skillops/scenario/Check.java 의 literal 과 같은 규칙이다.
    check_problem 이 부른다.
    """
    value = raw.strip()
    if len(value) >= 2 and value.startswith("'") and value.endswith("'"):
        return True
    return value in ("true", "false", "null") or bool(_NUMBER.fullmatch(value))


def _split_outside_quotes(inner: str) -> list[str]:
    """in 목록의 안쪽 글을 따옴표 밖의 쉼표로 나눈다.

    'A, B' 처럼 따옴표 안의 쉼표는 나누지 않고, 빈 글이면 빈 목록을 돌려준다.
    service/src/main/java/com/capstone4/skillops/scenario/Check.java 의 splitOutsideQuotes 와 같은
    규칙이다. check_problem 이 부른다.
    """
    if not inner.strip():
        return []
    parts, current, quoted = [], "", False
    for char in inner:
        if char == "'":
            quoted = not quoted
        if char == "," and not quoted:
            parts.append(current)
            current = ""
        else:
            current += char
    parts.append(current)
    return parts


def check_problem(check: str, known_keys: set[str]) -> str | None:
    """C 가 읽을 수 있고 실행 기록에 있는 값을 보는 조건이면 None, 아니면 버리는 이유다.

    C 는 읽지 못하는 조건이 하나라도 있으면 run_scenarios 요청 전체를 거절하므로, 확인 조건을 보내기
    전에 한 줄씩 검사한다. 문법을 먼저 보고 값 이름(known_keys)을 나중에 본다. 이유 문구는 제안서의
    dropped_checks 에 남아 운영자가 읽는다. skillops-agent/skillops_agent/nodes.py 의 _split_checks
    가 부르고 skillops-agent/tests/test_checks.py 가 규칙을 고정한다.
    """
    match = _CHECK_LINE.match(check) if isinstance(check, str) else None
    if not match:
        return "형식이 '<값 이름> (==|!=|in) <값>' 이 아닙니다"
    key, op, raw = match.groups()
    if op == "in":
        if not (raw.startswith("[") and raw.endswith("]")):
            return "in 뒤에는 [..] 목록이 와야 합니다"
        if not all(_is_literal(part) for part in _split_outside_quotes(raw[1:-1])):
            return "목록 안의 값을 읽을 수 없습니다"
    elif not _is_literal(raw):
        return "값을 읽을 수 없습니다('글자', true, false, null, 숫자만 됩니다)"
    if key not in known_keys:
        return "실행 기록에 없는 값 이름입니다"
    return None


# C 의 service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 가 확인 조건 실패
# 사유 앞에 붙이는 문구와 글자 그대로 같아야 한다. skillops-agent/skillops_agent/nodes.py 의
# _evaluate_intent_checks 가 이 문구가 든 실패만 의도 위반의 근거로 본다.
CHECK_FAILURE_PREFIX = "확인 조건 실패:"


def tools_called(trace: dict) -> list[str]:
    """실행 기록의 TOOL_REQUEST 이벤트에서 도구 이름(data.tool)을 순서대로 모은다.

    tool 칸이 없는 요청은 건너뛴다. skillops-agent/skillops_agent/nodes.py 의 _verify_candidate 가
    재현 실행마다 불러 후보 결과의 tools_called(PB-007)를 채운다.
    """
    return [
        event_data(e)["tool"]
        for e in trace.get("events", [])
        if e.get("kind") == "TOOL_REQUEST" and "tool" in event_data(e)
    ]


def file_hashes(manifest: dict) -> dict[str, str]:
    """snapshot manifest 의 파일 목록을 {경로: 해시} 로 바꾼다.

    skillops-agent/skillops_agent/nodes.py 의 _collect_changes 가 두 snapshot 의 실제 차이를 찾고,
    _preflight 가 재현 실행의 snapshot 이 후보 파일과 같은지 볼 때 쓴다.
    """
    return {f["path"]: f["hash"] for f in manifest["files"]}


def skill_file_path(skill: str) -> str:
    """snapshot 안에서 skill 원문이 있는 경로(skills/<skill>/SKILL.md)를 만든다.

    unified_patch 가 patch 머리줄에 쓰고, skill_of_file 이 이 모양을 거꾸로 읽는다.
    """
    return f"skills/{skill}/SKILL.md"


def skill_of_file(path: str) -> str | None:
    """skills/<skill>/SKILL.md 모양이면 skill 이름(빈 문자열도 그대로), 아니면 None 이다.

    skills//SKILL.md 처럼 이름이 비어도 모양이 맞으면 빈 글을 돌려준다.
    skillops-agent/skillops_agent/nodes.py 의 _collect_changes 가 resources 같은 skill 밖 파일을
    가려낼 때 쓴다.
    """
    parts = path.split("/")
    if len(parts) != 3 or parts[0] != "skills" or parts[2] != "SKILL.md":
        return None
    return parts[1]


def check_manifest(manifest: dict, snapshot_id: str, side: str) -> dict:
    """get_snapshot 응답이 요청한 snapshot 이고 해시가 있는지 확인한다.

    맞으면 manifest 를 그대로 돌려주고, 아니면 'invalid <side> snapshot manifest' ContractError 를
    던진다. side 는 오류 문구에 넣을 이름(candidate, baseline)이다.
    skillops-agent/skillops_agent/nodes.py 의 _fix_snapshots 가 부른다.
    """
    if manifest.get("snapshot_id") != snapshot_id or not manifest.get("hash"):
        raise ContractError(f"invalid {side} snapshot manifest")
    return manifest


def check_skill_listing(listing: dict, snapshot_id: str, snapshot_hash: str) -> dict[str, int]:
    """list_skills 응답이 같은 snapshot 의 완전한 목록인지 보고 {skill_id: version} 을 만든다.

    snapshot ID 와 해시가 모두 같아야 하고, partial 이거나 parse_errors 가 있으면 일부 skill 이 빠진
    목록이라 ContractError 를 던진다. 결과는 응답 순서를 지키며 상태의 skill_versions 가 된다.
    skillops-agent/skillops_agent/nodes.py 의 fetch_evidence 가 부른다.
    """
    if listing.get("snapshot_id") != snapshot_id or listing.get("snapshot_hash") != snapshot_hash:
        raise ContractError("skill list and snapshot differ")
    if listing.get("partial") or listing.get("parse_errors"):
        raise ContractError("candidate skill metadata is incomplete")
    return {item["skill_id"]: item["version"] for item in listing["items"]}


def patch_contract_ok(applied: dict) -> bool:
    """필수 오류 목록을 검증하고 입력 공급과 선행 단계 위반이 모두 없을 때만 True다."""
    for field, missing_key in (
        ("broken_links", "missing_input"),
        ("dependency_violations", "missing_dependency"),
    ):
        values = applied.get(field)
        if not isinstance(values, list):
            raise ContractError("invalid apply_patch response")
        for item in values:
            if not isinstance(item, dict) or any(
                not isinstance(item.get(key), str) or not item[key].strip()
                for key in ("skill", missing_key)
            ):
                raise ContractError("invalid apply_patch response")
    return not applied["broken_links"] and not applied["dependency_violations"]


def patch_metadata_ok(applied: dict) -> bool:
    """필수 정보 오류 목록을 검증하며 누락된 서버 검사 결과는 계약 오류로 처리한다."""
    errors = applied.get("metadata_errors")
    if not isinstance(errors, list):
        raise ContractError("invalid apply_patch response")
    for item in errors:
        if (
            not isinstance(item, dict)
            or any(
                not isinstance(item.get(key), str) or not item[key].strip()
                for key in ("skill", "field", "code", "message")
            )
            or item["code"] not in ("INVALID_SKILL_NAME", "INVALID_SKILL_FORMAT")
            or item["field"]
            not in ("name", "version", "owner", "description", "body", "frontmatter")
        ):
            raise ContractError("invalid apply_patch response")
    return not errors


def patch_flow_ok(applied: dict) -> bool:
    """flow 구조 검사 목록과 코드·사유 조합을 검증하며 누락된 검사 결과를 거부한다."""
    codes = {
        "EMPTY_STEPS": "INVALID_FLOW",
        "TOO_MANY_STEPS": "INVALID_FLOW",
        "DUPLICATE_STEP": "INVALID_FLOW",
        "INCOMPLETE_ANALYSIS": "INVALID_FLOW",
        "UNKNOWN_SKILL": "UNKNOWN_SKILL",
        "INVALID_SKILL_NAME": "INVALID_SKILL_NAME",
        "UNREADABLE_SKILL": "INVALID_SKILL_FORMAT",
        "NESTED_FLOW_NOT_SUPPORTED": "CONTRACT_VIOLATION",
    }
    errors = applied.get("flow_errors")
    if not isinstance(errors, list):
        raise ContractError("invalid apply_patch response")
    for item in errors:
        if (
            not isinstance(item, dict)
            or any(
                not isinstance(item.get(key), str) or not item[key].strip()
                for key in ("skill", "code", "reason", "message")
            )
            or codes.get(item["reason"]) != item["code"]
        ):
            raise ContractError("invalid apply_patch response")
    return not errors


def check_diff_evidence(diff: dict, before: dict, after: dict) -> list[dict]:
    """diff_skill 응답이 before·after 두 파일을 비교했고 hunk 가 온전한지 보고 hunk 를 준다.

    before 와 after 마다 snapshot_id, file_hash, snapshot_hash 가 고정한 값과 같아야 하고, hunk 마다
    hunk_id 와 diff 가 있어야 한다. 바뀐 파일인데 hunk 가 없어도 ContractError 다. before 나 after
    칸이 객체가 아니면(None) AttributeError 가 난다.
    skillops-agent/skillops_agent/nodes.py 의 _collect_changes 가 부른다.
    """
    for key, expected in (("before", before), ("after", after)):
        side = diff.get(key, {})
        if (
            side.get("snapshot_id") != expected["snapshot_id"]
            or side.get("file_hash") != expected["file_hash"]
            or side.get("snapshot_hash") != expected["snapshot_hash"]
        ):
            raise ContractError("diff evidence does not match the fixed snapshots")
    if not isinstance(diff.get("hunks"), list) or not diff["hunks"]:
        raise ContractError("changed file returned no C hunks")
    for hunk in diff["hunks"]:
        if not hunk.get("hunk_id") or not hunk.get("diff"):
            raise ContractError("C hunk identity/patch missing")
    return diff["hunks"]


def validate_report(
    report: dict, workspace_id: str, scenario_ids: list[str], repeat: int
) -> list[dict]:
    """run_scenarios 응답이 요청과 맞는지 모두 검사하고 시나리오별 결과 목록을 돌려준다.

    workspace_id 가 같은지, 요청한 시나리오가 같은 순서로 빠짐없이 왔는지, 시나리오마다 실행 ID 가
    repeat 개이고 서로 다른 UUID 인지, passed 와 failures 가 맞는지, 실패가 실제로 돈 실행을
    가리키는지, all_passed 가 결과와 맞는지를 본다. 하나라도 어긋나면 ContractError 를 던져, 결과가
    비거나 모자란 응답을 통과로 읽지 않게 한다. skillops-agent/skillops_agent/nodes.py 의
    _run_scenarios 가 부르고 skillops-agent/tests/test_scenarios.py 가 경계를 고정한다.
    """
    if not isinstance(report, dict) or report.get("workspace_id") != workspace_id:
        raise ContractError("run_scenarios workspace_id mismatch")
    results = report.get("results")
    if not isinstance(results, list) or len(results) != len(scenario_ids):
        raise ContractError("run_scenarios did not return every planned scenario")
    for expected, result in zip(scenario_ids, results, strict=True):
        if result.get("scenario_id") != expected or type(result.get("passed")) is not bool:
            raise ContractError("run_scenarios scenario_id/passed mismatch")
        ids = result.get("run_ids")
        if not isinstance(ids, list) or len(ids) != repeat or len(set(ids)) != repeat:
            raise ContractError("run_scenarios missing or duplicate trial evidence")
        for run_id in ids:
            execution_uuid(run_id)
        failures = result.get("failures")
        if not isinstance(failures, list) or result["passed"] != (not failures):
            raise ContractError("run_scenarios failures/passed mismatch")
        if any(not isinstance(f, dict) or f.get("run_id") not in ids for f in failures):
            raise ContractError("failure does not reference an executed trial")
    if type(report.get("all_passed")) is not bool or report["all_passed"] != all(
        r["passed"] for r in results
    ):
        raise ContractError("run_scenarios all_passed mismatch")
    return results


def replay_scenario_id(execution_id: str) -> str:
    """실제 실행 하나를 다시 돌리는 C 시나리오 ID(f-<execution UUID>)를 만든다.

    service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 는 REPLAY_PREFIX 'f-'
    뒤가 하이픈 있는 UUID 인 ID 만 재현 주문으로 읽으므로, execution_id 는 execution_uuid 로 맞춘
    값이어야 한다. skillops-agent/skillops_agent/nodes.py 의 ablate, verify_candidates,
    _evaluate_intent_checks 가 부른다.
    """
    return f"f-{execution_id}"


def run_ids_of(results: list[dict]) -> list[str]:
    """run_scenarios 결과의 실행 ID 를 시나리오 순서대로 펼친다.

    skillops-agent/skillops_agent/nodes.py 가 이 값을 근거 실행(evidence_run_ids,
    preflight_run_ids, proof_run_ids)으로 남긴다.
    """
    return [run_id for result in results for run_id in result["run_ids"]]


def failures_of(results: list[dict]) -> list[dict]:
    """run_scenarios 결과의 실패를 시나리오 순서대로 펼친다.

    skillops-agent/skillops_agent/nodes.py 의 사전 검증과 diagnosis_only 진단이 diagnosis.failures
    로 남긴다.
    """
    return [failure for result in results for failure in result["failures"]]


def all_passed(results: list[dict]) -> bool:
    """모든 시나리오가 통과했는지 본다. 빈 목록이면 True 다.

    의도 조건이 없어 의도 실행을 건너뛴 후보는 이 규칙으로 keeps_intent 가 참이 된다. 회귀 세트가
    비어 공허하게 통과하는 일은 skillops-agent/skillops_agent/nodes.py 의 _run_scenarios 가 미리
    막는다.
    """
    return all(result["passed"] for result in results)


def reverse_hunk(diff: str) -> str:
    """C 가 준 단독 unified hunk 하나를 문맥과 줄 위치를 지키며 뒤집는다.

    원인 실험(ablation)에서 그 변경 하나만 되돌리는 patch 를 만들려는 것이다. '@@ -a +b @@' 의 앞뒤
    범위를 맞바꾸고 '+' 줄과 '-' 줄을 서로 바꾸며, 문맥 줄(공백으로 시작)과 역슬래시로 시작하는 줄은
    그대로 둔다. '--- ', '+++ ' 머리줄은 버린다. C 의
    service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java 는 머리줄 없는 hunk 를
    받는다. hunk 가 정확히 하나가 아니거나 읽을 수 없는 줄이 있으면 ContractError 다.
    skillops-agent/skillops_agent/nodes.py 의 _ablate_change 가 부른다.
    """
    output = []
    headers = 0
    for line in diff.splitlines():
        if line.startswith(("--- ", "+++ ")):
            continue  # C PatchApplier 는 머리줄 없는 hunk 를 받는다.
        match = re.fullmatch(r"@@ -(\d+(?:,\d+)?) \+(\d+(?:,\d+)?) @@(.*)", line)
        if match:
            headers += 1
            output.append(f"@@ -{match[2]} +{match[1]} @@{match[3]}")
        elif line.startswith("+"):
            output.append("-" + line[1:])
        elif line.startswith("-"):
            output.append("+" + line[1:])
        elif line.startswith((" ", "\\")):
            output.append(line)
        else:
            raise ContractError("invalid unified hunk line")
    if headers != 1:
        raise ContractError("ablation requires one standalone C hunk")
    return "\n".join(output) + "\n"


def unified_patch(skill: str, before_text: str, after_text: str) -> str:
    """현재 원문 대비 수정 후 원문의 unified diff(a/·b/ 헤더)를 만든다. 차이가 없으면 빈 문자열이다.

    머리줄은 a/skills/<skill>/SKILL.md 와 b/skills/<skill>/SKILL.md 이고 끝에 줄바꿈을 붙인다. 줄
    단위(splitlines)로 비교하므로 마지막 줄바꿈만 다른 두 글은 차이가 없다고 보고 빈 글을 돌려준다.
    LLM 이 낸 전체 원문으로 B 가 고정 snapshot 원문 기준 patch 를 직접 만든다.
    skillops-agent/skillops_agent/nodes.py 의 _patches_from_spec 이 부른다.
    """
    path = skill_file_path(skill)
    patch = "\n".join(
        difflib.unified_diff(
            before_text.splitlines(),
            after_text.splitlines(),
            fromfile=f"a/{path}",
            tofile=f"b/{path}",
            lineterm="",
        )
    )
    return patch + "\n" if patch else ""
