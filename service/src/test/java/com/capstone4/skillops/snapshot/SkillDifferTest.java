package com.capstone4.skillops.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java 의 diff 가 만드는 덩어리 계약(hunk_id, part, header,
 * before 와 after, 단독 diff)과 frontmatter 변화 계산을 보호한다. hunk_id 와 diff 글은 진단 Agent 와 apply_patch 가 그대로
 * 쓰는 계약이라 글자 단위로 고정한다. 덩어리 경계(문맥 3줄), frontmatter 와 body 의 경계, 깨진 YAML, 새 파일과 지운 파일,
 * 장애 F1 수정도 확인한다.
 */
class SkillDifferTest {

    static final String PATH = "skills/x/SKILL.md";
    /** 단독 diff 앞에 붙는 '---' 와 '+++' 머리줄이다. */
    static final String FILE_HEADER = "--- a/" + PATH + "\n+++ b/" + PATH + "\n";

    @Test
    void singleLineChangeIsOneBodyHunk() {
        SkillFileDiff diff = SkillDiffer.diff(PATH, "a\nb\nc\n", "a\nB\nc\n", "h1", "h2");

        assertThat(diff.status()).isEqualTo("modified");
        assertThat(diff.frontmatterChanges()).isEmpty();
        assertThat(diff.frontmatterParseError()).isNull();
        assertThat(diff.hunks()).containsExactly(new SkillFileDiff.Hunk(0, "h_8ab98c34b234", "body", "@@ -1,3 +1,3 @@",
                "b", "B", FILE_HEADER + "@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n"));
    }

    /** hunk_id 는 "h_" 뒤에 SHA-256(beforeHash|afterHash|header|before|after) 의 앞 12자를 붙인 값이다. 없는 해시는 빈 글자로 넣는다. */
    @Test
    void hunkIdFollowsTheDocumentedRecipe() {
        SkillFileDiff.Hunk withHashes = SkillDiffer.diff(PATH, "a\nb\nc\n", "a\nB\nc\n", "h1", "h2").hunks().get(0);
        SkillFileDiff.Hunk withoutHashes = SkillDiffer.diff(PATH, "a\nb\nc\n", "a\nB\nc\n", null, null).hunks().get(0);
        SkillFileDiff.Hunk otherBefore = SkillDiffer.diff(PATH, "a\nb\nc\n", "a\nB\nc\n", "hX", "h2").hunks().get(0);
        SkillFileDiff.Hunk otherPath = SkillDiffer.diff("skills/y/SKILL.md", "a\nb\nc\n", "a\nB\nc\n", "h1", "h2").hunks().get(0);

        assertThat(withHashes.hunkId()).isEqualTo("h_" + sha256("h1|h2|@@ -1,3 +1,3 @@|b|B").substring(0, 12));
        assertThat(withoutHashes.hunkId()).isEqualTo("h_e3ee027a9fa0")
                .isEqualTo("h_" + sha256("||@@ -1,3 +1,3 @@|b|B").substring(0, 12));
        assertThat(otherBefore.hunkId()).isNotEqualTo(withHashes.hunkId());
        // 경로는 hunk_id 재료가 아니다.
        assertThat(otherPath.hunkId()).isEqualTo(withHashes.hunkId());
        assertThat(otherPath.diff()).startsWith("--- a/skills/y/SKILL.md\n+++ b/skills/y/SKILL.md\n@@ -1,3 +1,3 @@\n");
    }

    @Test
    void multiLineChangeJoinsRemovedAndAddedLines() {
        SkillFileDiff.Hunk hunk = SkillDiffer.diff(PATH, "a\nb\nc\nd\n", "a\nB\nC\nd\n", "h1", "h2").hunks().get(0);

        assertThat(hunk.header()).isEqualTo("@@ -1,4 +1,4 @@");
        assertThat(hunk.before()).isEqualTo("b\nc");
        assertThat(hunk.after()).isEqualTo("B\nC");
        assertThat(hunk.diff()).isEqualTo(FILE_HEADER + "@@ -1,4 +1,4 @@\n a\n-b\n-c\n+B\n+C\n d\n");
    }

    @Test
    void distantChangesBecomeSeparateHunksWithOneHeaderEach() {
        String before = numbered(20, -1, -1);
        String after = numbered(20, 2, 19);

        List<SkillFileDiff.Hunk> hunks = SkillDiffer.diff(PATH, before, after, "h1", "h2").hunks();

        assertThat(hunks).extracting(SkillFileDiff.Hunk::index).containsExactly(0, 1);
        assertThat(hunks).extracting(SkillFileDiff.Hunk::header).containsExactly("@@ -1,5 +1,5 @@", "@@ -16,5 +16,5 @@");
        assertThat(hunks).extracting(SkillFileDiff.Hunk::part).containsExactly("body", "body");
        assertThat(hunks.get(0).diff()).isEqualTo(FILE_HEADER + "@@ -1,5 +1,5 @@\n l1\n-l2\n+L2\n l3\n l4\n l5\n");
        assertThat(hunks.get(1).diff()).isEqualTo(FILE_HEADER + "@@ -16,5 +16,5 @@\n l16\n l17\n l18\n-l19\n+L19\n l20\n");
        for (SkillFileDiff.Hunk hunk : hunks) {
            assertThat(hunk.diff().lines().filter(l -> l.startsWith("@@")).count()).isEqualTo(1);
        }
        assertThat(hunks.get(0).hunkId()).isNotEqualTo(hunks.get(1).hunkId());
    }

    /** 문맥 3줄이 겹치면 한 덩어리다. */
    @Test
    void changesWithinContextShareOneHunk() {
        List<SkillFileDiff.Hunk> hunks = SkillDiffer.diff(PATH, numbered(20, -1, -1), numbered(20, 5, 11), "h1", "h2").hunks();

        assertThat(hunks).hasSize(1);
        assertThat(hunks.get(0).header()).isEqualTo("@@ -2,13 +2,13 @@");
        assertThat(hunks.get(0).before()).isEqualTo("l5\nl11");
        assertThat(hunks.get(0).after()).isEqualTo("L5\nL11");
    }

    @Test
    void frontmatterListChangeReportsAddedAndRemoved() {
        String before = "---\nname: x\ninputs: [a, b]\nversion: 1\n---\nbody\n";
        String after = "---\nname: x\ninputs: [b, c]\nversion: 2\n---\nbody\n";

        SkillFileDiff diff = SkillDiffer.diff(PATH, before, after, "h1", "h2");

        assertThat(diff.status()).isEqualTo("modified");
        assertThat(diff.frontmatterParseError()).isNull();
        assertThat(diff.frontmatterChanges()).containsExactly(
                new SkillFileDiff.FrontmatterChange("inputs", List.of("a", "b"), List.of("b", "c"), List.of("c"), List.of("a")),
                new SkillFileDiff.FrontmatterChange("version", 1, 2, List.of(), List.of()));
        assertThat(diff.hunks()).hasSize(1);
        assertThat(diff.hunks().get(0).part()).isEqualTo("frontmatter");
        assertThat(diff.hunks().get(0).header()).isEqualTo("@@ -1,6 +1,6 @@");
        assertThat(diff.hunks().get(0).before()).isEqualTo("inputs: [a, b]\nversion: 1");
        assertThat(diff.hunks().get(0).after()).isEqualTo("inputs: [b, c]\nversion: 2");
    }

    /** 바뀐 키 순서는 before 의 키 순서 뒤에 after 에만 있는 키다. */
    @Test
    void frontmatterChangesFollowBeforeKeysThenNewKeys() {
        String before = "---\nname: x\na: 1\nkeep: same\n---\nbody\n";
        String after = "---\nname: x\nkeep: same\nb: 2\n---\nbody\n";

        assertThat(SkillDiffer.diff(PATH, before, after, "h1", "h2").frontmatterChanges()).containsExactly(
                new SkillFileDiff.FrontmatterChange("a", 1, null, List.of(), List.of()),
                new SkillFileDiff.FrontmatterChange("b", null, 2, List.of(), List.of()));
    }

    @Test
    void listAgainstMissingKeyCountsEveryItem() {
        String before = "---\nname: x\n---\nbody\n";
        String after = "---\nname: x\nsteps: [p, q]\n---\nbody\n";

        assertThat(SkillDiffer.diff(PATH, before, after, "h1", "h2").frontmatterChanges()).containsExactly(
                new SkillFileDiff.FrontmatterChange("steps", null, List.of("p", "q"), List.of("p", "q"), List.of()));
    }

    /** 닫는 '---' 줄의 변경까지 frontmatter 다. 그 다음 줄부터 body 다. */
    @Test
    void closingDelimiterBelongsToFrontmatter() {
        String before = "---\nname: x\n---\nbody\n";

        SkillFileDiff closing = SkillDiffer.diff(PATH, before, "---\nname: x\n--- \nbody\n", "h1", "h2");
        SkillFileDiff firstBodyLine = SkillDiffer.diff(PATH, before, "---\nname: x\n---\nBODY\n", "h1", "h2");

        assertThat(closing.hunks()).extracting(SkillFileDiff.Hunk::part).containsExactly("frontmatter");
        assertThat(closing.frontmatterChanges()).containsExactly(
                new SkillFileDiff.FrontmatterChange("name", "x", null, List.of(), List.of()));
        assertThat(firstBodyLine.hunks()).extracting(SkillFileDiff.Hunk::part).containsExactly("body");
        assertThat(firstBodyLine.frontmatterChanges()).isEmpty();
    }

    /** "---\n---\n" 은 YAML 로는 블록이 없지만 덩어리 위치 판단에서는 2번째 줄까지 frontmatter 다. */
    @Test
    void emptyFrontmatterBlockEndsAtLineTwo() {
        String before = "---\n---\nbody\n";

        SkillFileDiff inserted = SkillDiffer.diff(PATH, before, "---\nname: y\n---\nbody\n", "h1", "h2");
        SkillFileDiff bodyChange = SkillDiffer.diff(PATH, before, "---\n---\nBODY\n", "h1", "h2");

        assertThat(inserted.hunks()).extracting(SkillFileDiff.Hunk::part).containsExactly("frontmatter");
        assertThat(inserted.frontmatterChanges()).containsExactly(
                new SkillFileDiff.FrontmatterChange("name", null, "y", List.of(), List.of()));
        assertThat(bodyChange.hunks()).extracting(SkillFileDiff.Hunk::part).containsExactly("body");
    }

    @Test
    void unclosedFrontmatterCountsAsBody() {
        SkillFileDiff diff = SkillDiffer.diff(PATH, "---\nname: x\nbody\n", "---\nname: y\nbody\n", "h1", "h2");

        assertThat(diff.hunks()).extracting(SkillFileDiff.Hunk::part).containsExactly("body");
        assertThat(diff.frontmatterChanges()).isEmpty();
    }

    /** YAML 이 깨져도 줄 비교는 그대로 돌려준다. */
    @Test
    void brokenYamlKeepsHunksAndReportsParseError() {
        String before = "---\nname: x\ninputs: [a\n---\nbody\n";
        String after = "---\nname: x\ninputs: [a]\n---\nbody\n";

        SkillFileDiff diff = SkillDiffer.diff(PATH, before, after, "h1", "h2");
        SkillFileDiff sameLinesValidYaml = SkillDiffer.diff(PATH, "---\nname: x\ninputs: [z]\n---\nbody\n", after, "h1", "h2");

        assertThat(diff.status()).isEqualTo("modified");
        assertThat(diff.frontmatterChanges()).isEmpty();
        assertThat(diff.frontmatterParseError()).isNotBlank();
        assertThat(diff.hunks()).hasSize(1);
        assertThat(diff.hunks().get(0).part()).isEqualTo("frontmatter");
        assertThat(diff.hunks().get(0).before()).isEqualTo("inputs: [a");
        assertThat(diff.hunks().get(0).after()).isEqualTo("inputs: [a]");
        assertThat(sameLinesValidYaml.frontmatterParseError()).isNull();
    }

    @Test
    void duplicateYamlKeyIsAParseError() {
        SkillFileDiff diff = SkillDiffer.diff(PATH, "---\nname: x\n---\nbody\n", "---\nname: x\nname: y\n---\nbody\n", "h1", "h2");

        assertThat(diff.frontmatterChanges()).isEmpty();
        assertThat(diff.frontmatterParseError()).isNotBlank();
        assertThat(diff.hunks()).hasSize(1);
    }

    /** 새로 생긴 파일은 바뀐 뒤 글로 위치를 판단한다. */
    @Test
    void addedFileUsesTheNewSide() {
        String after = "---\nname: x\n---\nbody\n";

        SkillFileDiff diff = SkillDiffer.diff(PATH, null, after, null, "h2");

        assertThat(diff.status()).isEqualTo("added");
        assertThat(diff.hunks()).hasSize(1);
        SkillFileDiff.Hunk hunk = diff.hunks().get(0);
        assertThat(hunk.index()).isZero();
        assertThat(hunk.part()).isEqualTo("frontmatter");
        assertThat(hunk.header()).isEqualTo("@@ -1,0 +1,4 @@");
        assertThat(hunk.before()).isEmpty();
        assertThat(hunk.after()).isEqualTo("---\nname: x\n---\nbody");
        assertThat(hunk.diff()).isEqualTo(FILE_HEADER + "@@ -1,0 +1,4 @@\n+---\n+name: x\n+---\n+body\n");
        assertThat(hunk.hunkId()).isEqualTo("h_" + sha256("|h2|@@ -1,0 +1,4 @@||---\nname: x\n---\nbody").substring(0, 12));
        assertThat(diff.frontmatterChanges()).containsExactly(
                new SkillFileDiff.FrontmatterChange("name", null, "x", List.of(), List.of()));
    }

    @Test
    void addedFileWithoutFrontmatterIsBody() {
        assertThat(SkillDiffer.diff(PATH, null, "body only\n", null, "h2").hunks())
                .extracting(SkillFileDiff.Hunk::part).containsExactly("body");
    }

    @Test
    void removedFileUsesTheOldSide() {
        String before = "---\nname: x\n---\nbody\n";

        SkillFileDiff diff = SkillDiffer.diff(PATH, before, null, "h1", null);

        assertThat(diff.status()).isEqualTo("removed");
        assertThat(diff.hunks()).hasSize(1);
        SkillFileDiff.Hunk hunk = diff.hunks().get(0);
        assertThat(hunk.part()).isEqualTo("frontmatter");
        assertThat(hunk.header()).isEqualTo("@@ -1,4 +1,0 @@");
        assertThat(hunk.before()).isEqualTo("---\nname: x\n---\nbody");
        assertThat(hunk.after()).isEmpty();
        assertThat(hunk.diff()).isEqualTo(FILE_HEADER + "@@ -1,4 +1,0 @@\n----\n-name: x\n----\n-body\n");
        assertThat(diff.frontmatterChanges()).containsExactly(
                new SkillFileDiff.FrontmatterChange("name", "x", null, List.of(), List.of()));
    }

    @Test
    void sameTextIsUnchangedWithoutHunks() {
        String text = "---\nname: x\ninputs: [a]\n---\nbody\n";

        assertThat(SkillDiffer.diff(PATH, text, text, "h1", "h1"))
                .isEqualTo(new SkillFileDiff("unchanged", List.of(), List.of(), null));
    }

    /** 판정 순서상 before 가 없으면 after 도 없어도 added 다. */
    @Test
    void bothMissingCountsAsAdded() {
        assertThat(SkillDiffer.diff(PATH, null, null, null, null))
                .isEqualTo(new SkillFileDiff("added", List.of(), List.of(), null));
    }

    @Test
    void trailingNewlineDifferenceIsInvisible() {
        assertThat(SkillDiffer.diff(PATH, "a\nb", "a\nb\n", "h1", "h2"))
                .isEqualTo(new SkillFileDiff("modified", List.of(), List.of(), null));
    }

    /** 장애 F1 의 broken 글을 repaired 글과 비교하면 outputs 한 줄이 frontmatter 덩어리 하나로 나온다. */
    @Test
    void f1RepairIsOneFrontmatterHunk() {
        String path = "skills/inventory-check/SKILL.md";

        SkillFileDiff diff = SkillDiffer.diff(path, SnapshotTestSupport.f1Broken(), SnapshotTestSupport.f1Repaired(),
                "sha256:broken", "sha256:repaired");

        assertThat(diff.status()).isEqualTo("modified");
        assertThat(diff.frontmatterParseError()).isNull();
        assertThat(diff.frontmatterChanges()).containsExactly(new SkillFileDiff.FrontmatterChange("outputs",
                List.of("inventory.reservable_quantity"), List.of("inventory.reservable_quantity", "inventory.reserved"),
                List.of("inventory.reserved"), List.of()));
        assertThat(diff.hunks()).hasSize(1);
        SkillFileDiff.Hunk hunk = diff.hunks().get(0);
        assertThat(hunk.part()).isEqualTo("frontmatter");
        assertThat(hunk.header()).isEqualTo("@@ -2,7 +2,7 @@");
        assertThat(hunk.before()).isEqualTo("outputs:    [inventory.reservable_quantity]");
        assertThat(hunk.after()).isEqualTo("outputs:    [inventory.reservable_quantity, inventory.reserved]");
        assertThat(hunk.diff()).startsWith("--- a/" + path + "\n+++ b/" + path + "\n@@ -2,7 +2,7 @@\n").endsWith("\n");
        assertThat(hunk.hunkId()).isEqualTo("h_" + sha256("sha256:broken|sha256:repaired|@@ -2,7 +2,7 @@|"
                + hunk.before() + "|" + hunk.after()).substring(0, 12));
    }

    /** l1 부터 count 번째 줄까지 만들고, change1 과 change2 로 고른 줄만 대문자 L 로 바꾼 글을 돌려준다. -1 은 고르지 않는다는 뜻이다. */
    private static String numbered(int count, int change1, int change2) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> (i == change1 || i == change2 ? "L" : "l") + i)
                .collect(Collectors.joining("\n", "", "\n"));
    }

    /** hunk_id 기대값을 만들 SHA-256 16진 계산이다. production 의 Sha256 을 쓰지 않고 따로 계산해 결과를 맞춰 본다. */
    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
