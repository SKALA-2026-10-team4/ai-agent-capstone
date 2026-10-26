package com.capstone4.skillops.proposal;

import static com.capstone4.skillops.proposal.ProposalRules.integer;
import static com.capstone4.skillops.proposal.ProposalRules.map;
import static com.capstone4.skillops.proposal.ProposalRules.maps;
import static com.capstone4.skillops.proposal.ProposalRules.str;
import static com.capstone4.skillops.proposal.ProposalRules.strings;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.capstone4.skillops.scenario.RunLedger;

/**
 * save_proposal 의 거부 규칙을 실행 순서대로 모은 검사기다. 규칙을 검사하는 순서가 곧 여러 규칙을 어긴 제안서에 어느 메시지가 나갈지를 정한다.
 * 순서는 다음과 같다. status 값과 snapshot_id 를 먼저 보고, 후보마다 차례로 번호와 근거 실행을 본 뒤, 개발 요청서
 * (development_request)가 있으면 그 형식과 근거 실행을 본다(checkSubmission). 그다음 재판정
 * (service/src/main/java/com/capstone4/skillops/proposal/ProposalJudge.java)이 판정을 다시 계산하고, 서버가 정한 status 가
 * proposed 이면 개발 요청서가 없는지(checkNoDevelopmentRequest), 원인 근거 실행이 있는지(checkRootCause)를 이 순서로 본다.
 * Agent 의 valid, status, recommended_candidate_no 가 서버 판정과 다른 것은 더 이상 거부하지 않는다. 재판정이 서버 값으로 덮고
 * server_overrides 에 남긴다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.7 절).
 * 개발 요청서는 SKILL.md 수정만으로 고칠 수 없다는 뜻이라 그 제안서의 status 는 no_valid_fix 여야 한다(같은 문서 2.11 절). Agent 가
 * 개발 요청서와 함께 proposed 를 보내도 거부하지 않는다. status 는 다른 판정 칸과 같이 재판정이 합격 후보 수로 정하고, 다르면 서버 값으로
 * 덮어 server_overrides 에 남긴다. 다만 서버가 합격 후보를 찾아 proposed 로 정했는데 개발 요청서가 있으면 두 판단이 서로 어긋나 어느 쪽을
 * 믿을지 정할 수 없으므로 거부한다. 그래서 저장된 제안서에 개발 요청서가 있으면 status 는 늘 no_valid_fix 다.
 * 근거 실행은 service/src/main/java/com/capstone4/skillops/scenario/RunLedger.java 장부에서 이 서비스가 run_scenarios 로 실제로 돌린
 * 실행인지 확인한다. service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 가 생성자에서 이 검사기를 만들고,
 * save 가 부른다. 거부는 모두 IllegalArgumentException 이고 메시지는 Agent 개발자가 읽는다.
 * service/src/test/java/com/capstone4/skillops/proposal/ProposalCharacterizationTest.java 가 메시지 전체와 우선순위를 고정하므로,
 * 규칙을 더하거나 순서를 바꾸면 그 테스트도 고친다.
 */
final class ProposalValidator {

    /** 제안서에서 개발 요청서를 담는 칸 이름이다. ProposalDocumentWriter 도 이 이름으로 읽는다. */
    static final String DEVELOPMENT_REQUEST = "development_request";
    /** 개발 요청서 종류(kind)로 받는 유일한 값이다. 업무 도구를 새로 만들거나 넓혀야 한다는 뜻이다. */
    static final String NEEDS_TOOL = "needs_tool";
    /** 빈틈(gaps[].field)이 나온 skill frontmatter 칸이다. */
    static final Set<String> GAP_FIELDS = Set.of("inputs", "rules", "outputs");
    /** 빈틈 종류(gaps[].kind)다. missing_value 는 어떤 도구도 만들지 않는 값, tool_output_missing 은 도구가 내놓지 않는 출력이다. */
    static final Set<String> GAP_KINDS = Set.of("missing_value", "tool_output_missing");
    /** 방법(options[].type)이다. extend_tool 은 있는 도구 넓히기, new_tool 은 새 도구 만들기다. */
    static final Set<String> OPTION_TYPES = Set.of("extend_tool", "new_tool");

    private final RunLedger ledger;

    /** 근거 실행을 대조할 장부를 받는다. ProposalService 의 생성자가 부른다. */
    ProposalValidator(RunLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * 재판정 전에 거부할 규칙을 순서대로 검사한다. status 값, snapshot_id, 후보마다 번호와(적용한 후보면) 근거 실행, 개발 요청서 순서다.
     * 잘못된 제안서는 IllegalArgumentException 으로 거부한다. 규칙의 실행 순서를 바꾸려면 이 메서드의 호출 순서를 고친다.
     */
    void checkSubmission(String status, Map<String, Object> proposal) {
        requireSavableStatus(status);
        requireSnapshotId(proposal);
        List<Map<String, Object>> candidates = maps(proposal.get("candidates"));
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> candidate = candidates.get(i);
            int no = requireCandidateNo(candidate, i + 1);
            if (ProposalRules.applied(candidate)) {
                checkEvidence(no, candidate);
            }
        }
        checkDevelopmentRequest(proposal.get(DEVELOPMENT_REQUEST));
    }

    /** status 가 proposed 나 no_valid_fix 인지 확인한다. */
    private static void requireSavableStatus(String status) {
        if (!ProposalStatus.isSavable(status)) {
            throw new IllegalArgumentException("status 는 proposed 나 no_valid_fix 여야 합니다 (받은 값 " + status + ")");
        }
    }

    /**
     * 진단한 snapshot 번호(snapshot_id)가 있는지 확인한다. 재판정은 이 snapshot 의 metadata 에서 담당자 의도와 기준본을 읽고, 후보
     * 연습 공간이 이 snapshot 에서 시작했는지 보므로 없으면 판정할 수 없다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.10 절
     * V3-1). 칸을 비워 의도 검사와 근거 대조를 피하는 것을 막는다.
     */
    private static void requireSnapshotId(Map<String, Object> proposal) {
        if (str(proposal.get("snapshot_id")) == null) {
            throw new IllegalArgumentException("snapshot_id 가 없습니다");
        }
    }

    /**
     * candidate_no 가 목록 순서와 같은 번호(1부터 빠짐없이)인지 확인하고, 맞으면 그 번호를 돌려준다.
     * 승인 요청과 화면이 이 번호로 후보를 가리키므로 번호가 목록 위치와 어긋나지 않게 한다.
     */
    private static int requireCandidateNo(Map<String, Object> candidate, int expected) {
        Integer no = integer(candidate.get("candidate_no"));
        if (no == null || no != expected) {
            throw new IllegalArgumentException(
                    "candidate_no 는 1부터 빠짐없이 매겨야 합니다 (" + expected + "번째 후보의 값 " + no + ")");
        }
        return no;
    }

    /**
     * 원문에 적용한 후보의 근거를 확인한다. workspace_id, results, evidence_run_ids 를 이 순서로 보고 하나라도 없으면 거부한다.
     * 근거 실행은 모두 그 후보의 연습 공간에서 돌린 실행이어야 하므로, 다른 연습 공간의 실행이나 돌린 적 없는 번호를 적으면 거부한다.
     */
    private void checkEvidence(int no, Map<String, Object> candidate) {
        String workspaceId = str(candidate.get("workspace_id"));
        if (workspaceId == null) {
            throw new IllegalArgumentException("후보 " + no + " 의 workspace_id 가 없습니다");
        }
        if (map(candidate.get("results")) == null) {
            throw new IllegalArgumentException("후보 " + no + " 의 results 가 없습니다");
        }
        List<String> evidence = strings(candidate.get("evidence_run_ids"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("후보 " + no + " 의 evidence_run_ids 가 비어 있습니다");
        }
        Set<String> ran = ledger.runIdsOf(workspaceId);
        for (String runId : evidence) {
            if (!ran.contains(runId)) {
                throw new IllegalArgumentException(
                        "후보 " + no + " 의 근거 실행 " + runId + " 을 연습 공간 " + workspaceId + " 에서 찾을 수 없습니다");
            }
        }
    }

    /**
     * 개발 요청서(development_request)의 형식과 근거 실행을 확인한다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11 절,
     * docs/contracts/proposal.schema.json). 칸이 없거나 null 이면 보지 않는다. 객체가 아니면 거부한다.
     * kind, title, gaps, options, interim, interim.proof_run_ids, evidence_run_ids 순서로 보고 처음 어긋난 것으로 거부한다.
     * kind 는 needs_tool, title 은 비지 않은 글, gaps 는 1개 이상이고 항목마다 key 와 needed_by 가 있고 field 와 kind 가 정해진 값,
     * options 는 목록(비어도 된다)이고 항목마다 type 이 정해진 값, interim 은 없거나 객체여야 한다.
     * interim.proof_run_ids 와 evidence_run_ids 의 실행은 어느 연습 공간에서든 이 서비스가 run_scenarios 로 돌린 실행이어야 한다.
     * 임시 대안의 근거는 원인 실험(변경을 되돌린 연습 공간)에서 나오고 제안서에 그 연습 공간 번호가 없으므로 checkRootCause 처럼
     * 연습 공간을 가리지 않고 찾는다. 방법(options)의 다른 칸과 change_points 는 본문에 옮겨 적기만 하므로 보지 않는다.
     */
    private void checkDevelopmentRequest(Object value) {
        if (value == null) {
            return;
        }
        Map<String, Object> request = map(value);
        if (request == null) {
            throw new IllegalArgumentException("development_request 가 객체가 아닙니다");
        }
        if (!NEEDS_TOOL.equals(request.get("kind"))) {
            throw new IllegalArgumentException(
                    "development_request.kind 는 needs_tool 이어야 합니다 (받은 값 " + request.get("kind") + ")");
        }
        if (str(request.get("title")) == null) {
            throw new IllegalArgumentException("development_request.title 이 없습니다");
        }
        checkGaps(ProposalRules.list(request.get("gaps")));
        checkOptions(request.get("options"));
        Object interimValue = request.get("interim");
        Map<String, Object> interim = map(interimValue);
        if (interimValue != null && interim == null) {
            throw new IllegalArgumentException("development_request.interim 이 객체가 아닙니다");
        }
        if (interim != null) {
            requireKnownRuns("development_request.interim.proof_run_ids", strings(interim.get("proof_run_ids")));
        }
        requireKnownRuns("development_request.evidence_run_ids", strings(request.get("evidence_run_ids")));
    }

    /**
     * 빈틈 목록을 확인한다. 1개 이상이어야 하고, 항목마다 key, needed_by, field, kind 순서로 본다. 객체가 아닌 항목은 key 가 없는
     * 항목으로 보고 거부한다. 번호는 목록 순서로 1부터 센다.
     */
    private static void checkGaps(List<Object> gaps) {
        if (gaps.isEmpty()) {
            throw new IllegalArgumentException("development_request.gaps 가 비어 있습니다");
        }
        for (int i = 0; i < gaps.size(); i++) {
            Map<String, Object> gap = map(gaps.get(i));
            Map<String, Object> fields = gap == null ? Map.of() : gap;
            String where = "development_request.gaps 의 " + (i + 1) + "번째 항목";
            for (String required : List.of("key", "needed_by")) {
                if (str(fields.get(required)) == null) {
                    throw new IllegalArgumentException(where + "에 " + required + " 가 없습니다");
                }
            }
            if (!GAP_FIELDS.contains(fields.get("field"))) {
                throw new IllegalArgumentException(
                        where + "의 field 는 inputs, rules, outputs 가운데 하나여야 합니다 (받은 값 " + fields.get("field") + ")");
            }
            if (!GAP_KINDS.contains(fields.get("kind"))) {
                throw new IllegalArgumentException(
                        where + "의 kind 는 missing_value 나 tool_output_missing 이어야 합니다 (받은 값 " + fields.get("kind") + ")");
            }
        }
    }

    /** 방법 목록을 확인한다. 목록이어야 하고(비어도 된다), 항목마다 type 이 extend_tool 이나 new_tool 이어야 한다. */
    private static void checkOptions(Object value) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("development_request.options 가 목록이 아닙니다");
        }
        List<Object> options = ProposalRules.list(value);
        for (int i = 0; i < options.size(); i++) {
            Map<String, Object> option = map(options.get(i));
            Object type = option == null ? null : option.get("type");
            if (!OPTION_TYPES.contains(type)) {
                throw new IllegalArgumentException("development_request.options 의 " + (i + 1)
                        + "번째 항목의 type 은 extend_tool 이나 new_tool 이어야 합니다 (받은 값 " + type + ")");
            }
        }
    }

    /** 실행 번호마다 어느 연습 공간에서든 이 서비스가 돌린 실행인지 확인한다. field 는 거부 메시지에 쓰는 칸 이름이다. */
    private void requireKnownRuns(String field, List<String> runIds) {
        for (String runId : runIds) {
            if (!ledger.known(runId)) {
                throw new IllegalArgumentException(field + " 의 실행 " + runId + " 을 찾을 수 없습니다");
            }
        }
    }

    /**
     * 서버가 정한 status 가 proposed 일 때 개발 요청서가 없는지 확인한다. 합격 후보는 SKILL.md 수정으로 고칠 수 있다는 근거이고, 개발
     * 요청서는 고칠 수 없다는 판단이라 둘이 함께 있으면 어느 쪽을 믿을지 정할 수 없으므로 거부한다. validCandidateNos 는 서버가 다시
     * 계산한 합격 후보 번호다.
     */
    void checkNoDevelopmentRequest(Map<String, Object> proposal, List<Integer> validCandidateNos) {
        if (proposal.get(DEVELOPMENT_REQUEST) != null) {
            throw new IllegalArgumentException(
                    "development_request 가 있으면 합격 후보가 없어야 합니다 (서비스가 다시 계산한 합격 후보 " + validCandidateNos + ")");
        }
    }

    /**
     * 서버가 정한 status 가 proposed 일 때 원인의 근거 실행을 확인한다. root_cause 가 없거나 proof_run_ids 가 비어 있으면 거부하고,
     * 모든 번호는 어느 연습 공간에서든 이 서비스가 돌린 실행이어야 한다. 원인 근거 실행은 수정 없는 control 연습 공간과 변경을 되돌린
     * ablation 연습 공간(의도 미실현이면 수정본 사전 검증 연습 공간)에서 나오고, 제안서에는 그 연습 공간 번호가 없어서 연습 공간을
     * 가리지 않고 찾는다.
     */
    void checkRootCause(Map<String, Object> rootCause) {
        List<String> proofs = rootCause == null ? List.of() : strings(rootCause.get("proof_run_ids"));
        if (proofs.isEmpty()) {
            throw new IllegalArgumentException("root_cause.proof_run_ids 가 비어 있습니다");
        }
        for (String runId : proofs) {
            if (!ledger.known(runId)) {
                throw new IllegalArgumentException("원인 근거 실행 " + runId + " 을 찾을 수 없습니다");
            }
        }
    }
}
