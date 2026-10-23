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

/**
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 에서 주문 Agent snapshot 으로 연습 공간을
 * 시작하는 경로(snapshot_id 를 준 create)와, 그 연습 공간을 읽는 filesOf, sourceSnapshotIdOf 를 보호한다.
 * skill 과 자원을 가르는 경로 규칙, snapshot 맨 위 칸의 버전 사용, 끝 줄바꿈 보존, skills/ 밖 파일 무시,
 * 주문 Agent 오류를 바꾸지 않고 올리는지, 실패하면 아무것도 저장하지 않는지를 본다.
 * 주문 Agent(OrderAgentClient)와 저장소는 Mockito 로 대신한다.
 */
class WorkspaceFromSnapshotTest {

    static final String SNAPSHOT = "11111111-2222-3333-4444-555555555555";

    WorkspaceRepository repository = mock(WorkspaceRepository.class);
    SkillVersionStore store = mock(SkillVersionStore.class);
    OrderAgentClient orderAgent = mock(OrderAgentClient.class);
    WorkspaceService service = new WorkspaceService(repository, store, orderAgent);
    /** manifest 에 올라간 파일 목록이다. serve 나 manifest 를 부를 때마다 하나씩 늘어난다. */
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
        // 운영 폴더는 읽지 않는다.
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

    /** YAML 목록을 해석하더라도 snapshot에서 복사한 원문은 그대로 보존한다. */
    @Test
    void copiesBlockListsWithoutRewritingSource() {
        String text = "---\r\nname: payment\r\nversion: 3\r\ninputs:\r\n  - 'inventory.reserved'\r\n"
                + "outputs: [\"payment.status\"]\r\n---\r\nbody\r\n";
        serve("skills/payment/SKILL.md", text);

        assertThat(service.create(null, SNAPSHOT).skillVersions()).isEqualTo(Map.of("payment", 3));
        assertThat(savedRecord().getSkills().get("payment").fullText()).isEqualTo(text);
    }

    /** 문자열 버전으로는 workspace를 생성하거나 일부 파일을 저장하지 않는다. */
    @Test
    void rejectsQuotedVersionBeforeSavingWorkspace() {
        serve("skills/payment/SKILL.md", "---\nname: payment\nversion: '1'\n---\nbody\n");

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
        // 이 칸이 생기기 전에 만든 연습 공간은 resources 와 source_snapshot_id 가 null 이다.
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

    /** 주문 Agent 실행기(harness)는 snapshot 에서 시작한 연습 공간만 돌리고, sourceSnapshotIdOf 가 그 판단 근거를 준다. */
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
    void snapshotSkillVersionsAreSorted() {
        serve("skills/payment/SKILL.md", skillText("payment", 1));
        serve("skills/order-flow/SKILL.md", skillText("order-flow", 2));

        assertThat(service.create(null, SNAPSHOT).skillVersions().keySet()).containsExactly("order-flow", "payment");
    }

    /** 돌려준 번호와 시각은 저장한 것과 같다. 시각은 마이크로초로 자른다. 번호는 w_ 와 영숫자 8자다. */
    @Test
    void snapshotStartReturnsWhatItSaved() {
        serve("skills/payment/SKILL.md", skillText("payment", 1));

        var created = service.create(null, SNAPSHOT);

        WorkspaceRecord saved = savedRecord();
        assertThat(created.workspaceId()).isEqualTo(saved.getWorkspaceId()).matches("w_[A-Za-z0-9]{8}");
        assertThat(created.createdAt()).isEqualTo(saved.getCreatedAt());
        assertThat(created.createdAt().getNano() % 1000).isZero();
        assertThat(saved.getStatus()).isEqualTo(WorkspaceRecord.Status.OPEN);
    }

    /** skills/ 밖의 파일은 읽지도 복사하지도 않는다. */
    @Test
    void filesOutsideSkillsAreNotCopied() {
        manifest("README.md");
        serve("skills/payment/SKILL.md", skillText("payment", 1));

        service.create(null, SNAPSHOT);

        WorkspaceRecord saved = savedRecord();
        assertThat(saved.getSkills()).containsOnlyKeys("payment");
        assertThat(saved.getResources()).isEmpty();
        verify(orderAgent, never()).readText(SNAPSHOT, "README.md");
    }

    @Test
    void snapshotStartWithoutOrderAgentConfiguredFails() {
        WorkspaceService withoutClient = new WorkspaceService(repository, store);

        assertThatThrownBy(() -> withoutClient.create(null, SNAPSHOT)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    // ---- 도우미 ----

    /** 맨 위 칸에 name 과 version 만 있는 최소 SKILL.md 글을 만든다. */
    private static String skillText(String name, int version) {
        return "---\nname: " + name + "\nversion: " + version + "\n---\n# " + name + "\n";
    }

    /** 이 snapshot 의 manifest 에 path 가 있고, readText 가 text 를 돌려주게 한다. 이미 있던 manifest 는 이어 붙인다. */
    private void serve(String path, String text) {
        manifest(path);
        when(orderAgent.readText(SNAPSHOT, path)).thenReturn(text);
    }

    /**
     * manifest 에 path 를 더하고, getSnapshot 이 지금까지 더한 파일 목록을 돌려주게 한다. readText 는 정하지 않으므로
     * 이 경로만으로 더한 파일을 읽으면 Mockito 기본값 null 이 온다.
     */
    private void manifest(String path) {
        entries.add(new FileEntry(path, "hash-" + entries.size(), 10));
        when(orderAgent.getSnapshot(SNAPSHOT)).thenReturn(new SnapshotManifest(
                SNAPSHOT, "snapshot-hash", "2026-10-21T00:00:00Z", Map.of(), List.copyOf(entries)));
    }

    /** repository.save 에 넘어간 WorkspaceRecord 를 꺼낸다. save 가 정확히 한 번 불렸는지도 함께 확인한다. */
    private WorkspaceRecord savedRecord() {
        ArgumentCaptor<WorkspaceRecord> captor = ArgumentCaptor.forClass(WorkspaceRecord.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
