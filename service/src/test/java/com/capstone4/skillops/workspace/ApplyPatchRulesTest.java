package com.capstone4.skillops.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.capstone4.skillops.skill.FlowStructureValidator;
import com.capstone4.skillops.skill.SkillMetadataValidator;
import com.capstone4.skillops.workspace.WorkspaceService.PatchInput;
import com.capstone4.skillops.workspace.WorkspaceService.PatchResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

/**
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 apply 가 실행 규칙(rules)을 정적으로 검사하는지,
 * 넣은 수정과 오류 수를 남기는지, run_scenarios 로 고정된 연습 공간을 WORKSPACE_FROZEN 으로 거부하는지 보호한다.
 * rules 검사는 주문 Agent 와 함께 쓰는 docs/contracts/examples/skill-rules-cases.json 의 모든 사례를 apply_patch 로 그대로 확인한다.
 * 사례마다 order-flow 본문에 한 줄을 더하는 수정을 넣고, 결과의 metadata_errors 와 flow_errors 가 사례의 기대와 정확히 같고 다른 오류가
 * 없는지 본다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.2 절).
 */
class ApplyPatchRulesTest {

    /** 공용 사례 파일의 모양이다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Cases(List<Case> cases) {
    }

    /** 사례 하나다. 기대 오류는 skill 과 field, 또는 skill 과 code, reason 만 적혀 있다. JSON 칸은 snake_case 다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Case(String id, Map<String, String> skills, List<Map<String, String>> metadataErrors,
            List<Map<String, String>> flowErrors) {

        @Override
        public String toString() {
            return id;
        }
    }

    WorkspaceRepository repository = mock(WorkspaceRepository.class);
    WorkspaceService service = new WorkspaceService(repository, mock(SkillVersionStore.class));

    /** 사례의 skill 묶음으로 연습 공간 w_rules 를 만들어 저장소 mock 이 돌려주게 한다. */
    WorkspaceRecord workspaceOf(Map<String, String> texts) {
        Map<String, WorkspaceSkill> skills = new TreeMap<>();
        texts.forEach((id, text) -> skills.put(id, new WorkspaceSkill(1, text)));
        WorkspaceRecord record = WorkspaceRecord.open("w_rules", Instant.parse("2026-10-26T00:00:00Z"), skills);
        when(repository.findById("w_rules")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return record;
    }

    /** order-flow 본문의 Body 줄 뒤에 한 줄을 더하는 수정이다. rules 와 연결에는 영향이 없다. */
    static PatchInput touchFlowBody(String flowText) {
        int line = Arrays.asList(flowText.split("\n", -1)).indexOf("Body") + 1;
        return new PatchInput("order-flow", 1, "@@ -" + line + ",1 +" + line + ",2 @@\n Body\n+More\n");
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void matchesSharedRulesCases(Case testCase) {
        workspaceOf(testCase.skills());

        PatchResult result = service.apply("w_rules", List.of(touchFlowBody(testCase.skills().get("order-flow"))));

        assertThat(result.brokenLinks()).as(testCase.id()).isEmpty();
        assertThat(result.dependencyViolations()).as(testCase.id()).isEmpty();
        assertThat(result.metadataErrors()).as(testCase.id())
                .extracting(SkillMetadataValidator.Violation::skill, SkillMetadataValidator.Violation::field)
                .containsExactlyElementsOf(testCase.metadataErrors().stream()
                        .map(e -> org.assertj.core.groups.Tuple.tuple(e.get("skill"), e.get("field"))).toList());
        assertThat(result.metadataErrors()).allSatisfy(e -> {
            assertThat(e.code()).isEqualTo("INVALID_SKILL_FORMAT");
            assertThat(e.message()).isNotBlank();
        });
        assertThat(result.flowErrors()).as(testCase.id())
                .extracting(FlowStructureValidator.Violation::skill, FlowStructureValidator.Violation::code,
                        FlowStructureValidator.Violation::reason)
                .containsExactlyElementsOf(testCase.flowErrors().stream()
                        .map(e -> org.assertj.core.groups.Tuple.tuple(e.get("skill"), e.get("code"), e.get("reason")))
                        .toList());
        assertThat(result.flowErrors()).allSatisfy(e -> assertThat(e.message()).isNotBlank());
    }

    /** apply 는 넣은 수정과 이번 결과의 오류 수를 연습 공간에 남긴다. 다음 apply 는 앞의 수정 뒤에 잇는다. */
    @Test
    void recordsAppliedPatchesAndErrorCounts() {
        Case keyNotInput = cases().filter(c -> c.id().equals("key-not-input")).findFirst().orElseThrow();
        WorkspaceRecord record = workspaceOf(keyNotInput.skills());
        PatchInput first = touchFlowBody(keyNotInput.skills().get("order-flow"));

        service.apply("w_rules", List.of(first));

        assertThat(record.getAppliedPatches()).containsExactly(new AppliedPatch("order-flow", 1, first.patch()));
        assertThat(record.getLastApply()).isEqualTo(new ApplyCheck(0, 0, 0, 1));
        assertThat(record.getStartTexts()).isEqualTo(keyNotInput.skills());
        assertThat(service.evidenceOf("w_rules")).hasValueSatisfying(evidence -> {
            assertThat(evidence.frozen()).isFalse();
            assertThat(evidence.appliedPatches()).hasSize(1);
            assertThat(evidence.startTexts()).isEqualTo(keyNotInput.skills());
            assertThat(evidence.currentTexts().get("order-flow")).endsWith("Body\nMore\n");
        });
    }

    /** run_scenarios 로 고정된 연습 공간은 WORKSPACE_FROZEN 으로 거부하고 아무것도 저장하지 않는다. */
    @Test
    void frozenWorkspaceRejectsPatches() {
        Case valid = cases().filter(c -> c.id().equals("valid-hold")).findFirst().orElseThrow();
        WorkspaceRecord record = workspaceOf(valid.skills());

        service.freeze("w_rules");
        Instant frozenAt = record.getFrozenAt();
        service.freeze("w_rules");

        assertThat(record.isFrozen()).isTrue();
        assertThat(record.getFrozenAt()).isEqualTo(frozenAt);
        assertThatThrownBy(() -> service.apply("w_rules", List.of(touchFlowBody(valid.skills().get("order-flow")))))
                .isInstanceOf(WorkspaceFrozenException.class).hasMessageContaining("w_rules");
        verify(repository).save(record);
        assertThat(record.getAppliedPatches()).isEmpty();
        assertThat(service.evidenceOf("w_rules")).hasValueSatisfying(e -> assertThat(e.frozen()).isTrue());
    }

    /** 없는 연습 공간은 예외 대신 빈 값이다. 재판정은 그 후보를 근거가 맞지 않는 후보로 본다. */
    @Test
    void evidenceOfUnknownWorkspaceIsEmpty() {
        when(repository.findById("w_none")).thenReturn(Optional.empty());

        assertThat(service.evidenceOf("w_none")).isEmpty();
        assertThat(service.evidenceOf(null)).isEmpty();
        verify(repository, never()).findById(null);
    }

    static Stream<Case> cases() {
        return JsonMapper.builder().build().readValue(
                Path.of("../docs/contracts/examples/skill-rules-cases.json").toFile(), Cases.class).cases().stream();
    }
}
