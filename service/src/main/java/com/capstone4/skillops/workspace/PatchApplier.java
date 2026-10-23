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
 * unified diff 를 SKILL.md 원문에 적용한다. service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의
 * applyOne 이 apply_patch 의 수정 하나마다 부른다.
 * 덩어리 위치와 앞뒤 줄이 원문과 하나라도 다르면 적용하지 않고 IllegalArgumentException 을 던진다. 일부만 적용하는 일은 없다.
 * 실제 적용은 라이브러리(java-diff-utils)의 UnifiedDiffUtils.parseUnifiedDiff 와 DiffUtils.patch 가 하고,
 * 이 클래스는 그 앞에서 diff 를 라이브러리가 바르게 읽을 모양으로 정규화한다.
 *
 * 정규화하는 까닭은 세 가지다. 첫째, LLM 이 쓴 수정 내용은 '---', '+++' 머리줄을 빼먹는 일이 많아서 첫 '@@' 줄부터만
 * 읽고 머리줄은 보지 않는다. 지금 Agent 쪽에서는 skillops-agent/skillops_agent/contracts.py 의 reverse_hunk 가 원인
 * 실험용으로 머리줄을 뺀 덩어리를, 같은 파일의 unified_patch 가 후보 수정안용으로 머리줄이 붙은 diff 를 만들어 보낸다.
 * 둘째, 라이브러리는 ' ', '+', '-' 가 아닌 글자로 시작하는 줄을 말없이 버려서 문맥 줄이나 '+' 줄이 사라진 채 적용하므로
 * 그런 줄은 거부한다. 역슬래시로 시작하는 '\ No newline at end of file' 표시 줄만 예외로 받는다. 셋째, 라이브러리는 앞 줄
 * 수가 0 인 덩어리(git diff -U0 의 끼워 넣기)를 git, GNU patch 와 다른 곳에 넣으므로, git 처럼 '앞 줄 N 다음'으로 읽히게
 * 시작 줄을 옮긴다. 본문이 '+' 줄뿐인 덩어리만 옮기고, 문맥 줄이나 '-' 줄이 있으면 그 줄이 위치를 정한다.
 *
 * diff 를 읽는 규칙을 바꾸려면 hunkLines 가 차례로 부르는 단계 함수를 고친다. 단계 순서에는 조건이 있으니 hunkLines 의
 * 설명을 먼저 읽는다. service/src/test/java/com/capstone4/skillops/workspace/PatchApplierTest.java 가 사례별 결과를 고정한다.
 */
final class PatchApplier {

    /**
     * 덩어리 머리줄 '@@ -앞시작[,앞개수] +뒤시작[,뒤개수] @@ 꼬리' 의 형식이다. 그룹 1 은 앞 시작, 그룹 2 는 앞 개수이고
     * 생략할 수 있다. 그룹 3 은 '+뒤시작' 부터 줄 끝까지의 나머지다. 이 클래스는 줄 수로 덩어리 본문을 가르지 않고
     * 본문 줄의 접두어로 읽으며, 줄 수 가운데 앞 개수가 0 인지만 본다. 숫자를 9자리까지만 받으므로 shiftInsertionHeaders 가
     * 앞 시작에 1 을 더해도 int 범위를 넘지 않는다.
     */
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d{1,9})(?:,(\\d{1,9}))? (\\+\\d{1,9}(?:,\\d{1,9})? @@.*)$");
    /**
     * 덩어리 본문 줄이 시작할 수 있는 글자다. 공백(문맥 줄), '+', '-', 그리고 '\ No newline at end of file' 표시의
     * 역슬래시다.
     */
    private static final String BODY_LINE_PREFIXES = " +-\\";
    /**
     * 라이브러리가 덩어리를 읽으려면 앞에 있어야 하는 '---', '+++' 머리줄이다. 라이브러리가 파일 이름을 보지 않으므로
     * 고정 글자를 쓴다.
     */
    private static final List<String> LIBRARY_FILE_HEADERS = List.of("--- a", "+++ b");

    /** 정적 메서드만 있는 도구 클래스라서 객체를 만들지 못하게 막는다. */
    private PatchApplier() {
    }

    /**
     * 원문에 unified diff 를 적용한 글을 돌려준다. hunkLines 로 정규화한 줄을 라이브러리가 파싱하고 적용한다.
     * 원문과 맞지 않으면 firstMisfitLine 으로 어긋난 줄 번호를 찾아 오류 문구에 넣는다. 라이브러리가 어긋남을
     * PatchFailedException 이 아닌 다른 RuntimeException 으로 알려도 같은 IllegalArgumentException 으로 바꾼다.
     * 이 문구는 WorkspaceService.applyOne 이 skill 이름을 앞에 붙여 apply_patch 오류로 Agent 까지 보낸다.
     * 원문을 String.lines 로 나누므로 CRLF 원문도 고칠 수 있고, 결과의 줄은 LF 로 잇는다.
     *
     * @param original    SKILL.md 원문 전체다.
     * @param unifiedDiff 원문에 적용할 unified diff 다. 덩어리(@@)가 하나 이상 있어야 한다.
     * @return 수정이 들어간 글이다. 원문 끝에 줄바꿈이 있었으면 결과에도 붙이고, 없었으면 붙이지 않는다.
     *         결과에 남은 줄이 하나도 없으면 줄바꿈을 붙이지 않는다.
     * @throws IllegalArgumentException 수정 내용이 비었거나, 읽을 수 없거나, 원문과 맞지 않으면 던진다.
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
     * 라이브러리 파서에 넘길 줄 목록을 만든다. 정규화는 아래 네 단계이고, 이 순서를 지켜야 한다.
     *
     * 1단계 fromFirstHunk 는 첫 '@@' 앞의 줄을 버리고, 머리줄 형식과 본문 줄의 접두어를 검사한다. 빈 줄이 아닌데 ' ', '+',
     * '-', 역슬래시로 시작하지 않는 줄은 라이브러리가 말없이 버리므로 여기서 거부한다. 뒤 단계들은 남은 줄이 머리줄,
     * 접두어가 맞는 본문 줄, 빈 줄 가운데 하나라고 보고 움직인다.
     * 2단계 blankLinesToContextOrSeparator 는 빈 줄의 뜻을 정한다. 덩어리 안의 빈 줄은 git 처럼 빈 문맥 줄(" ")로 바꾸고,
     * 수정 내용 끝이나 다음 '@@' 바로 앞의 빈 줄은 덩어리를 가르는 줄로 보고 지운다. 라이브러리는 빈 줄을 모두 빈 문맥 줄로
     * 읽으므로, 가르는 빈 줄을 남겨 두면 원문과 대조할 문맥 줄이 늘어 맞는 수정도 적용에 실패할 수 있다.
     * 3단계 shiftInsertionHeaders 는 앞 개수가 0 이고 본문이 '+' 줄뿐인 덩어리(끼워 넣기)의 시작 줄을 하나 올린다.
     * 라이브러리는 '-N,0' 을 'N번째 줄 앞'으로 읽지만 git 과 GNU patch 는 'N번째 줄 뒤'로 읽고, '-0,0' 은 맨 위를 뜻한다.
     * 4단계 withLibraryFileHeaders 는 맨 앞에 고정 머리줄을 붙인다. 라이브러리는 '---', '+++' 머리줄이 없으면 덩어리를
     * 하나도 읽지 않는다.
     *
     * 2단계는 반드시 3단계보다 먼저 와야 한다. shiftInsertionHeaders 는 본문 줄의 첫 글자(charAt(0))로 '+' 줄뿐인지를
     * 판단하는데, 앞 개수가 0 인 덩어리에 빈 줄이 남아 있으면 첫 글자가 없어 StringIndexOutOfBoundsException 이 난다.
     * 이 예외는 IllegalArgumentException 이 아니라서 apply_patch 가 INVALID_INPUT 이 아닌 INTERNAL_ERROR 로 실패한다.
     * 예외를 피하더라도 빈 줄의 뜻을 먼저 정해야 판단이 맞는다. 빈 줄을 '+' 가 아닌 줄로 세면 덩어리 끝에 붙은 가르는 빈 줄
     * 때문에 끼워 넣기를 옮기지 못하고, 빈 줄을 건너뛰면 덩어리 안의 빈 문맥 줄을 놓쳐 문맥이 있는 덩어리를 끼워 넣기로
     * 잘못 옮긴다. 4단계는 1단계보다 뒤에 와야 한다. 먼저 붙이면 fromFirstHunk 가 고정 머리줄을 첫 '@@' 앞의 줄로 보고
     * 버린다.
     */
    private static List<String> hunkLines(String unifiedDiff) {
        List<String> lines = fromFirstHunk(unifiedDiff.lines().toList());
        blankLinesToContextOrSeparator(lines);
        shiftInsertionHeaders(lines);
        return withLibraryFileHeaders(lines);
    }

    /**
     * 첫 '@@' 앞의 줄을 버리고, 머리줄 형식과 본문 줄의 접두어를 검사한 줄 목록을 돌려준다. hunkLines 의 1단계다.
     * git diff 의 'diff --git', 'index', '---', '+++' 같은 줄은 첫 '@@' 앞에 있으므로 검사 없이 버린다.
     * 빈 줄은 2단계가 뜻을 정하도록 그대로 둔다. 오류 문구의 줄 번호는 받은 글 전체에서 센다.
     */
    private static List<String> fromFirstHunk(List<String> all) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            String line = all.get(i);
            if (lines.isEmpty() && !isHunkHeader(line)) {
                continue;
            }
            if (isHunkHeader(line)) {
                checkHunkHeader(line);
                lines.add(line);
            } else if (line.isEmpty() || BODY_LINE_PREFIXES.indexOf(line.charAt(0)) >= 0) {
                lines.add(line);
            } else {
                throw new IllegalArgumentException("수정 내용 " + (i + 1) + "번째 줄은 ' ', '+', '-' 중 하나로 시작해야 합니다 "
                        + "(바뀌지 않는 줄도 앞에 공백 한 칸을 붙입니다): " + line);
            }
        }
        return lines;
    }

    /**
     * 수정 내용 끝과 다음 '@@' 바로 앞의 빈 줄은 덩어리를 가르는 줄로 보고 지우고, 나머지 빈 줄은 빈 문맥 줄(" ")로
     * 바꾼다. hunkLines 의 2단계이고 lines 를 그 자리에서 고친다. 빈 문맥 줄은 원문과 대조하므로 틀린 곳에 말없이 들어가지
     * 않는다. 덩어리 끝쪽의 문맥 줄은 빼도 적용 결과가 같으므로, 덩어리 끝의 빈 줄이 원래 빈 문맥 줄이었더라도 지워서
     * 생기는 차이는 없다.
     */
    private static void blankLinesToContextOrSeparator(List<String> lines) {
        // 뒤에서부터 훑는다. separator 는 지금 보는 빈 줄 바로 뒤가 글 끝이거나 머리줄인지를 뜻한다.
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
                separator = isHunkHeader(line);
            }
        }
    }

    /**
     * 라이브러리 파서가 덩어리를 읽도록 맨 앞에 고정 머리줄('--- a', '+++ b')을 붙인 새 목록을 돌려준다.
     * hunkLines 의 4단계다.
     */
    private static List<String> withLibraryFileHeaders(List<String> lines) {
        List<String> withHeaders = new ArrayList<>(LIBRARY_FILE_HEADERS);
        withHeaders.addAll(lines);
        return withHeaders;
    }

    /** '@@' 로 시작하면 덩어리 머리줄로 본다. 형식이 맞는지는 checkHunkHeader 가 따로 검사한다. */
    private static boolean isHunkHeader(String line) {
        return line.startsWith("@@");
    }

    /** '@@' 로 시작하지만 HUNK_HEADER 형식이 아닌 머리줄이면 그 줄을 담은 IllegalArgumentException 을 던진다. */
    private static void checkHunkHeader(String line) {
        if (!HUNK_HEADER.matcher(line).matches()) {
            throw new IllegalArgumentException("수정 내용의 덩어리 머리줄을 읽을 수 없습니다: " + line);
        }
    }

    /**
     * 앞 개수가 0 이고 본문이 '+' 줄뿐인 덩어리의 머리줄을 '-N,0' 에서 '-(N+1),0' 으로 바꿔, 라이브러리가 git 과 같은
     * 'N번째 줄 뒤'에 끼워 넣게 한다. hunkLines 의 3단계이고 lines 를 그 자리에서 고친다. 역슬래시 줄
     * ('\ No newline at end of file')은 '+' 줄과 같이 본다. 앞 개수를 적지 않은 머리줄은 앞 개수가 1 이므로 옮기지 않는다.
     * 본문에 문맥 줄이나 '-' 줄이 있는데 앞 개수가 0 이면 줄 수를 잘못 센 머리줄이므로 옮기지 않고 문맥 대조가 위치를
     * 정하게 둔다. 본문의 빈 줄은 2단계에서 이미 " " 문맥 줄로 바뀌었거나 지워졌다고 보고 첫 글자를 읽으므로,
     * blankLinesToContextOrSeparator 다음에만 불러야 한다.
     */
    private static void shiftInsertionHeaders(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = HUNK_HEADER.matcher(lines.get(i));
            if (!m.matches() || !"0".equals(m.group(2))) {
                continue;
            }
            boolean onlyAdditions = true;
            for (int j = i + 1; j < lines.size() && !isHunkHeader(lines.get(j)); j++) {
                char kind = lines.get(j).charAt(0);
                onlyAdditions &= kind == '+' || kind == '\\';
            }
            if (onlyAdditions) {
                lines.set(i, "@@ -" + (Integer.parseInt(m.group(1)) + 1) + ",0 " + m.group(3));
            }
        }
    }

    /**
     * 원문과 맞지 않는 첫 덩어리의 시작 줄 번호를 1부터 세어 돌려준다. apply 가 오류 문구의 'N번째 줄 근처' 에 쓴다.
     * 덩어리마다 verifyChunk 로 원문과 대조하고, 대조하다 예외가 나도 그 덩어리를 어긋난 것으로 본다.
     * 모든 덩어리가 따로는 맞는데 함께 적용하다 실패했다면(덩어리끼리 겹친 경우 등) 마지막 덩어리의 시작 줄을 돌려준다.
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
