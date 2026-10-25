package com.capstone4.skillops.evaluation;

import java.util.*;
import org.springframework.stereotype.Component;
import com.capstone4.skillops.scenario.*;
import com.capstone4.skillops.snapshot.*;
import com.capstone4.skillops.skill.*;
import com.capstone4.skillops.workspace.*;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;

/** 불변 snapshot만 실행하며 Python 선택기·자동 수정 경로를 호출하지 않는다. */
@Component
public class EvaluationRunner {
    private final OrderAgentClient snapshots;
    private final OrderAgentRunClient executions;
    private final ScenarioGrader grader;
    /** 격리 서버 설정이 있을 때만 새 실행 경계에서 해당 서버를 사용한다. */
    public EvaluationRunner(OrderAgentClient snapshots, OrderAgentRunClient executions,
            ScenarioGrader grader, EvaluationConfiguration.Settings settings) {
        this.snapshots=settings.fixedHarnessEnabled() && settings.isolatedOrderAgentUrl()!=null
                ? new OrderAgentClient(settings.isolatedOrderAgentUrl()) : snapshots;
        this.executions=settings.fixedHarnessEnabled() && settings.isolatedOrderAgentUrl()!=null
                ? new OrderAgentRunClient(settings.isolatedOrderAgentUrl()) : executions;
        this.grader=grader;
    }
    /** 원문·manifest 해시와 정적 검증을 접수 시점에 고정한다. */
    public FrozenSnapshot freeze(String id) {
        SnapshotManifest manifest=snapshots.getSnapshot(id);
        EvaluationException.require(manifest!=null && manifest.hash()!=null && manifest.files()!=null,"snapshot manifest가 불완전합니다");
        Map<String,String> texts=new TreeMap<>();
        List<String> errors=new ArrayList<>();
        for (SnapshotManifest.FileEntry file:manifest.files()) {
            if (!file.path().matches("skills/[^/]+/SKILL\\.md")) { continue; }
            String text=snapshots.readText(id,file.path());
            if (!EvaluationJson.textHash(text).equals(file.hash())) { throw new EvaluationException(409,"EVALUATION_INPUT_CHANGED","snapshot 파일 해시가 다릅니다"); }
            String skill=file.path().split("/")[1];
            EvaluationException.require(texts.put(skill,text)==null,"중복된 snapshot 경로입니다");
            SkillMetadataValidator.validate(skill,text).forEach(error -> errors.add(error.toString()));
            try { SkillHeader.parse(text); }
            catch (IllegalArgumentException error) { errors.add(skill+": "+error.getMessage()); }
        }
        if (texts.isEmpty()) { errors.add("SKILL 문서가 없습니다"); }
        try {
            Map<String,SkillSource> sources=new TreeMap<>();
            texts.forEach((name,text) -> sources.put(name,SkillSource.fromText("skills/"+name+"/SKILL.md",text)));
            DependencyGraph graph=DependencyAnalyzer.analyze("order-flow",sources);
            FlowStructureValidator.validate(graph).forEach(error -> errors.add(error.toString()));
            graph.missing().forEach(error -> errors.add(error.toString()));
        } catch (RuntimeException error) { errors.add("flow: "+error.getMessage()); }
        return new FrozenSnapshot(id,manifest.hash(),Map.copyOf(texts),List.copyOf(errors));
    }
    /** 요청한 snapshot의 실행 결과와 실제 trace를 함께 채점한다. */
    public Trial execute(String attemptId, Plan plan, CaseInput item, Arm arm, int round) {
        FrozenSnapshot snapshot=arm==Arm.BASELINE ? plan.baseline() : plan.candidate();
        String trialId=UUID.nameUUIDFromBytes((attemptId+":"+arm+":"+item.caseId()+":"+round).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        long started=System.nanoTime();
        RunOutcome outcome;
        String executionId=null;
        Map<String,Object> trace=Map.of();
        if (plan.request().runnerKind()==Runner.CONTRACT_ONLY) {
            Map<String,WorkspaceSkill> skills=new TreeMap<>();
            snapshot.skills().forEach((name,text) -> skills.put(name,new WorkspaceSkill(SkillHeader.parse(text).version(),text,0)));
            outcome=new ContractOnlyFlowRunner().runDryRun(new FlowRunner.DryRunRequest(item.caseId(),item.order(),null,skills));
        } else {
            OrderAgentRunClient.DryRunStarted result=executions.dryRun(snapshot.snapshotId(),item.order());
            executionId=result.executionId();
            try { trace=executions.trace(executionId); }
            catch (RuntimeException error) {
                return new Trial(trialId,attemptId,arm,item.caseId(),item.suite(),round,result.runId(),executionId,
                        Verdict.UNMEASURABLE,Verdict.UNMEASURABLE,Verdict.UNMEASURABLE,null,List.of("trace 조회 실패"),
                        "TRACE_MISSING",(System.nanoTime()-started)/1000000,null,null,null,Map.of());
            }
            if (!snapshot.snapshotId().equals(String.valueOf(trace.get("snapshot_id")))
                    || !snapshot.hash().equals(trace.get("snapshot_hash"))
                    || !result.runId().equals(trace.get("run_id"))
                    || !result.executionId().equals(String.valueOf(trace.get("execution_id")))
                    || !"dry_run".equals(trace.get("mode"))
                    || !"fixed".equals(trace.get("model_mode"))
                    || !EvaluationPolicy.FIXED_MODEL.equals(trace.get("model"))
                    || !(trace.get("api_calls") instanceof Number calls) || calls.longValue()!=0) {
                return new Trial(trialId,attemptId,arm,item.caseId(),item.suite(),round,result.runId(),executionId,
                        Verdict.UNMEASURABLE,Verdict.UNMEASURABLE,Verdict.UNMEASURABLE,null,
                        List.of("설정한 fixed snapshot 실행과 실제 trace가 다릅니다"),"EVIDENCE_MISMATCH",
                        (System.nanoTime()-started)/1000000,null,null,null,trace);
            }
            outcome=OrderAgentTraceConverter.toOutcome(result.runId(),trace);
        }
        Scenario.Expect expected=arm==Arm.BASELINE ? item.baselineExpectation() : item.candidateExpectation();
        GradeResult task=grader.grade(Expectation.of(expected),List.of(),outcome);
        List<String> intentChecks=item.intentChecks()==null ? List.of() : item.intentChecks();
        GradeResult intent=grader.grade(new Expectation(null,null,null,List.of(),false),intentChecks.stream().map(Check::parse).toList(),outcome);
        Selection expectedSelection=arm==Arm.BASELINE ? item.baselineSelection() : item.candidateSelection();
        String raw=trace.get("raw_selection") instanceof String selected ? selected : null;
        Verdict selection=selectionVerdict(expectedSelection,raw,snapshot.skills().keySet());
        List<String> reasons=new ArrayList<>(task.reasons());
        if (!intentChecks.isEmpty()) { reasons.addAll(intent.reasons()); }
        if (selection==Verdict.FAIL) { reasons.add("선택 기대 조건을 만족하지 않았습니다: "+raw); }
        return new Trial(trialId,attemptId,arm,item.caseId(),item.suite(),round,outcome.runId(),executionId,
                task.passed()?Verdict.PASS:Verdict.FAIL,selection,
                intentChecks.isEmpty()?Verdict.NOT_APPLICABLE:(intent.passed()?Verdict.PASS:Verdict.FAIL),raw,reasons,
                selection==Verdict.FAIL?"INVALID_SELECTION":(task.passed()?null:"TASK_FAILED"),
                (System.nanoTime()-started)/1000000,null,null,
                plan.request().runnerKind()==Runner.ORDER_HARNESS?"0":null,trace);
    }
    /** 미관측·실제 none·미등록 Skill을 서로 구분한다. */
    public static Verdict selectionVerdict(Selection expected,String raw,Set<String> known) {
        if (expected==null) { return Verdict.NOT_APPLICABLE; }
        if (raw==null) { return Verdict.UNMEASURABLE; }
        if (!"none".equals(raw) && !known.contains(raw)) { return Verdict.FAIL; }
        boolean hit=expected.mode()==SelectionMode.EXACT ? expected.skill().equals(raw) : !expected.skill().equals(raw);
        return hit?Verdict.PASS:Verdict.FAIL;
    }
}
