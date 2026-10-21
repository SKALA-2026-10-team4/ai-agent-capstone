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

class WorkspaceServiceTest {

    /** 운영 버전 order-flow 1, inventory-check 2. inventory-check 는 1 도 있다. 원문은 "text-이름-v번호". */
    static class FakeStore implements SkillVersionStore {
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
}
