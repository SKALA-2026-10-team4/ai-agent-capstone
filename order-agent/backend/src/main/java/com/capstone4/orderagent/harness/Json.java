package com.capstone4.orderagent.harness;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.math.BigDecimal;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.PropertyNamingStrategies;

/** One serialization policy for persisted evidence and API-independent tool requests. */
public final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
    private Json() {}
    public static String write(Object value) { return MAPPER.writeValueAsString(value); }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String value) { return MAPPER.readValue(value, Map.class); }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String hash(Object value) { return hash(write(canonical(value)).getBytes(StandardCharsets.UTF_8)); }
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
