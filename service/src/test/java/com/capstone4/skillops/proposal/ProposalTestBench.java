package com.capstone4.skillops.proposal;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.capstone4.skillops.scenario.TestFixtures.InMemoryRunLedger;
import com.capstone4.skillops.snapshot.OrderAgentException;
import com.capstone4.skillops.workspace.AppliedPatch;
import com.capstone4.skillops.workspace.ApplyCheck;
import com.capstone4.skillops.workspace.WorkspaceEvidence;

/**
 * save_proposal 테스트가 함께 쓰는 재판정 환경이다. 메모리 장부, 연습 공간 기록, 주문 Agent 의 snapshot metadata, 기준본 해시를 담고
 * 그것으로 service/src/main/java/com/capstone4/skillops/proposal/ProposalJudge.java 를 만든다.
 * metadata 는 snapshot 번호별로 두며, 없는 번호를 읽으면 주문 Agent 처럼 SNAPSHOT_NOT_FOUND 의 OrderAgentException 을 던진다.
 * 처음에는 SNAPSHOT 에 의도 없는 담당자 수정본 metadata(source=user-edit, baseline_snapshot_id=BASELINE)가 들어 있다.
 * withF1 은 F1 픽스처(service/src/test/resources/proposal/f1-proposed.json)의 후보 두 개가 서버 재판정에서도 픽스처의 results 와
 * 같은 판정을 받도록 장부와 연습 공간을 채운다. 1번 후보는 재현과 기준 주문 g-01~g-03 을 두 번씩 통과하고 연결 오류가 없으며,
 * 2번 후보는 g-02 한 번이 떨어지고(기준 주문 2/3) 끊긴 연결이 하나 있다.
 * ProposalServiceTest, ProposalCharacterizationTest, ProposalJudgeTest, ProposalServiceWiringTest 가 쓴다.
 */
final class ProposalTestBench {

    /** F1 픽스처의 수정 후 snapshot 이다. 후보 연습 공간은 여기서 시작했다고 본다. */
    static final String SNAPSHOT = "22222222-2222-4222-8222-222222222222";
    /** F1 픽스처의 수정 전(기준본) snapshot 이다. SNAPSHOT 의 metadata 가 이 번호를 baseline_snapshot_id 로 가리킨다. */
    static final String BASELINE = "11111111-1111-4111-8111-111111111111";
    /** F1 픽스처의 실패 실행을 다시 돌리는 재현 시나리오다. */
    static final String REPRO = "f-33333333-3333-4333-8333-333333333333";
    /** 재판정의 기준 주문 세트다. F1 픽스처의 keeps_old_orders.total(3)과 같다. */
    static final List<String> GOLDEN = List.of("g-01", "g-02", "g-03");
    /** 재판정의 최소 반복 횟수다. */
    static final int MIN_REPEAT = 2;

    final InMemoryRunLedger ledger = new InMemoryRunLedger();
    final Map<String, WorkspaceEvidence> workspaces = new HashMap<>();
    /** 주문 Agent 가 snapshot 번호별로 돌려줄 metadata 다. 없는 번호는 SNAPSHOT_NOT_FOUND 다. */
    final Map<String, Map<String, Object>> metadata = new HashMap<>();
    /** 기준본 snapshot 의 skill 별 SKILL.md 해시다. null 이면 기준본을 읽지 못한 것으로 본다. */
    Map<String, String> baseline;

    ProposalTestBench() {
        metadata.put(SNAPSHOT, userEdit(null));
    }

    /** 주문 Agent 가 담당자 수정 저장 때 남기는 모양의 metadata 다. intent 가 null 이면 intent 칸을 만들지 않는다. */
    static Map<String, Object> userEdit(Map<String, Object> intent) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "user-edit");
        metadata.put("baseline_snapshot_id", BASELINE);
        metadata.put("changed_paths", List.of("skills/payment/SKILL.md"));
        if (intent != null) {
            metadata.put("intent", intent);
        }
        return metadata;
    }

    /** 이 환경으로 재판정기를 만든다. metadata 나 기준본을 읽지 못하면 주문 Agent 오류처럼 OrderAgentException 을 던진다. */
    ProposalJudge judge() {
        return new ProposalJudge(ledger, id -> Optional.ofNullable(workspaces.get(id)), id -> {
            if (!metadata.containsKey(id)) {
                throw new OrderAgentException("SNAPSHOT_NOT_FOUND", "snapshot 을 찾을 수 없습니다", false, 404, id);
            }
            return metadata.get(id);
        }, id -> {
            if (baseline == null) {
                throw new OrderAgentException("ORDER_AGENT_UNAVAILABLE", "주문 Agent 에 연결할 수 없습니다", true, null, id);
            }
            return baseline;
        }, GOLDEN, MIN_REPEAT);
    }

    /** F1 픽스처에 맞춘 장부와 연습 공간을 채운다. 기존 테스트가 쓰던 실행 번호를 그대로 쓰고 모자란 실행을 더한다. */
    ProposalTestBench withF1() {
        // F1 픽스처가 근거로 적은 원인 실행과 후보 실행을 장부에 미리 남긴다.
        ledger.record("w_ablation", REPRO, "r_a0000000000000000000000000000001", false);
        ledger.record("w_ablation", REPRO, "r_a0000000000000000000000000000002", true);
        // 1번 후보: g-01~g-03 과 재현을 두 번씩 모두 통과했다.
        ledger.record("w_c1", "g-01", "r_c1000000000000000000000000000001", true);
        ledger.record("w_c1", "g-01", "r_c1000000000000000000000000000002", true);
        ledger.record("w_c1", "g-02", "r_c1000000000000000000000000000003", true);
        ledger.record("w_c1", "g-02", "r_c1000000000000000000000000000004", true);
        ledger.record("w_c1", "g-03", "r_c1000000000000000000000000000005", true);
        ledger.record("w_c1", "g-03", "r_c1000000000000000000000000000006", true);
        ledger.record("w_c1", REPRO, "r_c1000000000000000000000000000007", true);
        ledger.record("w_c1", REPRO, "r_c1000000000000000000000000000008", true);
        // 2번 후보: g-02 한 번이 떨어졌다.
        ledger.record("w_c2", "g-01", "r_c2000000000000000000000000000001", true);
        ledger.record("w_c2", "g-02", "r_c2000000000000000000000000000002", false);
        ledger.record("w_c2", "g-01", "r_c2000000000000000000000000000003", true);
        ledger.record("w_c2", "g-02", "r_c2000000000000000000000000000004", true);
        ledger.record("w_c2", "g-03", "r_c2000000000000000000000000000005", true);
        ledger.record("w_c2", "g-03", "r_c2000000000000000000000000000006", true);
        ledger.record("w_c2", REPRO, "r_c2000000000000000000000000000007", true);
        ledger.record("w_c2", REPRO, "r_c2000000000000000000000000000008", true);
        Map<String, Object> f1 = ProposalDocumentWriterTest.fixture("f1-proposed.json");
        workspaces.put("w_c1", frozen("w_c1", patchesOf(f1, 0), new ApplyCheck(0, 0, 0, 0)));
        workspaces.put("w_c2", frozen("w_c2", patchesOf(f1, 1), new ApplyCheck(1, 0, 0, 0)));
        return this;
    }

    /** SNAPSHOT 에서 시작해 patches 를 넣고 run_scenarios 로 고정된 연습 공간 기록이다. 시작·지금 원문은 비워 둔다. */
    static WorkspaceEvidence frozen(String workspaceId, List<AppliedPatch> patches, ApplyCheck check) {
        return new WorkspaceEvidence(workspaceId, SNAPSHOT, true, patches, check, null, Map.of());
    }

    /** 제안서 index 번째(0부터) 후보의 patches 를 연습 공간에 넣은 수정 목록으로 바꾼다. */
    static List<AppliedPatch> patchesOf(Map<String, Object> proposal, int index) {
        return ProposalRules.maps(ProposalRules.maps(proposal.get("candidates")).get(index).get("patches")).stream()
                .map(p -> new AppliedPatch((String) p.get("skill"), ((Number) p.get("base_version")).intValue(),
                        (String) p.get("patch")))
                .toList();
    }
}
