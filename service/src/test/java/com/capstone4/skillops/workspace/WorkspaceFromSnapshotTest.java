package com.capstone4.skillops.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.capstone4.skillops.snapshot.OrderAgentClient;
import com.capstone4.skillops.snapshot.OrderAgentException;
import com.capstone4.skillops.snapshot.SnapshotManifest;
import com.capstone4.skillops.snapshot.SnapshotManifest.FileEntry;

/** 주문 Agent snapshot 에서 연습 공간을 시작하는 경로. 주문 Agent 는 Mockito 로 대신한다. */
class WorkspaceFromSnapshotTest {

    static final String SNAPSHOT = "11111111-2222-3333-4444-555555555555";

    WorkspaceRepository repository = mock(WorkspaceRepository.class);
    SkillVersionStore store = mock(SkillVersionStore.class);
    OrderAgentClient orderAgent = mock(OrderAgentClient.class);
    WorkspaceService service = new WorkspaceService(repository, store, orderAgent);
    /** manifest 에 올라간 파일들. serve() 를 부를 때마다 하나씩 늘어난다. */
    List<FileEntry> entries = new ArrayList<>();

    @BeforeEach
    void saveReturnsItsArgument() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void copiesSkillsFromSnapshot() {
        serve("skills/payment/SKILL.md", skillText("payment", 1));
        serve("skills/order-flow/SKILL.md", skillText("order-flow", 1));

        var created = service.create(null, SNAPSHOT);

        assertThat(created.workspaceId()).startsWith("w_");
        assertThat(created.skillVersions()).isEqualTo(Map.of("order-flow", 1, "payment", 1));
        WorkspaceRecord saved = savedRecord();
        assertThat(saved.getSourceSnapshotId()).isEqualTo(SNAPSHOT);
        assertThat(saved.getSkills()).containsOnlyKeys("order-flow", "payment");
        assertThat(saved.getSkills().get("payment")).isEqualTo(new WorkspaceSkill(1, skillText("payment", 1)));
        assertThat(saved.getResources()).isEmpty();
        // 운영 폴더는 읽지 않는다
        verifyNoInteractions(store);
    }

    @Test
    void usesTheVersionInTheSnapshotHeaderNotTheLiveOne() {
        serve("skills/inventory-check/SKILL.md", skillText("inventory-check", 3));

        assertThat(service.create(null, SNAPSHOT).skillVersions()).isEqualTo(Map.of("inventory-check", 3));
        assertThat(savedRecord().getSkills().get("inventory-check").baseVersion()).isEqualTo(3);
    }

    @Test
    void copiesResourcesAndTrailingNewline() {
        String skillText = skillText("inventory-check", 1) + "\n";
        String policy = "재고 정책\n- 안전 재고 5\n";
        serve("skills/inventory-check/SKILL.md", skillText);
        serve("skills/inventory-check/resources/policy.md", policy);

        service.create(null, SNAPSHOT);

        WorkspaceRecord saved = savedRecord();
        assertThat(saved.getSkills().get("inventory-check").fullText()).isEqualTo(skillText).endsWith("\n");
        assertThat(saved.getResources())
                .containsOnlyKeys("skills/inventory-check/resources/policy.md")
                .containsEntry("skills/inventory-check/resources/policy.md", policy);
        assertThat(saved.getResources().get("skills/inventory-check/resources/policy.md")).endsWith("\n");
    }

    @Test
    void doesNotReadResourcesAsSkillsOrSkillsAsResources() {
        serve("skills/inventory-check/SKILL.md", skillText("inventory-check", 1));
        serve("skills/inventory-check/resources/SKILL.md", "중첩 폴더의 SKILL.md 는 자원이다\n");

        service.create(null, SNAPSHOT);

        WorkspaceRecord saved = savedRecord();
        assertThat(saved.getSkills()).containsOnlyKeys("inventory-check");
        assertThat(saved.getResources()).containsOnlyKeys("skills/inventory-check/resources/SKILL.md");
    }

    @Test
    void rejectsBaseWithSnapshot() {
        assertThatThrownBy(() -> service.create(Map.of("payment", 1), "uuid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("snapshot_id 와 start_versions 는 함께 쓸 수 없습니다");
        verifyNoInteractions(orderAgent);
        verify(repository, never()).save(any());
    }

    @Test
    void emptyStartVersionsCountsAsNotGiven() {
        serve("skills/payment/SKILL.md", skillText("payment", 1));

        assertThat(service.create(Map.of(), SNAPSHOT).skillVersions()).isEqualTo(Map.of("payment", 1));
    }

    @Test
    void nullSnapshotStartsFromLiveVersionsWithoutTouchingOrderAgent() {
        when(store.liveVersions()).thenReturn(Map.of("payment", 2));
        when(store.fullText("payment", 2)).thenReturn("text-payment-v2");

        var created = service.create(null, null);

        assertThat(created.skillVersions()).isEqualTo(Map.of("payment", 2));
        WorkspaceRecord saved = savedRecord();
        assertThat(saved.getSourceSnapshotId()).isNull();
        assertThat(saved.getResources()).isEmpty();
        verifyNoInteractions(orderAgent);
    }

    @Test
    void orderAgentFailureSavesNothing() {
        serve("skills/payment/SKILL.md", skillText("payment", 1));
        serve("skills/payment/resources/policy.md", "x");
        when(orderAgent.readText(SNAPSHOT, "skills/payment/resources/policy.md")).thenThrow(
                new OrderAgentException("ORDER_AGENT_UNAVAILABLE", "주문 Agent 에 연결하지 못했습니다", true, null, SNAPSHOT));

        assertThatThrownBy(() -> service.create(null, SNAPSHOT))
                .isInstanceOfSatisfying(OrderAgentException.class, e -> {
                    assertThat(e.code()).isEqualTo("ORDER_AGENT_UNAVAILABLE");
                    assertThat(e.retryable()).isTrue();
                });
        verify(repository, never()).save(any());
    }

    @Test
    void unknownSnapshotFailureIsNotTurnedIntoAnEmptyWorkspace() {
        when(orderAgent.getSnapshot(SNAPSHOT)).thenThrow(
                new OrderAgentException("SNAPSHOT_NOT_FOUND", "snapshot 이 없습니다", false, 404, SNAPSHOT));

        assertThatThrownBy(() -> service.create(null, SNAPSHOT))
                .isInstanceOfSatisfying(OrderAgentException.class, e -> assertThat(e.code()).isEqualTo("SNAPSHOT_NOT_FOUND"));
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsSnapshotWithoutAnySkill() {
        manifest("skills/inventory-check/resources/policy.md");

        assertThatThrownBy(() -> service.create(null, SNAPSHOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(SNAPSHOT)
                .hasMessageContaining("SKILL.md");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsSkillWithoutVersionInHeader() {
        serve("skills/payment/SKILL.md", "---\nname: payment\n---\n본문\n");

        assertThatThrownBy(() -> service.create(null, SNAPSHOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skills/payment/SKILL.md")
                .hasMessageContaining("version");
        verify(repository, never()).save(any());
    }

    @Test
    void filesOfMergesSkillsAndResources() {
        Map<String, WorkspaceSkill> skills = new HashMap<>();
        skills.put("inventory-check", new WorkspaceSkill(1, "inventory text\n"));
        skills.put("payment", new WorkspaceSkill(1, "payment text"));
        Map<String, String> resources = Map.of("skills/inventory-check/resources/policy.md", "policy\n");
        when(repository.findById("w_a")).thenReturn(Optional.of(
                WorkspaceRecord.open("w_a", Instant.parse("2026-10-21T00:00:00Z"), skills, resources, SNAPSHOT)));

        assertThat(service.filesOf("w_a")).isEqualTo(Map.of(
                "skills/inventory-check/SKILL.md", "inventory text\n",
                "skills/payment/SKILL.md", "payment text",
                "skills/inventory-check/resources/policy.md", "policy\n"));
    }

    @Test
    void filesOfTreatsMissingResourcesAsEmpty() {
        // 이 칸이 생기기 전에 만든 연습 공간: resources, source_snapshot_id 가 null
        when(repository.findById("w_old")).thenReturn(Optional.of(WorkspaceRecord.open(
                "w_old", Instant.parse("2026-10-20T00:00:00Z"), Map.of("payment", new WorkspaceSkill(1, "payment text")),
                null, null)));

        assertThat(service.filesOf("w_old")).isEqualTo(Map.of("skills/payment/SKILL.md", "payment text"));
    }

    @Test
    void filesOfUnknownWorkspaceFails() {
        when(repository.findById("w_x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.filesOf("w_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workspace_id w_x 이 없습니다. create_workspace 로 먼저 만드세요");
    }

    /** 주문 Agent 실행기(harness)는 snapshot 에서 시작한 연습 공간만 돌린다. 그 판단에 쓴다. */
    @Test
    void sourceSnapshotIdOfTellsWhereTheWorkspaceStarted() {
        when(repository.findById("w_a")).thenReturn(Optional.of(WorkspaceRecord.open("w_a",
                Instant.parse("2026-10-21T00:00:00Z"), Map.of("payment", new WorkspaceSkill(1, "payment text")),
                Map.of(), SNAPSHOT)));
        when(repository.findById("w_live")).thenReturn(Optional.of(WorkspaceRecord.open("w_live",
                Instant.parse("2026-10-21T00:00:00Z"), Map.of("payment", new WorkspaceSkill(1, "payment text")))));
        when(repository.findById("w_x")).thenReturn(Optional.empty());

        assertThat(service.sourceSnapshotIdOf("w_a")).isEqualTo(SNAPSHOT);
        assertThat(service.sourceSnapshotIdOf("w_live")).isNull();
        assertThatThrownBy(() -> service.sourceSnapshotIdOf("w_x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workspace_id w_x 이 없습니다. create_workspace 로 먼저 만드세요");
    }

    @Test
    void snapshotStartWithoutOrderAgentConfiguredFails() {
        WorkspaceService withoutClient = new WorkspaceService(repository, store);

        assertThatThrownBy(() -> withoutClient.create(null, SNAPSHOT)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    // ---- 도우미 ----

    private static String skillText(String name, int version) {
        return "---\nname: " + name + "\nversion: " + version + "\n---\n# " + name + "\n";
    }

    /** 이 snapshot 의 manifest 에 path 가 있고, readText 가 text 를 돌려주게 한다. 이미 있던 manifest 는 이어 붙인다. */
    private void serve(String path, String text) {
        manifest(path);
        when(orderAgent.readText(SNAPSHOT, path)).thenReturn(text);
    }

    private void manifest(String path) {
        entries.add(new FileEntry(path, "hash-" + entries.size(), 10));
        when(orderAgent.getSnapshot(SNAPSHOT)).thenReturn(new SnapshotManifest(
                SNAPSHOT, "snapshot-hash", "2026-10-21T00:00:00Z", Map.of(), List.copyOf(entries)));
    }

    private WorkspaceRecord savedRecord() {
        ArgumentCaptor<WorkspaceRecord> captor = ArgumentCaptor.forClass(WorkspaceRecord.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
