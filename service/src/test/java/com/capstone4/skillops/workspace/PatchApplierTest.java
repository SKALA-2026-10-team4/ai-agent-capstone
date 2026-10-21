package com.capstone4.skillops.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.capstone4.skillops.snapshot.SkillDiffer;
import com.capstone4.skillops.snapshot.SkillFileDiff;

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
}
