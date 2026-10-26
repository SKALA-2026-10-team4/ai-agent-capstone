package com.capstone4.orderagent.harness;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/RuleCondition.java 가 조건 문법 v2 공용 사례
 * docs/contracts/examples/condition-cases.json 과 같은 판정을 내는지 확인한다. service 의 Check 와 skillops-agent 의 contracts.py 가
 * 같은 파일로 테스트하므로, 이 테스트가 깨지면 세 구현이 어긋났다는 뜻이다. 사례 파일은 고치지 않고 구현을 맞춘다.
 */
@SuppressWarnings("unchecked")
class RuleConditionContractTest {
    /** 사례 파일 전체를 Map 으로 읽는다. 숫자는 Integer, 참과 거짓은 Boolean, 하위 객체는 Map 으로 읽혀 실행 문맥과 같은 모양이다. */
    static Map<String,Object> contract(){
        return JsonMapper.builder().build().readValue(Path.of("../../docs/contracts/examples/condition-cases.json").toFile(),Map.class);
    }
    /** evaluate 사례(text, holds)를 제공한다. */
    static Stream<Map<String,Object>> evaluate(){return ((List<Map<String,Object>>)contract().get("evaluate")).stream();}
    /** unreadable 사례(조건 원문)를 제공한다. */
    static Stream<String> unreadable(){return ((List<String>)contract().get("unreadable")).stream();}

    /** 읽을 수 있는 조건은 공용 문맥에서 사례의 holds 와 같은 참·거짓을 낸다. */
    @ParameterizedTest(name="{index}: {0}")
    @MethodSource("evaluate")
    void evaluatesLikeTheSharedCases(Map<String,Object> testCase){
        var context=(Map<String,Object>)contract().get("context");
        String text=(String)testCase.get("text");
        assertThat(RuleCondition.parse(text).holds(context)).as(text).isEqualTo(testCase.get("holds"));
    }
    /** 읽을 수 없는 조건은 IllegalArgumentException 으로 거절한다. */
    @ParameterizedTest(name="{index}: [{0}]")
    @MethodSource("unreadable")
    void rejectsTheSharedUnreadableCases(String text){
        assertThatThrownBy(()->RuleCondition.parse(text)).as(text).isInstanceOf(IllegalArgumentException.class);
    }
    /** null 원문도 읽을 수 없는 조건이다. */
    @Test void nullTextIsUnreadable(){
        assertThatThrownBy(()->RuleCondition.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
    /** 읽은 결과는 값 이름, 연산자, 기대 값으로 나뉘고 숫자는 BigDecimal, in 목록은 고칠 수 없는 목록이다. */
    @Test void parsedPartsKeepTheirTypes(){
        assertThat(RuleCondition.parse("order.total >= 100000")).isEqualTo(new RuleCondition("order.total",">=",new BigDecimal("100000")));
        assertThat(RuleCondition.parse("order.payment_method != 'prepaid'")).isEqualTo(new RuleCondition("order.payment_method","!=","prepaid"));
        var in=RuleCondition.parse("order.final_status in ['HOLD', 'REJECTED']");
        assertThat(in.expected()).isEqualTo(List.of("HOLD","REJECTED"));
        assertThatThrownBy(()->((List<Object>)in.expected()).add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(RuleCondition.parse("notify.sent == true").expected()).isEqualTo(Boolean.TRUE);
        assertThat(RuleCondition.parse("payment.id == null").expected()).isNull();
    }
    /** 값 찾기는 그대로 있는 이름, 가장 긴 앞부분의 Map 경로 순서이고 고른 앞부분에서 끊기면 더 짧은 앞부분으로 다시 찾지 않는다. */
    @Test void lookupUsesTheLongestPresentPrefixOnly(){
        var context=new LinkedHashMap<String,Object>();
        context.put("order",Map.of("shipping",Map.of("country","JP")));
        context.put("order.shipping",Map.of("country","US","address",Map.of("city","NYC")));
        context.put("order.shipping.zone","north");
        assertThat(RuleCondition.lookup(context,"order.shipping.zone")).isEqualTo("north");
        assertThat(RuleCondition.lookup(context,"order.shipping.country")).isEqualTo("US");
        assertThat(RuleCondition.lookup(context,"order.shipping.address.city")).isEqualTo("NYC");
        assertThat(RuleCondition.lookup(context,"order.shipping.zone.x")).isNull();
        assertThat(RuleCondition.lookup(context,"order.shipping.missing")).isNull();
        assertThat(RuleCondition.lookup(context,"payment.status")).isNull();
    }
    /** 크기 비교는 숫자에만 참이 될 수 있고 NaN 같은 값은 오류 없이 거짓이다. */
    @Test void orderingNeedsANumericActualValue(){
        var condition=RuleCondition.parse("order.total > 0");
        assertThat(condition.holds(Map.of("order.total",30000L))).isTrue();
        assertThat(condition.holds(Map.of("order.total",0.5))).isTrue();
        assertThat(condition.holds(Map.of("order.total","30000"))).isFalse();
        assertThat(condition.holds(Map.of("order.total",true))).isFalse();
        assertThat(condition.holds(Map.of("order.total",Double.NaN))).isFalse();
        assertThat(condition.holds(Map.of())).isFalse();
    }
}
