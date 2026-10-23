package com.capstone4.skillops.snapshot;

import static com.capstone4.skillops.snapshot.SnapshotTestSupport.AFTER;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.BEFORE;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.assertFailure;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.failure;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.file;
import static com.capstone4.skillops.snapshot.SnapshotTestSupport.manifest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffService.java 의 diff_skill 조립을 보호한다.
 * skill_id 입력 검사, manifest 와 파일의 조회 순서, 해시가 같으면 읽지 않는 지름길, SKILL_NOT_FOUND, added 와 removed 에서
 * 있는 쪽만 읽는 규칙, related_files 의 해시 비교와 정렬, manifest 경로 중복과 null 해시의 실패를 고정한다.
 * 주문 Agent 는 Mockito 로 대신한다.
 */
class SkillDiffServiceTest {

    static final String SKILL_MD = "skills/x/SKILL.md";

    OrderAgentClient orderAgent = mock(OrderAgentClient.class);
    SkillDiffService service = new SkillDiffService(orderAgent);

    @Test
    void badSkillIdIsRejectedWithoutCalling() {
        for (String bad : List.of("Bad_Id", "-x", "", "x/y", "../x", "X")) {
            assertFailure(failure(() -> service.diffSkill(bad, BEFORE, AFTER)),
                    "INVALID_INPUT", "skill_id 는 소문자·숫자·하이픈만 씁니다: " + bad, false, null, null);
        }
        assertFailure(failure(() -> service.diffSkill(null, BEFORE, AFTER)),
                "INVALID_INPUT", "skill_id 는 소문자·숫자·하이픈만 씁니다: null", false, null, null);
        verifyNoInteractions(orderAgent);
    }

    @Test
    void digitsAndHyphensAreAccepted() {
        serve(BEFORE, "snap-b", file("skills/0-a-/SKILL.md", "h"));
        serve(AFTER, "snap-a", file("skills/0-a-/SKILL.md", "h"));

        assertThat(service.diffSkill("0-a-", BEFORE, AFTER).status()).isEqualTo("unchanged");
    }

    @Test
    void missingInBothSnapshotsIsSkillNotFoundForAfter() {
        serve(BEFORE, "snap-b", file("skills/other/SKILL.md", "h1"));
        serve(AFTER, "snap-a", file("skills/x/resources/a.md", "h2"));

        assertFailure(failure(() -> service.diffSkill("x", BEFORE, AFTER)),
                "SKILL_NOT_FOUND", "두 snapshot 모두에 skills/x/SKILL.md 가 없습니다.", false, null, AFTER);
        verify(orderAgent, never()).readText(anyString(), anyString());
    }

    @Test
    void sameHashIsUnchangedWithoutReadingFiles() {
        serve(BEFORE, "snap-b", file(SKILL_MD, "same"));
        serve(AFTER, "snap-a", file(SKILL_MD, "same"));

        DiffSkillResult result = service.diffSkill("x", BEFORE, AFTER);

        assertThat(result).isEqualTo(new DiffSkillResult("x",
                new DiffSkillResult.Side(BEFORE, "snap-b", SKILL_MD, "same"),
                new DiffSkillResult.Side(AFTER, "snap-a", SKILL_MD, "same"),
                "unchanged", List.of(), List.of(), null, List.of()));
        verify(orderAgent, never()).readText(anyString(), anyString());
    }

    /** 해시가 같으면 깨진 YAML 이어도 읽지 않으므로 parse error 가 생기지 않는다. */
    @Test
    void sameHashSkipsYamlEvenIfBroken() {
        serve(BEFORE, "snap-b", file(SKILL_MD, "same"));
        serve(AFTER, "snap-a", file(SKILL_MD, "same"));
        when(orderAgent.readText(anyString(), anyString())).thenReturn("---\ninputs: [a\n---\n");

        assertThat(service.diffSkill("x", BEFORE, AFTER).frontmatterParseError()).isNull();
    }

    @Test
    void changedFileIsReadBeforeThenAfter() {
        serve(BEFORE, "snap-b", file(SKILL_MD, "h1"));
        serve(AFTER, "snap-a", file(SKILL_MD, "h2"));
        when(orderAgent.readText(BEFORE, SKILL_MD)).thenReturn("a\nb\nc\n");
        when(orderAgent.readText(AFTER, SKILL_MD)).thenReturn("a\nB\nc\n");

        DiffSkillResult result = service.diffSkill("x", BEFORE, AFTER);

        InOrder order = inOrder(orderAgent);
        order.verify(orderAgent).getSnapshot(BEFORE);
        order.verify(orderAgent).getSnapshot(AFTER);
        order.verify(orderAgent).readText(BEFORE, SKILL_MD);
        order.verify(orderAgent).readText(AFTER, SKILL_MD);
        verifyNoMoreInteractions(orderAgent);
        SkillFileDiff expected = SkillDiffer.diff(SKILL_MD, "a\nb\nc\n", "a\nB\nc\n", "h1", "h2");
        assertThat(result).isEqualTo(new DiffSkillResult("x",
                new DiffSkillResult.Side(BEFORE, "snap-b", SKILL_MD, "h1"),
                new DiffSkillResult.Side(AFTER, "snap-a", SKILL_MD, "h2"),
                "modified", List.of(), expected.hunks(), null, List.of()));
        assertThat(result.hunks()).extracting(SkillFileDiff.Hunk::hunkId).containsExactly("h_8ab98c34b234");
    }

    @Test
    void addedSkillReadsOnlyAfter() {
        serve(BEFORE, "snap-b", file("skills/other/SKILL.md", "h0"));
        serve(AFTER, "snap-a", file(SKILL_MD, "h2"));
        when(orderAgent.readText(AFTER, SKILL_MD)).thenReturn("---\nname: x\n---\nbody\n");

        DiffSkillResult result = service.diffSkill("x", BEFORE, AFTER);

        assertThat(result.before()).isEqualTo(new DiffSkillResult.Side(BEFORE, "snap-b", null, null));
        assertThat(result.after()).isEqualTo(new DiffSkillResult.Side(AFTER, "snap-a", SKILL_MD, "h2"));
        assertThat(result.status()).isEqualTo("added");
        assertThat(result.hunks()).extracting(SkillFileDiff.Hunk::part).containsExactly("frontmatter");
        verify(orderAgent, never()).readText(BEFORE, SKILL_MD);
    }

    @Test
    void removedSkillReadsOnlyBefore() {
        serve(BEFORE, "snap-b", file(SKILL_MD, "h1"));
        serve(AFTER, "snap-a");
        when(orderAgent.readText(BEFORE, SKILL_MD)).thenReturn("body\n");

        DiffSkillResult result = service.diffSkill("x", BEFORE, AFTER);

        assertThat(result.before()).isEqualTo(new DiffSkillResult.Side(BEFORE, "snap-b", SKILL_MD, "h1"));
        assertThat(result.after()).isEqualTo(new DiffSkillResult.Side(AFTER, "snap-a", null, null));
        assertThat(result.status()).isEqualTo("removed");
        verify(orderAgent, never()).readText(AFTER, SKILL_MD);
    }

    @Test
    void brokenYamlIsReportedNotThrown() {
        serve(BEFORE, "snap-b", file(SKILL_MD, "h1"));
        serve(AFTER, "snap-a", file(SKILL_MD, "h2"));
        when(orderAgent.readText(BEFORE, SKILL_MD)).thenReturn("---\nname: x\ninputs: [a\n---\nbody\n");
        when(orderAgent.readText(AFTER, SKILL_MD)).thenReturn("---\nname: x\ninputs: [a]\n---\nbody\n");

        DiffSkillResult result = service.diffSkill("x", BEFORE, AFTER);

        assertThat(result.frontmatterChanges()).isEmpty();
        assertThat(result.frontmatterParseError()).isNotBlank();
        assertThat(result.hunks()).hasSize(1);
    }

    /** 같은 폴더의 다른 파일은 해시로만 비교하고 경로순으로 돌려준다. SKILL.md 와 이웃 폴더(x-other) 파일은 빠진다. */
    @Test
    void relatedFilesAreHashComparedAndSorted() {
        serve(BEFORE, "snap-b", file(SKILL_MD, "same"), file("skills/x/resources/c.md", "c1"),
                file("skills/x/a.md", "a1"), file("skills/x/b.md", "b1"), file("skills/x-other/z.md", "z1"),
                file("skills/xy.md", "q"));
        serve(AFTER, "snap-a", file("skills/x/d.md", "d1"), file(SKILL_MD, "same"), file("skills/x/b.md", "b1"),
                file("skills/x/resources/c.md", "c2"), file("skills/x-other/z.md", "z2"));

        List<DiffSkillResult.RelatedFile> related = service.diffSkill("x", BEFORE, AFTER).relatedFiles();

        assertThat(related).containsExactly(
                new DiffSkillResult.RelatedFile("skills/x/a.md", "removed", "a1", null),
                new DiffSkillResult.RelatedFile("skills/x/b.md", "unchanged", "b1", "b1"),
                new DiffSkillResult.RelatedFile("skills/x/d.md", "added", null, "d1"),
                new DiffSkillResult.RelatedFile("skills/x/resources/c.md", "modified", "c1", "c2"));
        verify(orderAgent, never()).readText(anyString(), anyString());
    }

    @Test
    void beforeSnapshotFailureStopsBeforeAfterIsRead() {
        when(orderAgent.getSnapshot(BEFORE)).thenThrow(
                new OrderAgentException("SNAPSHOT_NOT_FOUND", "snapshot 이 없습니다", false, 404, BEFORE));

        assertFailure(failure(() -> service.diffSkill("x", BEFORE, AFTER)),
                "SNAPSHOT_NOT_FOUND", "snapshot 이 없습니다", false, 404, BEFORE);
        verify(orderAgent, never()).getSnapshot(AFTER);
    }

    @Test
    void afterReadFailureIsPassedThrough() {
        serve(BEFORE, "snap-b", file(SKILL_MD, "h1"));
        serve(AFTER, "snap-a", file(SKILL_MD, "h2"));
        when(orderAgent.readText(BEFORE, SKILL_MD)).thenReturn("a\n");
        when(orderAgent.readText(AFTER, SKILL_MD)).thenThrow(new OrderAgentException("SKILL_FILE_NOT_TEXT",
                SKILL_MD + " 가 UTF-8 글이 아닙니다(base64).", false, null, AFTER));

        assertFailure(failure(() -> service.diffSkill("x", BEFORE, AFTER)),
                "SKILL_FILE_NOT_TEXT", SKILL_MD + " 가 UTF-8 글이 아닙니다(base64).", false, null, AFTER);
    }

    /** manifest 를 맵으로 바꿀 때 skill 폴더 안 경로가 겹치면 그대로 실패한다(조용히 합치지 않는다). */
    @Test
    void duplicatePathInsideSkillFolderFails() {
        serve(BEFORE, "snap-b", file(SKILL_MD, "h1"), file(SKILL_MD, "h1b"));
        serve(AFTER, "snap-a", file(SKILL_MD, "h2"));

        assertThatThrownBy(() -> service.diffSkill("x", BEFORE, AFTER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicatePathOutsideSkillFolderIsIgnored() {
        serve(BEFORE, "snap-b", file(SKILL_MD, "same"), file("skills/y/SKILL.md", "1"), file("skills/y/SKILL.md", "2"));
        serve(AFTER, "snap-a", file(SKILL_MD, "same"));

        assertThat(service.diffSkill("x", BEFORE, AFTER).status()).isEqualTo("unchanged");
    }

    @Test
    void nullHashInsideSkillFolderFails() {
        serve(BEFORE, "snap-b", file(SKILL_MD, "h1"), file("skills/x/a.md", null));
        serve(AFTER, "snap-a", file(SKILL_MD, "h2"));

        assertThatThrownBy(() -> service.diffSkill("x", BEFORE, AFTER)).isInstanceOf(NullPointerException.class);
    }

    /** snapshotId 의 manifest 를 hash 와 files 로 꾸며 getSnapshot 이 돌려주게 한다. */
    private void serve(String snapshotId, String hash, SnapshotManifest.FileEntry... files) {
        when(orderAgent.getSnapshot(snapshotId)).thenReturn(manifest(snapshotId, hash, List.of(files)));
    }
}
