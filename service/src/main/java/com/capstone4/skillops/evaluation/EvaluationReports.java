package com.capstone4.skillops.evaluation;

import java.math.BigDecimal;
import java.util.*;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;

/** 제출된 성공 주장이 아닌 저장된 trial로 비교 결과를 계산한다. */
public final class EvaluationReports {
    /** 순수 집계 함수만 제공한다. */
    private EvaluationReports() {}
    /** 누락과 실행 유형을 포함해 최종 판정을 계산한다. */
    public static Report build(String jobId,String attemptId,Status status,Plan plan,List<Trial> trials) {
        int planned=plan.evaluationSet().cases().size()*plan.request().repeat()*2;
        List<Trial> baseline=trials.stream().filter(row -> row.arm()==Arm.BASELINE).toList();
        List<Trial> candidate=trials.stream().filter(row -> row.arm()==Arm.CANDIDATE).toList();
        Map<String,Trial> before=new HashMap<>();
        baseline.forEach(row -> before.put(pairKey(row),row));
        List<String> regressions=new ArrayList<>();
        int paired=0;
        for (Trial row:candidate) {
            Trial old=before.get(pairKey(row));
            if (old!=null && measurable(old) && measurable(row)) {
                paired++;
                CaseInput item=plan.evaluationSet().cases().stream().filter(value -> value.caseId().equals(row.caseId())).findFirst().orElseThrow();
                if (item.suite()!=Suite.NEW_SKILL && passed(old) && !passed(row)) { regressions.add(row.caseId()); }
            }
        }
        List<String> reasons=new ArrayList<>();
        Verdict verdict=Verdict.PASS;
        if (status!=Status.SUCCEEDED || trials.size()!=planned || paired!=planned/2) {
            verdict=Verdict.INCONCLUSIVE; reasons.add("필수 실행이 미완료이거나 측정할 수 없습니다");
        } else if (candidate.stream().anyMatch(row -> !passed(row)) || !regressions.isEmpty()) {
            verdict=Verdict.FAIL; reasons.add("후보의 필수 검사 실패 또는 기존 기능 회귀가 있습니다");
        }
        boolean verified=verdict==Verdict.PASS && plan.labelsReviewed()
                && plan.request().runnerKind()==Runner.ORDER_HARNESS
                && plan.request().purpose()==Purpose.CANDIDATE_VERIFICATION
                && plan.candidate().errors().isEmpty()
                && !plan.baseline().hash().equals(plan.candidate().hash())
                && baseline.stream().anyMatch(row -> row.suite()==Suite.REPRODUCTION && row.task()==Verdict.FAIL);
        String verification=verified?"SERVER_VERIFIED":"NOT_VERIFIED";
        if (!verified) { reasons.add("실제 fixed harness·검토된 라벨·후보 검증 목적·실패 재현·완료 결과가 모두 필요합니다"); }
        int completed=(int)trials.stream().filter(row -> row.task()==Verdict.PASS || row.task()==Verdict.FAIL).count();
        return new Report(jobId,attemptId,status,verdict,verification,planned,completed,paired,
                regressions.size(),regressions.stream().distinct().sorted().toList(),metrics(plan,baseline,Arm.BASELINE),
                metrics(plan,candidate,Arm.CANDIDATE),reasons,plan.policyVersion(),plan.graderVersion(),Verdict.UNMEASURABLE,
                new Provenance(plan.baseline().snapshotId(),plan.baseline().hash(),plan.candidate().snapshotId(),plan.candidate().hash(),
                        plan.request().evaluationSetId(),plan.request().evaluationSetVersion(),plan.setHash(),plan.request().environmentRef(),
                        plan.request().runnerKind(),plan.request().modelMode(),plan.request().repeat()));
    }
    /** 같은 사례·회차를 기준과 후보 사이에서 대응시킨다. */
    private static String pairKey(Trial row) { return row.caseId()+":"+row.repeatIndex(); }
    /** 업무·선택·의도 중 필수 관측이 빠졌는지 확인한다. */
    private static boolean measurable(Trial row) {
        return List.of(row.task(),row.selection(),row.intent()).stream()
                .noneMatch(value -> value==Verdict.UNMEASURABLE || value==Verdict.INCONCLUSIVE);
    }
    /** 해당 trial에 필요한 검사가 모두 통과했는지 확인한다. */
    public static boolean passed(Trial row) {
        return measurable(row) && List.of(row.task(),row.selection(),row.intent()).stream().noneMatch(value -> value==Verdict.FAIL);
    }
    /** 분모 0을 0%로 치환하지 않는다. */
    public static Ratio ratio(long numerator,long denominator) {
        return new Ratio(numerator,denominator,denominator==0?null:(double)numerator/denominator);
    }
    /** 원시 표본으로 업무·선택·대상 Skill·비용을 집계한다. */
    private static Metrics metrics(Plan plan,List<Trial> rows,Arm arm) {
        long tp=0,fn=0,fp=0;
        String target=plan.evaluationSet().targetSkill();
        for (Trial row:rows) {
            CaseInput item=plan.evaluationSet().cases().stream().filter(value -> value.caseId().equals(row.caseId())).findFirst().orElseThrow();
            Selection expected=arm==Arm.BASELINE?item.baselineSelection():item.candidateSelection();
            if (target==null || expected==null || row.rawSelection()==null) { continue; }
            boolean positive=expected.mode()==SelectionMode.EXACT && target.equals(expected.skill());
            boolean selected=target.equals(row.rawSelection());
            if (positive) { if (selected) { tp++; } else { fn++; } }
            else if (selected && (expected.mode()==SelectionMode.EXACT || target.equals(expected.skill()))) { fp++; }
        }
        List<Double> rounds=new ArrayList<>();
        for (int round=0;round<plan.request().repeat();round++) {
            final int index=round;
            List<Trial> measured=rows.stream().filter(row -> row.repeatIndex()==index && row.task()!=Verdict.UNMEASURABLE).toList();
            if (measured.size()==plan.evaluationSet().cases().size()) { rounds.add((double)measured.stream().filter(row -> row.task()==Verdict.PASS).count()/measured.size()); }
        }
        Double variance=null;
        if (rounds.size()>1) {
            double mean=rounds.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            variance=rounds.stream().mapToDouble(value -> (value-mean)*(value-mean)).sum()/(rounds.size()-1);
        }
        boolean allCost=!rows.isEmpty() && rows.stream().allMatch(row -> row.costUsd()!=null);
        return new Metrics(ratio(rows.stream().filter(row -> row.task()==Verdict.PASS).count(),
                rows.stream().filter(row -> row.task()==Verdict.PASS || row.task()==Verdict.FAIL).count()),
                ratio(rows.stream().filter(row -> row.selection()==Verdict.PASS).count(),
                rows.stream().filter(row -> row.selection()==Verdict.PASS || row.selection()==Verdict.FAIL).count()),
                ratio(tp,tp+fn),ratio(tp,tp+fp),sum(rows,0),sum(rows,1),sum(rows,2),
                allCost?rows.stream().map(row -> new BigDecimal(row.costUsd())).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString():null,
                allCost && sum(rows,1)!=null && sum(rows,2)!=null,rounds,variance);
    }
    /** 한 개라도 미관측이면 부분 합계를 확정 합계로 반환하지 않는다. */
    private static Long sum(List<Trial> rows,int field) {
        if (rows.isEmpty()) { return null; }
        long total=0;
        for (Trial row:rows) {
            Long value=switch(field) { case 0 -> row.elapsedMs(); case 1 -> row.inputTokens(); default -> row.outputTokens(); };
            if (value==null) { return null; }
            total+=value;
        }
        return total;
    }
}
