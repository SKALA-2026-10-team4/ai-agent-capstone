package com.capstone4.skillops.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 에서 운영 폴더로 연습 공간을 만드는
 * 경로(start_versions 를 쓰는 create)와 skillsOf 를 보호한다. 운영 버전 기본값, start_versions 덮어쓰기와 그 오류,
 * created_at 의 마이크로초 절단, resources 를 null 이 아닌 빈 맵으로 저장하는지, skill_versions 의 정렬과 읽기 전용,
 * 연습 공간 번호 모양을 본다. 버전 보관함은 FakeStore 로, 저장소는 Mockito 로 대신한다.
 */
class WorkspaceServiceTest {

    /**
     * SkillVersionStore 의 가짜 구현이다. 운영 버전은 order-flow 1, inventory-check 2 이고, inventory-check 는 예전 버전
     * 1 도 준다. 원문은 "text-이름-v번호" 글이다.
     */
    static class FakeStore implements SkillVersionStore {
        /** 원문을 줄 수 있는 "skill:버전" 쌍이다. */
        static final Set<String> AVAILABLE = Set.of("order-flow:1", "inventory-check:1", "inventory-check:2");

        @Override
        public Map<String, Integer> liveVersions() {
            return Map.of("order-flow", 1, "inventory-check", 2);
        }

        @Override
        public String fullText(String skill, int version) {
            if (!AVAILABLE.contains(skill + ":" + version)) {
                throw new IllegalArgumentException(skill + " 버전 " + version + " 이 없습니다");
            }
            return "text-" + skill + "-v" + version;
        }
    }

    WorkspaceRepository repository = mock(WorkspaceRepository.class);
    WorkspaceService service = new WorkspaceService(repository, new FakeStore());

    @BeforeEach
    void saveReturnsItsArgument() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createDefaultsToLiveVersions() {
        var created = service.create(null);
        assertThat(created.workspaceId()).startsWith("w_");
        assertThat(created.skillVersions()).isEqualTo(Map.of("order-flow", 1, "inventory-check", 2));
        assertThat(created.createdAt()).isNotNull();
        verify(repository).save(argThat((WorkspaceRecord r) ->
                r.getSkills().get("inventory-check").fullText().equals("text-inventory-check-v2")));
    }

    @Test
    void createOverridesStartVersion() {
        assertThat(service.create(Map.of("inventory-check", 1)).skillVersions()).containsEntry("inventory-check", 1);
    }

    @Test
    void createRejectsUnknownSkillAndSavesNothing() {
        assertThatThrownBy(() -> service.create(Map.of("nope", 1)))
                .hasMessageStartingWith("nope 이라는 skill 이 없습니다. 있는 skill: ");
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsUnavailableVersion() {
        assertThatThrownBy(() -> service.create(Map.of("inventory-check", 9)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsMissingVersionInStartVersions() {
        var startVersions = new java.util.HashMap<String, Integer>();
        startVersions.put("inventory-check", null);
        assertThatThrownBy(() -> service.create(startVersions))
                .hasMessage("start_versions 의 inventory-check 에 버전 번호를 넣으세요");
        verify(repository, never()).save(any());
    }

    @Test
    void skillsOfUnknownWorkspaceFails() {
        when(repository.findById("w_x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.skillsOf("w_x"))
                .hasMessage("workspace_id w_x 이 없습니다. create_workspace 로 먼저 만드세요");
    }

    /** created_at 은 DB 정밀도(마이크로초)로 자른 값이고, 돌려준 값과 저장한 값이 같다. */
    @Test
    void createdAtIsTruncatedToMicros() {
        var created = service.create(null);

        assertThat(created.createdAt().getNano() % 1000).isZero();
        assertThat(savedRecord().getCreatedAt()).isEqualTo(created.createdAt());
        assertThat(savedRecord().getWorkspaceId()).isEqualTo(created.workspaceId());
    }

    /** 운영 폴더에서 시작하면 resources 를 null 이 아니라 빈 맵으로 저장한다. */
    @Test
    void liveStartStoresEmptyResourcesNotNull() {
        service.create(null);

        WorkspaceRecord saved = savedRecord();
        assertThat(ReflectionTestUtils.getField(saved, "resources")).isNotNull().isEqualTo(Map.of());
        assertThat(saved.getSourceSnapshotId()).isNull();
        assertThat(saved.getStatus()).isEqualTo(WorkspaceRecord.Status.OPEN);
    }

    @Test
    void skillVersionsAreSortedAndReadOnly() {
        var versions = service.create(null).skillVersions();

        assertThat(versions.keySet()).containsExactly("inventory-check", "order-flow");
        assertThatThrownBy(() -> versions.put("payment", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void workspaceIdShape() {
        assertThat(service.create(null).workspaceId()).matches("w_[A-Za-z0-9]{8}");
    }

    /** repository.save 에 넘어간 WorkspaceRecord 를 꺼낸다. save 가 정확히 한 번 불렸는지도 함께 확인한다. */
    private WorkspaceRecord savedRecord() {
        ArgumentCaptor<WorkspaceRecord> captor = ArgumentCaptor.forClass(WorkspaceRecord.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
