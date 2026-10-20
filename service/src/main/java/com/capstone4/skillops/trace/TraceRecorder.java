package com.capstone4.skillops.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * harness(A)가 주문 한 건을 처리하면서 부르는 기록 API. start → step × n → finish 순서로 부른다.
 * 진행 중인 실행은 메모리에만 두고 finish 때 한 번에 저장한다. 계약 2 의 final_status 가 필수라
 * 끝나지 않은 실행을 DB 에 반쯤 남기지 않기 위해서다.
 */
@Service
public class TraceRecorder {

    private final TraceRepository repository;
    private final Map<String, Trace> running = new ConcurrentHashMap<>();

    public TraceRecorder(TraceRepository repository) {
        this.repository = repository;
    }

    /**
     * @param workspace     dry-run 이면 ws_id, live 면 null
     * @param scenarioId    시나리오 러너가 돌린 실행이면 그 id, 아니면 null
     * @param skillVersions 이 실행에 로드된 skill → version
     * @return 새 run_id (r_ 로 시작)
     */
    public String start(String orderId, RunMode mode, String workspace, String scenarioId,
            Map<String, Integer> skillVersions) {
        String runId = "r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        running.put(runId, new Trace(runId, orderId, mode, workspace, scenarioId, Instant.now(),
                Map.copyOf(skillVersions), new ArrayList<>(), null));
        return runId;
    }

    public void step(String runId, TraceStep step) {
        Trace trace = runningOrThrow(runId, running.get(runId));
        synchronized (trace) {
            trace.steps().add(step);
        }
    }

    @Transactional
    public Trace finish(String runId, FinalStatus finalStatus) {
        Trace trace = runningOrThrow(runId, running.remove(runId));
        Trace finished = new Trace(trace.runId(), trace.orderId(), trace.mode(), trace.workspace(),
                trace.scenarioId(), trace.startedAt(), trace.skillVersions(), List.copyOf(trace.steps()),
                finalStatus);
        repository.save(TraceRecord.from(finished));
        return finished;
    }

    private static Trace runningOrThrow(String runId, Trace trace) {
        if (trace == null) {
            throw new IllegalStateException("진행 중인 실행이 아닙니다: " + runId + " (start 전이거나 이미 finish 됨)");
        }
        return trace;
    }
}
