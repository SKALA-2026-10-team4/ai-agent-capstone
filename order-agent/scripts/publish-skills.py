#!/usr/bin/env python3
"""완성된 로컬 Skill 번들을 SHA-256 manifest(bundle.json)로 봉인한다.

order-agent/skills 아래 파일을 경로 순으로 훑어 파일별 해시를 모으고, release 와 files(상대
경로와 해시의 짝)를 bundle.json 에 쓴다. 주문 Agent 의
order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 에 있는
readPublishedBundle 은 실제 파일 집합이나 해시가 이 manifest 와 하나라도 다르면 번들을
거절한다(INCOMPLETE_BUNDLE). 그래서 SKILL.md 를 고친 뒤에는 이 스크립트를 다시 돌리고 backend
이미지를 다시 빌드해야 새 게시본이 쓰인다(order-agent/backend/Dockerfile 이 skills 폴더를
이미지의 /app/skills 로 복사한다).
사용법은 order-agent/docs/agent-evidence.md 의 'snapshot 저장과 게시' 절에 있다. 저장소
루트에서 python3 order-agent/scripts/publish-skills.py 로 돌리고, 첫 인자로 다른 번들 루트를
줄 수 있다.
order-agent/backend/src/test/java/com/capstone4/orderagent/workspace/PublishedBundleReaderTest.java
의 readsTheRepositoryBundle 이 저장소의 번들이 manifest 와 맞는지 확인한다.
"""
from pathlib import Path
import hashlib
import json
import os
import sys

# manifest 의 release 값이다.
# order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 의
# CAPTURE_RELEASE 와 같은 값이어야 한다. 주문 Agent 는 manifest 의 release 를 읽지 않고 그 상수를
# snapshot metadata 에 쓰므로, 이 값만 바꾸면 오류 없이 snapshot 이 옛 이름으로 남는다. 두 곳을
# 함께 바꾼다.
RELEASE = 'camping-v1'
# 해시 대상에서 빼는 파일 이름이다. manifest 자신과 원자 교체용 임시 파일이며, 하위 폴더의 같은
# 이름 파일도 빠진다. SnapshotService 는 루트의 bundle.json 만 빼므로 하위 폴더에 이 이름의
# 파일을 두면 번들이 거절된다.
EXCLUDED_NAMES = {'bundle.json', '.bundle.tmp'}


def resolve_root(args):
    """명령행 인자로 받은 번들 루트를 돌려준다.

    인자가 없으면 이 스크립트 기준 상위 폴더의 skills, 곧 order-agent/skills 를 쓴다. 루트가
    심볼릭 링크면 SystemExit 로 멈춘다. SnapshotService.readPublishedBundle 도 심볼릭 링크 루트를
    INVALID_BUNDLE_PATH 로 거절한다.
    """
    root = Path(args[0]) if args else Path(__file__).resolve().parents[1] / 'skills'
    if root.is_symlink():
        raise SystemExit('Symlink root is not allowed')
    return root


def collect_hashes(root):
    """루트 아래 파일을 정렬 순서로 돌며 상대 경로별 SHA-256 을 모은다.

    경로는 슬래시로 구분한 루트 기준 상대 경로이며, SnapshotService 가 snapshot 경로로 쓸 때
    붙이는 skills 접두어는 없다. 심볼릭 링크가 하나라도 있거나 담을 파일이 없으면 SystemExit 로
    멈춘다.
    """
    files = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise SystemExit('Symlinks are not allowed')
        if path.is_file() and path.name not in EXCLUDED_NAMES:
            files[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    if not files:
        raise SystemExit('Empty bundle')
    return files


def write_manifest(root, files):
    """manifest 를 임시 파일에 쓴 뒤 bundle.json 으로 원자 교체한다.

    읽는 쪽이 반쯤 쓴 manifest 를 보지 않게 하려고 임시 파일(.bundle.tmp)을 거친다. JSON 은
    한글을 그대로 두고 두 칸 들여쓰기에 끝 줄바꿈을 붙인다.
    """
    manifest = root / '.bundle.tmp'
    manifest.write_text(json.dumps({'release': RELEASE, 'files': files}, ensure_ascii=False, indent=2) + '\n')
    os.replace(manifest, root / 'bundle.json')


def main(argv=None):
    """번들 루트의 파일 해시를 봉인한 bundle.json 을 만든다.

    argv 를 주지 않으면 sys.argv[1:] 를 쓴다. 끝나면 봉인한 파일 수와 이미지를 다시 빌드하라는
    안내를 찍는다.
    """
    root = resolve_root(sys.argv[1:] if argv is None else argv)
    files = collect_hashes(root)
    write_manifest(root, files)
    print(f'Sealed {len(files)} files; rebuild the backend image to use this release')


if __name__ == '__main__':
    main()
