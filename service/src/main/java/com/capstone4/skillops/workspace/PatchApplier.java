package com.capstone4.skillops.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
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
 * 라이브러리(java-diff-utils)가 말없이 다르게 읽는 두 경우는 라이브러리에 넘기기 전에 바로잡는다.
 * 접두어(' ', '+', '-') 없는 줄은 라이브러리가 버리므로 거부하고, 앞 줄 수가 0인 덩어리(git diff -U0 의 끼워 넣기)는
 * git·GNU patch 와 같이 '앞 줄 N 다음'으로 읽도록 시작 줄을 옮긴다.
 */
final class PatchApplier {

    /**
     * unified diff 덩어리 머리줄 '@@ -앞시작[,앞개수] +뒤시작[,뒤개수] @@ ...'. 줄 수는 덩어리 본문을 세는 데 쓰지 않는다
     * (본문 줄의 접두어로 읽는다). 앞 개수가 0 인지만 본다. 그룹: 1 앞 시작, 2 앞 개수(없을 수 있다), 3 나머지(+뒤시작 …)
     */
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d{1,9})(?:,(\\d{1,9}))? (\\+\\d{1,9}(?:,\\d{1,9})? @@.*)$");

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
     * 라이브러리 파서가 읽을 줄들. 첫 '@@' 앞의 머리줄을 버리고, 본문 줄을 검사하고, 고정 머리줄을 붙인다.
     * 라이브러리는 '---', '+++' 머리줄이 없으면 덩어리를 하나도 읽지 않는다.
     * <ul>
     * <li>'@@' 로 시작하면 머리줄이다. 형식이 맞는지 보고, 앞 개수가 0 이면 시작 줄을 하나 올린다.
     *     라이브러리는 '-N,0' 을 'N번째 줄 앞'으로 읽지만 git·GNU 는 'N번째 줄 뒤'로 읽는다('-0,0' 은 맨 위).</li>
     * <li>' ', '+', '-', 역슬래시('\ No newline at end of file' 표시)로 시작하지 않는 줄은 거부한다. 라이브러리는 이런 줄을 말없이 버려서
     *     문맥 줄이나 '+' 줄이 사라진 채 적용된다.</li>
     * <li>빈 줄은 git 처럼 빈 문맥 줄로 본다(원문과 대조하므로 틀린 곳에 들어가지 않는다).
     *     다만 수정 내용 끝이나 다음 '@@' 바로 앞의 빈 줄은 덩어리를 가르는 줄로 보고 버린다. 끝쪽 문맥 줄을 빼도 결과는 같다.</li>
     * </ul>
     */
    private static List<String> hunkLines(String unifiedDiff) {
        List<String> all = unifiedDiff.lines().toList();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            String line = all.get(i);
            if (lines.isEmpty() && !line.startsWith("@@")) {
                continue;
            }
            if (line.startsWith("@@")) {
                lines.add(hunkHeader(line));
            } else if (line.isEmpty() || " +-\\".indexOf(line.charAt(0)) >= 0) {
                lines.add(line);
            } else {
                throw new IllegalArgumentException("수정 내용 " + (i + 1) + "번째 줄은 ' ', '+', '-' 중 하나로 시작해야 합니다 "
                        + "(바뀌지 않는 줄도 앞에 공백 한 칸을 붙입니다): " + line);
            }
        }
        boolean separator = true;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                if (separator) {
                    lines.remove(i);
                } else {
                    lines.set(i, " ");
                }
            } else {
                separator = line.startsWith("@@");
            }
        }
        lines.addFirst("+++ b");
        lines.addFirst("--- a");
        return lines;
    }

    private static String hunkHeader(String line) {
        Matcher m = HUNK_HEADER.matcher(line);
        if (!m.matches()) {
            throw new IllegalArgumentException("수정 내용의 덩어리 머리줄을 읽을 수 없습니다: " + line);
        }
        if (!"0".equals(m.group(2))) {
            return line;
        }
        return "@@ -" + (Integer.parseInt(m.group(1)) + 1) + ",0 " + m.group(3);
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
