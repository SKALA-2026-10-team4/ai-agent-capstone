package com.capstone4.skillops.scenario;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.workspace.WorkspaceService;

/**
 * skillops.flow-runner=harness 일 때 쓰는 실제 실행기다. 연습 공간의 skill 로 주문 Agent(order-agent/)에서 시험 주문을 모의 실행한다.
 * 1) 연습 공간의 파일 전부(WorkspaceService.filesOf, resources 포함)를 후보 snapshot 으로 올린다. 같은 묶음이면 다시 올리지 않는다.
 * 2) 시험 주문을 그 snapshot 으로 모의 실행한다. 주문 Agent 가 끝난 뒤 재고를 되돌리고, 이 실행은 live 실패 목록에 나오지 않는다.
 * 3) 실행 기록을 읽어 {@link OrderAgentTraceConverter} 로 RunOutcome 을 만든다.
 * 주문 Agent snapshot 에서 시작한 연습 공간만 받는다. 운영 폴더(skills/)의 SKILL.md 는 주문 Agent 가 돌리는 글과 다르고
 * resources/policy.md 도 없어서, 그 결과로는 주문 Agent 에서 고쳐질지 판정할 수 없기 때문이다.
 *
 * Spring 이 @ConditionalOnProperty 로 이 빈을 고르고, service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의
 * runScenario() 가 runDryRun 을 부른다. 주문 Agent 호출은 service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 가 맡는다.
 * 모의 실행은 공정 락 oneAtATime 으로 이 서비스 안에서 한 번에 하나만 돈다. ScenarioRunner 의 순차 반복과 이 락이 함께
 * 실행 순서를 정하므로, 병렬로 돌리는 것을 검토할 때는 두 곳을 같이 본다. snapshot 을 다시 올리는 정책은 startWithCachedSnapshot 에 있다.
 */
@Component
@ConditionalOnProperty(name = "skillops.flow-runner", havingValue = "harness")
public class HarnessFlowRunner implements FlowRunner {

    private final WorkspaceService workspaces;
    private final OrderAgentRunClient orderAgent;
    /**
     * 파일 묶음의 SHA-256(bundleHash)을 키로, 그 묶음을 올려 받은 주문 Agent 후보 snapshot_id 를 값으로 기억한다.
     * 서비스가 켜져 있는 동안만 기억하며 크기 제한은 없다.
     */
    private final Map<String, String> snapshots = new ConcurrentHashMap<>();
    /**
     * 모의 실행은 도는 동안 실제 재고를 잡는다. 여러 연습 공간(MCP 세션)이 함께 불러도 한 번에 하나만 돌려,
     * 다른 시험이 잡은 재고 때문에 REJECTED 가 나오지 않게 한다. 공정 락이라 먼저 기다린 호출이 먼저 들어간다.
     */
    private final ReentrantLock oneAtATime = new ReentrantLock(true);

    /** Spring 이 생성자로 연습 공간 서비스와 주문 Agent 클라이언트를 주입한다. */
    public HarnessFlowRunner(WorkspaceService workspaces, OrderAgentRunClient orderAgent) {
        this.workspaces = workspaces;
        this.orderAgent = orderAgent;
    }

    /**
     * 시험 주문 하나를 주문 Agent 에서 모의 실행하고 실행 기록을 RunOutcome 으로 바꿔 돌려준다.
     * 연습 공간이 snapshot 에서 시작했는지와 order 가 있는지를 파일 목록을 읽기 전에 먼저 검사한다.
     * snapshot 을 정하고 모의 실행하는 부분만 락 안에서 하고, 실행 기록 읽기와 변환은 락 밖에서 한다.
     * POST /api/dry-runs 는 실행이 끝날 때까지 기다리는 동기 호출이라 락은 모의 실행이 끝날 때까지 잡혀 있다.
     * request.skills() 는 쓰지 않고, filesOf 로 resources 까지 포함한 파일을 다시 읽는다.
     */
    @Override
    public RunOutcome runDryRun(DryRunRequest request) {
        String workspaceId = request.workspaceId();
        String sourceSnapshotId = requireSnapshotStart(workspaceId);
        Map<String, Object> order = orderFieldsOf(request);
        Map<String, String> files = workspaces.filesOf(workspaceId);
        String bundle = bundleHash(files);
        Map<String, Object> metadata = metadataOf(workspaceId, sourceSnapshotId);

        OrderAgentRunClient.DryRunStarted started = startOneAtATime(bundle, files, metadata, order);
        return OrderAgentTraceConverter.toOutcome(started.runId(), orderAgent.trace(started.executionId()));
    }

    /** 주문 Agent 에서 실제로 돌리므로 재현 주문을 받는다. */
    @Override
    public boolean supportsReplay() {
        return true;
    }

    /**
     * 연습 공간을 시작한 주문 Agent snapshot 번호를 돌려준다. 운영 폴더에서 시작한 연습 공간이면 create_workspace 에
     * snapshot_id 를 넣으라는 IllegalArgumentException 을 던진다. 없는 연습 공간이면 WorkspaceService.sourceSnapshotIdOf 의 오류가 나간다.
     */
    private String requireSnapshotStart(String workspaceId) {
        String sourceSnapshotId = workspaces.sourceSnapshotIdOf(workspaceId);
        if (sourceSnapshotId == null) {
            throw new IllegalArgumentException("연습 공간 " + workspaceId + " 은 운영 폴더에서 시작해 주문 Agent 에서 돌릴 수 없습니다. "
                    + "주문 Agent 에서 돌리려면 create_workspace 에 snapshot_id 를 넣어 연습 공간을 만드세요");
        }
        return sourceSnapshotId;
    }

    /**
     * 시험 주문 order 에서 모의 실행에 넘길 칸(OrderAgentRunClient.DRY_RUN_ORDER_FIELDS)만 추린다.
     * 없는 칸은 보내지 않고, 값이 null 인 칸은 null 로 보낸다. order 가 없으면 주문 Agent 를 부르기 전에 IllegalArgumentException 을 던진다.
     */
    private static Map<String, Object> orderFieldsOf(DryRunRequest request) {
        if (request.order() == null) {
            throw new IllegalArgumentException("시험 주문 " + request.scenarioId() + " 에 order 가 없습니다");
        }
        Map<String, Object> order = new LinkedHashMap<>();
        OrderAgentRunClient.DRY_RUN_ORDER_FIELDS.stream().filter(request.order()::containsKey)
                .forEach(f -> order.put(f, request.order().get(f)));
        return order;
    }

    /**
     * 후보 snapshot 에 붙이는 출처 정보(workspace_id, source_snapshot_id)를 만든다. 주문 Agent 의
     * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 에 있는 publishCandidate 가
     * 여기에 source=candidate 를 더해 저장한다.
     */
    private static Map<String, Object> metadataOf(String workspaceId, String sourceSnapshotId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workspace_id", workspaceId);
        metadata.put("source_snapshot_id", sourceSnapshotId);
        return metadata;
    }

    /**
     * 공정 락 안에서 snapshot 을 정하고 모의 실행까지만 한다. 실행 기록 읽기는 부르는 쪽(runDryRun)이 락 밖에서 한다.
     * 락 범위를 바꾸려면 이 메서드를 고친다.
     */
    private OrderAgentRunClient.DryRunStarted startOneAtATime(String bundle, Map<String, String> files,
            Map<String, Object> metadata, Map<String, Object> order) {
        oneAtATime.lock();
        try {
            return startWithCachedSnapshot(bundle, files, metadata, order);
        } finally {
            oneAtATime.unlock();
        }
    }

    /**
     * 같은 묶음이면 기억해 둔 snapshot 으로, 아니면 새로 올린 snapshot 으로 모의 실행한다. snapshot 을 다시 올리는 정책이 여기 있다.
     * 기억해 둔 snapshot 을 주문 Agent 가 SNAPSHOT_NOT_FOUND(404)로 거절했을 때만 한 번 다시 올리고 다시 돌린다.
     * 방금 올린 snapshot 이 거절됐거나 다른 이유로 거절됐으면 다시 올려도 같으므로 그 거절을 그대로 던진다.
     */
    private OrderAgentRunClient.DryRunStarted startWithCachedSnapshot(String bundle, Map<String, String> files,
            Map<String, Object> metadata, Map<String, Object> order) {
        String cached = snapshots.get(bundle);
        String snapshotId = cached != null ? cached : publish(bundle, files, metadata);
        try {
            return orderAgent.dryRun(snapshotId, order);
        } catch (OrderAgentRunClient.Rejected e) {
            if (cached == null || !e.snapshotNotFound()) {
                throw e;
            }
            // 주문 Agent 를 다시 켜면 DB 가 비어 기억해 둔 snapshot 이 사라진다. 한 번만 다시 올리고 다시 돌린다.
            snapshots.remove(bundle, cached);
            return orderAgent.dryRun(publish(bundle, files, metadata), order);
        }
    }

    /** 파일 묶음을 후보 snapshot 으로 올리고, 받은 snapshot_id 를 묶음 해시와 함께 기억한 뒤 돌려준다. */
    private String publish(String bundle, Map<String, String> files, Map<String, Object> metadata) {
        String snapshotId = orderAgent.publishCandidate(files, metadata);
        snapshots.put(bundle, snapshotId);
        return snapshotId;
    }

    /**
     * 파일 묶음의 SHA-256 을 16진수로 낸다. 경로 순으로 경로와 글을 각각 길이와 함께 이어 넣는다.
     * 길이를 넣는 것은 경계만 다른 두 묶음이 같은 해시가 되지 않게 하려는 것이다. 넘겨받은 Map 의 순서와 상관없이 같은 글이면 같은 해시다.
     * runDryRun 이 부르고, 그 해시를 snapshots 의 키로 쓴다.
     */
    static String bundleHash(Map<String, String> files) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        new TreeMap<>(files).forEach((path, text) -> {
            update(sha, path);
            update(sha, text);
        });
        return HexFormat.of().formatHex(sha.digest());
    }

    /** 글을 UTF-8 바이트 길이(4바이트 정수) 다음에 그 바이트를 이어 해시에 넣는다. */
    private static void update(MessageDigest sha, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        sha.update(bytes);
    }
}
