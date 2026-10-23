"""불완전한 Skill 목록의 증거만 수집한다. YAML 해석·LLM·workspace·patch는 수행하지 않는다."""

import base64
import binascii
import re
from copy import deepcopy
from hashlib import sha256

from .contracts import ContractError, check_diff_evidence, is_snapshot_path, skill_of_file
from .order_agent import SKILL_ID, SKILL_ID_MAX_LENGTH, OrderAgentError
from .tools import MCPToolError

# 주문 Agent snapshot 한도에 맞춰 두 snapshot의 증거 수집량도 유한하게 제한한다.
MAX_FILES_PER_SNAPSHOT = 256
MAX_FILE_BYTES = 1_000_000
MAX_SNAPSHOT_BYTES = 5_000_000
MAX_SOURCE_BYTES = 2 * MAX_SNAPSHOT_BYTES
SHA256 = re.compile(r"[0-9a-f]{64}")
SKILL_PATH = re.compile(r"skills/[^/]+/SKILL\.md")


def _text(value) -> bool:
    """비공백 문자열인지 확인한다."""
    return isinstance(value, str) and bool(value.strip())


def manifest_files(manifest: dict) -> dict[str, dict]:
    """원문 조회에 앞서 manifest의 파일 경로·해시·크기·수집 한도를 검증한다."""
    files = manifest.get("files")
    if not isinstance(files, list) or not 1 <= len(files) <= MAX_FILES_PER_SNAPSHOT:
        raise ContractError("invalid partial evidence manifest")
    result = {}
    total = 0
    for entry in files:
        if not isinstance(entry, dict):
            raise ContractError("invalid partial evidence manifest")
        path, digest, size = entry.get("path"), entry.get("hash"), entry.get("size")
        if (
            not is_snapshot_path(path)
            or path in result
            or not isinstance(digest, str)
            or not SHA256.fullmatch(digest)
            or type(size) is not int
            or not 0 <= size <= MAX_FILE_BYTES
        ):
            raise ContractError("invalid partial evidence manifest")
        total += size
        result[path] = entry
    if total > MAX_SNAPSHOT_BYTES:
        raise ContractError("partial evidence snapshot exceeds byte limit")
    return result


def prepare_partial_evidence(listing: dict, before: dict, after: dict) -> dict:
    """partial 응답의 완전한 경로 대응과 출처를 검증하고 저장할 증거 묶음을 만든다."""
    if (
        listing.get("snapshot_id") != after["snapshot_id"]
        or listing.get("snapshot_hash") != after["hash"]
    ):
        raise ContractError("skill list and snapshot differ")
    files = manifest_files(after)
    manifest_files(before)
    items, errors = listing.get("items"), listing.get("parse_errors")
    if (
        listing.get("partial") is not True
        or not isinstance(items, list)
        or not isinstance(errors, list)
        or not errors
    ):
        raise ContractError("invalid partial skill listing")
    seen = set()
    expected = {path for path in files if SKILL_PATH.fullmatch(path)}
    for item in items:
        if not isinstance(item, dict):
            raise ContractError("invalid partial skill listing")
        skill, version, path = item.get("skill_id"), item.get("version"), item.get("path")
        if (
            not isinstance(skill, str)
            or len(skill) > SKILL_ID_MAX_LENGTH
            or not SKILL_ID.fullmatch(skill)
            or type(version) is not int
            or version < 1
            or version > 2_147_483_647
            or path != f"skills/{skill}/SKILL.md"
            or path not in expected
            or path in seen
            or item.get("hash") != files[path]["hash"]
        ):
            raise ContractError("invalid partial skill listing")
        seen.add(path)
    for error in errors:
        if not isinstance(error, dict):
            raise ContractError("invalid partial skill listing")
        path = error.get("path")
        if (
            not isinstance(path, str)
            or path not in expected
            or path in seen
            or not _text(error.get("code"))
            or not _text(error.get("message"))
        ):
            raise ContractError("invalid partial skill listing")
        seen.add(path)
    if seen != expected:
        raise ContractError("invalid partial skill listing")
    return {
        "snapshots": {"before": deepcopy(before), "after": deepcopy(after)},
        "metadata": deepcopy(listing),
        "sources": [],
        "collection_errors": [],
        "limitations": ["PARTIAL_METADATA", "AUTOMATIC_REPAIR_DISABLED"],
        "collection_status": "METADATA_COLLECTED",
    }


def check_snapshot_file(response: dict, snapshot_id: str, entry: dict) -> dict:
    """조회한 원본 바이트가 manifest의 snapshot·경로·해시·크기와 일치하는지 검증한다."""
    if (
        not isinstance(response, dict)
        or any(
            response.get(key) != value
            for key, value in (
                ("snapshot_id", snapshot_id),
                ("path", entry["path"]),
                ("hash", entry["hash"]),
                ("size", entry["size"]),
            )
        )
        or type(response.get("size")) is not int
    ):
        raise ContractError("snapshot file does not match manifest")
    content, encoding = response.get("content"), response.get("encoding")
    if not isinstance(content, str) or len(content) > 4 * MAX_FILE_BYTES // 3 + 4:
        raise ContractError("invalid snapshot file content")
    try:
        if encoding == "utf-8":
            raw = content.encode("utf-8", errors="strict")
        elif encoding == "base64":
            raw = base64.b64decode(content, validate=True)
        else:
            raise ContractError("unsupported snapshot file encoding")
    except (UnicodeError, binascii.Error, ValueError) as exc:
        raise ContractError("invalid snapshot file encoding") from exc
    if len(raw) != entry["size"] or sha256(raw).hexdigest() != entry["hash"]:
        raise ContractError("snapshot file bytes do not match manifest")
    return {
        key: response[key] for key in ("snapshot_id", "path", "hash", "size", "encoding", "content")
    }


def collection_error(stage: str, snapshot_id: str, path: str | None, exc: Exception) -> dict:
    """원문 없는 오류 기록을 단계·출처·코드로 구조화한다."""
    return {
        "stage": stage,
        "snapshot_id": snapshot_id,
        "path": path,
        "code": getattr(exc, "code", None) or "EVIDENCE_CONTRACT_ERROR",
        "message": str(exc),
    }


def verified_diff(diff, skill: str, old: dict, new: dict, before: dict, after: dict) -> list:
    """diff의 형식·Skill·양쪽 출처를 검증해 정상 hunk만 반환한다."""
    if (
        not isinstance(diff, dict)
        or diff.get("skill_id") != skill
        or diff.get("status") != "modified"
        or not all(isinstance(diff.get(side), dict) for side in ("before", "after"))
        or not isinstance(diff.get("hunks"), list)
        or any(
            not isinstance(hunk, dict)
            or not _text(hunk.get("hunk_id"))
            or not _text(hunk.get("diff"))
            for hunk in diff["hunks"]
        )
    ):
        raise ContractError("invalid diff evidence")
    return check_diff_evidence(
        diff,
        before={
            "snapshot_id": before["snapshot_id"],
            "snapshot_hash": before["hash"],
            "file_hash": old["hash"],
        },
        after={
            "snapshot_id": after["snapshot_id"],
            "snapshot_hash": after["hash"],
            "file_hash": new["hash"],
        },
    )


async def collect_sources(tools, evidence: dict) -> tuple[dict, bool]:
    """양쪽 snapshot의 SKILL 원문을 읽으며 검증된 파일과 조회 실패를 따로 보존한다."""
    result = deepcopy(evidence)
    total = 0
    for manifest in result["snapshots"].values():
        snapshot_id = manifest["snapshot_id"]
        for path, entry in sorted(manifest_files(manifest).items()):
            if not SKILL_PATH.fullmatch(path):
                continue
            try:
                if total + entry["size"] > MAX_SOURCE_BYTES:
                    raise ContractError("partial evidence sources exceed byte limit")
                source = check_snapshot_file(
                    await tools.get_snapshot_file(snapshot_id, path), snapshot_id, entry
                )
            except (OrderAgentError, ContractError) as exc:
                result["collection_errors"].append(
                    collection_error("source", snapshot_id, path, exc)
                )
                if (
                    isinstance(exc, ContractError)
                    or getattr(exc, "code", "") == "SNAPSHOT_TAMPERED"
                ):
                    result["collection_status"] = "STOPPED"
                    return result, True
                continue
            total += source["size"]
            result["sources"].append(source)
    result["collection_status"] = "SOURCES_COLLECTED"
    return result, False


async def collect_diffs(tools, evidence: dict, announced) -> tuple[dict, dict, list]:
    """검증된 텍스트의 diff만 모으고 지원 밖 변경·개별 실패는 오류 증거로 남긴다."""
    result = deepcopy(evidence)
    before, after = result["snapshots"]["before"], result["snapshots"]["after"]
    old, new = manifest_files(before), manifest_files(after)
    paths = sorted(
        p
        for p in old.keys() | new.keys()
        if old.get(p, {}).get("hash") != new.get(p, {}).get("hash")
    )
    diffs, changes = [], []
    sources = {(s["snapshot_id"], s["path"]): s for s in result["sources"]}
    if announced is not None and (
        not isinstance(announced, list)
        or any(not isinstance(p, str) for p in announced)
        or set(announced) != set(paths)
    ):
        error = ContractError("changed_paths does not match the two snapshot manifests")
        result["collection_errors"].append(
            collection_error("diff", after["snapshot_id"], None, error)
        )
        result["collection_status"] = "STOPPED"
        return result, {"files": [], "hunks": []}, []
    for path in paths:
        skill = skill_of_file(path)
        limitation = None
        if (
            skill is None
            or not SKILL_ID.fullmatch(skill)
            or len(skill) > SKILL_ID_MAX_LENGTH
            or path not in old
            or path not in new
        ):
            limitation = "resource/added/removed/invalid skill diff is not supported"
        elif any(
            sources.get((side["snapshot_id"], path), {}).get("encoding") != "utf-8"
            for side in (before, after)
        ):
            limitation = "verified UTF-8 sources are required for diff"
        if limitation:
            result["collection_errors"].append(
                collection_error("diff", after["snapshot_id"], path, ContractError(limitation))
            )
            continue
        try:
            diff = await tools.diff_skill(skill, before["snapshot_id"], after["snapshot_id"])
        except (OrderAgentError, MCPToolError) as exc:
            result["collection_errors"].append(
                collection_error("diff", after["snapshot_id"], path, exc)
            )
            if exc.code == "SNAPSHOT_TAMPERED":
                result["collection_status"] = "STOPPED"
                return result, {"files": diffs, "hunks": changes}, changes
            continue
        try:
            hunks = verified_diff(diff, skill, old[path], new[path], before, after)
        except ContractError as exc:
            result["collection_errors"].append(
                collection_error("diff", after["snapshot_id"], path, exc)
            )
            result["collection_status"] = "STOPPED"
            return result, {"files": diffs, "hunks": changes}, changes
        diffs.append(diff)
        offset = len(changes)
        changes.extend(
            {**hunk, "change_no": offset + index + 1, "skill": skill}
            for index, hunk in enumerate(hunks)
        )
    result["collection_status"] = (
        "COLLECTED_WITH_ERRORS" if result["collection_errors"] else "COLLECTED"
    )
    return result, {"files": diffs, "hunks": changes}, changes
