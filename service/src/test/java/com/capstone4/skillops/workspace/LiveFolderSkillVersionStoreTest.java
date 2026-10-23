package com.capstone4.skillops.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * service/src/main/java/com/capstone4/skillops/workspace/LiveFolderSkillVersionStore.java 를 임시 폴더로 보호한다.
 * 맨 위 칸에서 운영 버전을 읽는지, 운영 버전일 때만 원문을 주는지, 예전 버전과 없는 skill 의 오류 문구가 글자 그대로인지
 * 본다.
 */
class LiveFolderSkillVersionStoreTest {

    @TempDir
    Path dir;

    LiveFolderSkillVersionStore store;

    /** 임시 폴더에 버전 3 인 skill a 하나를 만들고 그 폴더를 읽는 store 를 준비한다. */
    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(dir.resolve("a"));
        Files.writeString(dir.resolve("a/SKILL.md"), "---\nname: a\noutputs: [a.x]\nversion: 3\n---\n본문\n");
        store = new LiveFolderSkillVersionStore(dir.toString());
    }

    @Test
    void liveVersionsReadsHeader() {
        assertThat(store.liveVersions()).isEqualTo(Map.of("a", 3));
    }

    @Test
    void fullTextOfLiveVersion() {
        assertThat(store.fullText("a", 3)).contains("본문");
    }

    @Test
    void olderVersionNotInFolder() {
        assertThatThrownBy(() -> store.fullText("a", 1))
                .hasMessage("운영 폴더에는 a 버전 3 만 있습니다 (요청 1). 예전 버전은 C-1 버전 저장소가 붙은 뒤 쓸 수 있습니다");
    }

    @Test
    void unknownSkill() {
        assertThatThrownBy(() -> store.fullText("zz", 1)).hasMessage("zz 이라는 skill 이 없습니다");
    }
}
