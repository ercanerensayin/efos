"""EFOS-Ofis .docx belge okuma ve yazma motoru.

python-docx belgelerini zengin HTML'e dönüştürür (QTextEdit'te düzenlemek için)
ve QTextEdit'in ürettiği HTML'i biçimlendirmeleri koruyarak .docx dosyasına yazar.

Kaydetme ilkesi: açılmış bir belge kaydedilirken orijinal dosya temel alınır.
Gövde yeniden yazılır ama üstbilgi/altbilgi, sayfa yapısı (kenar boşlukları,
kâğıt boyutu, yönlendirme), stiller, numaralandırma tanımları ve belge
özellikleri korunur. Resimler düzenleyicide görünür ve geri yazılır; madde
işaretli/numaralı listeler liste olarak kalır.

HTML düzenleyicinin taşıyamadığı parçalar (dipnot, yorum, metin kutusu,
içindekiler alanı, izlenen değişiklikler vb.) ``unsupported_parts`` ile
önceden tespit edilir; arayüz bu durumda kullanıcıyı uyarır.
"""

from __future__ import annotations

import base64
import html
import io
import os
import re
import tempfile
import zipfile
from html.parser import HTMLParser
from typing import Dict, List, Optional, Tuple
from urllib.parse import unquote, urlparse

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn
from docx.shared import Emu, Pt, RGBColor

_EMU_PER_PX = 9525
_IMAGE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"


ALIGN_TO_CSS = {
    WD_ALIGN_PARAGRAPH.LEFT: "left",
    WD_ALIGN_PARAGRAPH.CENTER: "center",
    WD_ALIGN_PARAGRAPH.RIGHT: "right",
    WD_ALIGN_PARAGRAPH.JUSTIFY: "justify",
}

CSS_TO_ALIGN = {
    "left": WD_ALIGN_PARAGRAPH.LEFT,
    "center": WD_ALIGN_PARAGRAPH.CENTER,
    "right": WD_ALIGN_PARAGRAPH.RIGHT,
    "justify": WD_ALIGN_PARAGRAPH.JUSTIFY,
}


def _parse_style_attr(style_str: str) -> Dict[str, str]:
    props: Dict[str, str] = {}
    for part in style_str.split(";"):
        if ":" in part:
            k, v = part.split(":", 1)
            props[k.strip().lower()] = v.strip()
    return props


def _parse_hex_color(color_str: str) -> Optional[Tuple[int, int, int]]:
    c = color_str.strip().lower()
    m_hex = re.match(r"^#([0-9a-f]{6})$", c)
    if m_hex:
        h = m_hex.group(1)
        return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    m_rgb = re.match(r"^rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)$", c)
    if m_rgb:
        return int(m_rgb.group(1)), int(m_rgb.group(2)), int(m_rgb.group(3))
    return None


def _parse_font_size_pt(size_str: str) -> Optional[float]:
    m = re.match(r"^(\d+(?:\.\d+)?)\s*(pt|px)?$", size_str.strip().lower())
    if not m:
        return None
    val = float(m.group(1))
    unit = m.group(2) or "pt"
    return val * 0.75 if unit == "px" else val


def unsupported_parts(filepath: str) -> List[str]:
    """Kaydedilince kaybolacak/bozulacak belge parçalarını Türkçe adlarıyla listeler."""
    found: List[str] = []
    try:
        with zipfile.ZipFile(filepath) as zf:
            names = set(zf.namelist())
            body = zf.read("word/document.xml").decode("utf-8", "ignore")
            footnotes = zf.read("word/footnotes.xml").decode("utf-8", "ignore") if "word/footnotes.xml" in names else ""
    except (zipfile.BadZipFile, KeyError, OSError):
        return found
    if footnotes.count("<w:footnote ") > 2:
        found.append("dipnot")
    if "word/comments.xml" in names and "<w:commentReference" in body:
        found.append("yorum")
    if "<w:txbxContent" in body:
        found.append("metin kutusu")
    if re.search(r"instrText[^>]*>\s*TOC", body) or 'w:instr="TOC' in body:
        found.append("içindekiler tablosu")
    if "<w:ins " in body or "<w:del " in body:
        found.append("izlenen değişiklikler")
    if "<m:oMath" in body:
        found.append("denklem")
    if "<c:chart" in body or any(n.startswith("word/charts/") for n in names):
        found.append("grafik")
    if "dgm:relIds" in body:
        found.append("SmartArt")
    if body.count("<w:sectPr") > 1:
        found.append("bölüm sonu")
    if "<w:gridSpan" in body or "<w:vMerge" in body:
        found.append("birleştirilmiş tablo hücresi")
    if "<w:sdt>" in body or "<w:sdt " in body:
        found.append("içerik denetimi")
    return found


def _list_kind(p) -> Optional[str]:
    """Paragraf bir listeye aitse 'ul' veya 'ol' döndürür."""
    style_name = (p.style.name or "").lower() if p.style is not None else ""
    if "list number" in style_name:
        return "ol"
    if "list bullet" in style_name:
        return "ul"
    ppr = p._element.pPr
    num_pr = ppr.numPr if ppr is not None else None
    if num_pr is None or num_pr.numId is None:
        return None
    num_id = num_pr.numId.val
    if num_id == 0:
        return None
    ilvl = num_pr.ilvl.val if num_pr.ilvl is not None else 0
    try:
        numbering = p.part.numbering_part.element
        num = numbering.num_having_numId(num_id)
        abstract_id = num.abstractNumId.val
        for abstract in numbering.findall(qn("w:abstractNum")):
            if abstract.get(qn("w:abstractNumId")) == str(abstract_id):
                for lvl in abstract.findall(qn("w:lvl")):
                    if lvl.get(qn("w:ilvl")) == str(ilvl):
                        fmt = lvl.find(qn("w:numFmt"))
                        if fmt is not None and fmt.get(qn("w:val")) not in ("bullet", "none"):
                            return "ol"
                        return "ul"
    except (KeyError, AttributeError, NotImplementedError):
        pass
    return "ul"


def load_docx_to_html(filepath: str, image_dir: Optional[str] = None) -> str:
    """Bir .docx dosyasını QTextEdit ile uyumlu zengin HTML metnine çevirir.

    Belgedeki resimler ``image_dir`` klasörüne (verilmezse geçici bir klasöre)
    çıkarılır ve HTML'de dosya yolu ile gösterilir.
    """
    doc = Document(filepath)
    if image_dir is None:
        image_dir = tempfile.mkdtemp(prefix="efos_docx_")
    os.makedirs(image_dir, exist_ok=True)
    ctx = {"doc": doc, "image_dir": image_dir, "count": 0}

    blocks: List[str] = []
    open_list: Optional[str] = None
    para_map = {p._element: p for p in doc.paragraphs}
    table_map = {t._element: t for t in doc.tables}

    for child in doc.element.body:
        if child in para_map:
            p = para_map[child]
            kind = _list_kind(p)
            if kind != open_list:
                if open_list:
                    blocks.append(f"</{open_list}>")
                if kind:
                    blocks.append(f"<{kind}>")
                open_list = kind
            blocks.append(_paragraph_to_html(p, ctx, as_list_item=kind is not None))
        elif child in table_map:
            if open_list:
                blocks.append(f"</{open_list}>")
                open_list = None
            blocks.append(_table_to_html(table_map[child], ctx))
    if open_list:
        blocks.append(f"</{open_list}>")

    if not blocks:
        return "<p></p>"
    return "\n".join(blocks)


def _image_html(blip_rid: str, drawing, part, ctx) -> str:
    try:
        image_part = part.related_parts[blip_rid]
    except KeyError:
        return ""
    ext = os.path.splitext(getattr(image_part, "partname", "") or "")[1] or ".png"
    ctx["count"] += 1
    path = os.path.join(ctx["image_dir"], f"resim_{ctx['count']}{ext}")
    with open(path, "wb") as f:
        f.write(image_part.blob)
    w_px = h_px = 0
    extent = drawing.find(".//" + qn("wp:extent"))
    if extent is not None:
        try:
            w_px = int(int(extent.get("cx")) / _EMU_PER_PX)
            h_px = int(int(extent.get("cy")) / _EMU_PER_PX)
        except (TypeError, ValueError):
            pass
    size = f' width="{w_px}" height="{h_px}"' if w_px and h_px else ""
    return f'<img src="{html.escape(path)}"{size} />'


def _run_html(run, part, ctx) -> str:
    text_parts: List[str] = []
    image_parts: List[str] = []
    for el in run._element:
        tag = el.tag
        if tag == qn("w:t"):
            text_parts.append(html.escape(el.text or ""))
        elif tag == qn("w:tab"):
            text_parts.append("&#9;")
        elif tag in (qn("w:br"), qn("w:cr")):
            if el.get(qn("w:type")) != "page":
                text_parts.append("<br/>")
        elif tag == qn("w:drawing"):
            blip = el.find(".//" + qn("a:blip"))
            if blip is not None and blip.get(qn("r:embed")):
                image_parts.append(_image_html(blip.get(qn("r:embed")), el, part, ctx))
    text = "".join(text_parts)
    out = ""
    if text:
        r_styles: List[str] = []
        if run.font.name:
            r_styles.append(f"font-family: '{html.escape(run.font.name)}'")
        if run.font.size:
            r_styles.append(f"font-size: {run.font.size.pt:.1f}pt")
        if run.font.color is not None and run.font.color.type is not None and run.font.color.rgb:
            r_styles.append(f"color: #{run.font.color.rgb}")
        if run.font.highlight_color:
            r_styles.append("background-color: #ffff00")
        piece = text
        if run.bold:
            piece = f"<b>{piece}</b>"
        if run.italic:
            piece = f"<i>{piece}</i>"
        if run.underline:
            piece = f"<u>{piece}</u>"
        if run.font.strike:
            piece = f"<s>{piece}</s>"
        if run.font.superscript:
            piece = f"<sup>{piece}</sup>"
        if run.font.subscript:
            piece = f"<sub>{piece}</sub>"
        if r_styles:
            piece = f'<span style="{"; ".join(r_styles)}">{piece}</span>'
        out = piece
    return out + "".join(image_parts)


def _has_page_break(p) -> bool:
    if p.paragraph_format.page_break_before:
        return True
    for br in p._element.iter(qn("w:br")):
        if br.get(qn("w:type")) == "page":
            return True
    return False


def _paragraph_to_html(p, ctx, as_list_item: bool = False) -> str:
    style_name = (p.style.name or "").lower() if p.style is not None else ""
    tag = "p"
    if style_name.startswith("heading 1") or style_name == "title":
        tag = "h1"
    elif style_name.startswith("heading 2") or style_name == "subtitle":
        tag = "h2"
    elif style_name.startswith("heading 3"):
        tag = "h3"
    if as_list_item:
        tag = "li"

    p_styles: List[str] = []
    if p.alignment in ALIGN_TO_CSS:
        p_styles.append(f"text-align: {ALIGN_TO_CSS[p.alignment]}")
    if _has_page_break(p):
        p_styles.append("page-break-before: always")

    runs_html: List[str] = []
    # Köprü (hyperlink) içindeki metinler de dahil olmak üzere tüm run'lar
    from docx.text.run import Run

    for r_el in p._element.iter(qn("w:r")):
        runs_html.append(_run_html(Run(r_el, p), p.part, ctx))

    content = "".join(runs_html)
    if not content:
        p_styles.append("-qt-paragraph-type: empty")
        content = "<br />"
    style_attr = f' style="{"; ".join(p_styles)}"' if p_styles else ""
    return f"<{tag}{style_attr}>{content}</{tag}>"


def _table_to_html(table, ctx) -> str:
    rows_html: List[str] = []
    for row in table.rows:
        cells_html = []
        for cell in row.cells:
            parts = []
            for p in cell.paragraphs:
                inner = _paragraph_to_html(p, ctx)
                inner = re.sub(r"^<(?:p|h\d)[^>]*>|</(?:p|h\d)>$", "", inner)
                parts.append(inner)
            cell_html = "<br/>".join(parts)
            cells_html.append(
                f'<td style="border: 1px solid #cbd5e1; padding: 6px;">{cell_html}</td>'
            )
        rows_html.append("<tr>" + "".join(cells_html) + "</tr>")
    return (
        '<table border="1" cellspacing="0" cellpadding="4" '
        'style="border-collapse: collapse; width: 100%;">'
        + "".join(rows_html)
        + "</table>"
    )


class _HtmlToDocxParser(HTMLParser):
    """QTextEdit HTML çıktısını python-docx yapısına dönüştürür."""

    def __init__(self, doc: Document) -> None:
        super().__init__()
        self.doc = doc
        self.current_para = None
        self.in_body = False
        self.has_body_tag = False
        self.in_style_or_head = False
        self.list_stack: List[str] = []
        self.in_table = False
        self.table_rows: List[List[str]] = []
        self.current_row: List[str] = []
        self.current_cell_parts: List[str] = []
        self.in_cell = False
        self.empty_para = False

        # Biçimlendirme yığını
        self.fmt_stack: List[Dict[str, object]] = [
            {
                "bold": False,
                "italic": False,
                "underline": False,
                "strike": False,
                "font_name": None,
                "font_size": None,
                "color": None,
                "superscript": False,
                "subscript": False,
            }
        ]

    def _current_fmt(self) -> Dict[str, object]:
        return self.fmt_stack[-1]

    def _push_fmt(self, updates: Dict[str, object]) -> None:
        merged = dict(self._current_fmt())
        merged.update(updates)
        self.fmt_stack.append(merged)

    def _pop_fmt(self) -> None:
        if len(self.fmt_stack) > 1:
            self.fmt_stack.pop()

    def handle_starttag(self, tag: str, attrs: List[Tuple[str, Optional[str]]]) -> None:
        t = tag.lower()
        attr_map = {k.lower(): (v or "") for k, v in attrs}
        styles = _parse_style_attr(attr_map.get("style", ""))

        if t in ("head", "style", "title"):
            self.in_style_or_head = True
            return
        if t == "body":
            self.in_body = True
            self.has_body_tag = True
            return
        if self.in_style_or_head:
            return

        if t == "table":
            self.in_table = True
            self.table_rows = []
            self.current_para = None
            return
        if self.in_table:
            if t == "tr":
                self.current_row = []
            elif t in ("td", "th"):
                self.in_cell = True
                self.current_cell_parts = []
            elif t == "br" and self.in_cell:
                self.current_cell_parts.append("\n")
            return

        if t in ("ul", "ol"):
            self.list_stack.append(t)
            return

        if t == "img":
            self._add_image(attr_map)
            return

        if t in ("p", "h1", "h2", "h3", "div", "li"):
            if t in ("h1", "h2", "h3"):
                try:
                    self.current_para = self.doc.add_heading("", level=int(t[1]))
                except KeyError:
                    self.current_para = self.doc.add_paragraph()
            elif t == "li":
                list_style = (
                    "List Number"
                    if self.list_stack and self.list_stack[-1] == "ol"
                    else "List Bullet"
                )
                try:
                    self.current_para = self.doc.add_paragraph(style=list_style)
                except KeyError:
                    self.current_para = self.doc.add_paragraph()
                    self.current_para.add_run("• " if list_style == "List Bullet" else "")
            else:
                self.current_para = self.doc.add_paragraph()

            align_val = styles.get("text-align", attr_map.get("align", "")).lower()
            if align_val in CSS_TO_ALIGN:
                self.current_para.alignment = CSS_TO_ALIGN[align_val]
            self.empty_para = styles.get("-qt-paragraph-type", "") == "empty"
            if "always" in styles.get("page-break-before", "").lower():
                self.current_para.paragraph_format.page_break_before = True

        if t == "br" and self.current_para is not None:
            if not self.empty_para:
                self.current_para.add_run().add_break()
            return

        updates: Dict[str, object] = {}
        if t in ("b", "strong"):
            updates["bold"] = True
        if t in ("i", "em"):
            updates["italic"] = True
        if t == "u":
            updates["underline"] = True
        if t in ("s", "strike", "del"):
            updates["strike"] = True
        if t == "sup":
            updates["superscript"] = True
        if t == "sub":
            updates["subscript"] = True
        va = styles.get("vertical-align", "").lower()
        if va == "super":
            updates["superscript"] = True
        elif va == "sub":
            updates["subscript"] = True

        fw = styles.get("font-weight", "").lower()
        if fw in ("bold", "600", "700", "800", "900"):
            updates["bold"] = True
        if styles.get("font-style", "").lower() == "italic":
            updates["italic"] = True
        if "underline" in styles.get("text-decoration", "").lower():
            updates["underline"] = True
        if "line-through" in styles.get("text-decoration", "").lower():
            updates["strike"] = True
        if "font-family" in styles:
            fn = styles["font-family"].split(",")[0].strip(" '\"")
            if fn:
                updates["font_name"] = fn
        if "font-size" in styles:
            pt = _parse_font_size_pt(styles["font-size"])
            if pt:
                updates["font_size"] = pt
        if "color" in styles:
            rgb = _parse_hex_color(styles["color"])
            if rgb:
                updates["color"] = rgb

        self._push_fmt(updates)

    def handle_endtag(self, tag: str) -> None:
        t = tag.lower()
        if t in ("head", "style", "title"):
            self.in_style_or_head = False
            return
        if t == "body":
            self.in_body = False
            return
        if self.in_style_or_head:
            return

        if t == "table" and self.in_table:
            self._flush_table()
            self.in_table = False
            return
        if self.in_table:
            if t in ("td", "th") and self.in_cell:
                self.current_row.append("".join(self.current_cell_parts).strip())
                self.in_cell = False
            elif t == "tr":
                if self.current_row:
                    self.table_rows.append(self.current_row)
            return

        if t in ("ul", "ol") and self.list_stack:
            self.list_stack.pop()
            return

        if t in ("p", "h1", "h2", "h3", "div", "li"):
            self.current_para = None

        self._pop_fmt()

    def handle_data(self, data: str) -> None:
        if self.in_style_or_head:
            return
        if self.has_body_tag and not self.in_body:
            return
        if self.in_table:
            if self.in_cell:
                self.current_cell_parts.append(data)
            return

        if not data or (data.isspace() and "\n" in data and self.current_para is None):
            return

        if self.current_para is None:
            self.current_para = self.doc.add_paragraph()

        run = self.current_para.add_run(data)
        fmt = self._current_fmt()
        if fmt["bold"]:
            run.bold = True
        if fmt["italic"]:
            run.italic = True
        if fmt["underline"]:
            run.underline = True
        if fmt["strike"]:
            run.font.strike = True
        if fmt["font_name"]:
            run.font.name = str(fmt["font_name"])
        if fmt["font_size"]:
            run.font.size = Pt(float(fmt["font_size"]))
        if fmt["color"]:
            r, g, b = fmt["color"]  # type: ignore[misc]
            run.font.color.rgb = RGBColor(r, g, b)
        if fmt.get("superscript"):
            run.font.superscript = True
        if fmt.get("subscript"):
            run.font.subscript = True

    def _add_image(self, attr_map: Dict[str, str]) -> None:
        src = attr_map.get("src", "")
        stream = _open_image_source(src)
        if stream is None:
            return
        if self.current_para is None:
            self.current_para = self.doc.add_paragraph()
        width = height = None
        try:
            w = int(float(attr_map.get("width", "") or 0))
            h = int(float(attr_map.get("height", "") or 0))
            if w > 0:
                width = Emu(w * _EMU_PER_PX)
            if h > 0:
                height = Emu(h * _EMU_PER_PX)
        except ValueError:
            pass
        try:
            self.current_para.add_run().add_picture(stream, width=width, height=height)
        except Exception:
            # Tanınmayan resim biçimi: belgeyi bozmadan atla
            pass

    def _flush_table(self) -> None:
        if not self.table_rows:
            return
        max_cols = max(len(r) for r in self.table_rows)
        if max_cols == 0:
            return
        table = self.doc.add_table(rows=len(self.table_rows), cols=max_cols)
        try:
            table.style = "Table Grid"
        except KeyError:
            pass
        for r_idx, row_vals in enumerate(self.table_rows):
            for c_idx in range(max_cols):
                val = row_vals[c_idx] if c_idx < len(row_vals) else ""
                table.cell(r_idx, c_idx).text = val


def _open_image_source(src: str) -> Optional[io.BytesIO]:
    """<img src> değerini (dosya yolu, file:// adresi veya data: URI) okur."""
    if not src:
        return None
    try:
        if src.startswith("data:"):
            header, _, payload = src.partition(",")
            data = base64.b64decode(payload) if ";base64" in header else unquote(payload).encode("latin-1")
            return io.BytesIO(data)
        path = src
        if src.startswith("file:"):
            parsed = urlparse(src)
            path = unquote(parsed.path)
            if os.name == "nt" and re.match(r"^/[A-Za-z]:", path):
                path = path[1:]
        if os.path.isfile(path):
            with open(path, "rb") as f:
                return io.BytesIO(f.read())
    except (OSError, ValueError):
        return None
    return None


def _clear_body(doc) -> None:
    """Gövdeyi boşaltır; son bölüm ayarlarını (sayfa yapısı, üst/altbilgi) bırakır."""
    body = doc.element.body
    for child in list(body):
        if child.tag != qn("w:sectPr"):
            body.remove(child)


def _drop_unused_images(doc) -> None:
    """Gövdeden çıkan resimlerin artık kullanılmayan ilişkilerini temizler."""
    xml = doc.element.xml
    part = doc.part
    for r_id, rel in list(part.rels.items()):
        if rel.reltype == _IMAGE_REL and f'"{r_id}"' not in xml:
            try:
                part.drop_rel(r_id)
            except Exception:
                pass


def save_html_to_docx(html_content: str, filepath: str, source_path: Optional[str] = None) -> None:
    """Zengin HTML içeriğini biçimlendirmeleri koruyarak .docx dosyasına kaydeder.

    ``source_path`` mevcut bir .docx ise o belge temel alınır: üstbilgi, altbilgi,
    sayfa yapısı, stiller ve numaralandırma tanımları korunur.
    """
    if source_path and os.path.isfile(source_path):
        doc = Document(source_path)
        _clear_body(doc)
    else:
        doc = Document()
    parser = _HtmlToDocxParser(doc)
    parser.feed(html_content)
    parser.close()
    if not doc.paragraphs and not doc.tables:
        doc.add_paragraph("")
    # sectPr gövdenin son öğesi olmalı
    body = doc.element.body
    sect = body.find(qn("w:sectPr"))
    if sect is not None and body[-1] is not sect:
        body.remove(sect)
        body.append(sect)
    if source_path:
        _drop_unused_images(doc)
    tmp_path = filepath + ".efos-tmp"
    try:
        doc.save(tmp_path)
        os.replace(tmp_path, filepath)
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
