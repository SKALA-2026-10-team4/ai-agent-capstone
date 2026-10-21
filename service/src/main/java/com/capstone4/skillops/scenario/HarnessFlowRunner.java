package com.capstone4.skillops.scenario;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.workspace.WorkspaceService;

/**
 * 연습 공간의 skill 로 주문 Agent 에서 실제로 모의 실행하는 실행기({@code skillops.flow-runner=harness}).
 * 1) 연습 공간의 파일 전부(filesOf, resources 포함)를 후보 snapshot 으로 올린다. 같은 묶음이면 다시 올리지 않는다.
 * 2) 시험 주문을 그 snapshot 으로 모의 실행한다. 주문 Agent 가 재고를 되돌리고, 실행은 live 실패 목록에 나오지 않는다.
 * 3) 실행 기록을 읽어 {@link OrderAgentTraceConverter} 로 RunOutcome 을 만든다.
 * 주문 Agent snapshot 에서 시작한 연습 공간만 받는다. 운영 폴더(skills/)의 SKILL.md 는 주문 Agent 가 돌리는 글과 다르고
 * resources/policy.md 도 없어서, 그 결과로는 주문 Agent 에서 고쳐질지 판정할 수 없다.
 */
@Component
@ConditionalOnProperty(name = "skillops.flow-runner", havingValue = "harness")
public class HarnessFlowRunner implements FlowRunner {

    /** 주문 Agent 모의 실행에 넘기는 주문 칸. 시험 주문 order 의 다른 칸은 보내지 않는다. */
    static final List<String> ORDER_FIELDS = List.of("customer", "items", "payment_method", "shipping");

    private final WorkspaceService workspaces;
    private final OrderAgentRunClient orderAgent;
    /** 파일 묶음 SHA-256 → 주문 Agent 후보 snapshot_id. 서비스가 켜져 있는 동안만 기억한다. */
    private final Map<String, String> snapshots = new ConcurrentHashMap<>();
    /**
     * 모의 실행은 도는 동안 실제 재고를 잡는다. 여러 연습 공간(MCP 세션)이 함께 불러도 한 번에 하나만 돌려,
     * 다른 시험이 잡은 재고 때문에 REJECTED 가 나오지 않게 한다.
     */
    private final ReentrantLock oneAtATime = new ReentrantLock(true);

    public HarnessFlowRunner(WorkspaceService workspaces, OrderAgentRunClient orderAgent) {
        this.workspaces = workspaces;
        this.orderAgent = orderAgent;
    }

    @Override
    public RunOutcome runDryRun(DryRunRequest request) {
        String workspaceId = request.workspaceId();
        String sourceSnapshotId = workspaces.sourceSnapshotIdOf(workspaceId);
        if (sourceSnapshotId == null) {
            throw new IllegalArgumentException("연습 공간 " + workspaceId + " 은 운영 폴더에서 시작해 주문 Agent 에서 돌릴 수 없습니다. "
                    + "주문 Agent 에서 돌리려면 create_workspace 에 snapshot_id 를 넣어 연습 공간을 만드세요");
        }
        if (request.order() == null) {
            throw new IllegalArgumentException("시험 주문 " + request.scenarioId() + " 에 order 가 없습니다");
        }
        Map<String, String> files = workspaces.filesOf(workspaceId);
        String bundle = bundleHash(files);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workspace_id", workspaceId);
        metadata.put("source_snapshot_id", sourceSnapshotId);
        Map<String, Object> order = new LinkedHashMap<>();
        ORDER_FIELDS.stream().filter(request.order()::containsKey).forEach(f -> order.put(f, request.order().get(f)));

        OrderAgentRunClient.DryRunStarted started;
        oneAtATime.lock();
        try {
            String cached = snapshots.get(bundle);
            String snapshotId = cached != null ? cached : publish(bundle, files, metadata);
            try {
                started = orderAgent.dryRun(snapshotId, order);
            } catch (OrderAgentRunClient.Rejected e) {
                if (cached == null || !e.snapshotNotFound()) {
                    throw e;
                }
                // 주문 Agent 를 다시 켜면 DB 가 비어 기억해 둔 snapshot 이 사라진다. 한 번만 다시 올리고 다시 돌린다.
                snapshots.remove(bundle, cached);
                started = orderAgent.dryRun(publish(bundle, files, metadata), order);
            }
        } finally {
            oneAtATime.unlock();
        }
        return OrderAgentTraceConverter.toOutcome(started.runId(), orderAgent.trace(started.executionId()));
    }

    @Override
    public boolean supportsReplay() {
        return true;
    }

    private String publish(String bundle, Map<String, String> files, Map<String, Object> metadata) {
        String snapshotId = orderAgent.publishCandidate(files, metadata);
        snapshots.put(bundle, snapshotId);
        return snapshotId;
    }

    /** 경로 순으로 (경로, 글) 을 길이와 함께 이어 SHA-256 을 낸다. 길이를 넣어 경계가 다른 두 묶음이 같아지지 않게 한다. */
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

    private static void update(MessageDigest sha, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        sha.update(bytes);
    }
}
