package com.capstone4.skillops.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/** 저장·멱등 비교에 사용하는 결정적 JSON과 SHA-256을 제공한다. */
public final class EvaluationJson {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 인스턴스를 만들지 않는다. */
    private EvaluationJson() {}
    /** 객체 키만 정렬하고 배열 순서와 null을 유지해 저장한다. */
    public static String write(Object value) { return JSON.writeValueAsString(sorted(JSON.convertValue(value, Object.class))); }
    /** 저장한 JSON을 계약 타입으로 되읽는다. */
    public static <T> T read(String value, Class<T> type) { return JSON.readValue(value, type); }
    /** 정렬된 JSON의 UTF-8 바이트를 해시한다. */
    public static String hash(Object value) { return textHash(write(value)); }
    /** 원문 바이트를 바꾸지 않고 UTF-8 해시를 계산한다. */
    public static String textHash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** 중첩 객체까지 키를 정렬한다. */
    private static Object sorted(Object value) {
        if (value instanceof Map<?,?> map) {
            Map<String,Object> result = new TreeMap<>();
            map.forEach((key,item) -> result.put(key.toString(), sorted(item)));
            return result;
        }
        if (value instanceof List<?> list) { return list.stream().map(EvaluationJson::sorted).toList(); }
        return value;
    }
}
