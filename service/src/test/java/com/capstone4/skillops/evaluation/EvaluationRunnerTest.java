package com.capstone4.skillops.evaluation;

import java.util.*;
import org.junit.jupiter.api.Test;
import com.capstone4.skillops.scenario.*;
import com.capstone4.skillops.snapshot.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;
import static com.capstone4.skillops.evaluation.EvaluationFixtures.*;

class EvaluationRunnerTest {
    private final OrderAgentClient snapshots=mock(OrderAgentClient.class);
    private final OrderAgentRunClient executions=mock(OrderAgentRunClient.class);
    private final EvaluationRunner runner=new EvaluationRunner(snapshots,executions,new ScenarioGrader(),new EvaluationConfiguration.Settings(false,null,null));
    /** CRLF와 본문 원문을 정규화하지 않고 hash와 함께 고정한다. */
    @Test void snapshotContentIsPinnedAndNotFetchedAgainDuringExecution() {
        Map<String,String> texts=new TreeMap<>();
        skills().forEach((name,text) -> texts.put(name,text.replace("\n","\r\n")));
        List<SnapshotManifest.FileEntry> files=texts.entrySet().stream().map(row -> new SnapshotManifest.FileEntry("skills/"+row.getKey()+"/SKILL.md",EvaluationJson.textHash(row.getValue()),row.getValue().length())).toList();
        when(snapshots.getSnapshot("snapshot")).thenReturn(new SnapshotManifest("snapshot","hash",null,Map.of(),files));
        texts.forEach((name,text) -> when(snapshots.readText("snapshot","skills/"+name+"/SKILL.md")).thenReturn(text));
        FrozenSnapshot frozen=runner.freeze("snapshot");
        assertThat(frozen.errors()).isEmpty();
        assertThat(frozen.skills()).isEqualTo(texts);
        Plan old=plan(Runner.CONTRACT_ONLY);
        Plan fixed=new Plan(old.request(),old.evaluationSet(),old.setHash(),true,frozen,frozen,old.policyVersion(),old.graderVersion());
        reset(snapshots);
        Trial result=runner.execute("attempt",fixed,set().cases().getFirst(),Arm.CANDIDATE,0);
        assertThat(result.task()).isEqualTo(Verdict.PASS);
        verifyNoInteractions(snapshots,executions);
    }
    /** 변조된 원문은 저장 전에 거부한다. */
    @Test void mismatchedFileHashRejectsSubmission() {
        when(snapshots.getSnapshot("snapshot")).thenReturn(new SnapshotManifest("snapshot","hash",null,Map.of(),List.of(new SnapshotManifest.FileEntry("skills/order-flow/SKILL.md","bad",10))));
        when(snapshots.readText("snapshot","skills/order-flow/SKILL.md")).thenReturn("changed");
        assertThatThrownBy(() -> runner.freeze("snapshot")).isInstanceOf(EvaluationException.class).hasMessageContaining("해시");
    }
    /** 읽을 수 없는 문서는 실행 실패와 구분해 증거로 보존한다. */
    @Test void brokenYamlIsRecordedAsPreflightErrors() {
        String text="---\nname: [\n---\nbody";
        when(snapshots.getSnapshot("snapshot")).thenReturn(new SnapshotManifest("snapshot","hash",null,Map.of(),List.of(new SnapshotManifest.FileEntry("skills/order-flow/SKILL.md",EvaluationJson.textHash(text),text.length()))));
        when(snapshots.readText("snapshot","skills/order-flow/SKILL.md")).thenReturn(text);
        FrozenSnapshot frozen=runner.freeze("snapshot");
        assertThat(frozen.errors()).isNotEmpty();
        assertThat(frozen.skills()).containsEntry("order-flow",text);
        verifyNoInteractions(executions);
    }
    /** trace 조회가 실패해도 이미 알려진 실행 번호를 잃지 않는다. */
    @Test void missingTracePreservesActualExecutionIds() {
        Plan plan=plan(Runner.ORDER_HARNESS);
        when(executions.dryRun(plan.candidate().snapshotId(),set().cases().getFirst().order()))
                .thenReturn(new OrderAgentRunClient.DryRunStarted("exec","run","SHIPPED"));
        when(executions.trace("exec")).thenThrow(new IllegalStateException("timeout"));
        Trial result=runner.execute("attempt",plan,set().cases().getFirst(),Arm.CANDIDATE,0);
        assertThat(result.runId()).isEqualTo("run");
        assertThat(result.executionId()).isEqualTo("exec");
        assertThat(result.task()).isEqualTo(Verdict.UNMEASURABLE);
        assertThat(result.errorCode()).isEqualTo("TRACE_MISSING");
    }
    /** 예상과 다른 실제 모델이 실행되면 fixed 결과로 인정하지 않는다. */
    @Test void unexpectedRealModelIsNotVerified() {
        Plan plan=plan(Runner.ORDER_HARNESS);
        when(executions.dryRun(plan.candidate().snapshotId(),set().cases().getFirst().order()))
                .thenReturn(new OrderAgentRunClient.DryRunStarted("exec","run","SHIPPED"));
        when(executions.trace("exec")).thenReturn(Map.of("snapshot_id",plan.candidate().snapshotId(),"snapshot_hash",plan.candidate().hash(),
                "run_id","run","execution_id","exec","mode","dry_run","model_mode","openai","api_calls",1));
        Trial result=runner.execute("attempt",plan,set().cases().getFirst(),Arm.CANDIDATE,0);
        assertThat(result.errorCode()).isEqualTo("EVIDENCE_MISMATCH");
        assertThat(result.costUsd()).isNull();
    }
}
