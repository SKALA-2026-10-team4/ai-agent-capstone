package com.capstone4.skillops.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import com.github.difflib.patch.PatchFailedException;
import com.github.difflib.patch.VerifyChunk;

/**
 * unified diff 를 SKILL.md 원문에 적용한다. apply_patch 가 연습 공간의 skill 마다 부른다.
 * 덩어리 위치와 앞뒤 줄이 원문과 하나라도 다르면 적용하지 않고 IllegalArgumentException 을 던진다(일부만 적용하지 않는다).
 * LLM 이 쓴 수정 내용은 '---', '+++' 머리줄을 빼먹는 일이 많아서, 첫 '@@' 줄부터만 읽고 머리줄은 보지 않는다.
 */
final class PatchApplier {

    /** unified diff 덩어리 머리줄. 개수는 줄 수를 세는 데 쓰지 않으므로 있는지만 본다(없으면 1줄) */
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(,\\d+)? \\+\\d+(,\\d+)? @@.*");

    private PatchApplier() {
    }

    /**
     * @param original    SKILL.md 원문 전체
     * @param unifiedDiff 원문에 적용할 unified diff. 덩어리(@@)가 하나 이상 있어야 한다
     * @return 수정이 들어간 글. 원문 끝에 줄바꿈이 있었으면 결과에도 있고, 없었으면 없다
     * @throws IllegalArgumentException 수정 내용을 읽을 수 없거나 원문과 맞지 않을 때
     */
    static String apply(String original, String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            throw new IllegalArgumentException("수정 내용이 비어 있습니다");
        }
        List<String> source = original.lines().toList();
        Patch<String> patch = UnifiedDiffUtils.parseUnifiedDiff(hunkLines(unifiedDiff));
        if (patch.getDeltas().isEmpty()) {
            throw new IllegalArgumentException("수정 내용에서 '@@ -줄,개수 +줄,개수 @@' 로 시작하는 변경 덩어리를 찾지 못했습니다");
        }
        List<String> patched;
        try {
            patched = DiffUtils.patch(source, patch);
        } catch (PatchFailedException | RuntimeException e) {
            throw new IllegalArgumentException(
                    firstMisfitLine(source, patch) + "번째 줄 근처가 수정 내용과 맞지 않아 적용하지 못했습니다", e);
        }
        String text = String.join("\n", patched);
        return original.endsWith("\n") && !patched.isEmpty() ? text + "\n" : text;
    }

    /**
     * 라이브러리 파서가 읽을 줄들. 첫 '@@' 앞의 머리줄과 끝의 빈 줄을 버리고 고정 머리줄을 붙인다.
     * 라이브러리는 '---', '+++' 머리줄이 없으면 덩어리를 하나도 읽지 않는다.
     */
    private static List<String> hunkLines(String unifiedDiff) {
        List<String> lines = new ArrayList<>(unifiedDiff.lines().dropWhile(line -> !line.startsWith("@@")).toList());
        while (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        for (String line : lines) {
            if (line.startsWith("@@") && !HUNK_HEADER.matcher(line).matches()) {
                throw new IllegalArgumentException("수정 내용의 덩어리 머리줄을 읽을 수 없습니다: " + line);
            }
        }
        lines.addFirst("+++ b");
        lines.addFirst("--- a");
        return lines;
    }

    /**
     * 원문과 맞지 않는 첫 덩어리의 시작 줄(1부터). 덩어리마다는 맞는데 서로 겹쳐서 실패했다면 뒤쪽 덩어리의 줄이다.
     */
    private static int firstMisfitLine(List<String> source, Patch<String> patch) {
        List<AbstractDelta<String>> deltas = patch.getDeltas();
        for (AbstractDelta<String> delta : deltas) {
            try {
                if (delta.getSource().verifyChunk(source) != VerifyChunk.OK) {
                    return delta.getSource().getPosition() + 1;
                }
            } catch (PatchFailedException | RuntimeException e) {
                return delta.getSource().getPosition() + 1;
            }
        }
        return deltas.getLast().getSource().getPosition() + 1;
    }
}
