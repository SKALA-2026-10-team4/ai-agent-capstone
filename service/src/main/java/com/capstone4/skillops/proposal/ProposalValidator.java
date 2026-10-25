package com.capstone4.skillops.proposal;

import static com.capstone4.skillops.proposal.ProposalRules.integer;
import static com.capstone4.skillops.proposal.ProposalRules.map;
import static com.capstone4.skillops.proposal.ProposalRules.maps;
import static com.capstone4.skillops.proposal.ProposalRules.str;
import static com.capstone4.skillops.proposal.ProposalRules.strings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.capstone4.skillops.scenario.RunLedger;

/**
 * save_proposal 의 거부 규칙을 실행 순서대로 모은 검사기다. 규칙을 검사하는 순서가 곧 여러 규칙을 어긴 제안서에 어느 메시지가 나갈지를 정한다.
 * 순서는 다음과 같다. status 값을 먼저 보고, 후보마다 차례로 번호, 근거 실행, valid 일치를 본 뒤, status 와 합격 후보가 맞는지 본다.
 * status 가 proposed 이면 마지막으로 원인 근거 실행과 추천 후보를 본다.
 * 근거 실행은 service/src/main/java/com/capstone4/skillops/scenario/RunLedger.java 장부에서 이 서비스가 run_scenarios 로 실제로 돌린
 * 실행인지 확인한다. service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 가 생성자에서 이 검사기를 만들고,
 * save 가 validCandidateNos 를 부른다. 거부는 모두 IllegalArgumentException 이고 메시지는 Agent 개발자가 읽는다.
 * service/src/test/java/com/capstone4/skillops/proposal/ProposalCharacterizationTest.java 가 메시지 전체와 우선순위를 고정하므로,
 * 규칙을 더하거나 순서를 바꾸면 그 테스트도 고친다.
 */
final class ProposalValidator {

    private final RunLedger ledger;

    /** 근거 실행을 대조할 장부를 받는다. ProposalService 의 생성자가 부른다. */
    ProposalValidator(RunLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * 규칙을 순서대로 검사하고, 시험 결과로 다시 계산한 합격 후보 번호를 돌려준다. 잘못된 제안서는 IllegalArgumentException 으로 거부한다.
     * 규칙의 실행 순서를 바꾸려면 이 메서드의 호출 순서를 고친다.
     */
    List<Integer> validCandidateNos(String status, Map<String, Object> proposal) {
        requireSavableStatus(status);
        List<Integer> valid = checkCandidates(maps(proposal.get("candidates")));
        requireStatusMatchesCandidates(status, valid);
        if (ProposalStatus.PROPOSED.equals(status)) {
            checkRootCause(map(proposal.get("root_cause")));
            requireRecommendedIsValid(integer(proposal.get("recommended_candidate_no")), valid);
        }
        return valid;
    }

    /** status 가 proposed 나 no_valid_fix 인지 확인한다. */
    private static void requireSavableStatus(String status) {
        if (!ProposalStatus.isSavable(status)) {
            throw new IllegalArgumentException("status 는 proposed 나 no_valid_fix 여야 합니다 (받은 값 " + status + ")");
        }
    }

    /**
     * 후보를 목록 순서대로 검사하고 합격 후보 번호를 모은다. 후보마다 번호를 먼저 보고, 원문에 적용한 후보만 근거 실행을 확인한 뒤,
     * 다시 계산한 합격 여부를 Agent 의 valid 와 비교하고 합격 후보의 실제 실행 통과도 확인한다.
     * 적용에 실패한 후보는 근거 실행이 없어도 되지만 번호와 valid 검사는 받는다.
     */
    private List<Integer> checkCandidates(List<Map<String, Object>> candidates) {
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map<String, Object> candidate = candidates.get(i);
            int no = requireCandidateNo(candidate, i + 1);
            List<RunLedger.Evidence> evidence = ProposalRules.applied(candidate)
                    ? checkEvidence(no, candidate) : List.of();
            boolean passes = ProposalRules.passes(candidate);
            requireAgentValidMatches(no, candidate, passes);
            if (passes) {
                requirePassingEvidence(no, evidence);
                valid.add(no);
            }
        }
        return List.copyOf(valid);
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
    private List<RunLedger.Evidence> checkEvidence(int no, Map<String, Object> candidate) {
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
        Map<String, RunLedger.Evidence> ran = ledger.evidenceOf(workspaceId).stream()
                .collect(Collectors.toMap(RunLedger.Evidence::runId, Function.identity()));
        for (String runId : evidence) {
            if (!ran.containsKey(runId)) {
                throw new IllegalArgumentException(
                        "후보 " + no + " 의 근거 실행 " + runId + " 을 연습 공간 " + workspaceId + " 에서 찾을 수 없습니다");
            }
        }
        return evidence.stream().map(ran::get).toList();
    }

    /** 합격 후보의 인용 실행은 모두 서버의 실제 채점에서도 통과해야 한다. */
    private static void requirePassingEvidence(int no, List<RunLedger.Evidence> evidence) {
        for (RunLedger.Evidence execution : evidence) {
            if (!execution.passed()) {
                throw new IllegalArgumentException("후보 " + no + " 의 근거 실행 " + execution.runId()
                        + " 은 실제 채점에서 실패했습니다 (시나리오 " + execution.scenarioId() + ")");
            }
        }
    }

    /**
     * Agent 가 적은 valid 가 다시 계산한 합격 여부와 같은지 확인한다. valid 가 없거나 참·거짓 값이 아니면 비교하지 않는다.
     * 둘이 다르면 Agent 의 판정(skillops-agent/skillops_agent/nodes.py 의 _judge)과 ProposalRules 의 passes 가 어긋난 것이므로 저장하지 않는다.
     */
    private static void requireAgentValidMatches(int no, Map<String, Object> candidate, boolean passes) {
        if (candidate.get("valid") instanceof Boolean agentValid && agentValid != passes) {
            throw new IllegalArgumentException("후보 " + no + " 의 valid(" + agentValid + ")가 시험 결과로 다시 계산한 값("
                    + passes + ")과 다릅니다");
        }
    }

    /** status 가 합격 후보 유무와 맞는지 확인한다. proposed 면 합격 후보가 있어야 하고, no_valid_fix 면 없어야 한다. */
    private static void requireStatusMatchesCandidates(String status, List<Integer> valid) {
        if (ProposalStatus.PROPOSED.equals(status) && valid.isEmpty()) {
            throw new IllegalArgumentException("합격한 후보가 없는데 status 가 proposed 입니다");
        }
        if (ProposalStatus.NO_VALID_FIX.equals(status) && !valid.isEmpty()) {
            throw new IllegalArgumentException("합격한 후보 " + valid + " 가 있는데 status 가 no_valid_fix 입니다");
        }
    }

    /**
     * status 가 proposed 일 때 원인의 근거 실행을 확인한다. root_cause 가 없거나 proof_run_ids 가 비어 있으면 거부하고,
     * 모든 번호는 어느 연습 공간에서든 이 서비스가 돌린 실행이어야 한다. 원인 근거 실행은 수정 없는 control 연습 공간과 변경을 되돌린
     * ablation 연습 공간에서 나오고, 제안서에는 그 연습 공간 번호가 없어서 연습 공간을 가리지 않고 찾는다.
     */
    private void checkRootCause(Map<String, Object> rootCause) {
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

    /** recommended_candidate_no 가 합격 후보 번호 가운데 하나인지 확인한다. 숫자가 아니면 없는 값으로 본다. */
    private static void requireRecommendedIsValid(Integer recommended, List<Integer> valid) {
        if (recommended == null || !valid.contains(recommended)) {
            throw new IllegalArgumentException(
                    "recommended_candidate_no " + recommended + " 은 합격한 후보가 아닙니다 (합격 후보 " + valid + ")");
        }
    }
}
