package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioCatalog.java 가 기준 주문 파일을 읽는 규칙을 보호한다.
 * 저장소의 scenarios/golden/g-01.json 을 읽는지, 깨진 JSON 이나 id 없는 파일이나 같은 id 를 가진 두 파일이면
 * 파일 이름을 밝히며 실패하는지 본다. 폴더가 없을 때의 설정 안내, .json 이 아닌 파일 무시, 번호 정렬도 고정한다.
 * 테스트는 service/ 폴더에서 돌므로 ../scenarios 로 저장소 파일을 읽는다.
 */
class ScenarioCatalogTest {

    ScenarioCatalog catalog = new ScenarioCatalog("../scenarios", TestFixtures.JSON);

    @Test
    void loadsG01() {
        assertThat(catalog.get("g-01").expect().finalStatus()).isEqualTo("SHIPPED");
        assertThat(catalog.ids()).contains("g-01");
    }

    @Test
    void brokenJsonNamesTheFile(@TempDir Path dir) throws Exception {
        Path golden = Files.createDirectories(dir.resolve("golden"));
        Files.writeString(golden.resolve("g-03.json"), "{ not json");
        assertThatThrownBy(() -> new ScenarioCatalog(dir.toString(), TestFixtures.JSON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("시험 주문 파일을 읽을 수 없습니다: ")
                .hasMessageContaining("g-03.json");
    }

    @Test
    void missingIdNamesTheFile(@TempDir Path dir) throws Exception {
        Path golden = Files.createDirectories(dir.resolve("golden"));
        Files.writeString(golden.resolve("no-id.json"), "{\"name\": \"x\", \"order\": {}, \"expect\": {}}");
        assertThatThrownBy(() -> new ScenarioCatalog(dir.toString(), TestFixtures.JSON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("시험 주문 파일에 id 가 없습니다: ")
                .hasMessageContaining("no-id.json");
    }

    @Test
    void duplicateIdNamesBothFiles(@TempDir Path dir) throws Exception {
        Path golden = Files.createDirectories(dir.resolve("golden"));
        String g01 = Files.readString(Path.of("../scenarios/golden/g-01.json"));
        Files.writeString(golden.resolve("g-01.json"), g01);
        Files.writeString(golden.resolve("g-01-copy.json"), g01);
        assertThatThrownBy(() -> new ScenarioCatalog(dir.toString(), TestFixtures.JSON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("시험 주문 id g-01 이 두 파일에 있습니다: g-01-copy.json, g-01.json");
    }

    @Test
    void unknownIdListsAvailable() {
        assertThatThrownBy(() -> catalog.get("g-99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("g-99")
                .hasMessageContaining("g-01");
    }

    @Test
    void missingGoldenFolderNamesTheSetting(@TempDir Path dir) {
        assertThatThrownBy(() -> new ScenarioCatalog(dir.toString(), TestFixtures.JSON))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("시험 주문 폴더를 찾을 수 없습니다: " + dir.resolve("golden").toAbsolutePath()
                        + ". skillops.scenarios-dir 설정을 확인하세요");
    }

    @Test
    void nonJsonFilesAreIgnored(@TempDir Path dir) throws Exception {
        Path golden = Files.createDirectories(dir.resolve("golden"));
        Files.writeString(golden.resolve("g-01.json"), Files.readString(Path.of("../scenarios/golden/g-01.json")));
        Files.writeString(golden.resolve("README.md"), "# 시험 주문\n");

        assertThat(new ScenarioCatalog(dir.toString(), TestFixtures.JSON).ids()).containsExactly("g-01");
    }

    /** 번호 목록은 정렬돼 있고, 없는 번호 오류의 "있는 번호" 도 그 순서다. */
    @Test
    void idsAreSortedInTheUnknownIdMessage(@TempDir Path dir) throws Exception {
        Path golden = Files.createDirectories(dir.resolve("golden"));
        String g01 = Files.readString(Path.of("../scenarios/golden/g-01.json"));
        Files.writeString(golden.resolve("a.json"), g01.replace("\"g-01\"", "\"g-10\""));
        Files.writeString(golden.resolve("b.json"), g01.replace("\"g-01\"", "\"g-02\""));

        ScenarioCatalog sorted = new ScenarioCatalog(dir.toString(), TestFixtures.JSON);

        assertThat(sorted.ids()).containsExactly("g-02", "g-10");
        assertThatThrownBy(() -> sorted.get("g-99")).hasMessage("시험 주문 g-99 이 없습니다. 있는 번호: [g-02, g-10]");
    }
}
