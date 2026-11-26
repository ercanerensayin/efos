"""EFOS-Ofis güvenli ZIP ve arşiv ayıklama, oluşturma ve listeleme motoru."""

from __future__ import annotations

import fnmatch
import os
import tarfile
import zipfile
from datetime import datetime
from typing import Any, Dict, List, Optional


_ZIP_COMPRESSIONS = {
    "deflated": zipfile.ZIP_DEFLATED,
    "stored": zipfile.ZIP_STORED,
    "bzip2": zipfile.ZIP_BZIP2,
    "lzma": zipfile.ZIP_LZMA,
}


def _is_within_directory(base_dir: str, target_path: str) -> bool:
    """Zip Slip / Tar Slip saldırılarına karşı hedef yolun çıkış dizini içinde olduğunu doğrular."""
    real_base = os.path.realpath(base_dir)
    real_target = os.path.realpath(target_path)
    try:
        return os.path.commonpath([real_base, real_target]) == real_base
    except ValueError:
        return False


def _matches_filters(
    name: str,
    members: Optional[List[str]] = None,
    pattern: Optional[str] = None,
) -> bool:
    norm = name.replace("\\", "/")
    if members:
        member_set = {m.replace("\\", "/") for m in members}
        if norm not in member_set:
            return False
    if pattern:
        if not (fnmatch.fnmatch(norm, pattern) or fnmatch.fnmatch(os.path.basename(norm), pattern)):
            return False
    return True


def _detect_archive_type(archive_path: str) -> str:
    low = archive_path.lower()
    if low.endswith(".zip") or zipfile.is_zipfile(archive_path):
        return "zip"
    if low.endswith((".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz")):
        return "tar"
    if tarfile.is_tarfile(archive_path):
        return "tar"
    raise ValueError(f"Desteklenmeyen veya geçersiz arşiv formatı: {archive_path}")


def list_archive(archive_path: str) -> Dict[str, Any]:
    """Bir .zip veya .tar.* arşivinin içeriğini ayıklamadan listeler."""
    if not os.path.isfile(archive_path):
        raise FileNotFoundError(f"Arşiv dosyası bulunamadı: {archive_path}")

    kind = _detect_archive_type(archive_path)
    entries: List[Dict[str, Any]] = []
    total_size = 0
    total_compressed = 0

    if kind == "zip":
        with zipfile.ZipFile(archive_path, "r") as zf:
            for info in zf.infolist():
                dt = datetime(*info.date_time).isoformat(sep=" ")
                entries.append(
                    {
                        "name": info.filename,
                        "is_dir": info.is_dir(),
                        "size": info.file_size,
                        "compressed_size": info.compress_size,
                        "modified": dt,
                    }
                )
                if not info.is_dir():
                    total_size += info.file_size
                    total_compressed += info.compress_size
    else:
        with tarfile.open(archive_path, "r:*") as tf:
            for member in tf.getmembers():
                dt = datetime.fromtimestamp(member.mtime).isoformat(sep=" ")
                entries.append(
                    {
                        "name": member.name,
                        "is_dir": member.isdir(),
                        "size": member.size,
                        "compressed_size": None,
                        "modified": dt,
                    }
                )
                if member.isfile():
                    total_size += member.size

    return {
        "archive_path": archive_path,
        "format": kind,
        "entry_count": len(entries),
        "total_size_bytes": total_size,
        "total_compressed_bytes": total_compressed if kind == "zip" else os.path.getsize(archive_path),
        "entries": entries,
    }


# Sıkıştırma bombasına karşı varsayılan sınırlar
MAX_TOTAL_BYTES = 4 * 1024 ** 3  # açılmış toplam boyut: 4 GB
MAX_ENTRIES = 100_000
MAX_RATIO = 250  # açılmış / sıkıştırılmış oranı (büyük dosyalarda)


class _SizeGuard:
    def __init__(self, limit: int) -> None:
        self.limit = limit
        self.written = 0

    def add(self, n: int) -> None:
        self.written += n
        if self.written > self.limit:
            raise ValueError(
                "Güvenlik sınırı: arşivin açılmış boyutu "
                f"{self.limit // (1024 ** 2)} MB sınırını aşıyor (sıkıştırma bombası olabilir)."
            )


def _copy_limited(src, dst, guard: _SizeGuard) -> None:
    while True:
        chunk = src.read(65536)
        if not chunk:
            break
        guard.add(len(chunk))
        dst.write(chunk)


def extract_archive(
    archive_path: str,
    output_dir: str,
    members: Optional[List[str]] = None,
    pattern: Optional[str] = None,
    password: Optional[str] = None,
    overwrite: bool = True,
    max_total_bytes: int = MAX_TOTAL_BYTES,
) -> Dict[str, Any]:
    """Bir .zip veya .tar.* arşivini güvenli biçimde hedef klasöre ayıklar.

    Önce tüm girişler denetlenir (Zip Slip, sembolik bağlantı, toplam boyut,
    sıkıştırma oranı); sorun varsa hiçbir dosya yazılmaz. Ayıklama sırasında
    hata olursa o ana kadar yazılan dosyalar geri silinir.
    """
    if not os.path.isfile(archive_path):
        raise FileNotFoundError(f"Arşiv dosyası bulunamadı: {archive_path}")

    kind = _detect_archive_type(archive_path)
    extracted: List[str] = []
    skipped: List[str] = []
    created_dirs: List[str] = []
    guard = _SizeGuard(max_total_bytes)

    def _target(name: str, label: str) -> Optional[str]:
        rel_path = name.replace("\\", "/").lstrip("/")
        if not rel_path or rel_path in (".", "./"):
            return None
        target_path = os.path.normpath(os.path.join(output_dir, rel_path))
        if not _is_within_directory(output_dir, target_path):
            raise ValueError(f"Güvenlik ihlali ({label} engellendi): {name}")
        return target_path

    def _mkdirs(path: str) -> None:
        missing = []
        cur = path
        while cur and not os.path.isdir(cur):
            missing.append(cur)
            parent = os.path.dirname(cur)
            if parent == cur:
                break
            cur = parent
        os.makedirs(path, exist_ok=True)
        created_dirs.extend(reversed(missing))

    try:
        if kind == "zip":
            pwd_bytes = password.encode("utf-8") if password else None
            with zipfile.ZipFile(archive_path, "r") as zf:
                infos = zf.infolist()
                if len(infos) > MAX_ENTRIES:
                    raise ValueError(f"Güvenlik sınırı: arşivde çok fazla giriş var ({len(infos)}).")
                plan = []
                declared = 0
                for info in infos:
                    if not _matches_filters(info.filename, members=members, pattern=pattern):
                        continue
                    target_path = _target(info.filename, "Zip Slip")
                    if target_path is None:
                        continue
                    plan.append((info, target_path))
                    if not info.is_dir():
                        declared += info.file_size
                        if info.file_size > 100 * 1024 ** 2 and info.compress_size and (
                            info.file_size / info.compress_size > MAX_RATIO
                        ):
                            raise ValueError(
                                f"Güvenlik sınırı: '{info.filename}' olağan dışı yüksek sıkıştırma oranına sahip."
                            )
                if declared > max_total_bytes:
                    guard.add(declared)  # anlaşılır hata mesajıyla durdurur

                _mkdirs(output_dir)
                for info, target_path in plan:
                    if info.is_dir():
                        _mkdirs(target_path)
                        continue
                    if not overwrite and os.path.exists(target_path):
                        skipped.append(target_path)
                        continue
                    _mkdirs(os.path.dirname(target_path))
                    with zf.open(info, "r", pwd=pwd_bytes) as src, open(target_path, "wb") as dst:
                        extracted.append(target_path)
                        _copy_limited(src, dst, guard)
        else:
            with tarfile.open(archive_path, "r:*") as tf:
                all_members = tf.getmembers()
                if len(all_members) > MAX_ENTRIES:
                    raise ValueError(f"Güvenlik sınırı: arşivde çok fazla giriş var ({len(all_members)}).")
                plan = []
                declared = 0
                for member in all_members:
                    if not _matches_filters(member.name, members=members, pattern=pattern):
                        continue
                    if member.issym() or member.islnk():
                        raise ValueError(f"Güvenlik ihlali (sembolik bağlantı engellendi): {member.name}")
                    target_path = _target(member.name, "Tar Slip")
                    if target_path is None:
                        continue
                    if member.isdir() or member.isfile():
                        plan.append((member, target_path))
                        if member.isfile():
                            declared += member.size
                if declared > max_total_bytes:
                    guard.add(declared)

                _mkdirs(output_dir)
                for member, target_path in plan:
                    if member.isdir():
                        _mkdirs(target_path)
                        continue
                    if not overwrite and os.path.exists(target_path):
                        skipped.append(target_path)
                        continue
                    _mkdirs(os.path.dirname(target_path))
                    src = tf.extractfile(member)
                    if src is None:
                        continue
                    with src, open(target_path, "wb") as dst:
                        extracted.append(target_path)
                        _copy_limited(src, dst, guard)
    except BaseException:
        # Yarım kalan ayıklamayı geri al
        for path in reversed(extracted):
            try:
                os.remove(path)
            except OSError:
                pass
        for d in reversed(created_dirs):
            try:
                os.rmdir(d)
            except OSError:
                pass
        raise

    return {
        "status": "extracted",
        "archive_path": archive_path,
        "format": kind,
        "output_dir": os.path.abspath(output_dir),
        "extracted_count": len(extracted),
        "skipped_count": len(skipped),
        "extracted_files": extracted,
        "skipped_files": skipped,
    }


def create_zip(
    source_paths: List[str],
    output_zip: str,
    exclude_patterns: Optional[List[str]] = None,
    compression: str = "deflated",
) -> Dict[str, Any]:
    """Verilen dosya ve/veya klasörlerden yeni bir .zip arşivi oluşturur."""
    if not source_paths:
        raise ValueError("ZIP oluşturmak için en az bir dosya veya klasör yolu gerekli.")

    comp_key = (compression or "deflated").lower()
    if comp_key not in _ZIP_COMPRESSIONS:
        raise ValueError(f"Geçersiz sıkıştırma yöntemi: {compression}")

    out_abs = os.path.abspath(output_zip)
    parent = os.path.dirname(out_abs)
    if parent:
        os.makedirs(parent, exist_ok=True)

    excludes = exclude_patterns or []

    def _is_excluded(rel_name: str) -> bool:
        norm = rel_name.replace("\\", "/")
        base = os.path.basename(norm)
        return any(fnmatch.fnmatch(norm, pat) or fnmatch.fnmatch(base, pat) for pat in excludes)

    added_files: List[str] = []
    total_uncompressed = 0

    with zipfile.ZipFile(out_abs, "w", compression=_ZIP_COMPRESSIONS[comp_key]) as zf:
        for src in source_paths:
            src_abs = os.path.abspath(src)
            if not os.path.exists(src_abs):
                raise FileNotFoundError(f"Kaynak bulunamadı: {src}")

            if os.path.isfile(src_abs):
                if src_abs == out_abs:
                    continue
                arcname = os.path.basename(src_abs)
                if _is_excluded(arcname):
                    continue
                zf.write(src_abs, arcname=arcname)
                added_files.append(arcname)
                total_uncompressed += os.path.getsize(src_abs)
            elif os.path.isdir(src_abs):
                root_name = os.path.basename(src_abs.rstrip("\\/"))
                for dirpath, _dirnames, filenames in os.walk(src_abs):
                    for fn in filenames:
                        full_path = os.path.join(dirpath, fn)
                        if os.path.abspath(full_path) == out_abs:
                            continue
                        rel_inside = os.path.relpath(full_path, src_abs)
                        arcname = os.path.join(root_name, rel_inside).replace("\\", "/")
                        if _is_excluded(arcname):
                            continue
                        zf.write(full_path, arcname=arcname)
                        added_files.append(arcname)
                        total_uncompressed += os.path.getsize(full_path)

    return {
        "status": "created",
        "output_zip": out_abs,
        "compression": comp_key,
        "file_count": len(added_files),
        "total_uncompressed_bytes": total_uncompressed,
        "archive_size_bytes": os.path.getsize(out_abs),
        "files": added_files,
    }
