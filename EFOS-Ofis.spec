# -*- mode: python ; coding: utf-8 -*-
# EFOS-Ofis PyInstaller tarifi (exe_yap.bat ve GitHub Actions bunu kullanır).
#
#   Varsayılan            : dist\EFOS-Ofis.exe  (tek dosya; "EFOS-Ofis.exe --mcp" MCP sunucusu olarak çalışır)
#   EFOS_ONEDIR=1 ile     : dist\EFOS-Ofis\     (klasör; çok daha hızlı açılır, MCP için efos-mcp.exe içerir)
#
# Eski sürüm "--collect-all PyQt6" ile kullanılmayan tüm Qt modüllerini (Quick3D, QML,
# Designer, ffmpeg, 20 MB yazılımsal OpenGL...) paketliyordu. Artık yalnızca uygulamanın
# kullandığı QtCore/QtGui/QtWidgets/QtPrintSupport ve eklentileri girer.
import os

from PyInstaller.utils.hooks import collect_all, collect_data_files

ONEDIR = os.environ.get("EFOS_ONEDIR") == "1"

datas = []
binaries = []
hiddenimports = [
    "PyQt6.QtPrintSupport",
    "docx",
    "openpyxl",
    "pptx",
    "pypdf",
    "PIL",
    "mcp_server.efos_mcp_server",
]
for pkg in ("pypdfium2", "pypdfium2_raw"):
    try:
        d, b, h = collect_all(pkg)
        datas += d
        binaries += b
        hiddenimports += h
    except Exception:
        pass
# python-docx / python-pptx boş belge şablonları
datas += collect_data_files("docx")
datas += collect_data_files("pptx")

QT_UNUSED = [
    "QtQml", "QtQuick", "QtQuick3D", "QtQuickWidgets", "QtQuickControls2", "QtWebEngineCore",
    "QtWebEngineWidgets", "QtWebEngineQuick", "QtWebChannel", "QtWebSockets", "QtMultimedia",
    "QtMultimediaWidgets", "QtNetwork", "QtSql", "QtTest", "QtBluetooth", "QtPositioning",
    "QtSensors", "QtSerialPort", "QtDesigner", "QtOpenGL", "QtOpenGLWidgets", "QtPdf",
    "QtPdfWidgets", "QtCharts", "QtDataVisualization", "Qt3DCore", "Qt3DRender", "Qt3DInput",
    "Qt3DLogic", "Qt3DAnimation", "Qt3DExtras", "QtHelp", "QtNfc", "QtRemoteObjects",
    "QtSpatialAudio", "QtTextToSpeech", "QtXml", "QtDBus", "QtStateMachine", "QtSvgWidgets",
]
excludes = ["tkinter", "matplotlib", "numpy", "IPython", "pytest"] + [f"PyQt6.{m}" for m in QT_UNUSED]

a = Analysis(
    ["main.py"],
    pathex=["."],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=excludes,
    noarchive=False,
    optimize=1,
)

# Gereksiz büyük dosyaları ayıkla
DROP_BINARIES = ("opengl32sw.dll", "qt6quick", "qt6qml", "qt6pdf", "qt6network", "qt6virtualkeyboard")


def _keep_binary(entry):
    name = os.path.basename(entry[0]).lower()
    return not any(name.startswith(p) or name == p for p in DROP_BINARIES)


def _keep_data(entry):
    dest = entry[0].replace("\\", "/").lower()
    if "/translations/" in dest and dest.endswith(".qm"):
        base = os.path.basename(dest)
        return base.endswith("_tr.qm")  # yalnızca Türkçe Qt çevirileri
    return True


a.binaries = [b for b in a.binaries if _keep_binary(b)]
a.datas = [d for d in a.datas if _keep_data(d)]

pyz = PYZ(a.pure)

exe_options = dict(
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    runtime_tmpdir=None,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    version="version_info.txt",
)

if ONEDIR:
    exe_gui = EXE(pyz, a.scripts, [], exclude_binaries=True, name="EFOS-Ofis", console=False, **exe_options)
    exe_mcp = EXE(pyz, a.scripts, [], exclude_binaries=True, name="efos-mcp", console=True, **exe_options)
    coll = COLLECT(exe_gui, exe_mcp, a.binaries, a.datas, strip=False, upx=False, name="EFOS-Ofis")
else:
    exe = EXE(pyz, a.scripts, a.binaries, a.datas, [], name="EFOS-Ofis", console=False, **exe_options)
