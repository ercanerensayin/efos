"""EFOS-Ofis PDF görüntüleme, metin çıkarma, arama, birleştirme ve ayırma motoru."""

from __future__ import annotations

import html
import io
import os
import sys
import unicodedata
from typing import Dict, List, Optional, Tuple

from PIL import Image
import pypdfium2 as pdfium
from pypdf import PdfReader, PdfWriter



def _fold(text: str) -> str:
    """Aramada kullanılan, Türkçe İ/I farkını gözetmeyen karşılaştırma biçimi.

    Uzunluk korunur (karakter başına bir karakter), böylece bulunan konum özgün
    metindeki konumla aynı kalır.
    """
    text = unicodedata.normalize("NFC", text)
    out = []
    for ch in text:
        if ch in "İIıi":
            out.append("i")
        else:
            low = ch.lower()
            out.append(low if len(low) == 1 else ch)
    return "".join(out)

class PdfDocumentReader:
    """Harici araç gerektirmeden PDF sayfalarını PNG olarak render eder ve metin arar."""

    def __init__(self, filepath: str) -> None:
        self.filepath = filepath
        self._pdf = pdfium.PdfDocument(filepath)
        self._reader = PdfReader(filepath)

    @property
    def page_count(self) -> int:
        return len(self._pdf)

    def render_page_png(
        self, page_index: int, scale: float = 1.5, rotation: int = 0
    ) -> bytes:
        """Belirtilen sayfayı verilen ölçek ve döndürme açısıyla PNG baytlarına çevirir."""
        if page_index < 0 or page_index >= self.page_count:
            raise IndexError(f"Geçersiz sayfa numarası: {page_index}")
        page = self._pdf[page_index]
        bitmap = page.render(scale=scale, rotation=rotation % 360)
        pil_img = bitmap.to_pil()
        buf = io.BytesIO()
        pil_img.save(buf, format="PNG")
        return buf.getvalue()

    def get_page_text(self, page_index: int) -> str:
        """Sayfadaki metni çıkarır."""
        if page_index < 0 or page_index >= len(self._reader.pages):
            return ""
        return (self._reader.pages[page_index].extract_text() or "").strip()

    def search_text(self, query: str) -> List[Tuple[int, str]]:
        """Tüm sayfalarda büyük/küçük harf duyarsız metin araması yapar.

        Windows yazı tiplerinden çıkarılan metin ayrık aksanlar (u + ¨) ya da harf
        arasında boşluk içerebildiği için karşılaştırma Unicode normalleştirmesiyle
        ve gerekirse boşluklar yok sayılarak yapılır.
        """
        needle = _fold(query.strip())
        if not needle:
            return []
        needle_nospace = "".join(needle.split())
        matches: List[Tuple[int, str]] = []
        for idx in range(len(self._reader.pages)):
            txt = unicodedata.normalize("NFC", self.get_page_text(idx))
            low = _fold(txt)
            pos = low.find(needle)
            length = len(needle)
            if pos < 0 and needle_nospace:
                # Boşlukları atıp ara; bulunan yeri özgün metne geri eşle.
                keep = [i for i, ch in enumerate(low) if not ch.isspace()]
                squeezed = "".join(low[i] for i in keep)
                p2 = squeezed.find(needle_nospace)
                if p2 >= 0:
                    pos = keep[p2]
                    length = keep[p2 + len(needle_nospace) - 1] - pos + 1
            if pos >= 0:
                start = max(0, pos - 35)
                end = min(len(txt), pos + length + 55)
                snippet = txt[start:end].replace("\n", " ").strip()
                matches.append((idx, snippet))
        return matches

    def get_metadata(self) -> Dict[str, str]:
        meta = self._reader.metadata or {}
        return {
            "title": str(meta.get("/Title", "") or ""),
            "author": str(meta.get("/Author", "") or ""),
            "subject": str(meta.get("/Subject", "") or ""),
            "keywords": str(meta.get("/Keywords", "") or ""),
            "creator": str(meta.get("/Creator", "") or ""),
            "pages": str(self.page_count),
        }

    def close(self) -> None:
        try:
            self._pdf.close()
        except Exception:
            pass


def merge_pdfs(input_paths: List[str], output_path: str) -> int:
    """Birden fazla PDF dosyasını sırayla tek bir PDF dosyasında birleştirir."""
    if not input_paths:
        raise ValueError("Birleştirmek için en az bir PDF dosyası gerekli.")
    writer = PdfWriter()
    total_pages = 0
    for path in input_paths:
        reader = PdfReader(path)
        for page in reader.pages:
            writer.add_page(page)
            total_pages += 1
    with open(output_path, "wb") as f:
        writer.write(f)
    return total_pages


def split_pdf(
    input_path: str, output_path: str, start_page: int, end_page: int
) -> int:
    """1-tabanlı başlangıç ve bitiş sayfa aralığını yeni bir PDF olarak kaydeder."""
    reader = PdfReader(input_path)
    total = len(reader.pages)
    if start_page < 1 or end_page > total or start_page > end_page:
        raise ValueError(f"Geçersiz sayfa aralığı: {start_page}-{end_page} (Toplam: {total})")
    writer = PdfWriter()
    for idx in range(start_page - 1, end_page):
        writer.add_page(reader.pages[idx])
    with open(output_path, "wb") as f:
        writer.write(f)
    return end_page - start_page + 1


def create_pdf_from_html(
    html_content: str,
    output_path: str,
    title: str = "",
    landscape: bool = False,
) -> int:
    """HTML içeriğini Türkçe/UTF-8 destekli vektörel PDF dosyasına yazar."""
    out_abs = os.path.abspath(output_path)
    parent = os.path.dirname(out_abs)
    if parent:
        os.makedirs(parent, exist_ok=True)

    if sys.platform.startswith("linux") and not os.environ.get("DISPLAY"):
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

    from PyQt6.QtCore import QMarginsF
    from PyQt6.QtGui import QFont, QGuiApplication, QPageLayout, QPageSize, QPdfWriter, QTextDocument

    app = QGuiApplication.instance()
    if app is None:
        app = QGuiApplication(["efos-ofis"])

    writer = QPdfWriter(out_abs)
    orientation = (
        QPageLayout.Orientation.Landscape
        if landscape
        else QPageLayout.Orientation.Portrait
    )
    layout = QPageLayout(
        QPageSize(QPageSize.PageSizeId.A4),
        orientation,
        QMarginsF(18.0, 18.0, 18.0, 18.0),
        QPageLayout.Unit.Millimeter,
    )
    writer.setPageLayout(layout)
    writer.setCreator("EFOS-Ofis")
    if title:
        writer.setTitle(title)

    styled_html = (
        "<html><head><meta charset='utf-8'><style>"
        "body { font-family: 'Segoe UI', 'Calibri', 'DejaVu Sans', sans-serif; "
        "font-size: 11pt; color: #0f172a; line-height: 1.5; }"
        "h1 { color: #1d4ed8; font-size: 18pt; margin-bottom: 10px; }"
        "h2 { color: #1e293b; font-size: 14pt; margin-top: 14px; }"
        "h3 { color: #334155; font-size: 12pt; }"
        "table { border-collapse: collapse; width: 100%; margin: 10px 0; }"
        "th, td { border: 1px solid #cbd5e1; padding: 6px 8px; text-align: left; }"
        "th { background-color: #f1f5f9; font-weight: bold; }"
        "pre, code { font-family: 'Consolas', 'Courier New', monospace; background: #f8fafc; }"
        "</style></head><body>"
        f"{html_content}"
        "</body></html>"
    )

    doc = QTextDocument()
    doc.setDefaultFont(QFont("Segoe UI", 11))
    doc.setHtml(styled_html)
    doc.print(writer)

    reader = PdfReader(out_abs)
    return len(reader.pages)


def create_pdf_document(
    output_path: str,
    title: str = "",
    paragraphs: Optional[List[str]] = None,
    tables: Optional[List[List[List[object]]]] = None,
    html_content: Optional[str] = None,
    landscape: bool = False,
) -> int:
    """Başlık, paragraflar, tablolar veya doğrudan HTML kullanarak PDF dosyası oluşturur."""
    if html_content:
        body_html = html_content
    else:
        parts: List[str] = []
        if title:
            parts.append(f"<h1>{html.escape(title)}</h1>")
        for para in paragraphs or []:
            text = html.escape(str(para)).replace("\n", "<br/>")
            parts.append(f"<p>{text}</p>")
        raw_tables = tables or []
        if raw_tables and isinstance(raw_tables[0], list) and (
            not raw_tables[0] or not isinstance(raw_tables[0][0], list)
        ):
            raw_tables = [raw_tables]
        for tbl in raw_tables:
            if not tbl:
                continue
            rows_html = []
            for r_idx, row in enumerate(tbl):
                tag = "th" if r_idx == 0 else "td"
                cells = "".join(f"<{tag}>{html.escape(str(v))}</{tag}>" for v in row)
                rows_html.append(f"<tr>{cells}</tr>")
            parts.append("<table>" + "".join(rows_html) + "</table>")
        body_html = "\n".join(parts) if parts else "<p>&nbsp;</p>"
    return create_pdf_from_html(body_html, output_path, title=title, landscape=landscape)


def images_to_pdf(image_paths: List[str], output_path: str) -> int:
    """Bir veya birden fazla görsel dosyasını (PNG/JPG/WEBP/BMP) çok sayfalı PDF'ye dönüştürür."""
    if not image_paths:
        raise ValueError("PDF oluşturmak için en az bir görsel dosyası gerekli.")

    rgb_images: List[Image.Image] = []
    try:
        for path in image_paths:
            if not os.path.isfile(path):
                raise FileNotFoundError(f"Görsel dosyası bulunamadı: {path}")
            with Image.open(path) as img:
                rgb_images.append(img.convert("RGB"))

        out_abs = os.path.abspath(output_path)
        parent = os.path.dirname(out_abs)
        if parent:
            os.makedirs(parent, exist_ok=True)

        first, rest = rgb_images[0], rgb_images[1:]
        first.save(out_abs, "PDF", resolution=150.0, save_all=True, append_images=rest)
        return len(rgb_images)
    finally:
        for im in rgb_images:
            try:
                im.close()
            except Exception:
                pass


def rotate_pdf(
    input_path: str,
    output_path: str,
    angle: int,
    pages: Optional[List[int]] = None,
) -> int:
    """Belirtilen 1-tabanlı sayfaları (veya tümünü) 90/180/270 derece döndürerek kaydeder."""
    if angle % 90 != 0:
        raise ValueError("Döndürme açısı 90'ın katı olmalıdır (90, 180, 270, -90).")

    reader = PdfReader(input_path)
    total = len(reader.pages)
    target_pages = set(pages) if pages else set(range(1, total + 1))

    writer = PdfWriter()
    rotated_count = 0
    for idx, page in enumerate(reader.pages):
        page_num = idx + 1
        if page_num in target_pages:
            page.rotate(angle)
            rotated_count += 1
        writer.add_page(page)

    out_abs = os.path.abspath(output_path)
    parent = os.path.dirname(out_abs)
    if parent:
        os.makedirs(parent, exist_ok=True)
    tmp_path = out_abs + ".efos_tmp"
    with open(tmp_path, "wb") as f:
        writer.write(f)
    os.replace(tmp_path, out_abs)
    return rotated_count


def pdf_to_images(
    input_path: str,
    output_dir: str,
    pages: Optional[List[int]] = None,
    scale: float = 2.0,
    image_format: str = "PNG",
) -> List[str]:
    """PDF sayfalarını PNG veya JPEG görsel dosyalarına dışa aktarır."""
    fmt = (image_format or "PNG").upper()
    if fmt == "JPG":
        fmt = "JPEG"
    if fmt not in ("PNG", "JPEG", "WEBP"):
        raise ValueError(f"Desteklenmeyen görsel formatı: {image_format}")
    ext = "jpg" if fmt == "JPEG" else fmt.lower()

    os.makedirs(output_dir, exist_ok=True)
    stem = os.path.splitext(os.path.basename(input_path))[0]
    reader = PdfDocumentReader(input_path)
    out_files: List[str] = []
    try:
        selected = pages if pages else list(range(1, reader.page_count + 1))
        for p_num in selected:
            if p_num < 1 or p_num > reader.page_count:
                raise IndexError(f"Geçersiz sayfa numarası: {p_num} (Toplam: {reader.page_count})")
            png_bytes = reader.render_page_png(p_num - 1, scale=scale)
            out_path = os.path.join(output_dir, f"{stem}_sayfa_{p_num:03d}.{ext}")
            if fmt == "PNG":
                with open(out_path, "wb") as f:
                    f.write(png_bytes)
            else:
                with Image.open(io.BytesIO(png_bytes)) as img:
                    img.convert("RGB").save(out_path, format=fmt, quality=92)
            out_files.append(os.path.abspath(out_path))
    finally:
        reader.close()
    return out_files


def update_pdf_metadata(
    input_path: str, output_path: str, metadata: Dict[str, str]
) -> Dict[str, str]:
    """PDF dosyasının başlık, yazar, konu ve anahtar kelime üstverilerini günceller."""
    reader = PdfReader(input_path)
    writer = PdfWriter()
    for page in reader.pages:
        writer.add_page(page)

    merged: Dict[str, str] = {}
    if reader.metadata:
        for k, v in reader.metadata.items():
            if v is not None:
                merged[str(k)] = str(v)

    key_map = {
        "title": "/Title",
        "author": "/Author",
        "subject": "/Subject",
        "keywords": "/Keywords",
        "creator": "/Creator",
    }
    for k, v in metadata.items():
        pdf_key = key_map.get(k.lower(), k if k.startswith("/") else f"/{k}")
        merged[pdf_key] = str(v)

    writer.add_metadata(merged)
    out_abs = os.path.abspath(output_path)
    parent = os.path.dirname(out_abs)
    if parent:
        os.makedirs(parent, exist_ok=True)
    tmp_path = out_abs + ".efos_tmp"
    with open(tmp_path, "wb") as f:
        writer.write(f)
    os.replace(tmp_path, out_abs)

    return {
        "title": merged.get("/Title", ""),
        "author": merged.get("/Author", ""),
        "subject": merged.get("/Subject", ""),
        "keywords": merged.get("/Keywords", ""),
        "creator": merged.get("/Creator", ""),
        "pages": str(len(reader.pages)),
    }
