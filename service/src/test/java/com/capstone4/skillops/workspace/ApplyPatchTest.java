package com.capstone4.skillops.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.capstone4.skillops.scenario.TestFixtures;
import com.capstone4.skillops.workspace.WorkspaceService.BrokenLink;
import com.capstone4.skillops.workspace.WorkspaceService.ChangedSkill;
import com.capstone4.skillops.workspace.WorkspaceService.PatchInput;
import com.capstone4.skillops.workspace.WorkspaceService.PatchResult;

/** 분석기(DependencyAnalyzer)와 수정 적용기(PatchApplier)는 진짜를 쓰고, 저장소만 Mockito 로 대신한다. */
class ApplyPatchTest {

    WorkspaceRepository repository = mock(WorkspaceRepository.class);
    WorkspaceService service = new WorkspaceService(repository, mock(SkillVersionStore.class));
    /** F1 broken 상태로 시작한 연습 공간 w_1 */
    WorkspaceRecord record = WorkspaceRecord.open("w_1", Instant.parse("2026-10-21T00:00:00Z"), F1Texts.brokenSkills());

    @BeforeEach
    void workspaceExists() {
        when(repository.findById("w_1")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void appliesAndReportsNoViolations() {
        PatchResult result = service.apply("w_1", List.of(new PatchInput("inventory-check", 1, F1Texts.repairOutputsPatch())));

        assertThat(result.workspaceId()).isEqualTo("w_1");
        assertThat(result.changedSkills()).containsExactly(new ChangedSkill("inventory-check", 2));
        assertThat(result.brokenLinks()).isEmpty();
        WorkspaceSkill saved = savedRecord().getSkills().get("inventory-check");
        assertThat(saved).isEqualTo(new WorkspaceSkill(1, F1Texts.repaired(), 1));
    }

    @Test
    void reportsViolationsForBreakingPatch() {
        record = WorkspaceRecord.open("w_1", Instant.parse("2026-10-21T00:00:00Z"), TestFixtures.liveSkills());
        when(repository.findById("w_1")).thenReturn(Optional.of(record));

        PatchResult result = service.apply("w_1", List.of(new PatchInput("inventory-check", 1, F1Texts.breakOutputsPatch())));

        assertThat(result.changedSkills()).containsExactly(new ChangedSkill("inventory-check", 2));
        assertThat(result.brokenLinks()).containsExactly(new BrokenLink("payment", "inventory.reserved"));
        // 끊긴 연결이 있어도 수정은 연습 공간에 들어간다. 판단은 부른 쪽이 한다.
        assertThat(savedRecord().getSkills().get("inventory-check").fullText()).isEqualTo(F1Texts.broken());
    }

    @Test
    void reportsBrokenLinksThatWereAlreadyThere() {
        // 연습 공간이 이미 끊겨 있으면 다른 skill 만 고쳐도 남은 끊긴 연결을 그대로 보고한다
        String diff = "@@ -3,1 +3,1 @@\n-description: 주문 금액을 결제한다. 결제 정책은 여기서 바꾼다.\n+description: 주문 금액을 결제한다.\n";

        PatchResult result = service.apply("w_1", List.of(new PatchInput("payment", 1, diff)));

        assertThat(result.changedSkills()).containsExactly(new ChangedSkill("payment", 2));
        assertThat(result.brokenLinks()).containsExactly(new BrokenLink("payment", "inventory.reserved"));
    }

    @Test
    void contextMismatchRejectsAndKeepsWorkspace() {
        var patches = List.of(
                new PatchInput("inventory-check", 1, F1Texts.repairOutputsPatch()),
                new PatchInput("payment", 1, "@@ -2,1 +2,1 @@\n-name: 없는 이름\n+name: payment\n"));

        assertThatThrownBy(() -> service.apply("w_1", patches))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("payment: ")
                .hasMessageContaining("2번째 줄 근처가 수정 내용과 맞지 않아");
        verify(repository, never()).save(any());
        // 첫 번째 수정은 들어갔다가 취소된 것이 아니라 처음부터 연습 공간에 닿지 않았다
        assertThat(record.getSkills().get("inventory-check")).isEqualTo(new WorkspaceSkill(1, F1Texts.broken(), 0));
    }

    @Test
    void baseVersionMismatchRejects() {
        var patches = List.of(new PatchInput("inventory-check", 2, F1Texts.repairOutputsPatch()));

        assertThatThrownBy(() -> service.apply("w_1", patches))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("inventory-check 의 기준 버전이 맞지 않습니다 (연습 공간 v1, 수정 내용 v2)");
        verify(repository, never()).save(any());
    }

    @Test
    void secondPatchOfSameSkillStillComparesWithStartVersion() {
        // 앞의 수정으로 revision 이 1 이 되어도 기준 버전은 만들 때 복사한 버전(1) 그대로다
        String second = "@@ -3,1 +3,1 @@\n-description: 주문 상품의 재고를 확인하고 예약한다.\n+description: 주문 상품의 재고를 확인한다.\n";
        var patches = List.of(
                new PatchInput("inventory-check", 1, F1Texts.repairOutputsPatch()),
                new PatchInput("inventory-check", 1, second));

        PatchResult result = service.apply("w_1", patches);

        assertThat(result.changedSkills()).containsExactly(new ChangedSkill("inventory-check", 3));
        WorkspaceSkill saved = savedRecord().getSkills().get("inventory-check");
        assertThat(saved.revision()).isEqualTo(2);
        assertThat(saved.fullText()).contains(F1Texts.REPAIRED_OUTPUTS).contains("description: 주문 상품의 재고를 확인한다.");
    }

    @Test
    void versionKeepsCountingAcrossCalls() {
        service.apply("w_1", List.of(new PatchInput("inventory-check", 1, F1Texts.repairOutputsPatch())));
        String again = "@@ -3,1 +3,1 @@\n-description: 주문 상품의 재고를 확인하고 예약한다.\n+description: 다시 고침\n";

        PatchResult result = service.apply("w_1", List.of(new PatchInput("inventory-check", 1, again)));

        assertThat(result.changedSkills()).containsExactly(new ChangedSkill("inventory-check", 3));
    }

    @Test
    void unknownSkillRejects() {
        var patches = List.of(new PatchInput("nope", 1, F1Texts.repairOutputsPatch()));

        assertThatThrownBy(() -> service.apply("w_1", patches))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("nope 이라는 skill 이 연습 공간에 없습니다. 있는 skill: ");
        verify(repository, never()).save(any());
    }

    @Test
    void unknownWorkspaceFailsLikeOtherTools() {
        when(repository.findById("w_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply("w_x", List.of(new PatchInput("inventory-check", 1, "@@ -1 +1 @@\n-a\n+b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workspace_id w_x 이 없습니다. create_workspace 로 먼저 만드세요");
    }

    @Test
    void emptyPatchesRejects() {
        assertThatThrownBy(() -> service.apply("w_1", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("patches");
        assertThatThrownBy(() -> service.apply("w_1", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void closedWorkspaceRejects() {
        ReflectionTestUtils.setField(record, "status", WorkspaceRecord.Status.PROMOTED);

        assertThatThrownBy(() -> service.apply("w_1", List.of(new PatchInput("inventory-check", 1, F1Texts.repairOutputsPatch()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workspace_id w_1 은 사용 중(OPEN)이 아니라 PROMOTED 상태라서 수정할 수 없습니다");
        verify(repository, never()).save(any());
    }

    @Test
    void missingSkillNameOrPatchRejects() {
        assertThatThrownBy(() -> service.apply("w_1", List.of(new PatchInput(null, 1, F1Texts.repairOutputsPatch()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill");
        assertThatThrownBy(() -> service.apply("w_1", List.of(new PatchInput("inventory-check", 1, null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inventory-check");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsPatchThatBreaksHeader() {
        // name 이 폴더 이름과 달라지면 분석기가 이 skill 을 읽지 못한다. 그런 수정은 저장하지 않는다.
        var patches = List.of(new PatchInput("inventory-check", 1, "@@ -2,1 +2,1 @@\n-name: inventory-check\n+name: stock-check\n"));

        assertThatThrownBy(() -> service.apply("w_1", patches))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("inventory-check: ")
                .hasMessageContaining("name 이 폴더 이름(inventory-check)과 다릅니다");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsPatchThatBreaksEntryFlow() {
        var patches = List.of(new PatchInput("order-flow", 1,
                "@@ -4,1 +4,1 @@\n-steps: [order-intake, inventory-check, payment, shipping-reserve, notify]\n+steps: 순서없음\n"));

        assertThatThrownBy(() -> service.apply("w_1", patches))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("order-flow: ")
                .hasMessageContaining("steps");
        verify(repository, never()).save(any());
    }

    @Test
    void workspaceWithoutEntryFlowCannotBeChecked() {
        // 고친 skill 때문이 아니라 연습 공간에 order-flow 가 없는 경우도 저장하지 않고 이유를 알린다
        record = WorkspaceRecord.open("w_1", Instant.parse("2026-10-21T00:00:00Z"),
                Map.of("inventory-check", new WorkspaceSkill(1, F1Texts.broken())));
        when(repository.findById("w_1")).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.apply("w_1", List.of(new PatchInput("inventory-check", 1, F1Texts.repairOutputsPatch()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("order-flow: ");
        verify(repository, never()).save(any());
    }

    private WorkspaceRecord savedRecord() {
        ArgumentCaptor<WorkspaceRecord> captor = ArgumentCaptor.forClass(WorkspaceRecord.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
