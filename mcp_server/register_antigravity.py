"""EFOS-Ofis MCP sunucusunu Google Antigravity yapılandırmasına kaydeder.

Varsayılan yapılandırma dosyası: ~/.gemini/config/mcp_config.json

Çalışma biçimleri:
* Kaynak koddan (python main.py): komut = python.exe, argüman = efos_mcp_server.py
* Derlenmiş klasörden (dist/EFOS-Ofis/): komut = efos-mcp.exe (konsol uygulaması)
* Derlenmiş tek EXE (eski yapı): komut = EFOS-Ofis.exe, argüman = --mcp

Güvenlik: MCP araçları yalnızca ``EFOS_MCP_ALLOWED_DIRS`` ile izin verilen
klasörlerde çalışır (varsayılan: kullanıcının ev klasörü).
"""

from __future__ import annotations

import datetime as _dt
import json
import os
import shutil
import sys
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

SERVER_KEY = "efos-ofis"
MCP_EXE_NAME = "efos-mcp.exe"


def get_antigravity_config_path() -> Path:
    return Path.home() / ".gemini" / "config" / "mcp_config.json"


def is_frozen() -> bool:
    return bool(getattr(sys, "frozen", False))


def default_allowed_dirs() -> List[str]:
    return [str(Path.home())]


def build_mcp_entry(
    project_dir: Optional[str] = None,
    python_executable: Optional[str] = None,
    allowed_dirs: Optional[Iterable[str]] = None,
) -> Dict[str, Any]:
    dirs = [str(Path(d).expanduser()) for d in (allowed_dirs or default_allowed_dirs())]
    env = {"PYTHONUTF8": "1", "EFOS_MCP_ALLOWED_DIRS": os.pathsep.join(dirs)}

    if is_frozen() and python_executable is None and project_dir is None:
        exe_dir = Path(sys.executable).resolve().parent
        console_exe = exe_dir / MCP_EXE_NAME
        if console_exe.is_file():
            return {"command": str(console_exe), "args": [], "env": env}
        return {"command": str(Path(sys.executable).resolve()), "args": ["--mcp"], "env": env}

    base = Path(project_dir) if project_dir else Path(__file__).resolve().parent.parent
    server_script = base / "mcp_server" / "efos_mcp_server.py"
    py_exe = python_executable or sys.executable
    # pythonw.exe stdio vermez; aynı klasördeki python.exe kullanılır
    if os.path.basename(py_exe).lower() == "pythonw.exe":
        candidate = os.path.join(os.path.dirname(py_exe), "python.exe")
        if os.path.isfile(candidate):
            py_exe = candidate
    return {"command": str(py_exe), "args": [str(server_script)], "env": env}


def _load_config(target: Path) -> tuple[Dict[str, Any], Optional[Path]]:
    """Yapılandırmayı okur. Bozuksa yedekler; diğer sunucuları silmeden boş başlar."""
    if not target.is_file():
        return {}, None
    text = target.read_text(encoding="utf-8-sig")
    if not text.strip():
        return {}, None
    try:
        data = json.loads(text)
        if isinstance(data, dict):
            return data, None
    except json.JSONDecodeError:
        pass
    stamp = _dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    backup = target.with_name(f"{target.name}.bozuk-{stamp}.bak")
    shutil.copy2(target, backup)
    return {}, backup


def register_with_report(
    config_path: Optional[Path] = None,
    project_dir: Optional[str] = None,
    python_executable: Optional[str] = None,
    allowed_dirs: Optional[Iterable[str]] = None,
) -> Dict[str, Any]:
    """Kaydı yapar ve ne yapıldığını anlatan bir sözlük döndürür."""
    target = Path(config_path) if config_path else get_antigravity_config_path()
    target.parent.mkdir(parents=True, exist_ok=True)

    data, broken_backup = _load_config(target)
    servers = data.get("mcpServers")
    if not isinstance(servers, dict):
        servers = {}
        data["mcpServers"] = servers

    entry = build_mcp_entry(project_dir, python_executable, allowed_dirs)
    servers[SERVER_KEY] = entry

    tmp = target.with_name(target.name + ".efos-tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, target)
    return {
        "config_path": target,
        "entry": entry,
        "broken_backup": broken_backup,
        "other_servers": sorted(k for k in servers if k != SERVER_KEY),
    }


def register_in_antigravity(
    config_path: Optional[Path] = None,
    project_dir: Optional[str] = None,
    python_executable: Optional[str] = None,
    allowed_dirs: Optional[Iterable[str]] = None,
) -> Path:
    return register_with_report(config_path, project_dir, python_executable, allowed_dirs)["config_path"]


def _parse_cli(argv: List[str]) -> Dict[str, Any]:
    allowed: List[str] = []
    i = 0
    while i < len(argv):
        if argv[i] in ("--allow", "--izin") and i + 1 < len(argv):
            allowed.append(argv[i + 1])
            i += 2
        else:
            i += 1
    return {"allowed_dirs": allowed or None}


if __name__ == "__main__":
    opts = _parse_cli(sys.argv[1:])
    report = register_with_report(allowed_dirs=opts["allowed_dirs"])
    print(f"[BASARILI] EFOS-Ofis MCP sunucusu Antigravity yapilandirmasina eklendi: {report['config_path']}")
    print(f"           Izinli klasorler: {report['entry']['env']['EFOS_MCP_ALLOWED_DIRS']}")
    if report["broken_backup"]:
        print(f"[UYARI] Eski yapilandirma okunamadi, yedegi alindi: {report['broken_backup']}")
