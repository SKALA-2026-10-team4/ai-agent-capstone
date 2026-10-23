package com.capstone4.skillops.snapshot;

import static com.capstone4.skillops.snapshot.SnapshotTestSupport.LIVE_SKILLS;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.assertFailure;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.failure;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.file;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.flowSkill;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.liveText;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.manifest;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.stepSkill;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * service/src/main/java/com/capstone4/skillops/snapshot/DependencyGraphService.java 의 get_dependency_graph 조립을 보호한다.
 * entry_skill_id 입력 검사, 조회 대상 선택(manifest 에 있는지, 형식이 맞는지, 중복인지)과 조회 순서, 노드 해시가 manifest 값에서
 * 온다는 점, 오류에 실리는 snapshot_id 를 고정한다. 주문 Agent(OrderAgentClient)는 Mockito 로 대신한다.
 */
class DependencyGraphServiceTest {

    static final String SNAPSHOT = "33333333-3333-3333-3333-333333333333";

    OrderAgentClient orderAgent = mock(OrderAgentClient.class);
    DependencyGraphService service = new DependencyGraphService(orderAgent);

    @Test
    void badEntrySkillIdIsRejectedWithSnapshotId() {
        for (String bad : List.of("Order_Flow", "-x", "", "../x")) {
            assertFailure(failure(() -> service.dependencyGraph(SNAPSHOT, bad)),
                    "INVALID_INPUT", "entry_skill_id 는 소문자·숫자·하이픈만 씁니다: " + bad, false, null, SNAPSHOT);
        }
        assertFailure(failure(() -> service.dependencyGraph(SNAPSHOT, null)),
                "INVALID_INPUT", "entry_skill_id 는 소문자·숫자·하이픈만 씁니다: null", false, null, SNAPSHOT);
        verifyNoInteractions(orderAgent);
    }

    @Test
    void missingEntryIsSkillNotFoundWithSnapshotId() {
        when(orderAgent.getSnapshot(SNAPSHOT)).thenReturn(manifest(SNAPSHOT, "snap-h",
                List.of(file("skills/payment/SKILL.md", "h"))));

        assertFailure(failure(() -> service.dependencyGraph(SNAPSHOT, "order-flow")),
                "SKILL_NOT_FOUND", "snapshot 에 skills/order-flow/SKILL.md 가 없습니다.", false, null, SNAPSHOT);
        verify(orderAgent, never()).readText(anyString(), anyString());
    }

    @Test
    void liveSnapshotUsesManifestHashes() {
        serveLive();

        DependencyGraphResult result = service.dependencyGraph(SNAPSHOT, "order-flow");

        assertThat(result.snapshotId()).isEqualTo(SNAPSHOT);
        assertThat(result.snapshotHash()).isEqualTo("snap-h");
        assertThat(result.entrySkillId()).isEqualTo("order-flow");
        assertThat(result.steps()).containsExactly("order-intake", "inventory-check", "payment", "shipping-reserve", "notify");
        assertThat(result.complete()).isTrue();
        assertThat(result.missing()).isEmpty();
        assertThat(result.ambiguous()).isEmpty();
        assertThat(result.edges()).hasSize(16);
        assertThat(result.nodes()).extracting(DependencyGraph.Node::fileHash)
                .containsExactly("m-order-intake", "m-inventory-check", "m-payment", "m-shipping-reserve", "m-notify");
        assertThat(result.nodes()).extracting(DependencyGraph.Node::path).containsExactly("skills/order-intake/SKILL.md",
                "skills/inventory-check/SKILL.md", "skills/payment/SKILL.md", "skills/shipping-reserve/SKILL.md",
                "skills/notify/SKILL.md");
    }

    @Test
    void resultEqualsAnalyzerGraphWithSnapshotFields() {
        serveLive();

        DependencyGraphResult result = service.dependencyGraph(SNAPSHOT, "order-flow");

        Map<String, SkillSource> sources = new LinkedHashMap<>();
        for (String id : LIVE_SKILLS) {
            sources.put(id, new SkillSource("skills/" + id + "/SKILL.md", "m-" + id, liveText(id)));
        }
        DependencyGraph g = DependencyAnalyzer.analyze("order-flow", sources);
        assertThat(result).isEqualTo(new DependencyGraphResult(SNAPSHOT, "snap-h", g.entrySkillId(), g.steps(), g.nodes(),
                g.edges(), g.missing(), g.ambiguous(), g.complete()));
    }

    @Test
    void filesAreReadEntryFirstThenStepsInOrder() {
        serveLive();

        service.dependencyGraph(SNAPSHOT, "order-flow");

        InOrder order = inOrder(orderAgent);
        order.verify(orderAgent).getSnapshot(SNAPSHOT);
        for (String id : LIVE_SKILLS) {
            order.verify(orderAgent).readText(SNAPSHOT, "skills/" + id + "/SKILL.md");
        }
        verifyNoMoreInteractions(orderAgent);
    }

    /** manifest 에 없는 단계와 형식이 틀린 단계는 조회하지 않고 UNKNOWN_SKILL 로 남긴다. */
    @Test
    void stepsOutsideManifestOrWithBadNamesAreNotRead() {
        when(orderAgent.getSnapshot(SNAPSHOT)).thenReturn(manifest(SNAPSHOT, "snap-h", List.of(
                file("skills/order-flow/SKILL.md", "m-flow"),
                file("skills/a/SKILL.md", "m-a"),
                file("skills/Bad_Step/SKILL.md", "m-bad"))));
        when(orderAgent.readText(SNAPSHOT, "skills/order-flow/SKILL.md")).thenReturn(flowSkill("order-flow", "a, Bad_Step, ghost"));
        when(orderAgent.readText(SNAPSHOT, "skills/a/SKILL.md")).thenReturn(stepSkill("a", "", "k", ""));

        DependencyGraphResult result = service.dependencyGraph(SNAPSHOT, "order-flow");

        verify(orderAgent, never()).readText(SNAPSHOT, "skills/Bad_Step/SKILL.md");
        verify(orderAgent, never()).readText(SNAPSHOT, "skills/ghost/SKILL.md");
        assertThat(result.complete()).isFalse();
        assertThat(result.nodes()).containsExactly(
                new DependencyGraph.Node("a", "skills/a/SKILL.md", "m-a", List.of(), List.of("k"), List.of(), null),
                new DependencyGraph.Node("Bad_Step", null, null, List.of(), List.of(), List.of(), "UNKNOWN_SKILL"),
                new DependencyGraph.Node("ghost", null, null, List.of(), List.of(), List.of(), "UNKNOWN_SKILL"));
    }

    @Test
    void repeatedStepIsReadOnce() {
        when(orderAgent.getSnapshot(SNAPSHOT)).thenReturn(manifest(SNAPSHOT, "snap-h", List.of(
                file("skills/order-flow/SKILL.md", "m-flow"), file("skills/a/SKILL.md", "m-a"))));
        when(orderAgent.readText(SNAPSHOT, "skills/order-flow/SKILL.md")).thenReturn(flowSkill("order-flow", "a, a"));
        when(orderAgent.readText(SNAPSHOT, "skills/a/SKILL.md")).thenReturn(stepSkill("a", "", "", ""));

        DependencyGraphResult result = service.dependencyGraph(SNAPSHOT, "order-flow");

        verify(orderAgent, times(1)).readText(SNAPSHOT, "skills/a/SKILL.md");
        assertThat(result.nodes()).extracting(DependencyGraph.Node::skillId).containsExactly("a", "a");
    }

    /** entry 를 steps 에 다시 넣으면 단계로 한 번 더 읽고, 단계로서는 중첩 flow 다. */
    @Test
    void entryListedAsStepIsReadAgainAsStep() {
        when(orderAgent.getSnapshot(SNAPSHOT)).thenReturn(manifest(SNAPSHOT, "snap-h", List.of(
                file("skills/order-flow/SKILL.md", "m-flow"))));
        when(orderAgent.readText(SNAPSHOT, "skills/order-flow/SKILL.md")).thenReturn(flowSkill("order-flow", "order-flow"));

        DependencyGraphResult result = service.dependencyGraph(SNAPSHOT, "order-flow");

        verify(orderAgent, times(2)).readText(SNAPSHOT, "skills/order-flow/SKILL.md");
        assertThat(result.nodes()).extracting(DependencyGraph.Node::parseError).containsExactly("NESTED_FLOW_NOT_SUPPORTED");
    }

    /** entry 의 steps 를 읽지 못한 INVALID_FLOW 는 이 경로에서도 snapshot_id 가 null 이다. */
    @Test
    void invalidFlowHasNoSnapshotId() {
        when(orderAgent.getSnapshot(SNAPSHOT)).thenReturn(manifest(SNAPSHOT, "snap-h", List.of(
                file("skills/order-flow/SKILL.md", "m-flow"))));
        when(orderAgent.readText(SNAPSHOT, "skills/order-flow/SKILL.md")).thenReturn("---\nname: order-flow\n---\n");

        assertFailure(failure(() -> service.dependencyGraph(SNAPSHOT, "order-flow")),
                "INVALID_FLOW", "entry flow 를 읽을 수 없습니다: order-flow 에 steps 가 없습니다.", false, null, null);
    }

    @Test
    void manifestFailureIsPassedThrough() {
        when(orderAgent.getSnapshot(SNAPSHOT)).thenThrow(
                new OrderAgentException("SNAPSHOT_TAMPERED", "snapshot 해시가 맞지 않습니다", false, 409, SNAPSHOT));

        assertFailure(failure(() -> service.dependencyGraph(SNAPSHOT, "order-flow")),
                "SNAPSHOT_TAMPERED", "snapshot 해시가 맞지 않습니다", false, 409, SNAPSHOT);
        verify(orderAgent, never()).readText(anyString(), anyString());
    }

    /** manifest 전체를 맵으로 바꾸므로 어느 경로든 겹치면 그대로 실패한다. */
    @Test
    void duplicatePathAnywhereFails() {
        when(orderAgent.getSnapshot(SNAPSHOT)).thenReturn(manifest(SNAPSHOT, "snap-h", List.of(
                file("skills/order-flow/SKILL.md", "m-flow"), file("skills/z/x.md", "1"), file("skills/z/x.md", "2"))));

        assertThatThrownBy(() -> service.dependencyGraph(SNAPSHOT, "order-flow")).isInstanceOf(IllegalStateException.class);
        verify(orderAgent, never()).readText(anyString(), anyString());
    }

    /** 운영 skill 여섯 개의 SKILL.md 와 resources 파일 하나가 든 manifest 를 꾸민다. SKILL.md 해시는 m- 뒤에 skill ID 를 붙인 값이다. */
    private void serveLive() {
        List<SnapshotManifest.FileEntry> files = new ArrayList<>();
        for (String id : LIVE_SKILLS) {
            String path = "skills/" + id + "/SKILL.md";
            files.add(file(path, "m-" + id));
            when(orderAgent.readText(SNAPSHOT, path)).thenReturn(liveText(id));
        }
        files.add(file("skills/inventory-check/resources/policy.md", "m-policy"));
        when(orderAgent.getSnapshot(SNAPSHOT)).thenReturn(manifest(SNAPSHOT, "snap-h", files));
    }
}
