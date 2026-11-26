"""EFOS-Ofis .xlsx ve .csv çalışma kitabı okuma/yazma motoru.

Kaydetme ilkesi: açılmış bir .xlsx dosyası kaydedilirken çalışma kitabı sıfırdan
yazılmaz. Orijinal dosya yeniden açılır ve yalnızca kullanıcının değiştirdiği
hücreler güncellenir. Böylece sayı biçimleri, tarihler, birleştirilmiş hücreler,
sütun genişlikleri, koşullu biçimler, veri doğrulama, tanımlı adlar ve
dokunulmayan hücrelerin stilleri korunur.

openpyxl'in kendisinin koruyamadığı parçalar (grafik, resim, pivot tablo, makro
dışı ActiveX vb.) ``unsupported_parts`` ile önceden tespit edilir; arayüz bu
durumda kullanıcıyı uyarır.
"""

from __future__ import annotations

import copy
import csv
import datetime as _dt
import os
import zipfile
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

import openpyxl
from openpyxl.cell.cell import MergedCell
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

from core.formula_engine import (
    format_display_value,
    from_excel_formula,
    is_text_number,
    parse_date_text,
    to_excel_formula,
    to_number,
)


@dataclass
class CellData:
    raw: str = ""
    bold: bool = False
    italic: bool = False
    align: str = ""  # "left", "center", "right" veya ""
    bg_color: str = ""  # "#RRGGBB"
    fg_color: str = ""  # "#RRGGBB"
    # Dosyadan yüklenirken alınan durum (değişiklik tespiti için)
    orig_raw: Optional[str] = None
    orig_style: Optional[Tuple[Any, ...]] = None

    def style_key(self) -> Tuple[Any, ...]:
        return (self.bold, self.italic, self.align, self.bg_color, self.fg_color)

    def is_empty(self) -> bool:
        return not (self.raw or self.bold or self.italic or self.align or self.bg_color or self.fg_color)

    def was_loaded(self) -> bool:
        return self.orig_raw is not None


@dataclass
class SheetData:
    name: str = "Sayfa1"
    rows: int = 50
    cols: int = 20
    cells: Dict[Tuple[int, int], CellData] = field(default_factory=dict)
    # Dosyadaki asıl sayfa adı (yeniden adlandırma ve eşleştirme için)
    source_title: str = ""


def _argb_to_hex(rgb_val: Optional[str]) -> str:
    if not rgb_val or not isinstance(rgb_val, str):
        return ""
    clean = rgb_val.strip()
    if len(clean) == 8:
        if clean.upper() == "00000000":
            return ""
        return "#" + clean[2:].upper()
    if len(clean) == 6:
        return "#" + clean.upper()
    return ""


def value_to_raw(value: Any) -> str:
    """openpyxl hücre değerini düzenleyicideki ham metne çevirir."""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "DOĞRU" if value else "YANLIŞ"
    if isinstance(value, (_dt.datetime, _dt.date, _dt.time)):
        return format_display_value(value)
    if isinstance(value, float):
        if value.is_integer() and abs(value) < 1e15:
            return str(int(value))
        return repr(value).replace(".", ",")
    if isinstance(value, int):
        return str(value)
    text = str(value)
    if text.startswith("="):
        return from_excel_formula(text)
    # Sayı gibi görünen metinler metin olarak kalsın: Excel'deki gibi ' öneki
    if to_number(text) is not None or parse_date_text(text) is not None or text.upper() in (
        "TRUE",
        "FALSE",
        "DOĞRU",
        "YANLIŞ",
    ):
        if not is_text_number(text):
            return "'" + text
    if text.startswith("'"):
        return "'" + text
    return text


def raw_to_value(raw: str) -> Any:
    """Düzenleyicideki ham metni Excel'e yazılacak değere çevirir."""
    if raw == "":
        return None
    if raw.startswith("="):
        return to_excel_formula(raw)
    if raw.startswith("'"):
        return raw[1:]
    text = raw.strip()
    if is_text_number(text):
        return raw
    upper = text.replace("i", "İ").upper()
    if upper in ("DOĞRU", "TRUE"):
        return True
    if upper in ("YANLIŞ", "FALSE"):
        return False
    dt = parse_date_text(text)
    if dt is not None:
        if dt.hour or dt.minute or dt.second:
            return dt
        return dt.date() if isinstance(dt, _dt.datetime) else dt
    num = to_number(text)
    if num is not None:
        # %12 gibi değerler yüzde biçimiyle saklanır; tam sayılar int olarak yazılır
        if num.is_integer() and abs(num) < 1e15 and "%" not in text:
            return int(num)
        return num
    return raw


def _is_percent_text(raw: str) -> bool:
    t = raw.strip()
    return not t.startswith(("=", "'")) and (t.endswith("%") or t.startswith("%")) and to_number(t) is not None


def _read_style(cell: Any) -> Tuple[bool, bool, str, str, str]:
    bold = bool(cell.font and cell.font.bold)
    italic = bool(cell.font and cell.font.italic)
    align = ""
    if cell.alignment and cell.alignment.horizontal in ("left", "center", "right"):
        align = cell.alignment.horizontal
    bg_color = ""
    if (
        cell.fill
        and cell.fill.fill_type == "solid"
        and cell.fill.fgColor is not None
        and cell.fill.fgColor.type == "rgb"
    ):
        bg_color = _argb_to_hex(cell.fill.fgColor.rgb)
    fg_color = ""
    if cell.font and cell.font.color is not None and cell.font.color.type == "rgb":
        fg_color = _argb_to_hex(cell.font.color.rgb)
    return bold, italic, align, bg_color, fg_color


def unsupported_parts(filepath: str) -> List[str]:
    """Kaydedilince kaybolacak çalışma kitabı parçalarını Türkçe adlarıyla listeler."""
    found: List[str] = []
    try:
        with zipfile.ZipFile(filepath) as zf:
            names = zf.namelist()
    except (zipfile.BadZipFile, OSError):
        return found
    checks = [
        ("xl/charts/", "grafik"),
        ("xl/media/", "resim"),
        ("xl/pivotTables/", "pivot tablo"),
        ("xl/slicers/", "dilimleyici"),
        ("xl/activeX/", "ActiveX denetimi"),
        ("xl/embeddings/", "gömülü nesne"),
        ("xl/drawings/vmlDrawing", "form denetimi"),
    ]
    for prefix, label in checks:
        if any(n.startswith(prefix) for n in names) and label not in found:
            found.append(label)
    if any(n.startswith("xl/drawings/") and not n.startswith("xl/drawings/vml") for n in names):
        if "grafik" not in found and "resim" not in found:
            found.append("çizim/şekil")
    return found


def load_xlsx(filepath: str) -> List[SheetData]:
    """Bir .xlsx çalışma kitabını tüm sayfaları ve formülleriyle yükler."""
    keep_vba = filepath.lower().endswith(".xlsm")
    wb = openpyxl.load_workbook(filepath, data_only=False, keep_vba=keep_vba)
    sheets: List[SheetData] = []
    try:
        for ws in wb.worksheets:
            max_r = max(ws.max_row or 0, 50)
            max_c = max(ws.max_column or 0, 20)
            cells: Dict[Tuple[int, int], CellData] = {}
            for row in ws.iter_rows():
                for cell in row:
                    if isinstance(cell, MergedCell):
                        continue
                    value = cell.value
                    if value is not None and not isinstance(value, (str, int, float, bool, _dt.date, _dt.time)):
                        # Dizi formülü, zengin metin vb. -> metin karşılığı
                        value = getattr(value, "text", None) or str(value)
                    raw_val = value_to_raw(value)
                    if isinstance(value, (int, float)) and not isinstance(value, bool):
                        fmt = cell.number_format or ""
                        if "%" in fmt:
                            raw_val = format_display_value(value * 100).replace(".", ",") + "%"
                    style = _read_style(cell)
                    if raw_val or any(style):
                        cd = CellData(raw_val, *style)
                        cd.orig_raw = raw_val
                        cd.orig_style = cd.style_key()
                        cells[(cell.row - 1, cell.column - 1)] = cd
            sheets.append(
                SheetData(name=ws.title, rows=max_r, cols=max_c, cells=cells, source_title=ws.title)
            )
    finally:
        wb.close()
    return sheets or [SheetData()]


def _apply_style(ws_cell: Any, cd: CellData, previous: Optional[Tuple[Any, ...]]) -> None:
    """Yalnızca değişen stil özelliklerini, mevcut stili bozmadan uygular."""
    prev = previous or (False, False, "", "", "")
    bold, italic, align, bg, fg = cd.style_key()
    if (bold, italic, fg) != (prev[0], prev[1], prev[4]):
        font = copy.copy(ws_cell.font) if ws_cell.has_style else Font(name="Calibri", size=11)
        font.bold = bold
        font.italic = italic
        if fg != prev[4]:
            font.color = fg.lstrip("#") if fg else None
        ws_cell.font = font
    if align != prev[2]:
        al = copy.copy(ws_cell.alignment) if ws_cell.has_style else Alignment()
        al.horizontal = align or None
        ws_cell.alignment = al
    if bg != prev[3]:
        if bg:
            hx = bg.lstrip("#")
            ws_cell.fill = PatternFill(fill_type="solid", start_color=hx, end_color=hx)
        else:
            ws_cell.fill = PatternFill(fill_type=None)


def _write_value(ws_cell: Any, cd: CellData) -> None:
    value = raw_to_value(cd.raw)
    if cd.raw.startswith("'") or (isinstance(value, str) and not value.startswith("=")):
        ws_cell.value = value
        ws_cell.data_type = "s"
        # Sayı gibi görünen metnin Excel'de de metin kalması için "@" biçimi
        if ws_cell.number_format in ("General", None) and (is_text_number(value) or cd.raw.startswith("'")):
            ws_cell.number_format = "@"
        return
    prev_fmt = ws_cell.number_format or "General"
    ws_cell.value = value
    if isinstance(value, (_dt.date, _dt.datetime)) and (prev_fmt == "General" or prev_fmt == "@"):
        ws_cell.number_format = "DD.MM.YYYY HH:MM" if isinstance(value, _dt.datetime) else "DD.MM.YYYY"
    elif _is_percent_text(cd.raw) and "%" not in (ws_cell.number_format or ""):
        ws_cell.number_format = "0%" if float(value).is_integer() or (value * 100).is_integer() else "0.00%"
    elif ws_cell.number_format == "@" and isinstance(value, (int, float)) and not isinstance(value, bool):
        ws_cell.number_format = "General"


def _merged_anchor_ok(ws: Any, row: int, col: int) -> bool:
    cell = ws.cell(row=row, column=col)
    return not isinstance(cell, MergedCell)


def save_xlsx(
    sheets: List[SheetData], filepath: str, source_path: Optional[str] = None
) -> None:
    """Sayfa listesini .xlsx dosyasına kaydeder.

    ``source_path`` verilirse (veya hedef dosya zaten varsa ve sayfalar o dosyadan
    yüklendiyse) o çalışma kitabı açılıp yalnızca değişen hücreler yazılır.
    """
    template = source_path
    if template is None and os.path.exists(filepath) and any(s.source_title for s in sheets):
        template = filepath
    if template and not os.path.exists(template):
        template = None

    if template:
        keep_vba = template.lower().endswith(".xlsm")
        wb = openpyxl.load_workbook(template, keep_vba=keep_vba)
    else:
        wb = openpyxl.Workbook()
        wb.remove(wb.active)

    used_ws = []
    for idx, sheet in enumerate(sheets):
        title = (sheet.name or f"Sayfa{idx + 1}")[:31]
        ws = None
        if template and sheet.source_title and sheet.source_title in wb.sheetnames:
            ws = wb[sheet.source_title]
            if ws in used_ws:
                ws = None
        if ws is None:
            ws = wb.create_sheet(title=title)
            fresh = True
        else:
            fresh = False
            if ws.title != title:
                ws.title = title
        used_ws.append(ws)
        _write_sheet(ws, sheet, fresh)

    # Kullanıcının kapattığı sayfaları kaldır, sırayı düzenleyicideki gibi yap
    for ws in list(wb.worksheets):
        if ws not in used_ws:
            wb.remove(ws)
    others = [s for s in wb._sheets if s not in used_ws]  # grafik sayfaları
    wb._sheets = list(used_ws) + others
    wb.active = 0
    for ws in wb.worksheets:
        ws.sheet_view.tabSelected = ws is used_ws[0]

    tmp_path = filepath + ".efos-tmp"
    try:
        wb.save(tmp_path)
        os.replace(tmp_path, filepath)
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
        wb.close()


def _write_sheet(ws: Any, sheet: SheetData, fresh: bool) -> None:
    col_widths: Dict[int, int] = {}
    for (r_idx, c_idx), cd in sheet.cells.items():
        value_changed = cd.orig_raw is None or cd.raw != cd.orig_raw
        style_changed = cd.orig_style is None or cd.style_key() != cd.orig_style
        if fresh:
            value_changed = bool(cd.raw)
            style_changed = any(cd.style_key())
        if not value_changed and not style_changed:
            continue
        if not _merged_anchor_ok(ws, r_idx + 1, c_idx + 1):
            continue
        ws_cell = ws.cell(row=r_idx + 1, column=c_idx + 1)
        if value_changed:
            _write_value(ws_cell, cd)
        if style_changed:
            _apply_style(ws_cell, cd, None if fresh else cd.orig_style)
        if fresh and cd.raw:
            shown = len(format_display_value(cd.raw)) if not cd.raw.startswith("=") else 10
            col_widths[c_idx] = max(col_widths.get(c_idx, 10), min(shown + 4, 42))

    for c_idx, width in col_widths.items():
        ws.column_dimensions[get_column_letter(c_idx + 1)].width = width


def mark_saved(sheets: List[SheetData]) -> None:
    """Kayıttan sonra mevcut durumu 'orijinal' kabul eder (sonraki kayıtlar için)."""
    for sheet in sheets:
        sheet.source_title = sheet.name[:31]
        for cd in sheet.cells.values():
            cd.orig_raw = cd.raw
            cd.orig_style = cd.style_key()


def load_csv(filepath: str) -> List[SheetData]:
    """Bir .csv dosyasını ayırıcıyı (virgül, noktalı virgül, sekme) algılayarak yükler."""
    with open(filepath, "rb") as fb:
        data = fb.read()
    for enc in ("utf-8-sig", "cp1254", "latin-1"):
        try:
            text = data.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    sample = text[:4096]
    try:
        delimiter = csv.Sniffer().sniff(sample, delimiters=";,\t|").delimiter
    except csv.Error:
        delimiter = ";" if sample.count(";") > sample.count(",") else ","
    rows_list = list(csv.reader(text.splitlines(), delimiter=delimiter))

    max_r = max(len(rows_list), 50)
    max_c = max((len(r) for r in rows_list), default=0)
    max_c = max(max_c, 20)

    cells: Dict[Tuple[int, int], CellData] = {}
    for r_idx, row in enumerate(rows_list):
        for c_idx, val in enumerate(row):
            if val:
                cells[(r_idx, c_idx)] = CellData(raw=val)
    return [SheetData(name="CSV", rows=max_r, cols=max_c, cells=cells)]


def save_csv(sheet: SheetData, filepath: str, delimiter: str = ";") -> None:
    """Aktif sayfayı UTF-8 BOM ile .csv olarak kaydeder (Excel uyumlu)."""
    filled = [k for k, cd in sheet.cells.items() if cd.raw]
    if not filled:
        max_r, max_c = 0, 0
    else:
        max_r = max(r for r, _ in filled) + 1
        max_c = max(c for _, c in filled) + 1

    with open(filepath, "w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f, delimiter=delimiter)
        for r in range(max_r):
            row_vals = []
            for c in range(max_c):
                raw = sheet.cells[(r, c)].raw if (r, c) in sheet.cells else ""
                row_vals.append(raw[1:] if raw.startswith("'") else raw)
            writer.writerow(row_vals)


def changed_cell_count(sheets: List[SheetData]) -> int:
    return sum(
        1
        for s in sheets
        for cd in s.cells.values()
        if cd.orig_raw is None and cd.raw or (cd.orig_raw is not None and cd.raw != cd.orig_raw)
    )


__all__ = [
    "CellData",
    "SheetData",
    "load_xlsx",
    "save_xlsx",
    "load_csv",
    "save_csv",
    "mark_saved",
    "unsupported_parts",
    "value_to_raw",
    "raw_to_value",
]
