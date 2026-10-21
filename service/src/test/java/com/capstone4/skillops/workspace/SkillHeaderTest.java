package com.capstone4.skillops.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class SkillHeaderTest {

    @Test
    void readsRealPaymentSkill() throws Exception {
        SkillHeader h = SkillHeader.parse(Files.readString(Path.of("../skills/payment/SKILL.md")));
        assertThat(h.name()).isEqualTo("payment");
        assertThat(h.version()).isEqualTo(1);
        assertThat(h.inputs()).contains("inventory.reserved");
        assertThat(h.outputs()).containsExactly("payment.id", "payment.status");
    }

    @Test
    void readsOrderFlowSteps() throws Exception {
        SkillHeader h = SkillHeader.parse(Files.readString(Path.of("../skills/order-flow/SKILL.md")));
        assertThat(h.steps()).containsExactly("order-intake", "inventory-check", "payment", "shipping-reserve", "notify");
        assertThat(h.inputs()).isEmpty();
    }

    @Test
    void rejectsTextWithoutHeader() {
        assertThatThrownBy(() -> SkillHeader.parse("# 제목만 있음"))
                .hasMessage("SKILL.md 맨 위 칸(--- 사이)을 찾을 수 없습니다");
    }
}
