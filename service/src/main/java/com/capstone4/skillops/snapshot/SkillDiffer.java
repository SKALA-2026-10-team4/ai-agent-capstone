package com.capstone4.skillops.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.error.YAMLException;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;

/**
 * 같은 skill 파일의 두 글을 비교한다. 주문 Agent 와 무관한 순수 계산이라 입력 글만 받는다.
 * 덩어리 경계는 unified diff(문맥 3줄) 기준이라 C-2 apply_patch 가 그대로 적용할 수 있다.
 */
public final class SkillDiffer {

    private static final int CONTEXT_LINES = 3;
    private static final Pattern HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private SkillDiffer() {
    }

    /**
     * @param before     바뀌기 전 글. 파일이 없으면 null
     * @param after      바뀐 뒤 글. 파일이 없으면 null
     * @param beforeHash 바뀌기 전 파일 해시 (hunk_id 재료). 없으면 null
     * @param afterHash  바뀐 뒤 파일 해시 (hunk_id 재료). 없으면 null
     */
    public static SkillFileDiff diff(String path, String before, String after, String beforeHash, String afterHash) {
        String status = before == null ? "added" : after == null ? "removed" : before.equals(after) ? "unchanged" : "modified";
        List<String> a = lines(before);
        List<String> b = lines(after);
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + path, "b/" + path, a, DiffUtils.diff(a, b), CONTEXT_LINES);

        // 덩어리 위치를 frontmatter/본문으로 가를 기준. 없는 쪽 파일은 반대편으로 판단한다.
        int frontmatterEnd = frontmatterEndLine(before != null ? before : after);
        boolean useNewSide = before == null;

        List<SkillFileDiff.Hunk> hunks = new ArrayList<>();
        List<String> current = null;
        for (String line : unified) {
            if (line.startsWith("@@")) {
                if (current != null) {
                    hunks.add(toHunk(hunks.size(), path, current, frontmatterEnd, useNewSide, beforeHash, afterHash));
                }
                current = new ArrayList<>();
            }
            if (current != null) {
                current.add(line);
            }
        }
        if (current != null) {
            hunks.add(toHunk(hunks.size(), path, current, frontmatterEnd, useNewSide, beforeHash, afterHash));
        }
        // YAML 이 깨져도 줄 비교는 그대로 돌려준다. 깨진 frontmatter 자체가 원인 증거일 수 있다.
        try {
            return new SkillFileDiff(status, frontmatterChanges(before, after), hunks, null);
        } catch (YAMLException e) {
            return new SkillFileDiff(status, List.of(), hunks, e.getMessage());
        }
    }

    private static SkillFileDiff.Hunk toHunk(int index, String path, List<String> lines, int frontmatterEnd,
            boolean useNewSide, String beforeHash, String afterHash) {
        String header = lines.get(0);
        Matcher m = HEADER.matcher(header);
        if (!m.find()) {
            throw new IllegalStateException("unified diff 헤더를 읽을 수 없습니다: " + header);
        }
        int oldLine = Integer.parseInt(m.group(1));
        int newLine = Integer.parseInt(m.group(2));
        int firstChange = -1;
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            char kind = line.isEmpty() ? ' ' : line.charAt(0);
            if (kind != ' ' && firstChange < 0) {
                firstChange = useNewSide ? newLine : oldLine;
            }
            switch (kind) {
                case '-' -> {
                    removed.add(line.substring(1));
                    oldLine++;
                }
                case '+' -> {
                    added.add(line.substring(1));
                    newLine++;
                }
                default -> {
                    oldLine++;
                    newLine++;
                }
            }
        }
        String part = firstChange <= frontmatterEnd ? "frontmatter" : "body";
        String before = String.join("\n", removed);
        String after = String.join("\n", added);
        String diff = "--- a/" + path + "\n+++ b/" + path + "\n" + String.join("\n", lines) + "\n";
        String hunkId = "h_" + sha256(Objects.toString(beforeHash, "") + "|" + Objects.toString(afterHash, "")
                + "|" + header + "|" + before + "|" + after).substring(0, 12);
        return new SkillFileDiff.Hunk(index, hunkId, part, header, before, after, diff);
    }

    /** frontmatter 를 닫는 '---' 의 줄 번호(1부터). frontmatter 가 없으면 0. */
    private static int frontmatterEndLine(String text) {
        if (text == null) {
            return 0;
        }
        List<String> lines = lines(text);
        if (lines.isEmpty() || !lines.get(0).equals("---")) {
            return 0;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).equals("---")) {
                return i + 1;
            }
        }
        return 0;
    }

    private static List<SkillFileDiff.FrontmatterChange> frontmatterChanges(String before, String after) {
        Map<String, Object> a = Frontmatter.parse(before);
        Map<String, Object> b = Frontmatter.parse(after);
        Set<String> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        List<SkillFileDiff.FrontmatterChange> changes = new ArrayList<>();
        for (String key : keys) {
            Object x = a.get(key);
            Object y = b.get(key);
            if (!Objects.equals(x, y)) {
                changes.add(new SkillFileDiff.FrontmatterChange(key, x, y, minus(y, x), minus(x, y)));
            }
        }
        return changes;
    }

    /** 목록 값일 때 left 에만 있는 항목. 목록이 아니면 빈 목록. */
    private static List<String> minus(Object left, Object right) {
        if (!(left instanceof List<?> l)) {
            return List.of();
        }
        List<?> r = right instanceof List<?> list ? list : List.of();
        return l.stream().filter(v -> !r.contains(v)).map(String::valueOf).toList();
    }

    private static List<String> lines(String text) {
        return text == null ? List.of() : text.lines().toList();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
