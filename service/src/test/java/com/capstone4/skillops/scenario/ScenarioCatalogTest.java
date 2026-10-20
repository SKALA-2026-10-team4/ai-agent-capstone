package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ScenarioCatalogTest {

    ScenarioCatalog catalog = new ScenarioCatalog(Path.of("../scenarios"), TestFixtures.JSON);

    @Test
    void loadsG01() {
        assertThat(catalog.get("g-01").expect().finalStatus()).isEqualTo("SHIPPED");
        assertThat(catalog.ids()).contains("g-01");
    }

    @Test
    void unknownIdListsAvailable() {
        assertThatThrownBy(() -> catalog.get("g-99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("g-99")
                .hasMessageContaining("g-01");
    }
}
