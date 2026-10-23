package com.capstone4.orderagent.harness;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.math.BigDecimal;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.PropertyNamingStrategies;

/**
 * 저장하는 증거(trace 이벤트, 멱등 결과, snapshot 메타데이터)와 모델 API 와 무관한 도구 요청이 한 가지 직렬화 규칙을 쓰도록 모은 도구다.
 * record 필드 이름은 snake_case 로 쓰고 Map 키는 그대로 두며, 해시는 SHA-256 소문자 16진수다. 정규화 해시는 키 정렬과
 * 숫자 정규화 뒤에 계산하므로 키 순서나 30000 과 30000.0 같은 숫자 표기 차이에 흔들리지 않는다.
 * 이 패키지(SkillLoader, SkillExecutor, FixedDecisionProvider, OpenAiDecisionProvider 등)와
 * order-agent/backend/src/main/java/com/capstone4/orderagent 아래의 trace(ExecutionRepository),
 * workspace(SnapshotService, SnapshotRepository, SkillChangeDispatcher), service(OrderToolService),
 * controller(EvidenceController) 패키지가 함께 쓴다.
 */
public final class Json {
    /** snake_case 이름 규칙을 쓰는 공용 JsonMapper 다. 이 규칙은 record 필드 이름에만 적용되고 Map 키는 바꾸지 않는다. */
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    /** 정적 메서드만 있는 도구 클래스라 객체를 만들지 않는다. */
    private Json() {}
    /** 값을 JSON 문자열로 쓴다. record 필드 이름은 snake_case 가 되고 Map 키는 그대로 남는다. */
    public static String write(Object value) { return MAPPER.writeValueAsString(value); }
    /** JSON 객체 문자열을 Map 으로 읽는다. 형식이 틀리거나 객체가 아니면 Jackson 예외가 나며, SkillExecutor 는 이를 MODEL_FORMAT_ERROR 로 바꾼다. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String value) { return MAPPER.readValue(value, Map.class); }
    /** 바이트의 SHA-256 을 소문자 16진수로 돌려준다. Skill 파일 해시와 snapshot 파일 해시에 쓴다. */
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /**
     * 값을 정규화해 JSON 으로 쓴 뒤 SHA-256 을 돌려준다.
     * order-agent/backend/src/main/java/com/capstone4/orderagent/service/OrderToolService.java 가 도구 인자를 서버 인자와 대조하고
     * 멱등 해시로 쓸 때, order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 의 bundleHash 가
     * snapshot 해시를 만들 때 쓴다. 정규화 규칙을 바꾸면 저장된 snapshot 해시가 모두 달라져 기존 snapshot 이 SNAPSHOT_TAMPERED 로
     * 거절되므로 바꾸지 않는다.
     */
    public static String hash(Object value) { return hash(write(canonical(value)).getBytes(StandardCharsets.UTF_8)); }
    /** Map 은 키를 문자열로 바꿔 정렬하고, 목록은 원소마다 정규화하고, 숫자는 BigDecimal 로 바꿔 뒤쪽 0 을 지운다. */
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            map.forEach((key, v) -> result.put(key.toString(), canonical(v))); return result;
        }
        if (value instanceof Collection<?> list) return list.stream().map(Json::canonical).toList();
        if (value instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros();
        return value;
    }
}
