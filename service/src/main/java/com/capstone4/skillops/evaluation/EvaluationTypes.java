package com.capstone4.skillops.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.capstone4.skillops.scenario.Scenario;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** 평가 경계에서 사용하는 타입이다. 미관측 값은 null로 보존한다. */
public final class EvaluationTypes {
    /** 정적 계약 타입만 제공한다. */
    private EvaluationTypes() {}
    public enum Runner { CONTRACT_ONLY, ORDER_HARNESS }
    public enum Purpose { CHANGE_COMPARISON, CAUSE_ANALYSIS, CANDIDATE_VERIFICATION }
    public enum Suite { REGRESSION, REPRODUCTION, INTENT, NEW_SKILL }
    public enum Status { QUEUED, RUNNING, CANCEL_REQUESTED, SUCCEEDED, BLOCKED, FAILED, STOPPED, CANCELLED, INTERRUPTED }
    public enum Verdict { PASS, FAIL, INCONCLUSIVE, NOT_APPLICABLE, UNMEASURABLE }
    public enum Arm { BASELINE, CANDIDATE }
    public enum SelectionMode { EXACT, FORBID }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Selection(SelectionMode mode, String skill) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CaseInput(String caseId, Suite suite, Map<String,Object> order,
            Scenario.Expect baselineExpectation, Scenario.Expect candidateExpectation,
            Selection baselineSelection, Selection candidateSelection, List<String> intentChecks) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SetInput(String name, String targetSkill, List<CaseInput> cases) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SetView(String evaluationSetId, int version, String sha256, boolean reviewed, String reviewedBy) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SetDetail(SetView metadata, SetInput definition) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Limits(long wallTimeMs, long maxTotalTokens, String maxCostUsd) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Submit(String baselineSnapshotId, String candidateSnapshotId, String evaluationSetId,
            int evaluationSetVersion, Runner runnerKind, String modelMode, int repeat,
            Purpose purpose, String environmentRef, Limits limits) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FrozenSnapshot(String snapshotId, String hash, Map<String,String> skills, List<String> errors) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Plan(Submit request, SetInput evaluationSet, String setHash, boolean labelsReviewed,
            FrozenSnapshot baseline, FrozenSnapshot candidate, String policyVersion, String graderVersion) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Accepted(String evaluationId, String attemptId, Status status, String statusUrl) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record View(String evaluationId, String attemptId, Status status, String reason, int planned,
            long completed, String baselineSnapshotId, String candidateSnapshotId, String setHash,
            Runner runnerKind, String modelMode, Instant createdAt) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AttemptView(String attemptId, String parentAttemptId, Status status, String reason, Instant createdAt) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Provenance(String baselineSnapshotId, String baselineHash, String candidateSnapshotId,
            String candidateHash, String evaluationSetId, int evaluationSetVersion, String evaluationSetHash,
            String environmentRef, Runner runnerKind, String modelMode, int repeat) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Trial(String trialId, String attemptId, Arm arm, String caseId, Suite suite, int repeatIndex,
            String runId, String executionId, Verdict task, Verdict selection, Verdict intent,
            String rawSelection, List<String> reasons, String errorCode, Long elapsedMs,
            Long inputTokens, Long outputTokens, String costUsd, Map<String,Object> trace) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Ratio(long numerator, long denominator, Double value) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Metrics(Ratio taskSuccess, Ratio selectionAccuracy, Ratio recall, Ratio precision,
            Long elapsedMs, Long inputTokens, Long outputTokens, String costUsd,
            boolean usageComplete, List<Double> successByRound, Double successVariance) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Report(String evaluationId, String attemptId, Status status, Verdict verdict,
            String verificationStatus, int planned, int completed, int paired, int regressionTrials,
            List<String> regressionCaseIds, Metrics baseline, Metrics candidate, List<String> reasons,
            String policyVersion, String graderVersion, Verdict safety, Provenance input) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Page<T>(List<T> items, Integer nextPage) {}
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CasePage<T>(List<T> items, Integer nextOffset) {}
}
