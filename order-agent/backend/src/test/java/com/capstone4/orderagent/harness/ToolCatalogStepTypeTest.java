package com.capstone4.orderagent.harness;

import com.capstone4.orderagent.entity.enums.ProcessingStepType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 단계 Skill 이 기록할 주문 처리 단계 종류를 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ToolCatalog.java 의
 * BINDINGS 표에서 읽는지, 서버 전용 해제 도구가 모델에 드러나지 않는지 보호한다. 실패 기록이 어느 주문 단계를 FAILED 로 남길지가
 * 이 매핑에 달려 있으므로, 단계를 추가하면 이 테스트의 기대값도 함께 고친다.
 */
class ToolCatalogStepTypeTest {
    /** 다섯 단계 Skill 은 자기 업무 단계로, 그 밖의 이름은 RECOVERY 로 기록한다. */
    @Test void stepSkillsMapToTheirOrderSteps(){
        assertThat(ToolCatalog.stepTypeFor("order-intake")).isEqualTo(ProcessingStepType.INTAKE);
        assertThat(ToolCatalog.stepTypeFor("inventory-check")).isEqualTo(ProcessingStepType.INVENTORY);
        assertThat(ToolCatalog.stepTypeFor("payment")).isEqualTo(ProcessingStepType.PAYMENT);
        assertThat(ToolCatalog.stepTypeFor("shipping-reserve")).isEqualTo(ProcessingStepType.SHIPPING);
        assertThat(ToolCatalog.stepTypeFor("notify")).isEqualTo(ProcessingStepType.NOTIFICATION);
        for(String other:new String[]{"order-flow","gift-wrap","sub-flow",""})
            assertThat(ToolCatalog.stepTypeFor(other)).as(other).isEqualTo(ProcessingStepType.RECOVERY);
        assertThatThrownBy(()->ToolCatalog.stepTypeFor(null)).isInstanceOf(NullPointerException.class);
    }
    /** 해제 도구 이름은 모델에 내놓는 도구 목록에 없다. */
    @Test void releaseToolIsServerOnly(){
        assertThat(ToolCatalog.RELEASE_TOOL).isEqualTo("inventory.release");
        assertThatThrownBy(()->ToolCatalog.definition(ToolCatalog.RELEASE_TOOL)).hasMessage("UNKNOWN_TOOL");
    }
}
