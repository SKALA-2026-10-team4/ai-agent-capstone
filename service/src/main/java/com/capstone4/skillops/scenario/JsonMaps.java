package com.capstone4.skillops.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 Agent 응답처럼 모양을 믿을 수 없는 JSON(Map 과 List)을 읽을 때 쓰는 복사 도우미다.
 * 모양이 기대와 다르면 예외를 던지지 않고 빈 맵이나 빈 목록을 돌려준다.
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 toDryRunOrder() 와
 * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java 의 toOutcome() 이 쓴다.
 */
final class JsonMaps {

    /** 정적 메서드만 있는 도우미라 객체를 만들지 않는다. */
    private JsonMaps() {
    }

    /**
     * Map 이면 키를 글자로 바꾼 복사본을, 아니면 빈 맵을 돌려준다. 둘 다 고칠 수 있는 LinkedHashMap 이고 원래 순서를 지킨다.
     * 값이 null 인 칸도 그대로 둔다. Collectors.toMap 은 null 값을 받지 않으므로 forEach 로 복사한다.
     */
    static Map<String, Object> mapOf(Object value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        }
        return copy;
    }

    /** List 안의 Map 만 {@link #mapOf} 로 복사한 목록을 돌려준다. Map 이 아닌 칸은 버리고, List 가 아니면 빈 목록이다. */
    static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Map.class::isInstance).map(JsonMaps::mapOf).toList();
    }
}
