package com.capstone4.skillops.proposal;

import static com.capstone4.skillops.proposal.ProposalRules.integer;
import static com.capstone4.skillops.proposal.ProposalRules.map;
import static com.capstone4.skillops.proposal.ProposalRules.maps;
import static com.capstone4.skillops.proposal.ProposalRules.str;
import static com.capstone4.skillops.proposal.ProposalRules.strings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.scenario.Check;
import com.capstone4.skillops.scenario.IntentExpectation;
import com.capstone4.skillops.scenario.IntentPlan;
import com.capstone4.skillops.scenario.RunLedger;
import com.capstone4.skillops.scenario.ScenarioCatalog;
import com.capstone4.skillops.skill.SkillFrontmatter;
import com.capstone4.skillops.skill.SkillRules;
import com.capstone4.skillops.snapshot.OrderAgentClient;
import com.capstone4.skillops.snapshot.SnapshotManifest;
import com.capstone4.skillops.workspace.AppliedPatch;
import com.capstone4.skillops.workspace.ApplyCheck;
import com.capstone4.skillops.workspace.WorkspaceEvidence;
import com.capstone4.skillops.workspace.WorkspaceService;

/**
 * save_proposal 의 재판정이다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.7, 2.10 절). Agent 가 보낸 참·거짓을 믿지 않고,
 * 실행 장부(RunLedger)와 연습 공간 기록(WorkspaceEvidence), 주문 Agent 의 snapshot metadata 로 후보의 판정을 다시 계산한다.
 * 값이 다르면 거부하지 않고 서버 값으로 덮은 뒤 무엇을 바꿨는지 server_overrides 에 남긴다. 합격 후보가 바뀌면 status 와
 * recommended_candidate_no 도 서버 기준으로 바꾼다.
 *
 * 의도의 진실 원천은 주문 Agent 다(V3-1). 시험한 후보가 하나라도 있으면 proposal.snapshot_id 의 snapshot metadata 를 한 번 읽고,
 * metadata 에 intent 가 있으면 의도 요청으로 본다. 제안서의 trigger.intent, trigger.baseline_snapshot_id, baseline_snapshot_id 는
 * 보지 않는다. expectations 는 metadata 의 intent.expectations(담당자, owner)가 있으면 그것이고, 없으면 제안서의
 * intent.expectations(Agent 가 추론, inferred)다. 기준본은 metadata 의 baseline_snapshot_id 다. metadata 나 기준본 manifest 를
 * 주문 Agent 에서 읽지 못하면 Agent 값으로 물러서지 않고 저장을 거부한다(주문 Agent 오류 code 를 그대로 올린다).
 *
 * 후보마다 다시 계산하는 칸은 다음과 같다.
 * results.evidence_matches 는 서버만 쓰는 칸이며, 후보 patches 가 1개 이상이고 연습 공간에 넣은 수정(applied_patches)과 같고,
 * 연습 공간이 proposal.snapshot_id 에서 시작했고, run_scenarios 로 고정됐을 때 참이다. 거짓이면 그 후보는 합격하지 못한다.
 * patches 가 없는 후보는 담당자 수정본을 그대로 시험한 것이라 수정안이 아니므로 떨어진다.
 * results.fixes_failure 는 root_cause.repro_scenario_ids(없으면 장부에서 f- 로 시작하는 시나리오)가 그 연습 공간에서 min-repeat 번
 * 이상 돌았고 모두 통과했을 때 참이다. repro_scenario_ids 를 적었으면 그 id 마다 root_cause.proof_run_ids 가운데 그 시나리오로 돌아
 * 떨어진 실행이 장부에 하나 이상 있어야 하고(V3-5), 하나라도 없으면 모든 후보의 fixes_failure 가 거짓이며 server_overrides 에
 * {candidate_no: null, field: root_cause.repro_scenario_ids, agent_value: 적은 목록, server_value: 근거가 있는 목록} 을 남긴다.
 * results.keeps_old_orders 는 golden-ids 의 기준 주문마다 같은 기준으로 센 통과 수와 그 세트 크기다. 판정에 쓰는 실행은 의도
 * 요청이면 그 expectations 로 채점한 실행(checks_hash 가 같은 실행)이고, 아니면 덧붙인 조건 없이 돌린 실행(checks_hash 가 null)이다.
 * results.no_broken_links, metadata_valid, flow_valid 는 연습 공간의 마지막 apply_patch 결과 수로 다시 계산한다.
 * 의도 요청일 때만 keeps_intent, is_revert, weakens_contract 를 다시 계산한다(정의는 isRevert, weakensContract, keepsIntent).
 * 의도가 없는 요청은 Agent 의 keeps_intent 를 그대로 쓰고 is_revert, weakens_contract 는 보지 않는다(QA 의 revert_allowed 같은
 * 기존 흐름 보존). is_revert 나 weakens_contract 를 계산할 수 없으면(기준본 번호가 없거나 연습 공간 시작 원문이 없음) 두 칸은
 * Agent 값을 그대로 두되 keeps_intent 를 거짓으로 둔다. Agent 값으로 합격시키지 않는다.
 *
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 의 save 가 거부 검사(ProposalValidator) 뒤에 judge 를
 * 부른다. 재판정 기준은 service/src/main/resources/application.yml 의 skillops.min-repeat(SKILLOPS_MIN_REPEAT, 기본 2)와
 * skillops.golden-ids(SKILLOPS_GOLDEN_IDS, 비우면 기준 주문 폴더의 모든 기준 주문)다. 진단 Agent 가 정하는 반복 횟수
 * (skillops-agent/skillops_agent/config.py 의 VERIFY_REPEAT)는 min-repeat 이상이어야 후보가 합격할 수 있다.
 */
@Component
public class ProposalJudge {

    private static final Logger log = LoggerFactory.getLogger(ProposalJudge.class);
    /** 재현 주문 번호의 앞머리다. root_cause.repro_scenario_ids 가 없을 때 장부에서 이 앞머리의 시나리오를 재현으로 본다. */
    static final String REPLAY_PREFIX = "f-";
    /** snapshot manifest 에서 skill 원문으로 볼 경로다. 그룹 1 이 skill 이름이다. */
    private static final Pattern SKILL_MD_PATH = Pattern.compile("skills/([^/]+)/SKILL\\.md");

    /** 재판정이 연습 공간 사실을 읽는 접점이다. 운영은 WorkspaceService.evidenceOf 이고 테스트는 맵을 넣는다. */
    @FunctionalInterface
    interface WorkspaceLookup {
        Optional<WorkspaceEvidence> evidenceOf(String workspaceId);
    }

    /**
     * 제안서가 진단한 snapshot(proposal.snapshot_id)의 metadata 를 읽는 접점이다. 운영은 주문 Agent GET /api/snapshots/{id} 의
     * metadata 이고, 읽지 못하면 RuntimeException(운영은 OrderAgentException)을 던진다.
     */
    @FunctionalInterface
    interface MetadataLookup {
        Map<String, Object> metadataOf(String snapshotId);
    }

    /** 기준본 snapshot 의 skill 이름별 SKILL.md 해시를 읽는 접점이다. 읽지 못하면 RuntimeException 을 던진다. */
    @FunctionalInterface
    interface BaselineLookup {
        Map<String, String> skillHashes(String baselineSnapshotId);
    }

    /**
     * 재판정 결과다.
     *
     * @param payload           서버 값으로 덮고 server_overrides 를 넣은 제안서 사본이다. 저장과 본문 작성에 쓴다
     * @param status            서버가 다시 정한 status 다
     * @param validCandidateNos 서버가 다시 계산한 합격 후보 번호다
     * @param overrides         server_overrides 와 같은 목록이다
     */
    record Judgement(Map<String, Object> payload, String status, List<Integer> validCandidateNos,
            List<Map<String, Object>> overrides) {
    }

    private final RunLedger ledger;
    private final WorkspaceLookup workspaces;
    private final MetadataLookup metadata;
    private final BaselineLookup baseline;
    /** keeps_old_orders 의 기준 주문 세트다. total 은 이 세트 크기다. */
    private final List<String> goldenIds;
    /** 시험 주문 하나가 통과로 인정받으려면 돌아야 하는 최소 횟수다. */
    private final int minRepeat;

    /**
     * Spring 이 쓰는 생성자다. golden-ids 가 비어 있으면 기준 주문 폴더(ScenarioCatalog)의 모든 번호를 쓰고, 적었으면 모두 그 폴더에
     * 있어야 한다. min-repeat 는 1 이상이어야 한다. 어긋나면 IllegalStateException 으로 기동을 멈춘다.
     * snapshot metadata 와 기준본 manifest 는 OrderAgentClient.getSnapshot 으로 읽는다.
     */
    @Autowired
    public ProposalJudge(RunLedger ledger, WorkspaceService workspaces, ScenarioCatalog catalog, OrderAgentClient orderAgent,
            @Value("${skillops.min-repeat:2}") int minRepeat, @Value("${skillops.golden-ids:}") String goldenIds) {
        this(ledger, workspaces::evidenceOf, id -> metadataOf(orderAgent.getSnapshot(id)),
                id -> skillHashesOf(orderAgent.getSnapshot(id)), goldenIdsOf(goldenIds, catalog.ids()), minRepeat);
    }

    /** 테스트가 연습 공간, snapshot metadata, 기준본을 직접 넣을 때 쓰는 생성자다. */
    ProposalJudge(RunLedger ledger, WorkspaceLookup workspaces, MetadataLookup metadata, BaselineLookup baseline,
            List<String> goldenIds, int minRepeat) {
        if (minRepeat < 1) {
            throw new IllegalStateException("skillops.min-repeat 는 1 이상이어야 합니다 (받은 값 " + minRepeat + ")");
        }
        this.ledger = ledger;
        this.workspaces = workspaces;
        this.metadata = metadata;
        this.baseline = baseline;
        this.goldenIds = List.copyOf(goldenIds);
        this.minRepeat = minRepeat;
    }

    /**
     * 설정 글(쉼표로 나눈 번호)을 기준 주문 세트로 바꾼다. 비어 있으면 catalogIds 전부다. 폴더에 없는 번호가 있으면
     * IllegalStateException 이다. contract-only 실행기(scenarios/golden)는 g-01 만 있으므로 그 모드에서는 SKILLOPS_GOLDEN_IDS=g-01 이다.
     */
    static List<String> goldenIdsOf(String configured, Set<String> catalogIds) {
        List<String> ids = configured == null ? List.of()
                : Arrays.stream(configured.split(",")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        if (ids.isEmpty()) {
            return List.copyOf(catalogIds);
        }
        for (String id : ids) {
            if (!catalogIds.contains(id)) {
                throw new IllegalStateException("skillops.golden-ids 의 " + id + " 이 기준 주문 폴더에 없습니다. 있는 번호: " + catalogIds);
            }
        }
        return ids;
    }

    /** manifest 의 metadata 다. 없으면 빈 맵이다. */
    private static Map<String, Object> metadataOf(SnapshotManifest manifest) {
        return manifest == null || manifest.metadata() == null ? Map.of() : manifest.metadata();
    }

    /** manifest 에서 skills/&lt;이름&gt;/SKILL.md 파일의 해시만 skill 이름별로 모은다. */
    private static Map<String, String> skillHashesOf(SnapshotManifest manifest) {
        Map<String, String> hashes = new TreeMap<>();
        for (SnapshotManifest.FileEntry file : manifest.files()) {
            Matcher m = SKILL_MD_PATH.matcher(file.path());
            if (m.matches()) {
                hashes.put(m.group(1), file.hash());
            }
        }
        return hashes;
    }

    /**
     * 제안서 하나를 다시 판정한다. 받은 제안서는 고치지 않고 사본을 고친다. 후보 번호, 근거 실행, snapshot_id 검사는 이미 끝났다고 본다.
     * 시험한 후보가 있으면 snapshot metadata 를 읽고 재현 근거(checkReproProof)를 먼저 본 뒤, 시험한 후보마다 judgeCandidate 로 칸을
     * 덮고, valid 를 ProposalRules.passes 로 다시 정한 뒤 status 와 추천 후보를 맞춘다.
     * metadata 나 기준본을 주문 Agent 에서 읽지 못하면 그 RuntimeException 이 그대로 올라가 저장이 거부된다.
     */
    Judgement judge(Map<String, Object> proposal) {
        Map<String, Object> payload = copyOf(proposal);
        List<Map<String, Object>> candidates = maps(payload.get("candidates"));
        boolean anyTested = candidates.stream().anyMatch(ProposalRules::tested);
        Request request = new Request(payload, anyTested ? metadataOf(str(payload.get("snapshot_id"))) : Map.of());
        List<Map<String, Object>> overrides = new ArrayList<>();
        if (anyTested) {
            checkReproProof(request, overrides);
        }
        List<Integer> valid = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            Integer no = integer(candidate.get("candidate_no"));
            if (ProposalRules.tested(candidate)) {
                judgeCandidate(request, no, candidate, overrides);
            }
            boolean passes = ProposalRules.passes(candidate);
            if (candidate.get("valid") instanceof Boolean agent && agent != passes) {
                overrides.add(override(no, "valid", agent, passes));
            }
            candidate.put("valid", passes);
            if (passes) {
                valid.add(no);
            }
        }
        String status = valid.isEmpty() ? ProposalStatus.NO_VALID_FIX : ProposalStatus.PROPOSED;
        Object agentStatus = payload.get("status");
        if (!status.equals(agentStatus)) {
            overrides.add(override(null, "status", agentStatus, status));
            payload.put("status", status);
        }
        reconcileRecommended(payload, status, valid, candidates, overrides);
        payload.put("server_overrides", overrides);
        return new Judgement(payload, status, List.copyOf(valid), List.copyOf(overrides));
    }

    /** 진단한 snapshot 의 metadata 를 읽는다. 없으면 빈 맵이고, 읽지 못하면 그 예외를 그대로 올린다(Agent 값으로 물러서지 않는다). */
    private Map<String, Object> metadataOf(String snapshotId) {
        Map<String, Object> read = metadata.metadataOf(snapshotId);
        return read == null ? Map.of() : read;
    }

    /**
     * 재현 근거를 확인한다(V3-5). root_cause.repro_scenario_ids 의 id 마다 root_cause.proof_run_ids 가운데 그 시나리오로 돌아
     * 떨어진(passed 거짓) 실행이 장부에 있어야 한다. 하나라도 없으면 request.reproProven 을 거짓으로 두어 모든 후보의 fixes_failure 를
     * 거짓으로 만들고, 제안서 전체의 칸으로 server_overrides 에 남긴다. repro_scenario_ids 가 비면(f- 로 대신) 보지 않는다.
     */
    private void checkReproProof(Request request, List<Map<String, Object>> overrides) {
        if (request.reproIds.isEmpty()) {
            return;
        }
        Set<String> failedInProof = request.proofRunIds.stream().map(ledger::find).flatMap(Optional::stream)
                .filter(run -> !run.passed()).map(RunLedger.Run::scenarioId).collect(Collectors.toSet());
        List<String> proven = request.reproIds.stream().filter(failedInProof::contains).toList();
        if (proven.size() != request.reproIds.size()) {
            request.reproProven = false;
            overrides.add(override(null, "root_cause.repro_scenario_ids", request.reproIds, proven));
        }
    }

    /**
     * 추천 후보를 서버 판정에 맞춘다. proposed 인데 Agent 의 추천이 합격 후보가 아니면 합격 후보 가운데 rank 가 가장 작은 후보
     * (rank 가 없으면 번호가 가장 작은 후보)로 바꾼다. no_valid_fix 인데 추천이 있으면 비운다. 바꾸면 server_overrides 에 남긴다.
     */
    private static void reconcileRecommended(Map<String, Object> payload, String status, List<Integer> valid,
            List<Map<String, Object>> candidates, List<Map<String, Object>> overrides) {
        Object agent = payload.get("recommended_candidate_no");
        Object server;
        if (ProposalStatus.PROPOSED.equals(status)) {
            Integer chosen = integer(agent);
            if (chosen != null && valid.contains(chosen)) {
                return;
            }
            server = candidates.stream().filter(c -> valid.contains(integer(c.get("candidate_no"))))
                    .min(Comparator.comparing((Map<String, Object> c) -> rankOf(c))
                            .thenComparing(c -> integer(c.get("candidate_no"))))
                    .map(c -> integer(c.get("candidate_no"))).orElseThrow();
        } else {
            if (agent == null) {
                return;
            }
            server = null;
        }
        overrides.add(override(null, "recommended_candidate_no", agent, server));
        payload.put("recommended_candidate_no", server);
    }

    /** 추천을 고를 때의 순위다. rank 가 숫자가 아니면 가장 뒤로 보낸다. */
    private static int rankOf(Map<String, Object> candidate) {
        Integer rank = integer(candidate.get("rank"));
        return rank == null ? Integer.MAX_VALUE : rank;
    }

    /**
     * 시험한 후보 하나의 판정 칸을 서버 값으로 덮는다. 칸 순서는 evidence_matches, fixes_failure, keeps_old_orders, no_broken_links,
     * metadata_valid, flow_valid, (의도 요청이면) is_revert, weakens_contract, keeps_intent 이고 server_overrides 도 이 순서로 쌓는다.
     * 제안서 전체의 재현 근거 칸(root_cause.repro_scenario_ids)은 후보보다 먼저 쌓인다.
     */
    private void judgeCandidate(Request request, Integer no, Map<String, Object> candidate,
            List<Map<String, Object>> overrides) {
        String workspaceId = str(candidate.get("workspace_id"));
        Map<String, Object> results = map(candidate.get("results"));
        WorkspaceEvidence workspace = workspaces.evidenceOf(workspaceId).orElse(null);
        List<RunLedger.Run> graded = ledger.runsOf(workspaceId).stream()
                .filter(run -> Objects.equals(run.checksHash(), request.runHash)).toList();

        boolean evidenceMatches = evidenceMatches(workspace, candidate, request.snapshotId);
        results.put("evidence_matches", evidenceMatches);
        if (!evidenceMatches) {
            // Agent 가 보내지 않는 칸이라 agent_value 는 null 이지만, 후보가 떨어진 까닭을 남기려고 늘 기록한다.
            overrides.add(override(no, "results.evidence_matches", null, false));
        }
        replace(results, "fixes_failure", fixesFailure(request, graded), no, overrides);
        replaceOldOrders(results, (int) goldenIds.stream().filter(id -> passes(graded, id)).count(), no, overrides);
        ApplyCheck check = workspace == null ? null : workspace.lastApply();
        replace(results, "no_broken_links", check != null && check.noBrokenLinks(), no, overrides);
        replace(results, "metadata_valid", check != null && check.metadataErrors() == 0, no, overrides);
        replace(results, "flow_valid", check != null && check.flowErrors() == 0, no, overrides);
        if (request.hasIntent) {
            Boolean revert = isRevert(request, workspace);
            Boolean weakens = weakensContract(request, workspace);
            if (revert != null) {
                replaceCandidate(candidate, "is_revert", revert, no, overrides);
            }
            if (weakens != null) {
                replaceCandidate(candidate, "weakens_contract", weakens, no, overrides);
            }
            boolean keeps = Boolean.FALSE.equals(revert) && Boolean.FALSE.equals(weakens) && keepsIntent(request, graded);
            replace(results, "keeps_intent", keeps, no, overrides);
        }
    }

    /**
     * 연습 공간 기록이 후보와 맞는지 본다. 연습 공간이 있고, 고정됐고, 후보 patches 가 1개 이상이고, 넣은 수정 목록이 후보 patches 와
     * 칸마다 같고(순서 포함), 시작 snapshot 이 제안서의 snapshot_id 와 같아야 참이다. bundle 해시는 비교하지 않는다.
     * snapshot_id 는 ProposalValidator 가 비어 있지 않음을 확인했으므로 운영 폴더에서 시작한 연습 공간(시작 snapshot 이 null)은
     * 맞지 않는다.
     */
    private static boolean evidenceMatches(WorkspaceEvidence workspace, Map<String, Object> candidate, String snapshotId) {
        if (snapshotId == null || workspace == null || !workspace.frozen() || workspace.appliedPatches() == null
                || !snapshotId.equals(workspace.sourceSnapshotId())) {
            return false;
        }
        List<Map<String, Object>> patches = maps(candidate.get("patches"));
        List<AppliedPatch> applied = workspace.appliedPatches();
        if (patches.isEmpty() || patches.size() != applied.size()) {
            return false;
        }
        for (int i = 0; i < patches.size(); i++) {
            Map<String, Object> patch = patches.get(i);
            AppliedPatch done = applied.get(i);
            Integer baseVersion = integer(patch.get("base_version"));
            if (!done.skill().equals(patch.get("skill")) || baseVersion == null || baseVersion != done.baseVersion()
                    || !done.patch().equals(patch.get("patch"))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 실패가 고쳐졌는지 본다. 재현 시나리오는 root_cause.repro_scenario_ids 이고, 없으면 이 연습 공간 장부에서 f- 로 시작하는
     * 시나리오다. 재현 시나리오가 하나도 없거나, repro_scenario_ids 의 재현 근거(checkReproProof)가 없으면 고쳐졌다는 근거가 없으므로
     * 거짓이다.
     */
    private boolean fixesFailure(Request request, List<RunLedger.Run> graded) {
        if (!request.reproProven) {
            return false;
        }
        List<String> repro = request.reproIds.isEmpty()
                ? graded.stream().map(RunLedger.Run::scenarioId).filter(id -> id.startsWith(REPLAY_PREFIX)).distinct().toList()
                : request.reproIds;
        return !repro.isEmpty() && repro.stream().allMatch(id -> passes(graded, id));
    }

    /** 시나리오 하나가 min-repeat 번 이상 돌았고 그 실행이 모두 통과했으면 true 다. */
    private boolean passes(List<RunLedger.Run> graded, String scenarioId) {
        List<RunLedger.Run> runs = graded.stream().filter(run -> run.scenarioId().equals(scenarioId)).toList();
        return runs.size() >= minRepeat && runs.stream().allMatch(RunLedger.Run::passed);
    }

    /**
     * 의도가 지켜졌는지 본다(is_revert·weakens_contract 는 judgeCandidate 가 따로 본다). expectations 가 하나 이상이고, 그
     * expectations 로 채점한 실행이 하나 이상이며 모두 통과했고, scope 가 있는 expectation 마다 그 expectation 이 채점에 붙은 실행
     * (장부 intent_matches 에 그 자리가 든 실행)이 하나 이상 있어야 참이다(V3-4). scope 가 null 인 expectation 은 모든 실행에 붙으므로
     * 채점한 실행이 하나라도 있으면 시험된 것으로 본다. intent_matches 칸이 생기기 전 줄은 아무 expectation 에도 맞지 않은 것으로 본다.
     */
    private static boolean keepsIntent(Request request, List<RunLedger.Run> graded) {
        if (request.expectations.isEmpty() || graded.isEmpty() || !graded.stream().allMatch(RunLedger.Run::passed)) {
            return false;
        }
        List<Integer> canonical = IntentPlan.canonicalIndices(request.expectations);
        for (int i = 0; i < request.expectations.size(); i++) {
            if (request.expectations.get(i).scope() == null) {
                continue;
            }
            int index = canonical.get(i);
            if (graded.stream().noneMatch(run -> run.intentMatches() != null && run.intentMatches().contains(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 후보가 담당자의 수정을 되돌렸는지 본다. 담당자가 바꾼 SKILL.md 는 기준본 manifest 의 SKILL.md 해시와 연습 공간 시작 원문의 해시가
     * 다른 skill 이다(기준본에 없는 skill 은 보지 않는다). 그 가운데 하나라도 후보를 넣은 지금 원문의 해시가 기준본과 같으면, 즉 바이트까지
     * 같으면 true 다. 기준본 해시(metadata 의 baseline_snapshot_id 가 없거나 snapshot_id 와 같음)나 연습 공간 시작 원문이 없어 계산할 수
     * 없으면 null 이다. 이때 judgeCandidate 는 keeps_intent 를 거짓으로 둔다.
     */
    private static Boolean isRevert(Request request, WorkspaceEvidence workspace) {
        Map<String, String> base = request.baselineHashes();
        if (workspace == null || workspace.startTexts() == null || base == null) {
            return null;
        }
        for (Map.Entry<String, String> start : workspace.startTexts().entrySet()) {
            String baseHash = base.get(start.getKey());
            String now = workspace.currentTexts().get(start.getKey());
            if (baseHash != null && now != null && !baseHash.equals(sha256(start.getValue()))
                    && baseHash.equals(sha256(now))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 후보가 계약을 약하게 만들었는지 본다(V3-7, service·Agent·QA 가 같은 정의). 후보가 바꾼 skill(시작 원문과 지금 원문이 다른 skill,
     * 지금 원문이 없으면 빈 글로 본다)마다 다음 가운데 하나면 true 다.
     * (1) 규칙을 지웠다: rules 항목 수가 줄었다.
     * (2) 규칙의 when 을 바꿨다: 시작 원문 rules 의 when(앞뒤 공백만 뗀 글자)이 지금 원문 rules 에 같은 개수만큼 남아 있지 않다.
     * 순서를 바꾸거나 규칙을 더하는 것, then·reason 만 바꾸는 것은 약화가 아니다.
     * (3) 쓰이는 키를 덮던 input 을 지웠다: 시작 원문 inputs 에 있고 지금 원문 inputs 에 없는 input 가운데, 시작·지금 원문 rules 의
     * when 이나 의도 expectations 의 scope 가 쓰는 값 이름을 덮던(같거나 그 이름이 input + '.' 로 시작) 것이 있다. 다른 input 이 여전히
     * 그 키를 덮어도 약화다.
     * 연습 공간 시작 원문이 없어 계산할 수 없으면 null 이다. 이때 judgeCandidate 는 keeps_intent 를 거짓으로 둔다.
     */
    private static Boolean weakensContract(Request request, WorkspaceEvidence workspace) {
        if (workspace == null || workspace.startTexts() == null) {
            return null;
        }
        List<String> scopeKeys = request.expectations.stream().map(IntentExpectation::scope).filter(Objects::nonNull)
                .map(ProposalJudge::keyOf).filter(Objects::nonNull).toList();
        for (Map.Entry<String, String> start : workspace.startTexts().entrySet()) {
            String now = workspace.currentTexts().get(start.getKey());
            if (start.getValue().equals(now)) {
                continue;
            }
            Map<String, Object> before = fieldsOf(start.getValue());
            Map<String, Object> after = now == null ? Map.of() : fieldsOf(now);
            List<String> beforeWhens = whensOf(before);
            List<String> afterWhens = whensOf(after);
            if (rulesOf(after).size() < rulesOf(before).size() || !containsEach(afterWhens, beforeWhens)) {
                return true;
            }
            Set<String> usedKeys = new LinkedHashSet<>(scopeKeys);
            beforeWhens.stream().map(ProposalJudge::keyOf).filter(Objects::nonNull).forEach(usedKeys::add);
            afterWhens.stream().map(ProposalJudge::keyOf).filter(Objects::nonNull).forEach(usedKeys::add);
            List<String> removed = new ArrayList<>(strings(before.get("inputs")));
            removed.removeAll(strings(after.get("inputs")));
            for (String input : removed) {
                if (usedKeys.stream().anyMatch(key -> SkillRules.isCoveredBy(key, List.of(input)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** whole 이 part 의 글자마다 같은 개수 이상을 담고 있으면 true 다(중복을 세는 포함 관계). */
    private static boolean containsEach(List<String> whole, List<String> part) {
        Map<String, Integer> left = new HashMap<>();
        whole.forEach(item -> left.merge(item, 1, Integer::sum));
        for (String item : part) {
            if (left.merge(item, -1, Integer::sum) < 0) {
                return false;
            }
        }
        return true;
    }

    /** 원문의 frontmatter 를 읽는다. 읽을 수 없으면 빈 맵이다. */
    private static Map<String, Object> fieldsOf(String text) {
        try {
            return SkillFrontmatter.parse(text);
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }

    /** rules 칸의 항목 가운데 Map 인 것만 모은다. 형식 검사는 하지 않는다. */
    private static List<Map<String, Object>> rulesOf(Map<String, Object> fields) {
        return maps(fields.get(SkillRules.FIELD));
    }

    /** rules 칸에서 글자인 when 만 앞뒤 공백을 떼고 모은다. 형식 검사는 하지 않는다. */
    private static List<String> whensOf(Map<String, Object> fields) {
        return rulesOf(fields).stream().map(rule -> rule.get("when"))
                .filter(String.class::isInstance).map(when -> ((String) when).strip()).toList();
    }

    /** 조건 한 줄의 값 이름이다. 읽을 수 없으면 null 이다. */
    private static String keyOf(String condition) {
        try {
            return Check.parse(condition).key();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** results 의 칸을 서버 값으로 바꾼다. Agent 가 그 칸을 보냈고 값이 다르면 server_overrides 에 남긴다. */
    private static void replace(Map<String, Object> results, String field, boolean server, Integer no,
            List<Map<String, Object>> overrides) {
        Object agent = results.get(field);
        if (agent != null && !Boolean.valueOf(server).equals(agent)) {
            overrides.add(override(no, "results." + field, agent, server));
        }
        results.put(field, server);
    }

    /** 후보 자체의 칸(is_revert, weakens_contract)을 서버 값으로 바꾼다. 기록 규칙은 replace 와 같다. */
    private static void replaceCandidate(Map<String, Object> candidate, String field, boolean server, Integer no,
            List<Map<String, Object>> overrides) {
        Object agent = candidate.get(field);
        if (agent != null && !Boolean.valueOf(server).equals(agent)) {
            overrides.add(override(no, field, agent, server));
        }
        candidate.put(field, server);
    }

    /** keeps_old_orders 를 {passed, total} 서버 값으로 바꾼다. Agent 가 보낸 passed 나 total 이 다르면 기록한다. */
    private void replaceOldOrders(Map<String, Object> results, int passed, Integer no,
            List<Map<String, Object>> overrides) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("passed", passed);
        server.put("total", goldenIds.size());
        Object agent = results.get("keeps_old_orders");
        if (agent != null) {
            Map<String, Object> sent = map(agent);
            if (sent == null || !Objects.equals(integer(sent.get("passed")), passed)
                    || !Objects.equals(integer(sent.get("total")), goldenIds.size())) {
                overrides.add(override(no, "results.keeps_old_orders", agent, server));
            }
        }
        results.put("keeps_old_orders", server);
    }

    /** server_overrides 의 한 칸 {candidate_no, field, agent_value, server_value} 을 만든다. 제안서 전체의 칸이면 candidate_no 가 null 이다. */
    private static Map<String, Object> override(Integer candidateNo, String field, Object agentValue, Object serverValue) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("candidate_no", candidateNo);
        entry.put("field", field);
        entry.put("agent_value", agentValue);
        entry.put("server_value", serverValue);
        return entry;
    }

    /** JSON 모양(Map, List, 글자와 숫자)의 값을 고칠 수 있는 사본으로 깊게 복사한다. 받은 제안서를 바꾸지 않으려는 것이다. */
    @SuppressWarnings("unchecked")
    private static <T> T copyOf(T value) {
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, item) -> copy.put(String.valueOf(key), copyOf(item)));
            return (T) copy;
        }
        if (value instanceof List<?> source) {
            List<Object> copy = new ArrayList<>();
            source.forEach(item -> copy.add(copyOf(item)));
            return (T) copy;
        }
        return value;
    }

    /** UTF-8 글의 SHA-256 을 소문자 16진수로 낸다. 주문 Agent manifest 의 파일 해시와 같은 계산이다. */
    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 제안서 하나를 판정하는 동안 함께 쓰는 값이다. 진단한 snapshot 번호, 의도 요청인지, 기준이 되는 expectations, 판정에 쓸 실행의
     * checks_hash, 재현 시나리오와 원인 근거 실행, 재현 근거가 확인됐는지, 기준본 번호와 해시(처음 쓸 때 한 번만 읽는다)를 담는다.
     * 의도 요청인지, expectations 의 담당자 몫, 기준본 번호는 snapshot metadata 에서만 읽는다(제안서의 trigger 는 보지 않는다).
     */
    private final class Request {
        final String snapshotId;
        final boolean hasIntent;
        final List<IntentExpectation> expectations;
        /** 판정에 쓸 실행의 checks_hash 다. 의도와 expectations 가 있으면 그 지문, 아니면 null(덧붙인 조건 없는 실행)이다. */
        final String runHash;
        final List<String> reproIds;
        final List<String> proofRunIds;
        /** metadata 의 baseline_snapshot_id 다. 의도 요청이 아니거나 없으면 null 이다. */
        final String baselineId;
        /** repro_scenario_ids 마다 원인 근거 실행 가운데 떨어진 실행이 있으면 true 다. checkReproProof 가 거짓으로 바꾼다. */
        boolean reproProven = true;
        private boolean baselineRead;
        private Map<String, String> baselineHashes;

        /** metadata 는 proposal.snapshot_id 의 snapshot metadata 다. 시험한 후보가 없어 읽지 않았으면 빈 맵이다. */
        Request(Map<String, Object> payload, Map<String, Object> metadata) {
            this.snapshotId = str(payload.get("snapshot_id"));
            Map<String, Object> ownerIntent = map(metadata.get("intent"));
            this.hasIntent = ownerIntent != null;
            List<IntentExpectation> owner = ownerIntent == null ? List.of() : expectationsOf(ownerIntent.get("expectations"));
            Map<String, Object> inferred = map(payload.get("intent"));
            this.expectations = !hasIntent ? List.of()
                    : !owner.isEmpty() ? owner : inferred == null ? List.of() : expectationsOf(inferred.get("expectations"));
            this.runHash = expectations.isEmpty() ? null : IntentPlan.intentHash(expectations);
            this.baselineId = hasIntent ? str(metadata.get("baseline_snapshot_id")) : null;
            Map<String, Object> root = map(payload.get("root_cause"));
            this.reproIds = root == null ? List.of() : strings(root.get("repro_scenario_ids"));
            this.proofRunIds = root == null ? List.of() : strings(root.get("proof_run_ids"));
        }

        /**
         * 기준본의 skill 별 SKILL.md 해시다. 기준본 번호가 없거나 진단한 snapshot 자신이면 계산할 근거가 없으므로 null 이다.
         * 주문 Agent 에서 읽지 못하면 그 예외를 그대로 올린다(저장 거부). 한 번만 읽는다.
         */
        Map<String, String> baselineHashes() {
            if (!baselineRead) {
                baselineRead = true;
                if (baselineId == null || baselineId.equals(snapshotId)) {
                    log.warn("snapshot {} metadata 의 baseline_snapshot_id({})로 is_revert 를 계산할 수 없어 keeps_intent 를 거짓으로 둡니다",
                            snapshotId, baselineId);
                } else {
                    baselineHashes = baseline.skillHashes(baselineId);
                }
            }
            return baselineHashes;
        }

        /** expectations 칸(목록)을 IntentExpectation 목록으로 읽는다. scope 가 글자가 아니면 null 로 본다. */
        private static List<IntentExpectation> expectationsOf(Object value) {
            return maps(value).stream()
                    .map(e -> new IntentExpectation(e.get("scope") instanceof String scope ? scope : null,
                            strings(e.get("checks"))))
                    .toList();
        }
    }
}
