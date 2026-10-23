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
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.capstone4.skillops.scenario.TestFixtures;
import com.capstone4.skillops.workspace.WorkspaceService.BrokenLink;
import com.capstone4.skillops.workspace.WorkspaceService.ChangedSkill;
import com.capstone4.skillops.workspace.WorkspaceService.PatchInput;
import com.capstone4.skillops.workspace.WorkspaceService.PatchResult;

/**
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 apply(apply_patch 본체)를 보호한다.
 * 전부 적용하거나 하나도 적용하지 않는지, 실패하면 아무것도 저장하지 않는지, 기준 버전을 시작 버전과 비교하는지,
 * 버전 번호(시작 버전과 수정 횟수의 합)와 changed_skills 순서, broken_links 범위, 맨 위 칸을 깨는 수정과 entry flow 를 깨는
 * 수정의 거부, 오류 문구를 본다. 분석기(DependencyAnalyzer)와 수정 적용기(PatchApplier)는 진짜를 쓰고, 저장소만 Mockito 로
 * 대신한다. 시작 상태는 장애 F1 의 broken 연습 공간(F1Texts)이다.
 */
class ApplyPatchTest {

    WorkspaceRepository repository = mock(WorkspaceRepository.class);
    WorkspaceService service = new WorkspaceService(repository, mock(SkillVersionStore.class));
    /** 장애 F1 의 broken 상태로 시작한 연습 공간 w_1 이다. 몇몇 시험은 다른 원문으로 바꿔 넣는다. */
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
        // 연습 공간이 이미 끊겨 있으면 다른 skill 만 고쳐도 남은 끊긴 연결을 그대로 보고한다.
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
        // 첫 번째 수정은 들어갔다가 취소된 것이 아니라 처음부터 연습 공간에 닿지 않았다.
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
        // 앞의 수정으로 revision 이 1 이 되어도 기준 버전은 만들 때 복사한 버전(1) 그대로다.
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
        assertWorkspaceUntouched();
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
        assertWorkspaceUntouched();
    }

    @Test
    void workspaceWithoutEntryFlowCannotBeChecked() {
        // 고친 skill 때문이 아니라 연습 공간에 order-flow 가 없는 경우도 저장하지 않고 이유를 알린다.
        record = WorkspaceRecord.open("w_1", Instant.parse("2026-10-21T00:00:00Z"),
                Map.of("inventory-check", new WorkspaceSkill(1, F1Texts.broken())));
        when(repository.findById("w_1")).thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.apply("w_1", List.of(new PatchInput("inventory-check", 1, F1Texts.repairOutputsPatch()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("order-flow: ");
        verify(repository, never()).save(any());
    }

    /** changed_skills 는 처음 고친 순서이고, 같은 skill 을 여러 번 고치면 한 번만 나온다. */
    @Test
    void changedSkillsKeepFirstAppearanceOrder() {
        String payment1 = "@@ -3,1 +3,1 @@\n-description: 주문 금액을 결제한다. 결제 정책은 여기서 바꾼다.\n+description: 주문 금액을 결제한다.\n";
        String payment2 = "@@ -3,1 +3,1 @@\n-description: 주문 금액을 결제한다.\n+description: 결제한다.\n";
        var patches = List.of(
                new PatchInput("payment", 1, payment1),
                new PatchInput("inventory-check", 1, F1Texts.repairOutputsPatch()),
                new PatchInput("payment", 1, payment2));

        PatchResult result = service.apply("w_1", patches);

        assertThat(result.changedSkills())
                .containsExactly(new ChangedSkill("payment", 3), new ChangedSkill("inventory-check", 2));
        assertThat(result.brokenLinks()).isEmpty();
    }

    /** 맨 위 칸 검사는 고친 skill 만 본다. 원래부터 깨진 다른 skill 이 있어도 수정은 저장된다. */
    @Test
    void unchangedBrokenSkillDoesNotBlockPatch() {
        Map<String, WorkspaceSkill> skills = new TreeMap<>(F1Texts.brokenSkills());
        skills.put("notify", new WorkspaceSkill(1, skills.get("notify").fullText().replace("name: notify", "name: notice")));
        record = WorkspaceRecord.open("w_1", Instant.parse("2026-10-21T00:00:00Z"), skills);
        when(repository.findById("w_1")).thenReturn(Optional.of(record));

        PatchResult result = service.apply("w_1", List.of(new PatchInput("inventory-check", 1, F1Texts.repairOutputsPatch())));

        assertThat(result.changedSkills()).containsExactly(new ChangedSkill("inventory-check", 2));
        WorkspaceRecord saved = savedRecord();
        assertThat(saved.getSkills().get("inventory-check").fullText()).isEqualTo(F1Texts.repaired());
        assertThat(saved.getSkills().get("notify").fullText()).contains("name: notice");
    }

    /** broken_links 에는 빠진 입력만 넣는다. 먼저 끝나야 할 skill(depends_on)이 늦게 오는 것은 넣지 않는다. */
    @Test
    void missingDependenciesAreNotReportedAsBrokenLinks() {
        record = WorkspaceRecord.open("w_1", Instant.parse("2026-10-21T00:00:00Z"), TestFixtures.liveSkills());
        when(repository.findById("w_1")).thenReturn(Optional.of(record));
        String diff = "@@ -6,1 +6,1 @@\n-depends_on: [inventory-check]\n+depends_on: [notify]\n";

        PatchResult result = service.apply("w_1", List.of(new PatchInput("payment", 1, diff)));

        assertThat(result.changedSkills()).containsExactly(new ChangedSkill("payment", 2));
        assertThat(result.brokenLinks()).isEmpty();
        assertThat(savedRecord().getSkills().get("payment").fullText()).contains("depends_on: [notify]");
    }

    /** 실패한 수정이 불러온 연습 공간 원문을 건드리지 않았는지 확인한다. */
    private void assertWorkspaceUntouched() {
        assertThat(record.getSkills()).isEqualTo(F1Texts.brokenSkills());
    }

    /** repository.save 에 넘어간 WorkspaceRecord 를 꺼낸다. save 가 정확히 한 번 불렸는지도 함께 확인한다. */
    private WorkspaceRecord savedRecord() {
        ArgumentCaptor<WorkspaceRecord> captor = ArgumentCaptor.forClass(WorkspaceRecord.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
