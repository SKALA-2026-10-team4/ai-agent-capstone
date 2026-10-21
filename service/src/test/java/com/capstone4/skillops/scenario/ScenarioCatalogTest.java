package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
