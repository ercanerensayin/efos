"""EFOS-Ofis Model Context Protocol (MCP) Sunucusu (Google Antigravity uyumlu).

Standart JSON-RPC 2.0 stdio protokolü üzerinden Antigravity IDE / Desktop App / CLI
ajanlarına yerel Excel (.xlsx/.csv), Word (.docx), PowerPoint (.pptx), PDF,
ZIP/arşiv ayıklama-oluşturma, belge dönüştürme, çoklu ofis arama, Markdown not
ve görsel işleme yetenekleri sunar.
"""

from __future__ import annotations

import html as html_lib
import json
import os
import re
import shutil
import sys
from typing import Any, Dict, List, Optional

# Proje kökünü Python yoluna ekle
ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if ROOT_DIR not in sys.path:
    sys.path.insert(0, ROOT_DIR)

from PIL import Image
from docx import Document

from core.archive_engine import create_zip, extract_archive, list_archive
from core.docx_engine import load_docx_to_html, save_html_to_docx
from core.formula_engine import FormulaEngine, format_display_value, parse_cell_ref
from core.pdf_engine import (
    PdfDocumentReader,
    create_pdf_document,
    create_pdf_from_html,
    images_to_pdf,
    merge_pdfs,
    pdf_to_images,
    rotate_pdf,
    split_pdf,
    update_pdf_metadata,
)
from core.pptx_engine import SLIDE_THEMES, SlideData, load_pptx, save_pptx
from core.xlsx_engine import CellData, SheetData, load_csv, load_xlsx, save_csv, save_xlsx


SERVER_INFO = {"name": "efos-ofis", "version": "2.2.0"}
PROTOCOL_VERSION = "2024-11-05"

TOOLS_SPEC: List[Dict[str, Any]] = [
    # --- EXCEL / CSV ---
    {
        "name": "excel_read",
        "description": "Bir .xlsx veya .csv dosyasındaki sayfaları, hücre değerlerini ve hesaplanmış formül sonuçlarını okur.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "filepath": {"type": "string", "description": "Okunacak .xlsx veya .csv dosya yolu"},
                "sheet_name": {"type": "string", "description": "İsteğe bağlı belirli sayfa adı"},
                "evaluate_formulas": {"type": "boolean", "default": True},
            },
            "required": ["filepath"],
        },
    },
    {
        "name": "excel_write",
        "description": "Yeni bir .xlsx dosyası oluşturur veya mevcut .xlsx dosyasına satır/hücre yazar (Türkçe/İngilizce formül destekli).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "filepath": {"type": "string", "description": "Hedef .xlsx dosya yolu"},
                "sheet_name": {"type": "string", "default": "Sayfa1"},
                "rows": {
                    "type": "array",
                    "description": "Satır listesi (her satır hücre değerlerinden oluşan liste)",
                    "items": {"type": "array"},
                },
                "cell_updates": {
                    "type": "object",
                    "description": "Koordinat bazlı güncellemeler (örn. {'B5': '=TOPLA(B2:B4)'})",
                },
            },
            "required": ["filepath"],
        },
    },
    {
        "name": "csv_to_excel",
        "description": "Bir .csv dosyasını ayırıcıyı otomatik algılayarak biçimlendirilmiş .xlsx Excel çalışma kitabına dönüştürür.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "input_csv": {"type": "string", "description": "Kaynak .csv dosya yolu"},
                "output_xlsx": {"type": "string", "description": "Hedef .xlsx dosya yolu"},
                "sheet_name": {"type": "string", "default": "Veriler"},
                "bold_header": {"type": "boolean", "default": True},
            },
            "required": ["input_csv", "output_xlsx"],
        },
    },
    {
        "name": "excel_to_csv",
        "description": "Bir .xlsx dosyasının belirtilen sayfasını (formülleri hesaplayarak) UTF-8 BOM destekli .csv dosyasına aktarır.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "input_xlsx": {"type": "string", "description": "Kaynak .xlsx dosya yolu"},
                "output_csv": {"type": "string", "description": "Hedef .csv dosya yolu"},
                "sheet_name": {"type": "string", "description": "Aktarılacak sayfa adı (boşsa ilk sayfa)"},
                "delimiter": {"type": "string", "default": ";", "description": "Sütun ayırıcı (';' veya ',')"},
                "evaluate_formulas": {"type": "boolean", "default": True},
            },
            "required": ["input_xlsx", "output_csv"],
        },
    },
    # --- WORD ---
    {
        "name": "word_read",
        "description": "Bir .docx Word belgesindeki paragrafları ve tabloları okur.",
        "inputSchema": {
            "type": "object",
            "properties": {"filepath": {"type": "string"}},
            "required": ["filepath"],
        },
    },
    {
        "name": "word_write",
        "description": "Başlık, paragraflar, tablolar veya zengin HTML içeriğiyle yeni bir .docx Word belgesi oluşturur.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "filepath": {"type": "string"},
                "title": {"type": "string"},
                "paragraphs": {"type": "array", "items": {"type": "string"}},
                "tables": {"type": "array", "description": "2B metin dizilerinden oluşan tablolar"},
                "html": {"type": "string", "description": "İsteğe bağlı zengin HTML içerik (biçimlendirmeler korunur)"},
            },
            "required": ["filepath"],
        },
    },
    {
        "name": "word_find_replace",
        "description": "Mevcut bir .docx Word belgesinde (paragraflar, tablolar, üstbilgi ve altbilgilerde) toplu bul-değiştir veya şablon doldurma yapar.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "filepath": {"type": "string", "description": "Kaynak .docx dosya yolu"},
                "output_path": {"type": "string", "description": "İsteğe bağlı hedef dosya yolu (boşsa dosyanın üzerine yazar; önce <ad>.efos-yedek.docx yedeği alınır)"},
                "find": {"type": "string", "description": "Tekil arama metni"},
                "replace": {"type": "string", "description": "Tekil değiştirme metni"},
                "replacements": {
                    "type": "object",
                    "description": "Çoklu eşleme sözlüğü (örn. {'{{MUSTERI}}': 'EFOS A.Ş.', '{{TARIH}}': '2026-11-25'})",
                },
            },
            "required": ["filepath"],
        },
    },
    # --- POWERPOINT ---
    {
        "name": "pptx_read",
        "description": "Bir .pptx PowerPoint sunusundaki tüm slaytları ve konuşmacı notlarını okur.",
        "inputSchema": {
            "type": "object",
            "properties": {"filepath": {"type": "string"}},
            "required": ["filepath"],
        },
    },
    {
        "name": "pptx_write",
        "description": "Slayt listesinden tema ve görsel destekli 16:9 .pptx PowerPoint sunusu oluşturur.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "filepath": {"type": "string"},
                "theme": {
                    "type": "string",
                    "description": "İsteğe bağlı tema adı (Modern Açık, Kurumsal Lacivert, Orman Yeşili, Sıcak Bordo, Koyu Antrasit)",
                },
                "slides": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "title": {"type": "string"},
                            "body": {"type": "string"},
                            "right_body": {"type": "string"},
                            "notes": {"type": "string"},
                            "image_path": {"type": "string"},
                            "layout": {"type": "string", "enum": ["title", "content", "two_col"]},
                        },
                    },
                },
            },
            "required": ["filepath", "slides"],
        },
    },
    # --- PDF ---
    {
        "name": "pdf_read_and_search",
        "description": "PDF dosyasından sayfa metni çıkarır veya tüm belgede kelime araması yapar.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "filepath": {"type": "string"},
                "query": {"type": "string", "description": "İsteğe bağlı arama kelimesi"},
                "page": {"type": "integer", "description": "1-tabanlı sayfa numarası (opsiyonel)"},
            },
            "required": ["filepath"],
        },
    },
    {
        "name": "pdf_write",
        "description": "Başlık, paragraflar, tablolar, Markdown veya HTML içeriğinden Türkçe/UTF-8 destekli yeni bir PDF dosyası oluşturur.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "filepath": {"type": "string", "description": "Hedef .pdf dosya yolu"},
                "title": {"type": "string", "description": "Belge başlığı"},
                "paragraphs": {"type": "array", "items": {"type": "string"}},
                "tables": {"type": "array", "description": "2B dizilerden oluşan tablolar"},
                "markdown": {"type": "string", "description": "İsteğe bağlı Markdown içeriği"},
                "html": {"type": "string", "description": "İsteğe bağlı doğrudan HTML içeriği"},
                "landscape": {"type": "boolean", "default": False, "description": "Yatay A4 sayfa düzeni"},
            },
            "required": ["filepath"],
        },
    },
    {
        "name": "pdf_merge",
        "description": "Birden fazla PDF dosyasını tek bir PDF dosyasında birleştirir.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "input_paths": {"type": "array", "items": {"type": "string"}},
                "output_path": {"type": "string"},
            },
            "required": ["input_paths", "output_path"],
        },
    },
    {
        "name": "pdf_split",
        "description": "Bir PDF dosyasının belirtilen sayfa aralığını yeni bir PDF dosyasına ayırır.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "input_path": {"type": "string"},
                "output_path": {"type": "string"},
                "start_page": {"type": "integer"},
                "end_page": {"type": "integer"},
            },
            "required": ["input_path", "output_path", "start_page", "end_page"],
        },
    },
    {
        "name": "pdf_rotate",
        "description": "Bir PDF dosyasının tüm sayfalarını veya seçili 1-tabanlı sayfalarını 90/180/270 derece döndürür.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "input_path": {"type": "string"},
                "output_path": {"type": "string"},
                "angle": {"type": "integer", "description": "Döndürme açısı (90, 180, 270 veya -90)"},
                "pages": {
                    "type": "array",
                    "items": {"type": "integer"},
                    "description": "İsteğe bağlı 1-tabanlı sayfa listesi (boşsa tüm sayfalar)",
                },
            },
            "required": ["input_path", "output_path", "angle"],
        },
    },
    {
        "name": "pdf_to_images",
        "description": "PDF sayfalarını yüksek çözünürlüklü PNG, JPEG veya WEBP görsellerine dönüştürür.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "input_path": {"type": "string", "description": "Kaynak .pdf dosya yolu"},
                "output_dir": {"type": "string", "description": "Görsellerin kaydedileceği klasör"},
                "pages": {"type": "array", "items": {"type": "integer"}, "description": "İsteğe bağlı 1-tabanlı sayfa listesi"},
                "scale": {"type": "number", "default": 2.0, "description": "Render ölçeği (varsayılan 2.0)"},
                "image_format": {"type": "string", "enum": ["PNG", "JPEG", "JPG", "WEBP"], "default": "PNG"},
            },
            "required": ["input_path", "output_dir"],
        },
    },
    {
        "name": "pdf_metadata",
        "description": "PDF üstverilerini (başlık, yazar, konu, anahtar kelimeler, sayfa sayısı) okur veya günceller.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "filepath": {"type": "string", "description": "Kaynak .pdf dosya yolu"},
                "output_path": {"type": "string", "description": "Güncelleme için hedef dosya yolu (boşsa üzerine yazar; önce .efos-yedek.pdf yedeği alınır)"},
                "updates": {
                    "type": "object",
                    "description": "Güncellenecek alanlar (title, author, subject, keywords, creator)",
                },
            },
            "required": ["filepath"],
        },
    },
    # --- ZIP / ARŞİV ---
    {
        "name": "zip_extract",
        "description": "Bir .zip (veya .tar/.tar.gz/.tgz) arşivini Zip Slip güvenlik korumasıyla hedef klasöre ayıklar; desen/üye filtresi ve şifre destekler.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "archive_path": {"type": "string", "description": "Ayıklanacak .zip veya .tar.* arşiv yolu"},
                "output_dir": {"type": "string", "description": "Hedef çıkış klasörü"},
                "members": {"type": "array", "items": {"type": "string"}, "description": "Yalnızca belirtilen arşiv üyelerini ayıkla"},
                "pattern": {"type": "string", "description": "İsteğe bağlı glob filtresi (örn. '*.xlsx' veya 'raporlar/*.pdf')"},
                "password": {"type": "string", "description": "Şifreli ZIP dosyaları için parola"},
                "overwrite": {"type": "boolean", "default": True},
            },
            "required": ["archive_path", "output_dir"],
        },
    },
    {
        "name": "zip_create",
        "description": "Dosya ve/veya klasörleri sıkıştırarak yeni bir .zip arşivi oluşturur.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "source_paths": {"type": "array", "items": {"type": "string"}, "description": "Arşivlenecek dosya/klasör yolları"},
                "output_zip": {"type": "string", "description": "Oluşturulacak .zip dosya yolu"},
                "exclude_patterns": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Hariç tutulacak desenler (örn. ['*.tmp', '__pycache__/*'])",
                },
                "compression": {
                    "type": "string",
                    "enum": ["deflated", "stored", "bzip2", "lzma"],
                    "default": "deflated",
                },
            },
            "required": ["source_paths", "output_zip"],
        },
    },
    {
        "name": "zip_list",
        "description": "Bir .zip veya .tar.* arşivinin içindeki dosya adlarını, boyutlarını ve tarihlerini ayıklamadan listeler.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "archive_path": {"type": "string", "description": "İncelenecek arşiv dosya yolu"},
            },
            "required": ["archive_path"],
        },
    },
    # --- GENEL OFİS, DÖNÜŞTÜRME, ARAMA, NOT & GÖRSEL ---
    {
        "name": "office_to_pdf",
        "description": "Word (.docx), Markdown (.md), metin (.txt), HTML (.html) veya görsel dosyalarını (.png/.jpg/.webp/.bmp) PDF'ye dönüştürür.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "input_path": {"type": "string", "description": "Kaynak ofis/görsel dosya yolu"},
                "input_paths": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "Çoklu görselden tek PDF oluşturmak için dosya yolları listesi",
                },
                "output_pdf": {"type": "string", "description": "Hedef .pdf dosya yolu"},
                "landscape": {"type": "boolean", "default": False},
            },
            "required": ["output_pdf"],
        },
    },
    {
        "name": "office_search_folder",
        "description": "Bir klasördeki tüm ofis belgelerinde (.docx, .xlsx, .csv, .pptx, .pdf, .md, .txt) özyinelemeli tam metin araması yapar.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "folder_path": {"type": "string", "description": "Taranacak klasör yolu"},
                "query": {"type": "string", "description": "Aranacak kelime veya ifade"},
                "extensions": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "İsteğe bağlı uzantı filtresi (örn. ['.docx', '.pdf', '.xlsx'])",
                },
                "max_results": {"type": "integer", "default": 50},
            },
            "required": ["folder_path", "query"],
        },
    },
    {
        "name": "markdown_note",
        "description": "Markdown (.md) veya metin (.txt) not dosyalarını okur, yazar, sonuna ekler; başlıkları ve görev listelerini ([ ] / [x]) analiz eder.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "filepath": {"type": "string", "description": "Hedef .md veya .txt dosya yolu"},
                "action": {"type": "string", "enum": ["read", "write", "append"], "default": "read"},
                "content": {"type": "string", "description": "Yazılacak veya eklenecek içerik (write/append için)"},
            },
            "required": ["filepath"],
        },
    },
    {
        "name": "image_office_tool",
        "description": "Ofis belgeleri ve sunular için görsel bilgisi okur, en-boy oranını koruyarak yeniden boyutlandırır veya format dönüştürür (PNG/JPEG/WEBP/BMP/PDF).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "input_path": {"type": "string", "description": "Kaynak görsel dosya yolu"},
                "output_path": {"type": "string", "description": "Hedef görsel dosya yolu (resize/convert için)"},
                "action": {"type": "string", "enum": ["info", "resize", "convert"], "default": "info"},
                "max_width": {"type": "integer", "description": "Maksimum genişlik (piksel)"},
                "max_height": {"type": "integer", "description": "Maksimum yükseklik (piksel)"},
                "target_format": {"type": "string", "enum": ["PNG", "JPEG", "JPG", "WEBP", "BMP", "PDF"]},
                "quality": {"type": "integer", "default": 90},
            },
            "required": ["input_path"],
        },
    },
]


# ---------------------------------------------------------------------------
# Güvenlik: izinli klasörler ve otomatik yedek
# ---------------------------------------------------------------------------

PATH_KEYS = {
    "filepath",
    "archive_path",
    "folder_path",
    "input_csv",
    "input_path",
    "input_xlsx",
    "output_csv",
    "output_dir",
    "output_path",
    "output_pdf",
    "output_xlsx",
    "output_zip",
}
PATH_LIST_KEYS = {"input_paths", "source_paths"}


def allowed_roots() -> List[str]:
    """EFOS_MCP_ALLOWED_DIRS (os.pathsep ile ayrılmış) veya ev klasörü. '*' = sınırsız."""
    raw = os.environ.get("EFOS_MCP_ALLOWED_DIRS", "").strip()
    if raw == "*":
        return ["*"]
    parts = [p.strip() for p in raw.split(os.pathsep) if p.strip()] if raw else []
    if not parts:
        parts = [os.path.expanduser("~")]
    return [os.path.normcase(os.path.realpath(os.path.expanduser(p))) for p in parts]


def check_path_allowed(path: str) -> str:
    """Yol izinli klasörlerden birinin içindeyse gerçek yolunu döndürür, değilse hata verir."""
    if not isinstance(path, str) or not path.strip():
        raise ValueError("Geçerli bir dosya yolu verilmedi.")
    roots = allowed_roots()
    real = os.path.normcase(os.path.realpath(os.path.expanduser(path)))
    if roots == ["*"]:
        return real
    for root in roots:
        try:
            if os.path.commonpath([real, root]) == root:
                return real
        except ValueError:  # farklı sürücüler
            continue
    raise PermissionError(
        f"İzin verilmeyen konum: {path}. EFOS-Ofis MCP yalnızca şu klasörlerde çalışır: "
        + "; ".join(roots)
        + " (EFOS_MCP_ALLOWED_DIRS ayarıyla değiştirilebilir)"
    )


def validate_tool_paths(arguments: Dict[str, Any]) -> None:
    for key, val in arguments.items():
        if key in PATH_KEYS and val:
            check_path_allowed(str(val))
        elif key in PATH_LIST_KEYS and isinstance(val, list):
            for item in val:
                check_path_allowed(str(item))
        elif key == "slides" and isinstance(val, list):
            for item in val:
                if isinstance(item, dict) and item.get("image_path"):
                    check_path_allowed(str(item["image_path"]))


def backup_before_overwrite(filepath: str) -> Optional[str]:
    """Var olan dosyanın üzerine yazılmadan önce '<ad>.efos-yedek<uzantı>' kopyasını alır."""
    if not filepath or not os.path.isfile(filepath):
        return None
    stem, ext = os.path.splitext(filepath)
    backup = f"{stem}.efos-yedek{ext}"
    shutil.copy2(filepath, backup)
    return backup


def _ensure_parent_dir(filepath: str) -> None:
    parent = os.path.dirname(os.path.abspath(filepath))
    if parent:
        os.makedirs(parent, exist_ok=True)


def _markdown_to_basic_html(md_text: str) -> str:
    """Markdown metnini başlık, liste, kod ve kalın/italik biçimlendirmesiyle HTML'e çevirir."""
    lines = md_text.splitlines()
    out: List[str] = []
    in_ul = False
    in_code = False

    def _inline(text: str) -> str:
        esc = html_lib.escape(text)
        esc = re.sub(r"`([^`]+)`", r"<code>\1</code>", esc)
        esc = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", esc)
        esc = re.sub(r"\*([^*]+)\*", r"<i>\1</i>", esc)
        return esc

    for raw in lines:
        stripped = raw.strip()
        if stripped.startswith("```"):
            if in_ul:
                out.append("</ul>")
                in_ul = False
            if in_code:
                out.append("</pre>")
                in_code = False
            else:
                out.append("<pre>")
                in_code = True
            continue
        if in_code:
            out.append(html_lib.escape(raw))
            continue
        if not stripped:
            if in_ul:
                out.append("</ul>")
                in_ul = False
            continue
        if stripped.startswith("### "):
            if in_ul:
                out.append("</ul>")
                in_ul = False
            out.append(f"<h3>{_inline(stripped[4:])}</h3>")
        elif stripped.startswith("## "):
            if in_ul:
                out.append("</ul>")
                in_ul = False
            out.append(f"<h2>{_inline(stripped[3:])}</h2>")
        elif stripped.startswith("# "):
            if in_ul:
                out.append("</ul>")
                in_ul = False
            out.append(f"<h1>{_inline(stripped[2:])}</h1>")
        elif stripped.startswith(("- ", "* ")):
            if not in_ul:
                out.append("<ul>")
                in_ul = True
            item = stripped[2:]
            item = item.replace("[ ] ", "☐ ").replace("[x] ", "☑ ").replace("[X] ", "☑ ")
            out.append(f"<li>{_inline(item)}</li>")
        else:
            if in_ul:
                out.append("</ul>")
                in_ul = False
            out.append(f"<p>{_inline(raw)}</p>")

    if in_ul:
        out.append("</ul>")
    if in_code:
        out.append("</pre>")
    return "\n".join(out) or "<p>&nbsp;</p>"


def _tool_excel_read(args: Dict[str, Any]) -> Dict[str, Any]:
    filepath = args["filepath"]
    sheet_filter = args.get("sheet_name")
    eval_formulas = args.get("evaluate_formulas", True)
    ext = os.path.splitext(filepath)[1].lower()
    sheets = load_csv(filepath) if ext == ".csv" else load_xlsx(filepath)

    out_sheets = []
    for sh in sheets:
        if sheet_filter and sh.name != sheet_filter:
            continue
        engine = FormulaEngine(
            lambda r, c, s=sh: s.cells[(r, c)].raw if (r, c) in s.cells else "",
            lambda s=sh: (max(s.rows, 1), max(s.cols, 1)),
        )
        if not sh.cells:
            out_sheets.append({"name": sh.name, "rows": []})
            continue
        max_r = max(r for r, _ in sh.cells.keys()) + 1
        max_c = max(c for _, c in sh.cells.keys()) + 1
        grid_rows = []
        for r in range(max_r):
            row_vals = []
            for c in range(max_c):
                raw = sh.cells[(r, c)].raw if (r, c) in sh.cells else ""
                if eval_formulas and raw.startswith("="):
                    val = format_display_value(engine.evaluate_cell(r, c))
                    row_vals.append(val)
                else:
                    row_vals.append(raw[1:] if raw.startswith("'") else raw)
            grid_rows.append(row_vals)
        out_sheets.append({"name": sh.name, "rows": grid_rows})
    return {"filepath": filepath, "sheet_names": [s.name for s in sheets], "sheets": out_sheets}


def _tool_excel_write(args: Dict[str, Any]) -> Dict[str, Any]:
    filepath = args["filepath"]
    sheet_name = args.get("sheet_name", "Sayfa1")
    rows = args.get("rows")
    cell_updates = args.get("cell_updates") or {}
    _ensure_parent_dir(filepath)

    backup = None
    if os.path.isfile(filepath):
        sheets = load_xlsx(filepath)
        backup = backup_before_overwrite(filepath)
    else:
        sheets = [SheetData(name=sheet_name)]

    target = next((s for s in sheets if s.name == sheet_name), None)
    if target is None:
        target = SheetData(name=sheet_name)
        sheets.append(target)

    if rows is not None:
        # Eski hücreler dosyadan da silinsin diye boşaltılarak bırakılır
        for cd in target.cells.values():
            cd.raw = ""
        for r_idx, row in enumerate(rows):
            for c_idx, val in enumerate(row):
                target.cells[(r_idx, c_idx)] = CellData(raw="" if val is None else str(val), bold=(r_idx == 0))
        target.rows = max(len(rows), 50)
        target.cols = max((len(r) for r in rows), default=10)

    for coord, val in cell_updates.items():
        parsed = parse_cell_ref(coord)
        if parsed:
            r, c = parsed
            existing = target.cells.get((r, c), CellData())
            existing.raw = "" if val is None else str(val)
            target.cells[(r, c)] = existing
            target.rows = max(target.rows, r + 1)
            target.cols = max(target.cols, c + 1)

    save_xlsx(sheets, filepath)
    result: Dict[str, Any] = {"status": "saved", "filepath": filepath, "sheet": sheet_name}
    if backup:
        result["backup_path"] = backup
    return result


def _tool_csv_to_excel(args: Dict[str, Any]) -> Dict[str, Any]:
    input_csv = args["input_csv"]
    output_xlsx = args["output_xlsx"]
    sheet_name = args.get("sheet_name", "Veriler")
    bold_header = args.get("bold_header", True)

    sheets = load_csv(input_csv)
    sh = sheets[0]
    sh.name = sheet_name
    if bold_header:
        for (r, c), cell in sh.cells.items():
            if r == 0:
                cell.bold = True
                cell.bg_color = "#E2E8F0"

    _ensure_parent_dir(output_xlsx)
    save_xlsx([sh], output_xlsx)
    max_r = max((r for r, _ in sh.cells.keys()), default=-1) + 1
    max_c = max((c for _, c in sh.cells.keys()), default=-1) + 1
    return {
        "status": "converted",
        "input_csv": input_csv,
        "output_xlsx": output_xlsx,
        "sheet_name": sheet_name,
        "rows": max_r,
        "cols": max_c,
    }


def _tool_excel_to_csv(args: Dict[str, Any]) -> Dict[str, Any]:
    input_xlsx = args["input_xlsx"]
    output_csv = args["output_csv"]
    sheet_name = args.get("sheet_name")
    delimiter = args.get("delimiter", ";")
    eval_formulas = args.get("evaluate_formulas", True)

    sheets = load_xlsx(input_xlsx)
    target = next((s for s in sheets if s.name == sheet_name), sheets[0]) if sheet_name else sheets[0]

    export_sheet = SheetData(name=target.name, rows=target.rows, cols=target.cols)
    engine = FormulaEngine(lambda r, c: target.cells[(r, c)].raw if (r, c) in target.cells else "")
    for (r, c), cell in target.cells.items():
        val = cell.raw
        if eval_formulas and val.startswith("="):
            val = format_display_value(engine.evaluate_cell(r, c))
        export_sheet.cells[(r, c)] = CellData(raw=val)

    _ensure_parent_dir(output_csv)
    save_csv(export_sheet, output_csv, delimiter=delimiter)
    return {
        "status": "converted",
        "input_xlsx": input_xlsx,
        "output_csv": output_csv,
        "sheet_name": target.name,
        "delimiter": delimiter,
    }


def _tool_word_read(args: Dict[str, Any]) -> Dict[str, Any]:
    doc = Document(args["filepath"])
    paragraphs = [p.text for p in doc.paragraphs if p.text.strip()]
    tables = [
        [[cell.text.strip() for cell in row.cells] for row in t.rows]
        for t in doc.tables
    ]
    return {
        "filepath": args["filepath"],
        "paragraph_count": len(paragraphs),
        "table_count": len(tables),
        "paragraphs": paragraphs,
        "tables": tables,
    }


def _tool_word_write(args: Dict[str, Any]) -> Dict[str, Any]:
    filepath = args["filepath"]
    _ensure_parent_dir(filepath)
    backup_before_overwrite(filepath)
    if args.get("html"):
        save_html_to_docx(str(args["html"]), filepath)
        return {"status": "saved", "filepath": filepath, "mode": "html"}

    doc = Document()
    if args.get("title"):
        doc.add_heading(args["title"], level=1)
    for para in args.get("paragraphs") or []:
        doc.add_paragraph(str(para))
    raw_tables = args.get("tables") or []
    if raw_tables and isinstance(raw_tables[0], list) and (
        not raw_tables[0] or not isinstance(raw_tables[0][0], list)
    ):
        raw_tables = [raw_tables]
    for tbl in raw_tables:
        if not tbl:
            continue
        cols = max(len(r) for r in tbl)
        t = doc.add_table(rows=len(tbl), cols=cols)
        for r_idx, row in enumerate(tbl):
            for c_idx, val in enumerate(row):
                t.cell(r_idx, c_idx).text = str(val)
    doc.save(filepath)
    return {"status": "saved", "filepath": filepath}


def _replace_in_paragraph(paragraph, replacements: Dict[str, str]) -> int:
    count = 0
    for old, new in replacements.items():
        if not old or old not in paragraph.text:
            continue
        replaced_in_runs = False
        for run in paragraph.runs:
            if old in run.text:
                occ = run.text.count(old)
                run.text = run.text.replace(old, new)
                count += occ
                replaced_in_runs = True
        if not replaced_in_runs and old in paragraph.text:
            occ = paragraph.text.count(old)
            full_text = paragraph.text.replace(old, new)
            if paragraph.runs:
                paragraph.runs[0].text = full_text
                for extra in paragraph.runs[1:]:
                    extra.text = ""
            else:
                paragraph.text = full_text
            count += occ
    return count


def _tool_word_find_replace(args: Dict[str, Any]) -> Dict[str, Any]:
    filepath = args["filepath"]
    output_path = args.get("output_path") or filepath
    replacements: Dict[str, str] = {}
    if isinstance(args.get("replacements"), dict):
        replacements.update({str(k): str(v) for k, v in args["replacements"].items()})
    if args.get("find"):
        replacements[str(args["find"])] = str(args.get("replace", ""))
    if not replacements:
        raise ValueError("Değiştirme için 'replacements' sözlüğü veya 'find' parametresi gerekli.")

    doc = Document(filepath)
    total = 0

    for p in doc.paragraphs:
        total += _replace_in_paragraph(p, replacements)
    for tbl in doc.tables:
        for row in tbl.rows:
            for cell in row.cells:
                for p in cell.paragraphs:
                    total += _replace_in_paragraph(p, replacements)
    for section in doc.sections:
        for container in (section.header, section.footer):
            for p in container.paragraphs:
                total += _replace_in_paragraph(p, replacements)

    _ensure_parent_dir(output_path)
    backup = backup_before_overwrite(output_path)
    doc.save(output_path)
    result: Dict[str, Any] = {
        "status": "updated",
        "filepath": output_path,
        "replacements_made": total,
    }
    if backup:
        result["backup_path"] = backup
    return result


def _tool_pptx_read(args: Dict[str, Any]) -> Dict[str, Any]:
    slides = load_pptx(args["filepath"])
    return {
        "filepath": args["filepath"],
        "slide_count": len(slides),
        "slides": [
            {
                "title": s.title,
                "body": s.body,
                "right_body": s.right_body,
                "notes": s.notes,
                "layout": s.layout,
            }
            for s in slides
        ],
    }


def _tool_pptx_write(args: Dict[str, Any]) -> Dict[str, Any]:
    filepath = args["filepath"]
    _ensure_parent_dir(filepath)
    theme_name = args.get("theme")
    theme_colors = SLIDE_THEMES.get(theme_name, {}) if theme_name else {}

    slides = [
        SlideData(
            title=item.get("title", "Slayt"),
            body=item.get("body", ""),
            right_body=item.get("right_body", ""),
            notes=item.get("notes", ""),
            layout=item.get("layout", "content"),
            bg_color=theme_colors.get("bg_color", "#FFFFFF"),
            text_color=theme_colors.get("text_color", "#0F172A"),
            accent_color=theme_colors.get("accent_color", "#2563EB"),
            image_path=item.get("image_path", ""),
        )
        for item in args["slides"]
    ]
    backup_before_overwrite(filepath)
    save_pptx(slides, filepath)
    return {"status": "saved", "filepath": filepath, "slide_count": len(slides)}


def _tool_pdf_read_and_search(args: Dict[str, Any]) -> Dict[str, Any]:
    reader = PdfDocumentReader(args["filepath"])
    try:
        query = args.get("query")
        if query:
            matches = reader.search_text(query)
            return {
                "filepath": args["filepath"],
                "query": query,
                "matches": [{"page": p + 1, "snippet": s} for p, s in matches],
            }
        page = args.get("page")
        if page is not None:
            return {
                "filepath": args["filepath"],
                "page": page,
                "text": reader.get_page_text(int(page) - 1),
            }
        pages = [reader.get_page_text(i) for i in range(reader.page_count)]
        return {"filepath": args["filepath"], "page_count": reader.page_count, "pages": pages}
    finally:
        reader.close()


def _tool_pdf_write(args: Dict[str, Any]) -> Dict[str, Any]:
    filepath = args["filepath"]
    title = str(args.get("title") or "")
    html_content = args.get("html")
    if not html_content and args.get("markdown"):
        md_html = _markdown_to_basic_html(str(args["markdown"]))
        html_content = (f"<h1>{html_lib.escape(title)}</h1>\n" if title else "") + md_html

    page_count = create_pdf_document(
        output_path=filepath,
        title=title,
        paragraphs=args.get("paragraphs"),
        tables=args.get("tables"),
        html_content=html_content,
        landscape=bool(args.get("landscape", False)),
    )
    return {"status": "saved", "filepath": os.path.abspath(filepath), "page_count": page_count}


def _tool_pdf_metadata(args: Dict[str, Any]) -> Dict[str, Any]:
    filepath = args["filepath"]
    updates = args.get("updates")
    if isinstance(updates, dict) and updates:
        out_path = args.get("output_path") or filepath
        backup = backup_before_overwrite(out_path)
        meta = update_pdf_metadata(filepath, out_path, updates)
        result = {"status": "updated", "filepath": os.path.abspath(out_path), "metadata": meta}
        if backup:
            result["backup_path"] = backup
        return result

    reader = PdfDocumentReader(filepath)
    try:
        return {"filepath": os.path.abspath(filepath), "metadata": reader.get_metadata()}
    finally:
        reader.close()


def _tool_office_to_pdf(args: Dict[str, Any]) -> Dict[str, Any]:
    output_pdf = args["output_pdf"]
    landscape = bool(args.get("landscape", False))
    input_paths = args.get("input_paths")
    input_path = args.get("input_path")

    if input_paths:
        pages = images_to_pdf(list(input_paths), output_pdf)
        return {"status": "converted", "output_pdf": os.path.abspath(output_pdf), "page_count": pages}

    if not input_path:
        raise ValueError("Dönüştürmek için 'input_path' veya 'input_paths' gerekli.")
    if not os.path.isfile(input_path):
        raise FileNotFoundError(f"Kaynak dosya bulunamadı: {input_path}")

    ext = os.path.splitext(input_path)[1].lower()
    title = os.path.splitext(os.path.basename(input_path))[0]

    if ext in (".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tif", ".tiff"):
        pages = images_to_pdf([input_path], output_pdf)
    elif ext == ".docx":
        body_html = load_docx_to_html(input_path)
        pages = create_pdf_from_html(body_html, output_pdf, title=title, landscape=landscape)
    elif ext in (".html", ".htm"):
        with open(input_path, "r", encoding="utf-8", errors="replace") as f:
            raw_html = f.read()
        pages = create_pdf_from_html(raw_html, output_pdf, title=title, landscape=landscape)
    elif ext == ".md":
        with open(input_path, "r", encoding="utf-8", errors="replace") as f:
            md_text = f.read()
        pages = create_pdf_from_html(_markdown_to_basic_html(md_text), output_pdf, title=title, landscape=landscape)
    elif ext in (".txt", ".csv", ".log", ".json"):
        with open(input_path, "r", encoding="utf-8", errors="replace") as f:
            txt = f.read()
        body_html = f"<pre style='white-space: pre-wrap;'>{html_lib.escape(txt)}</pre>"
        pages = create_pdf_from_html(body_html, output_pdf, title=title, landscape=landscape)
    else:
        raise ValueError(f"PDF'ye dönüştürme için desteklenmeyen uzantı: {ext}")

    return {
        "status": "converted",
        "input_path": os.path.abspath(input_path),
        "output_pdf": os.path.abspath(output_pdf),
        "page_count": pages,
    }


def _make_snippet(text: str, needle: str, radius: int = 45) -> str:
    pos = text.lower().find(needle)
    if pos < 0:
        return text[: radius * 2].replace("\n", " ").strip()
    start = max(0, pos - radius)
    end = min(len(text), pos + len(needle) + radius)
    return text[start:end].replace("\n", " ").strip()


def _tool_office_search_folder(args: Dict[str, Any]) -> Dict[str, Any]:
    folder = args["folder_path"]
    query = str(args["query"]).strip()
    if not os.path.isdir(folder):
        raise FileNotFoundError(f"Klasör bulunamadı: {folder}")
    if not query:
        raise ValueError("Arama kelimesi boş olamaz.")

    needle = query.lower()
    default_exts = {".docx", ".xlsx", ".csv", ".pptx", ".pdf", ".md", ".txt"}
    if args.get("extensions"):
        allowed_exts = {e.lower() if e.startswith(".") else f".{e.lower()}" for e in args["extensions"]}
    else:
        allowed_exts = default_exts
    max_results = int(args.get("max_results", 50))
    skip_dirs = {".git", ".venv", "__pycache__", "node_modules", "build", "dist", ".buildozer"}

    matches: List[Dict[str, Any]] = []
    scanned_files = 0

    for root, dirnames, filenames in os.walk(folder):
        dirnames[:] = [d for d in dirnames if d not in skip_dirs]
        for fn in filenames:
            if len(matches) >= max_results:
                break
            ext = os.path.splitext(fn)[1].lower()
            if ext not in allowed_exts:
                continue
            full_path = os.path.join(root, fn)
            scanned_files += 1
            try:
                if ext in (".md", ".txt"):
                    with open(full_path, "r", encoding="utf-8", errors="replace") as f:
                        for idx, line in enumerate(f, start=1):
                            if needle in line.lower():
                                matches.append({"filepath": full_path, "location": f"Satır {idx}", "snippet": _make_snippet(line, needle)})
                                break
                elif ext == ".docx":
                    doc = Document(full_path)
                    found = False
                    for idx, p in enumerate(doc.paragraphs, start=1):
                        if needle in p.text.lower():
                            matches.append({"filepath": full_path, "location": f"Paragraf {idx}", "snippet": _make_snippet(p.text, needle)})
                            found = True
                            break
                    if not found:
                        for t_idx, tbl in enumerate(doc.tables, start=1):
                            tbl_text = " | ".join(c.text.strip() for row in tbl.rows for c in row.cells if c.text.strip())
                            if needle in tbl_text.lower():
                                matches.append({"filepath": full_path, "location": f"Tablo {t_idx}", "snippet": _make_snippet(tbl_text, needle)})
                                break
                elif ext in (".xlsx", ".csv"):
                    sheets = load_csv(full_path) if ext == ".csv" else load_xlsx(full_path)
                    for sh in sheets:
                        hit = None
                        for (r, c), cell in sh.cells.items():
                            if needle in cell.raw.lower():
                                hit = (r, c, cell.raw)
                                break
                        if hit:
                            matches.append({"filepath": full_path, "location": f"{sh.name}!R{hit[0]+1}C{hit[1]+1}", "snippet": _make_snippet(hit[2], needle)})
                            break
                elif ext == ".pptx":
                    slides = load_pptx(full_path)
                    for s_idx, s in enumerate(slides, start=1):
                        combined = "\n".join([s.title, s.body, s.right_body, s.notes])
                        if needle in combined.lower():
                            matches.append({"filepath": full_path, "location": f"Slayt {s_idx}", "snippet": _make_snippet(combined, needle)})
                            break
                elif ext == ".pdf":
                    reader = PdfDocumentReader(full_path)
                    try:
                        pdf_hits = reader.search_text(query)
                        if pdf_hits:
                            p_idx, snip = pdf_hits[0]
                            matches.append({"filepath": full_path, "location": f"Sayfa {p_idx + 1}", "snippet": snip})
                    finally:
                        reader.close()
            except Exception:
                continue

    return {
        "folder_path": os.path.abspath(folder),
        "query": query,
        "scanned_files": scanned_files,
        "match_count": len(matches),
        "matches": matches,
    }


def _tool_markdown_note(args: Dict[str, Any]) -> Dict[str, Any]:
    filepath = args["filepath"]
    action = str(args.get("action", "read")).lower()

    if action == "write":
        _ensure_parent_dir(filepath)
        backup_before_overwrite(filepath)
        content = str(args.get("content", ""))
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(content)
        return {"status": "saved", "filepath": os.path.abspath(filepath), "characters": len(content)}

    if action == "append":
        _ensure_parent_dir(filepath)
        addition = str(args.get("content", ""))
        prefix = ""
        if os.path.isfile(filepath) and os.path.getsize(filepath) > 0:
            prefix = "\n"
        with open(filepath, "a", encoding="utf-8") as f:
            f.write(prefix + addition)
        return {"status": "appended", "filepath": os.path.abspath(filepath), "added_characters": len(addition)}

    if not os.path.isfile(filepath):
        raise FileNotFoundError(f"Not dosyası bulunamadı: {filepath}")
    with open(filepath, "r", encoding="utf-8", errors="replace") as f:
        text = f.read()

    headings = [line.strip() for line in text.splitlines() if line.strip().startswith("#")]
    open_tasks = [line.strip()[6:].strip() for line in text.splitlines() if line.strip().startswith("- [ ] ")]
    done_tasks = [line.strip()[6:].strip() for line in text.splitlines() if line.strip().lower().startswith("- [x] ")]
    return {
        "filepath": os.path.abspath(filepath),
        "content": text,
        "headings": headings,
        "open_tasks": open_tasks,
        "done_tasks": done_tasks,
        "line_count": len(text.splitlines()),
        "word_count": len(text.split()),
        "char_count": len(text),
    }


def _tool_image_office_tool(args: Dict[str, Any]) -> Dict[str, Any]:
    input_path = args["input_path"]
    if not os.path.isfile(input_path):
        raise FileNotFoundError(f"Görsel dosyası bulunamadı: {input_path}")

    action = str(args.get("action", "info")).lower()
    with Image.open(input_path) as img:
        orig_w, orig_h = img.size
        orig_fmt = img.format or ""
        orig_mode = img.mode

        if action == "info":
            return {
                "filepath": os.path.abspath(input_path),
                "width": orig_w,
                "height": orig_h,
                "format": orig_fmt,
                "mode": orig_mode,
                "size_bytes": os.path.getsize(input_path),
            }

        output_path = args.get("output_path")
        if not output_path:
            raise ValueError(f"'{action}' işlemi için 'output_path' gerekli.")
        _ensure_parent_dir(output_path)

        working = img.copy()
        if action == "resize":
            max_w = int(args.get("max_width") or orig_w)
            max_h = int(args.get("max_height") or orig_h)
            working.thumbnail((max_w, max_h), Image.Resampling.LANCZOS)

        fmt = str(args.get("target_format") or os.path.splitext(output_path)[1].lstrip(".") or orig_fmt or "PNG").upper()
        if fmt == "JPG":
            fmt = "JPEG"
        quality = int(args.get("quality", 90))

        save_kwargs: Dict[str, Any] = {}
        if fmt in ("JPEG", "PDF"):
            working = working.convert("RGB")
        if fmt in ("JPEG", "WEBP"):
            save_kwargs["quality"] = quality

        working.save(output_path, format=fmt, **save_kwargs)
        new_w, new_h = working.size
        return {
            "status": action,
            "input_path": os.path.abspath(input_path),
            "output_path": os.path.abspath(output_path),
            "format": fmt,
            "original_size": [orig_w, orig_h],
            "new_size": [new_w, new_h],
            "size_bytes": os.path.getsize(output_path),
        }


def _tool_pdf_merge(args: Dict[str, Any]) -> Dict[str, Any]:
    _ensure_parent_dir(args["output_path"])
    return {
        "status": "merged",
        "output_path": args["output_path"],
        "total_pages": merge_pdfs(args["input_paths"], args["output_path"]),
    }


def _tool_pdf_split(args: Dict[str, Any]) -> Dict[str, Any]:
    _ensure_parent_dir(args["output_path"])
    return {
        "status": "split",
        "output_path": args["output_path"],
        "extracted_pages": split_pdf(
            args["input_path"], args["output_path"], int(args["start_page"]), int(args["end_page"])
        ),
    }


TOOL_HANDLERS = {
    "excel_read": _tool_excel_read,
    "excel_write": _tool_excel_write,
    "csv_to_excel": _tool_csv_to_excel,
    "excel_to_csv": _tool_excel_to_csv,
    "word_read": _tool_word_read,
    "word_write": _tool_word_write,
    "word_find_replace": _tool_word_find_replace,
    "pptx_read": _tool_pptx_read,
    "pptx_write": _tool_pptx_write,
    "pdf_read_and_search": _tool_pdf_read_and_search,
    "pdf_write": _tool_pdf_write,
    "pdf_merge": _tool_pdf_merge,
    "pdf_split": _tool_pdf_split,
    "pdf_rotate": lambda a: {
        "status": "rotated",
        "output_path": os.path.abspath(a["output_path"]),
        "rotated_pages": rotate_pdf(
            a["input_path"], a["output_path"], int(a["angle"]), a.get("pages")
        ),
    },
    "pdf_to_images": lambda a: {
        "status": "rendered",
        "output_dir": os.path.abspath(a["output_dir"]),
        "images": pdf_to_images(
            a["input_path"],
            a["output_dir"],
            pages=a.get("pages"),
            scale=float(a.get("scale", 2.0)),
            image_format=str(a.get("image_format", "PNG")),
        ),
    },
    "pdf_metadata": _tool_pdf_metadata,
    "zip_extract": lambda a: extract_archive(
        a["archive_path"],
        a["output_dir"],
        members=a.get("members"),
        pattern=a.get("pattern"),
        password=a.get("password"),
        overwrite=bool(a.get("overwrite", True)),
    ),
    "zip_create": lambda a: create_zip(
        a["source_paths"],
        a["output_zip"],
        exclude_patterns=a.get("exclude_patterns"),
        compression=str(a.get("compression", "deflated")),
    ),
    "zip_list": lambda a: list_archive(a["archive_path"]),
    "office_to_pdf": _tool_office_to_pdf,
    "office_search_folder": _tool_office_search_folder,
    "markdown_note": _tool_markdown_note,
    "image_office_tool": _tool_image_office_tool,
}


def handle_rpc_request(request: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Tek bir MCP JSON-RPC 2.0 isteğini işler ve yanıt sözlüğü döndürür."""
    req_id = request.get("id")
    method = request.get("method", "")
    params = request.get("params") or {}

    if method == "notifications/initialized" or method.startswith("notifications/"):
        return None

    if method == "ping":
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}

    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": SERVER_INFO,
            },
        }

    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": TOOLS_SPEC}}

    if method == "tools/call":
        name = params.get("name", "")
        arguments = params.get("arguments") or {}
        handler = TOOL_HANDLERS.get(name)
        if not handler:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {"code": -32601, "message": f"Bilinmeyen araç: {name}"},
            }
        try:
            if not isinstance(arguments, dict):
                raise ValueError("Araç argümanları bir nesne olmalı.")
            validate_tool_paths(arguments)
            result = handler(arguments)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "content": [
                        {"type": "text", "text": json.dumps(result, ensure_ascii=False, indent=2)}
                    ]
                },
            }
        except Exception as exc:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {
                    "isError": True,
                    "content": [{"type": "text", "text": f"Hata: {exc}"}],
                },
            }

    if req_id is None:
        return None

    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {"code": -32601, "message": f"Desteklenmeyen yöntem: {method}"},
    }


def serve_stdio() -> None:
    """Antigravity MCP istemcisi için stdin/stdout üzerinden sürekli JSON-RPC dinler."""
    for stream in (sys.stdin, sys.stdout):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass

    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
            resp = handle_rpc_request(req)
            if resp is not None:
                sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
                sys.stdout.flush()
        except Exception as exc:
            err = {
                "jsonrpc": "2.0",
                "id": None,
                "error": {"code": -32700, "message": str(exc)},
            }
            sys.stdout.write(json.dumps(err, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    serve_stdio()
