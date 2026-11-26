"""EFOS-Ofis PowerPoint (.pptx) okuma ve yazma motoru.

Kaydetme ilkesi: açılmış bir sunu kaydedilirken orijinal dosya temel alınır.
Değiştirilmeyen slaytlara hiç dokunulmaz (grafik, resim, tablo, şekil, animasyon
ve geçişler olduğu gibi kalır). Değiştirilen slaytlarda yalnızca başlık, içerik
ve not metni, mevcut yazı biçimi korunarak güncellenir. Silinen slaytlar
çıkarılır, sıra değişikliği uygulanır, yeni slaytlar sunu temasıyla eklenir.
"""

from __future__ import annotations

import copy
import os
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from PIL import Image
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Inches, Pt


SLIDE_THEMES: Dict[str, Dict[str, str]] = {
    "Modern Açık": {
        "bg_color": "#FFFFFF",
        "text_color": "#0F172A",
        "accent_color": "#2563EB",
    },
    "Kurumsal Lacivert": {
        "bg_color": "#0F172A",
        "text_color": "#F8FAFC",
        "accent_color": "#38BDF8",
    },
    "Orman Yeşili": {
        "bg_color": "#F0FDF4",
        "text_color": "#14532D",
        "accent_color": "#16A34A",
    },
    "Sıcak Bordo": {
        "bg_color": "#FFFBEB",
        "text_color": "#451A03",
        "accent_color": "#B91C1C",
    },
    "Koyu Antrasit": {
        "bg_color": "#18181B",
        "text_color": "#F4F4F5",
        "accent_color": "#F59E0B",
    },
}


@dataclass
class SlideData:
    title: str = "Yeni Slayt"
    body: str = ""
    right_body: str = ""
    notes: str = ""
    layout: str = "content"  # "title", "content", "two_col"
    bg_color: str = "#FFFFFF"
    text_color: str = "#0F172A"
    accent_color: str = "#2563EB"
    image_path: str = ""
    # Dosyadan yüklendiyse: orijinal slayt sırası ve metin şekillerinin kimlikleri
    source_index: int = -1
    title_shape_id: int = -1
    body_shape_ids: List[int] = field(default_factory=list)
    orig_state: Optional[Tuple[Any, ...]] = None

    def state(self) -> Tuple[Any, ...]:
        return (
            self.title,
            self.body,
            self.right_body,
            self.notes,
            self.layout,
            self.bg_color.upper(),
            self.image_path,
        )

    def is_changed(self) -> bool:
        return self.orig_state is None or self.state() != self.orig_state


def _hex_to_rgb(hex_color: str, fallback: Tuple[int, int, int] = (15, 23, 42)) -> RGBColor:
    c = (hex_color or "").strip().lstrip("#")
    if len(c) == 6:
        try:
            return RGBColor(int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16))
        except ValueError:
            pass
    return RGBColor(*fallback)


def _rgb_to_hex(rgb: Optional[RGBColor], default: str) -> str:
    if rgb is None:
        return default
    try:
        return f"#{rgb}"
    except Exception:
        return default


def _text_shapes(slide) -> List[Any]:
    """Slayttaki metin içeren şekiller (gruplar dahil değil), görsel sırasıyla."""
    shapes = []
    for shape in slide.shapes:
        if not getattr(shape, "has_text_frame", False) or not shape.has_text_frame:
            continue
        shapes.append(shape)
    return shapes


def _is_footer_like(shape, slide_height: int) -> bool:
    txt = shape.text_frame.text.strip()
    try:
        ph_type = shape.placeholder_format.type if shape.is_placeholder else None
    except ValueError:
        ph_type = None
    if ph_type is not None and int(ph_type) in (13, 15, 16):  # slayt no, altbilgi, tarih
        return True
    top = shape.top if shape.top is not None else 0
    return txt.isdigit() and len(txt) <= 3 and top > slide_height * 0.85


def load_pptx(filepath: str) -> List[SlideData]:
    """Bir .pptx dosyasındaki tüm slaytları düzenlenebilir SlideData listesine yükler."""
    prs = Presentation(filepath)
    slides: List[SlideData] = []
    slide_h = int(prs.slide_height or Inches(7.5))

    for idx, slide in enumerate(prs.slides):
        title_text = ""
        title_id = -1
        body_blocks: List[str] = []
        body_ids: List[int] = []

        title_shape = slide.shapes.title
        if title_shape is not None and title_shape.has_text_frame:
            title_text = title_shape.text_frame.text.strip()
            title_id = title_shape.shape_id

        for shape in _text_shapes(slide):
            if title_shape is not None and shape.shape_id == title_shape.shape_id:
                continue
            txt = shape.text_frame.text.strip()
            if not txt or _is_footer_like(shape, slide_h):
                continue
            if not title_text and title_id < 0 and idx == 0:
                title_text = txt
                title_id = shape.shape_id
            else:
                body_blocks.append(shape.text_frame.text.strip("\n"))
                body_ids.append(shape.shape_id)

        layout = "content"
        body = ""
        right_body = ""
        if len(body_blocks) >= 2:
            layout = "two_col"
            body = body_blocks[0]
            right_body = "\n\n".join(body_blocks[1:])
        elif len(body_blocks) == 1:
            body = body_blocks[0]
            if idx == 0 and len(body.splitlines()) <= 2:
                layout = "title"
        elif idx == 0:
            layout = "title"

        notes = ""
        if slide.has_notes_slide and slide.notes_slide.notes_text_frame is not None:
            notes = slide.notes_slide.notes_text_frame.text.strip()

        bg_hex = "#FFFFFF"
        try:
            fill = slide.background.fill
            if fill and fill.type is not None and fill.fore_color and fill.fore_color.rgb:
                bg_hex = _rgb_to_hex(fill.fore_color.rgb, "#FFFFFF")
        except Exception:
            pass

        # Arka plan koyuysa yazı rengini açık seç
        r, g, b = int(bg_hex[1:3], 16), int(bg_hex[3:5], 16), int(bg_hex[5:7], 16)
        luminance = 0.299 * r + 0.587 * g + 0.114 * b
        text_hex = "#F8FAFC" if luminance < 128 else "#0F172A"
        accent_hex = "#38BDF8" if luminance < 128 else "#2563EB"

        sd = SlideData(
            title=title_text or f"Slayt {idx + 1}",
            body=body,
            right_body=right_body,
            notes=notes,
            layout=layout,
            bg_color=bg_hex,
            text_color=text_hex,
            accent_color=accent_hex,
            source_index=idx,
            title_shape_id=title_id,
            body_shape_ids=body_ids,
        )
        sd.orig_state = sd.state()
        slides.append(sd)

    return slides or [SlideData(title="Sunum Başlığı", body="Alt başlık veya tarih", layout="title")]


def unsupported_parts(filepath: str) -> List[str]:
    """Bilgi amaçlı: sunudaki düzenleyicide görünmeyen ama korunan öğeler."""
    found: List[str] = []
    try:
        prs = Presentation(filepath)
    except Exception:
        return found
    kinds = set()
    for slide in prs.slides:
        for shape in slide.shapes:
            if getattr(shape, "has_chart", False) and shape.has_chart:
                kinds.add("grafik")
            elif getattr(shape, "has_table", False) and shape.has_table:
                kinds.add("tablo")
            elif shape.shape_type is not None and int(shape.shape_type) == 13:
                kinds.add("resim")
            elif shape.shape_type is not None and int(shape.shape_type) == 6:
                kinds.add("şekil grubu")
    return sorted(kinds)


def _add_lines_to_frame(
    text_frame,
    text: str,
    font_size: int,
    color: RGBColor,
    bold: bool = False,
    align: PP_ALIGN = PP_ALIGN.LEFT,
) -> None:
    text_frame.word_wrap = True
    lines = text.splitlines() if text else [""]
    for i, line in enumerate(lines):
        p = text_frame.paragraphs[0] if i == 0 else text_frame.add_paragraph()
        p.alignment = align
        p.space_after = Pt(8)
        run = p.add_run()
        run.text = line
        run.font.name = "Calibri"
        run.font.size = Pt(font_size)
        run.font.bold = bold
        run.font.color.rgb = color


def _set_text_keep_format(text_frame, text: str) -> None:
    """Metni değiştirir; ilk run'ın yazı tipi/rengi/boyutu ve paragraf düzeyi korunur."""
    lines = text.split("\n") if text else [""]
    paras = list(text_frame.paragraphs)
    while len(paras) < len(lines):
        new_p = copy.deepcopy(paras[-1]._p)
        paras[-1]._p.addnext(new_p)
        paras = list(text_frame.paragraphs)
    for p, line in zip(paras, lines):
        for el in list(p._p):
            if el.tag in (qn("a:br"), qn("a:fld")):
                p._p.remove(el)
        runs = p.runs
        if runs:
            runs[0].text = line
            for r in runs[1:]:
                r._r.getparent().remove(r._r)
        elif line:
            p.add_run().text = line
    tx_body = text_frame._txBody
    for p in paras[len(lines):]:
        tx_body.remove(p._p)


def _shape_by_id(slide, shape_id: int):
    if shape_id < 0:
        return None
    for shape in slide.shapes:
        if shape.shape_id == shape_id:
            return shape
    return None


class _Geom:
    """13,333 x 7,5 inç tasarım ölçülerini sununun gerçek boyutuna ölçekler."""

    def __init__(self, prs) -> None:
        self.sx = (prs.slide_width or Inches(13.333)) / Inches(13.333)
        self.sy = (prs.slide_height or Inches(7.5)) / Inches(7.5)

    def x(self, inches: float) -> int:
        return int(Inches(inches) * self.sx)

    def y(self, inches: float) -> int:
        return int(Inches(inches) * self.sy)


def _blank_layout(prs):
    for layout in prs.slide_layouts:
        name = (layout.name or "").lower()
        if "blank" in name or "boş" in name:
            return layout
    return min(prs.slide_layouts, key=lambda lay: len(lay.placeholders))


def _build_new_slide(prs, sd: SlideData, number: int, layout=None) -> None:
    g = _Geom(prs)
    slide = prs.slides.add_slide(layout if layout is not None else _blank_layout(prs))
    for ph in list(slide.placeholders):
        ph._element.getparent().remove(ph._element)

    bg_rgb = _hex_to_rgb(sd.bg_color, (255, 255, 255))
    text_rgb = _hex_to_rgb(sd.text_color, (15, 23, 42))
    accent_rgb = _hex_to_rgb(sd.accent_color, (37, 99, 235))

    bg_fill = slide.background.fill
    bg_fill.solid()
    bg_fill.fore_color.rgb = bg_rgb

    bar = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, g.x(13.333), g.y(0.16))
    bar.fill.solid()
    bar.fill.fore_color.rgb = accent_rgb
    bar.line.fill.background()

    if sd.layout == "title":
        title_box = slide.shapes.add_textbox(g.x(1.0), g.y(2.2), g.x(11.333), g.y(1.8))
        _add_lines_to_frame(title_box.text_frame, sd.title, font_size=42, color=text_rgb, bold=True, align=PP_ALIGN.CENTER)
        if sd.body:
            sub_box = slide.shapes.add_textbox(g.x(1.5), g.y(4.1), g.x(10.333), g.y(1.5))
            _add_lines_to_frame(sub_box.text_frame, sd.body, font_size=22, color=accent_rgb, align=PP_ALIGN.CENTER)
    else:
        title_box = slide.shapes.add_textbox(g.x(0.8), g.y(0.45), g.x(11.7), g.y(0.95))
        _add_lines_to_frame(title_box.text_frame, sd.title, font_size=30, color=accent_rgb, bold=True)

        has_img = bool(sd.image_path and os.path.isfile(sd.image_path))
        if sd.layout == "two_col":
            left_box = slide.shapes.add_textbox(g.x(0.8), g.y(1.6), g.x(5.6), g.y(5.1))
            _add_lines_to_frame(left_box.text_frame, sd.body, font_size=18, color=text_rgb)
            right_box = slide.shapes.add_textbox(g.x(6.8), g.y(1.6), g.x(5.7), g.y(5.1))
            _add_lines_to_frame(right_box.text_frame, sd.right_body, font_size=18, color=text_rgb)
        elif has_img:
            body_box = slide.shapes.add_textbox(g.x(0.8), g.y(1.6), g.x(6.2), g.y(5.1))
            _add_lines_to_frame(body_box.text_frame, sd.body, font_size=19, color=text_rgb)
            _add_fitted_image(slide, sd.image_path, g.x(7.3), g.y(1.6), g.x(5.2), g.y(4.9))
        else:
            body_box = slide.shapes.add_textbox(g.x(0.8), g.y(1.6), g.x(11.7), g.y(5.1))
            _add_lines_to_frame(body_box.text_frame, sd.body, font_size=20, color=text_rgb)

    num_box = slide.shapes.add_textbox(g.x(12.2), g.y(6.95), g.x(0.8), g.y(0.4))
    _add_lines_to_frame(num_box.text_frame, str(number), font_size=12, color=accent_rgb, align=PP_ALIGN.RIGHT)

    if sd.notes:
        slide.notes_slide.notes_text_frame.text = sd.notes


def _update_existing_slide(prs, slide, sd: SlideData) -> None:
    """Orijinal slayttaki yalnızca değişen metinleri/arka planı günceller."""
    g = _Geom(prs)
    orig = sd.orig_state or ()
    text_rgb = _hex_to_rgb(sd.text_color, (15, 23, 42))
    accent_rgb = _hex_to_rgb(sd.accent_color, (37, 99, 235))

    # Başlık
    if not orig or sd.title != orig[0]:
        shape = _shape_by_id(slide, sd.title_shape_id)
        if shape is not None and shape.has_text_frame:
            _set_text_keep_format(shape.text_frame, sd.title)
        elif sd.title:
            box = slide.shapes.add_textbox(g.x(0.8), g.y(0.45), g.x(11.7), g.y(0.95))
            _add_lines_to_frame(box.text_frame, sd.title, font_size=30, color=accent_rgb, bold=True)

    # İçerik
    if not orig or sd.body != orig[1] or sd.right_body != orig[2]:
        ids = list(sd.body_shape_ids)
        if len(ids) <= 2:
            targets = [sd.body, sd.right_body][: max(len(ids), 1)]
        else:
            parts = sd.right_body.split("\n\n")
            if len(parts) == len(ids) - 1:
                targets = [sd.body] + parts
            else:
                targets = [sd.body, sd.right_body] + [""] * (len(ids) - 2)
        for i, new_text in enumerate(targets):
            shape = _shape_by_id(slide, ids[i]) if i < len(ids) else None
            if shape is not None and shape.has_text_frame:
                if shape.text_frame.text.strip("\n") != new_text:
                    _set_text_keep_format(shape.text_frame, new_text)
            elif new_text:
                box = slide.shapes.add_textbox(g.x(0.8), g.y(1.6), g.x(11.7), g.y(5.1))
                _add_lines_to_frame(box.text_frame, new_text, font_size=20, color=text_rgb)
        if len(ids) == 1 and sd.right_body and (not orig or sd.right_body != orig[2]):
            box = slide.shapes.add_textbox(g.x(6.8), g.y(1.6), g.x(5.7), g.y(5.1))
            _add_lines_to_frame(box.text_frame, sd.right_body, font_size=18, color=text_rgb)

    # Notlar
    if not orig or sd.notes != orig[3]:
        if sd.notes or slide.has_notes_slide:
            frame = slide.notes_slide.notes_text_frame
            if frame is not None:
                _set_text_keep_format(frame, sd.notes)

    # Arka plan
    if not orig or sd.bg_color.upper() != orig[5]:
        fill = slide.background.fill
        fill.solid()
        fill.fore_color.rgb = _hex_to_rgb(sd.bg_color, (255, 255, 255))

    # Yeni eklenen görsel
    if sd.image_path and (not orig or sd.image_path != orig[6]) and os.path.isfile(sd.image_path):
        _add_fitted_image(slide, sd.image_path, g.x(7.3), g.y(1.6), g.x(5.2), g.y(4.9))


_R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
_CHART_RT = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart"
_NOTES_RT = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide"
_LAYOUT_RT = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"


def _remap_rids(element, mapping: Dict[str, str]) -> None:
    for el in element.iter():
        for key, val in list(el.attrib.items()):
            if key.startswith("{" + _R_NS + "}") and val in mapping:
                el.set(key, mapping[val])


def _clone_chart_part(old_part, package):
    """Grafik parçasını (ve gömülü çalışma kitabını) bağımsız bir kopya olarak çoğaltır."""
    from pptx.opc.package import Part
    from pptx.parts.chart import ChartPart

    partname = package.next_partname("/ppt/charts/chart%d.xml")
    new_part = ChartPart.load(partname, old_part.content_type, package, old_part.blob)
    mapping: Dict[str, str] = {}
    for r_id, rel in old_part.rels.items():
        if rel.is_external:
            mapping[r_id] = new_part.relate_to(rel.target_ref, rel.reltype, is_external=True)
            continue
        target = rel.target_part
        ext = os.path.splitext(str(target.partname))[1] or ".bin"
        tmpl = os.path.dirname(str(target.partname)) + "/" + os.path.basename(str(target.partname)).rstrip("0123456789" + ext) + "%d" + ext
        try:
            new_name = package.next_partname(tmpl)
            copied = Part(new_name, target.content_type, package, target.blob)
            mapping[r_id] = new_part.relate_to(copied, rel.reltype)
        except Exception:
            mapping[r_id] = new_part.relate_to(target, rel.reltype)
    _remap_rids(new_part._element, mapping)
    return new_part


def _duplicate_slide(prs, src_slide):
    """Bir slaydı tüm şekilleri ve ilişkileriyle (resim, grafik) kopyalar."""
    new_slide = prs.slides.add_slide(src_slide.slide_layout)
    sp_tree = new_slide.shapes._spTree
    for ph in list(new_slide.placeholders):
        ph._element.getparent().remove(ph._element)
    mapping: Dict[str, str] = {}
    for r_id, rel in src_slide.part.rels.items():
        if rel.reltype in (_NOTES_RT, _LAYOUT_RT):
            continue
        if rel.is_external:
            mapping[r_id] = new_slide.part.relate_to(rel.target_ref, rel.reltype, is_external=True)
        elif rel.reltype == _CHART_RT:
            mapping[r_id] = new_slide.part.relate_to(_clone_chart_part(rel.target_part, prs.part.package), rel.reltype)
        else:
            mapping[r_id] = new_slide.part.relate_to(rel.target_part, rel.reltype)
    for child in list(src_slide.shapes._spTree)[2:]:
        new_el = copy.deepcopy(child)
        _remap_rids(new_el, mapping)
        sp_tree.append(new_el)
    src_bg = src_slide._element.cSld.bg
    if src_bg is not None:
        new_slide._element.cSld.insert(0, copy.deepcopy(src_bg))
    if src_slide.has_notes_slide and src_slide.notes_slide.notes_text_frame is not None:
        new_slide.notes_slide.notes_text_frame.text = src_slide.notes_slide.notes_text_frame.text
    return new_slide


def save_pptx(slides: List[SlideData], filepath: str, source_path: Optional[str] = None) -> None:
    """SlideData listesini .pptx sunusu olarak kaydeder.

    ``source_path`` mevcut bir sunu ise o dosya temel alınır ve yalnızca
    değişiklikler uygulanır; aksi halde 16:9 yeni bir sunu oluşturulur.
    """
    if source_path and os.path.isfile(source_path):
        prs = Presentation(source_path)
    else:
        prs = Presentation()
        prs.slide_width = Inches(13.333)
        prs.slide_height = Inches(7.5)
        source_path = None

    sld_id_lst = prs.slides._sldIdLst
    orig_ids = list(sld_id_lst)
    orig_slides = list(prs.slides)
    used: set = set()
    final_order = []
    new_layout = _blank_layout(prs) if source_path else prs.slide_layouts[6]

    for number, sd in enumerate(slides, start=1):
        src = sd.source_index if source_path else -1
        if 0 <= src < len(orig_slides) and src not in used:
            used.add(src)
            if sd.is_changed():
                _update_existing_slide(prs, orig_slides[src], sd)
            final_order.append(orig_ids[src])
        elif 0 <= src < len(orig_slides):
            dup = _duplicate_slide(prs, orig_slides[src])
            if sd.is_changed():
                # Kopyada şekil kimlikleri aynı kalır
                _update_existing_slide(prs, dup, sd)
            final_order.append(sld_id_lst[-1])
        else:
            _build_new_slide(prs, sd, number, new_layout)
            final_order.append(sld_id_lst[-1])

    # Silinen slaytları ilişkileriyle birlikte çıkar, sırayı uygula
    for idx, sld_id in enumerate(orig_ids):
        if idx not in used:
            prs.part.drop_rel(sld_id.rId)
    for sld_id in list(sld_id_lst):
        sld_id_lst.remove(sld_id)
    for sld_id in final_order:
        sld_id_lst.append(sld_id)

    tmp_path = filepath + ".efos-tmp"
    try:
        prs.save(tmp_path)
        os.replace(tmp_path, filepath)
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)


def _add_fitted_image(slide, img_path: str, left, top, max_w, max_h) -> None:
    """Görseli en-boy oranını koruyarak hedef kutuya yerleştirir."""
    try:
        with Image.open(img_path) as im:
            w, h = im.size
        if w <= 0 or h <= 0:
            return
        ratio = min(max_w / w, max_h / h)
        new_w = int(w * ratio)
        new_h = int(h * ratio)
        offset_x = int(left + (max_w - new_w) / 2)
        offset_y = int(top + (max_h - new_h) / 2)
        slide.shapes.add_picture(img_path, offset_x, offset_y, width=new_w, height=new_h)
    except Exception:
        pass
