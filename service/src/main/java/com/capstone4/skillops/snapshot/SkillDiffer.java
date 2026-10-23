package com.capstone4.skillops.snapshot;

import java.util.ArrayList;
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
 * 같은 skill 파일의 두 글을 비교해 덩어리(hunk) 목록과 frontmatter 키 변화를 계산하는 순수 함수다. 주문 Agent 를 부르지
 * 않고 입력 글만 받는다. service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java 의 compareSkillMd 가
 * diff_skill 결과를 만들 때 부른다.
 *
 * 덩어리 경계는 문맥 CONTEXT_LINES 줄짜리 unified diff 를 따른다. 덩어리마다 그 덩어리만 담은 diff 를 붙이므로 C-2
 * apply_patch(service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java 의 apply)가 바뀌기 전 글에 그대로
 * 적용할 수 있다. 진단 Agent 는 skillops-agent/skillops_agent/nodes.py 의 _ablate_change 에서 이 diff 를
 * skillops-agent/skillops_agent/contracts.py 의 reverse_hunk 로 뒤집어 바뀐 뒤 글에 적용하고, 변경을 하나씩 되돌려 원인을
 * 찾는다. 그래서 덩어리 하나가 원인 확인(ablation)의 단위다.
 *
 * 튜닝 지점은 다음과 같다. CONTEXT_LINES 를 바꾸면 덩어리 경계와 hunk_id 가 함께 바뀌어 ablation 단위가 달라진다.
 * hunk_id 규칙은 hunkId 와 HUNK_ID_PREFIX, HUNK_ID_HEX_LENGTH 에 있고, frontmatter 와 body 를 가르는 기준은 toHunk 와
 * Frontmatter.closingLine 에 있다.
 */
public final class SkillDiffer {

    /** unified diff 의 문맥 줄 수다. 문맥이 겹칠 만큼 가까운 변경은 한 덩어리로 묶인다. */
    private static final int CONTEXT_LINES = 3;
    /** '@@' 머리줄에서 바뀌기 전과 바뀐 뒤의 시작 줄 번호를 꺼내는 정규식이다. 줄 수 부분은 없어도 된다. */
    private static final Pattern HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
    /** hunk_id 접두어다. 바꾸면 진단 Agent 와 제안서가 가리키는 덩어리 ID 가 모두 바뀐다. */
    private static final String HUNK_ID_PREFIX = "h_";
    /** hunk_id 에 쓰는 SHA-256 16진 글자 수다. */
    private static final int HUNK_ID_HEX_LENGTH = 12;
    // 덩어리 위치(part) 값이다. diff_skill 결과의 hunks[].part 계약이다.
    private static final String PART_FRONTMATTER = "frontmatter";
    private static final String PART_BODY = "body";

    /** 정적 메서드만 있는 도구 클래스라서 객체를 만들지 못하게 막는다. */
    private SkillDiffer() {
    }

    /**
     * 두 글을 비교해 변경 상태, 덩어리 목록, frontmatter 키 변화를 담은 결과를 만든다. 덩어리는 파일 위에서부터 순서대로
     * index 가 0부터 붙는다. 줄 끝 줄바꿈만 다르면 상태는 modified 지만 덩어리는 없다.
     *
     * @param path       diff 머리줄(a/, b/)에 쓰는 파일 경로다. hunk_id 재료가 아니다.
     * @param before     바뀌기 전 글이다. 파일이 없으면 null 이다.
     * @param after      바뀐 뒤 글이다. 파일이 없으면 null 이다.
     * @param beforeHash 바뀌기 전 파일 해시이며 hunk_id 재료다. 없으면 null 이다.
     * @param afterHash  바뀐 뒤 파일 해시이며 hunk_id 재료다. 없으면 null 이다.
     */
    public static SkillFileDiff diff(String path, String before, String after, String beforeHash, String afterHash) {
        String status = SkillFileDiff.statusOf(before, after);
        List<String> unified = unifiedDiff(path, before, after);

        // 덩어리 위치를 frontmatter 와 본문으로 가를 기준을 정한다. 바뀌기 전 글이 없으면(새 파일) 바뀐 뒤 글로 판단한다.
        HunkContext context = new HunkContext(path, Frontmatter.closingLine(before != null ? before : after),
                before == null, beforeHash, afterHash);

        List<SkillFileDiff.Hunk> hunks = new ArrayList<>();
        for (List<String> lines : splitHunks(unified)) {
            hunks.add(toHunk(hunks.size(), lines, context));
        }
        return withFrontmatterChanges(status, hunks, before, after);
    }

    /** 두 글의 unified diff 를 문맥 CONTEXT_LINES 줄로 만들어 줄 목록으로 돌려준다. 없는 파일은 빈 글로 본다. */
    private static List<String> unifiedDiff(String path, String before, String after) {
        List<String> a = lines(before);
        List<String> b = lines(after);
        return UnifiedDiffUtils.generateUnifiedDiff("a/" + path, "b/" + path, a, DiffUtils.diff(a, b), CONTEXT_LINES);
    }

    /** unified diff 를 '@@' 머리줄마다 덩어리로 나눈다. 첫 '@@' 앞의 '---' 와 '+++' 머리줄은 버린다. */
    private static List<List<String>> splitHunks(List<String> unified) {
        List<List<String>> hunks = new ArrayList<>();
        List<String> current = null;
        for (String line : unified) {
            if (line.startsWith("@@")) {
                current = new ArrayList<>();
                hunks.add(current);
            }
            if (current != null) {
                current.add(line);
            }
        }
        return hunks;
    }

    /**
     * 덩어리 줄 목록 하나를 Hunk 로 만든다. 줄 목록의 첫 줄은 '@@' 머리줄이다. 첫 변경 줄 번호가 frontmatter 를 닫는 줄 번호
     * 이하이면 part 를 frontmatter 로, 아니면 body 로 정하므로 닫는 '---' 줄의 변경까지 frontmatter 다. before 와 after 에는
     * 문맥 줄을 뺀 지운 줄과 더한 줄을, diff 에는 이 덩어리만 담은 unified diff 를 넣는다.
     */
    private static SkillFileDiff.Hunk toHunk(int index, List<String> lines, HunkContext context) {
        String header = lines.get(0);
        HunkBody body = HunkBody.read(header, lines.subList(1, lines.size()), context.useNewSide());
        String part = body.firstChangedLine() <= context.frontmatterEnd() ? PART_FRONTMATTER : PART_BODY;
        String before = String.join("\n", body.removed());
        String after = String.join("\n", body.added());
        String diff = standaloneDiff(context.path(), lines);
        String hunkId = hunkId(context.beforeHash(), context.afterHash(), header, before, after);
        return new SkillFileDiff.Hunk(index, hunkId, part, header, before, after, diff);
    }

    /** 덩어리 하나만 담은 unified diff 글을 만든다. '---' 와 '+++' 머리줄, '@@' 머리줄 하나와 본문을 잇고 끝에 줄바꿈을 붙인다. */
    private static String standaloneDiff(String path, List<String> lines) {
        return "--- a/" + path + "\n+++ b/" + path + "\n" + String.join("\n", lines) + "\n";
    }

    /**
     * 덩어리의 고정 ID 를 만든다. 두 파일 해시(없으면 빈 글자), 머리줄, 지운 줄, 더한 줄을 이 순서로 '|' 로 이은 글의 SHA-256
     * 에서 앞 HUNK_ID_HEX_LENGTH 자를 HUNK_ID_PREFIX 뒤에 붙인다. 파일 경로와 index 는 재료가 아니므로 다시 조회해도 같은
     * 덩어리는 같은 ID 를 받는다. 진단 Agent 는 확정한 원인의 hunk_id 를 제안서 root_cause 에 남기므로
     * (skillops-agent/skillops_agent/nodes.py 의 _root_cause), 재료나 순서를 바꾸면 이미 저장된 제안서의 hunk_id 와 맞지 않는다.
     */
    private static String hunkId(String beforeHash, String afterHash, String header, String before, String after) {
        return HUNK_ID_PREFIX + Sha256.hex(Objects.toString(beforeHash, "") + "|" + Objects.toString(afterHash, "")
                + "|" + header + "|" + before + "|" + after).substring(0, HUNK_ID_HEX_LENGTH);
    }

    /**
     * 덩어리 목록에 frontmatter 키 변화를 붙여 파일 비교 결과를 만든다. 어느 쪽이든 YAML 을 읽지 못하면 키 변화를 비우고
     * frontmatter_parse_error 에 이유를 담는다.
     */
    private static SkillFileDiff withFrontmatterChanges(String status, List<SkillFileDiff.Hunk> hunks, String before,
            String after) {
        // YAML 이 깨져도 줄 비교는 그대로 돌려준다. 깨진 frontmatter 자체가 원인 증거일 수 있다.
        try {
            return new SkillFileDiff(status, frontmatterChanges(before, after), hunks, null);
        } catch (YAMLException e) {
            return new SkillFileDiff(status, List.of(), hunks, e.getMessage());
        }
    }

    /**
     * 두 글의 frontmatter 를 읽어 값이 달라진 키만 FrontmatterChange 로 만든다. 키 순서는 before 의 키 순서 뒤에 after 에만
     * 있는 키를 붙인 순서다. 목록 값이면 added 와 removed 를 채운다. YAML 이 깨지면 YAMLException 이 그대로 나간다.
     */
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

    /**
     * left 가 목록이면 right 에 없는 항목을 문자열로 바꿔 돌려준다. left 가 목록이 아니면 빈 목록을 돌려주고, right 가 목록이
     * 아니면 right 를 빈 목록으로 본다.
     */
    private static List<String> minus(Object left, Object right) {
        if (!(left instanceof List<?> l)) {
            return List.of();
        }
        List<?> r = right instanceof List<?> list ? list : List.of();
        return l.stream().filter(v -> !r.contains(v)).map(String::valueOf).toList();
    }

    /** 글을 줄 목록으로 나눈다. null 이면 빈 목록이고, 끝 줄바꿈 뒤의 빈 줄은 세지 않는다. */
    private static List<String> lines(String text) {
        return text == null ? List.of() : text.lines().toList();
    }

    /**
     * 한 파일의 덩어리들이 함께 쓰는 값이다. 파일 경로, frontmatter 를 닫는 줄 번호, 첫 변경 위치를 바뀐 뒤 글 기준으로 셀지,
     * hunk_id 재료인 두 파일 해시를 담는다.
     */
    private record HunkContext(String path, int frontmatterEnd, boolean useNewSide, String beforeHash, String afterHash) {
    }

    /** 덩어리 본문을 센 결과다. 첫 변경 줄 번호(변경이 없으면 -1)와 문맥 줄을 뺀 지운 줄, 더한 줄을 담는다. */
    private record HunkBody(int firstChangedLine, List<String> removed, List<String> added) {

        /**
         * '@@' 머리줄의 시작 줄 번호부터 본문을 한 줄씩 센다. useNewSide 면 첫 변경 위치를 바뀐 뒤 글 기준으로 잡는다.
         * 빈 줄은 문맥 줄로 보고, 머리줄을 읽지 못하면 IllegalStateException 을 던진다.
         */
        static HunkBody read(String header, List<String> body, boolean useNewSide) {
            Matcher m = HEADER.matcher(header);
            if (!m.find()) {
                throw new IllegalStateException("unified diff 헤더를 읽을 수 없습니다: " + header);
            }
            int oldLine = Integer.parseInt(m.group(1));
            int newLine = Integer.parseInt(m.group(2));
            int firstChange = -1;
            List<String> removed = new ArrayList<>();
            List<String> added = new ArrayList<>();
            for (String line : body) {
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
            return new HunkBody(firstChange, removed, added);
        }
    }
}
