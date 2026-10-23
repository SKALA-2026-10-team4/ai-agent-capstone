package com.capstone4.skillops.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.capstone4.skillops.snapshot.SkillDiffer;
import com.capstone4.skillops.snapshot.SkillFileDiff;

/**
 * service/src/main/java/com/capstone4/skillops/workspace/PatchApplier.java 의 apply 를 사례별로 보호한다.
 * 덩어리 하나와 여럿의 적용, 끝 줄바꿈 보존, 어긋난 덩어리의 줄 번호가 담긴 오류 문구, 머리줄 없는 diff, C-1 SkillDiffer 가
 * 만든 diff_skill 덩어리, 문맥 없는 끼워 넣기(git diff -U0)의 위치, 접두어 없는 줄 거부, 빈 줄 처리, CRLF 원문,
 * '\ No newline at end of file' 표시를 본다. 정규화 단계를 고칠 때 이 사례들로 결과가 바뀌지 않았는지 확인한다.
 * git apply 와 같은 결과를 기대하는 사례는 그 주석에 적었다. 머리줄 없는 diff 와 CRLF 원문처럼 git apply 가 그대로 받지
 * 않는 사례도 있다.
 */
class PatchApplierTest {

    @Test
    void appliesSingleHunk() {
        assertThat(PatchApplier.apply(F1Texts.broken(), F1Texts.repairOutputsPatch())).isEqualTo(F1Texts.repaired());
    }

    @Test
    void appliesMultiHunk() {
        String original = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n";
        String diff = """
                --- a/x
                +++ b/x
                @@ -2,2 +2,2 @@
                 b
                -c
                +C
                @@ -8,3 +8,3 @@
                 h
                -i
                +I
                 j
                """;
        assertThat(PatchApplier.apply(original, diff)).isEqualTo("a\nb\nC\nd\ne\nf\ng\nh\nI\nj\n");
    }

    @Test
    void keepsTrailingNewline() {
        String diff = "--- a/x\n+++ b/x\n@@ -2,1 +2,1 @@\n-b\n+B\n";
        assertThat(PatchApplier.apply("a\nb\nc\n", diff)).isEqualTo("a\nB\nc\n");
        assertThat(PatchApplier.apply("a\nb\nc", diff)).isEqualTo("a\nB\nc");
    }

    @Test
    void contextMismatchFails() {
        String diff = "--- a/x\n+++ b/x\n@@ -2,1 +2,1 @@\n-없는 줄\n+B\n";
        assertThatThrownBy(() -> PatchApplier.apply("a\nb\nc\n", diff))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("2번째 줄 근처가 수정 내용과 맞지 않아 적용하지 못했습니다");
    }

    @Test
    void reportsTheLineOfTheFirstHunkThatDoesNotFit() {
        String diff = "--- a/x\n+++ b/x\n@@ -1,1 +1,1 @@\n-a\n+A\n@@ -3,1 +3,1 @@\n-없는 줄\n+C\n";
        assertThatThrownBy(() -> PatchApplier.apply("a\nb\nc\n", diff))
                .hasMessage("3번째 줄 근처가 수정 내용과 맞지 않아 적용하지 못했습니다");
    }

    @Test
    void appliesC1HunkDiff() {
        SkillFileDiff diff = SkillDiffer.diff(F1Texts.PATH, F1Texts.broken(), F1Texts.repaired(), "before", "after");
        assertThat(diff.hunks()).isNotEmpty();
        assertThat(PatchApplier.apply(F1Texts.broken(), diff.hunks().get(0).diff())).isEqualTo(F1Texts.repaired());
    }

    @Test
    void appliesHeaderlessDiff() {
        String diff = "@@ -5,1 +5,1 @@\n-" + F1Texts.BROKEN_OUTPUTS + "\n+" + F1Texts.REPAIRED_OUTPUTS + "\n";
        assertThat(PatchApplier.apply(F1Texts.broken(), diff)).isEqualTo(F1Texts.repaired());
    }

    @Test
    void ignoresTrailingBlankLinesInDiff() {
        String diff = "@@ -2,1 +2,1 @@\n-b\n+B\n\n\n";
        assertThat(PatchApplier.apply("a\nb\nc\n", diff)).isEqualTo("a\nB\nc\n");
    }

    @Test
    void diffWithoutHunkFails() {
        assertThatThrownBy(() -> PatchApplier.apply("a\n", "--- a/x\n+++ b/x\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@@");
        assertThatThrownBy(() -> PatchApplier.apply("a\n", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비어 있");
        assertThatThrownBy(() -> PatchApplier.apply("a\n", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("비어 있");
    }

    @Test
    void malformedHunkHeaderFails() {
        assertThatThrownBy(() -> PatchApplier.apply("a\nb\n", "@@ 2번째 줄 @@\n-b\n+B\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수정 내용의 덩어리 머리줄을 읽을 수 없습니다: @@ 2번째 줄 @@");
    }

    // 문맥 없는 끼워 넣기(git diff -U0 모양)를 다룬다. 기대 결과는 git apply --unidiff-zero 가 만든 결과와 같다.

    /** 끼워 넣기와 접두어 시험에 쓰는 다섯 줄 원문이다. */
    static final String FIVE_LINES = "l1\nl2\nl3\nl4\nl5\n";

    @Test
    void insertsAtTopWithZeroContext() {
        assertThat(PatchApplier.apply(FIVE_LINES, "@@ -0,0 +1 @@\n+X\n")).isEqualTo("X\nl1\nl2\nl3\nl4\nl5\n");
    }

    @Test
    void insertsAfterOldLineWithZeroContext() {
        assertThat(PatchApplier.apply(FIVE_LINES, "@@ -2,0 +3 @@\n+X\n")).isEqualTo("l1\nl2\nX\nl3\nl4\nl5\n");
    }

    @Test
    void insertsAtEndWithZeroContext() {
        assertThat(PatchApplier.apply(FIVE_LINES, "@@ -5,0 +6 @@\n+X\n")).isEqualTo("l1\nl2\nl3\nl4\nl5\nX\n");
        assertThat(PatchApplier.apply("l1\nl2", "@@ -2,0 +3 @@\n+X\n")).isEqualTo("l1\nl2\nX");
    }

    @Test
    void insertsSeveralZeroContextHunksInOriginalCoordinates() {
        String diff = "--- a/x\n+++ b/x\n@@ -1,0 +2 @@\n+A\n@@ -3,0 +5,2 @@\n+B\n+C\n";
        assertThat(PatchApplier.apply(FIVE_LINES, diff)).isEqualTo("l1\nA\nl2\nl3\nB\nC\nl4\nl5\n");
    }

    @Test
    void zeroOldCountHeaderWithContextKeepsItsStart() {
        // 줄 수를 잘못 센 머리줄(-2,0 인데 문맥 줄이 있다)이다. 올바른 머리줄 -2,2 +2,3 에 git apply 가 만드는 결과와 같다.
        assertThat(PatchApplier.apply("a\na\na\na\n", "@@ -2,0 +2,3 @@\n a\n+X\n a\n")).isEqualTo("a\na\nX\na\na\n");
    }

    @Test
    void zeroOldCountHeaderWithContextIsPlacedByTheContext() {
        assertThat(PatchApplier.apply(FIVE_LINES, "@@ -3,0 +3,2 @@\n l3\n+X\n")).isEqualTo("l1\nl2\nl3\nX\nl4\nl5\n");
    }

    @Test
    void zeroOldCountHeaderWithRemovalKeepsItsStart() {
        assertThat(PatchApplier.apply(FIVE_LINES, "@@ -3,0 +3 @@\n-l3\n+X\n")).isEqualTo("l1\nl2\nX\nl4\nl5\n");
    }

    @Test
    void insertionPastTheEndFails() {
        // git 은 파일 끝에 붙이지만, 없는 줄 뒤에 넣으라는 수정은 위치를 잘못 센 것일 수 있어 거부한다.
        assertThatThrownBy(() -> PatchApplier.apply(FIVE_LINES, "@@ -6,0 +7 @@\n+X\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("맞지 않아");
    }

    // 접두어 없는 줄을 다룬다. java-diff-utils 는 이런 줄을 말없이 버리므로 PatchApplier 가 먼저 거부한다.

    @Test
    void contextLineWithoutLeadingSpaceFails() {
        assertThatThrownBy(() -> PatchApplier.apply(FIVE_LINES, "@@ -2,2 +2,3 @@\nl2\n+X\nl3\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수정 내용 2번째 줄은 ' ', '+', '-' 중 하나로 시작해야 합니다 (바뀌지 않는 줄도 앞에 공백 한 칸을 붙입니다): l2");
    }

    @Test
    void continuationLineInPlusBlockWithoutPlusFails() {
        assertThatThrownBy(() -> PatchApplier.apply(FIVE_LINES, "--- a/x\n+++ b/x\n@@ -2,1 +2,2 @@\n-l2\n+A\nB\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("수정 내용 6번째 줄은 ' ', '+', '-' 중 하나로 시작해야 합니다")
                .hasMessageEndingWith(": B");
    }

    @Test
    void lineAfterMinusBlockWithoutPrefixFails() {
        assertThatThrownBy(() -> PatchApplier.apply(FIVE_LINES, "@@ -2,2 +2,2 @@\n-l2\nl3\n+X\nY\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("수정 내용 3번째 줄은 ' ', '+', '-' 중 하나로 시작해야 합니다");
    }

    // 덩어리 안의 빈 줄은 git 처럼 빈 문맥 줄로 본다. 문맥은 원문과 대조하므로 틀린 곳에 말없이 들어가지 않는다.

    @Test
    void emptyLineInsideHunkIsAnEmptyContextLine() {
        assertThat(PatchApplier.apply("a\n\nb\nc\n", "@@ -1,3 +1,3 @@\n a\n\n-b\n+B\n")).isEqualTo("a\n\nB\nc\n");
    }

    @Test
    void emptyContextLineIsVerifiedAgainstTheOriginal() {
        assertThatThrownBy(() -> PatchApplier.apply("a\nx\nb\nc\n", "@@ -1,3 +1,3 @@\n a\n\n-b\n+B\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("1번째 줄 근처가 수정 내용과 맞지 않아 적용하지 못했습니다");
    }

    @Test
    void emptyLinesBetweenHunksAreSeparators() {
        String diff = "@@ -1 +1 @@\n-a\n+A\n\n@@ -4 +4 @@\n-d\n+D\n\n";
        assertThat(PatchApplier.apply("a\nb\nc\nd\n", diff)).isEqualTo("A\nb\nc\nD\n");
    }

    /** CRLF 원문도 고칠 수 있고, 결과 줄바꿈은 LF 로 이어진다(지금 동작). */
    @Test
    void crlfOriginalComesBackWithLfLineEnds() {
        assertThat(PatchApplier.apply("a\r\nb\r\nc\r\n", "@@ -2,1 +2,1 @@\n-b\n+B\n")).isEqualTo("a\nB\nc\n");
    }

    /** git diff 의 머리줄(diff --git, index 등)은 첫 '@@' 앞이라 읽지 않는다. 접두어 검사도 하지 않는다. */
    @Test
    void ignoresGitHeaderLinesBeforeFirstHunk() {
        String diff = "diff --git a/x b/x\nindex 1234567..89abcde 100644\n--- a/x\n+++ b/x\n@@ -2,1 +2,1 @@\n-b\n+B\n";
        assertThat(PatchApplier.apply("a\nb\nc\n", diff)).isEqualTo("a\nB\nc\n");
    }

    /** 덩어리 본문 줄 앞의 빈 줄은 빈 문맥 줄이고, 첫 '@@' 앞의 빈 줄은 버린다. */
    @Test
    void emptyLineRightAfterHeaderIsAContextLine() {
        assertThat(PatchApplier.apply("\nb\nc\n", "\n\n@@ -1,2 +1,2 @@\n\n-b\n+B\n")).isEqualTo("\nB\nc\n");
    }

    @Test
    void noNewlineMarkerIsAccepted() {
        String diff = "@@ -2,1 +2,1 @@\n-b\n\\ No newline at end of file\n+B\n\\ No newline at end of file\n";
        assertThat(PatchApplier.apply("a\nb", diff)).isEqualTo("a\nB");
    }
}
