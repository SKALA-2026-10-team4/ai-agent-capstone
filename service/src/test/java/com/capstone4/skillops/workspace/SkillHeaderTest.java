package com.capstone4.skillops.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * service/src/main/java/com/capstone4/skillops/workspace/SkillHeader.java 의 parse 를 저장소의 실제 SKILL.md
 * (skills/payment, skills/order-flow)로 보호한다. 운영 skill 의 맨 위 칸 모양이 바뀌어 간이 파서가 읽지 못하게 되면 여기서
 * 먼저 깨진다. 테스트는 service 폴더에서 돌기 때문에 ../skills 를 읽는다.
 */
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
