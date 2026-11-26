"""MiniOfis PowerPoint Benzeri Sunu Düzenleyici (.pptx ve Tam Ekran Sunum Modu)."""

from __future__ import annotations

import copy
import os
from typing import List, Optional

from PyQt6.QtCore import QRectF, Qt, pyqtSignal
from PyQt6.QtGui import QColor, QFont, QKeyEvent, QPainter, QPixmap
from PyQt6.QtWidgets import (
    QColorDialog,
    QComboBox,
    QDialog,
    QFileDialog,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QMessageBox,
    QPushButton,
    QSplitter,
    QTextEdit,
    QToolBar,
    QVBoxLayout,
    QWidget,
)

from core.pptx_engine import SLIDE_THEMES, SlideData, load_pptx, save_pptx


LAYOUT_LABELS = [
    ("Başlık Slaytı", "title"),
    ("Başlık + İçerik", "content"),
    ("İki Sütun", "two_col"),
]


class _SlideCanvas(QWidget):
    """16:9 oranında canlı slayt önizlemesi ve tam ekran sunum çizici."""

    def __init__(self, parent: Optional[QWidget] = None) -> None:
        super().__init__(parent)
        self._slide = SlideData()
        self._slide_number = 1
        self._pixmap_cache: Optional[QPixmap] = None
        self._cached_img_path = ""
        self.setMinimumSize(480, 270)

    def set_slide(self, slide: SlideData, slide_number: int) -> None:
        self._slide = slide
        self._slide_number = slide_number
        if slide.image_path != self._cached_img_path:
            self._cached_img_path = slide.image_path
            if slide.image_path and os.path.isfile(slide.image_path):
                self._pixmap_cache = QPixmap(slide.image_path)
            else:
                self._pixmap_cache = None
        self.update()

    def paintEvent(self, _event) -> None:
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.setRenderHint(QPainter.RenderHint.TextAntialiasing)

        # Arka plan çerçevesi (16:9 oranını koru)
        w, h = self.width(), self.height()
        target_w = w
        target_h = int(w * 9 / 16)
        if target_h > h:
            target_h = h
            target_w = int(h * 16 / 9)
        ox = (w - target_w) // 2
        oy = (h - target_h) // 2
        rect = QRectF(ox, oy, target_w, target_h)

        sd = self._slide
        bg = QColor(sd.bg_color or "#FFFFFF")
        fg = QColor(sd.text_color or "#0F172A")
        accent = QColor(sd.accent_color or "#2563EB")

        painter.fillRect(rect, bg)
        painter.setPen(QColor("#CBD5E1"))
        painter.drawRect(rect)

        scale = target_w / 960.0

        # Üst vurgu şeridi
        bar_h = max(4.0, 12.0 * scale)
        painter.fillRect(QRectF(ox, oy, target_w, bar_h), accent)

        pad_x = 56.0 * scale
        pad_y = 40.0 * scale

        if sd.layout == "title":
            title_font = QFont("Calibri", max(12, int(34 * scale)), QFont.Weight.Bold)
            painter.setFont(title_font)
            painter.setPen(fg)
            title_rect = QRectF(ox + pad_x, oy + target_h * 0.28, target_w - 2 * pad_x, target_h * 0.26)
            painter.drawText(
                title_rect,
                Qt.AlignmentFlag.AlignCenter | Qt.TextFlag.TextWordWrap,
                sd.title,
            )

            sub_font = QFont("Calibri", max(10, int(18 * scale)))
            painter.setFont(sub_font)
            painter.setPen(accent)
            sub_rect = QRectF(ox + pad_x * 1.5, oy + target_h * 0.56, target_w - 3 * pad_x, target_h * 0.22)
            painter.drawText(
                sub_rect,
                Qt.AlignmentFlag.AlignHCenter | Qt.AlignmentFlag.AlignTop | Qt.TextFlag.TextWordWrap,
                sd.body,
            )
        else:
            title_font = QFont("Calibri", max(11, int(24 * scale)), QFont.Weight.Bold)
            painter.setFont(title_font)
            painter.setPen(accent)
            title_rect = QRectF(ox + pad_x, oy + pad_y, target_w - 2 * pad_x, 64 * scale)
            painter.drawText(
                title_rect,
                Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter | Qt.TextFlag.TextWordWrap,
                sd.title,
            )

            body_top = oy + pad_y + 74 * scale
            body_h = target_h - (pad_y + 115 * scale)
            body_font = QFont("Calibri", max(9, int(15 * scale)))
            painter.setFont(body_font)
            painter.setPen(fg)

            if sd.layout == "two_col":
                col_w = (target_w - 2 * pad_x - 28 * scale) / 2
                left_rect = QRectF(ox + pad_x, body_top, col_w, body_h)
                right_rect = QRectF(ox + pad_x + col_w + 28 * scale, body_top, col_w, body_h)
                painter.drawText(left_rect, Qt.AlignmentFlag.AlignLeft | Qt.TextFlag.TextWordWrap, sd.body)
                painter.drawText(right_rect, Qt.AlignmentFlag.AlignLeft | Qt.TextFlag.TextWordWrap, sd.right_body)
            elif self._pixmap_cache and not self._pixmap_cache.isNull():
                left_w = (target_w - 2 * pad_x) * 0.54
                body_rect = QRectF(ox + pad_x, body_top, left_w, body_h)
                painter.drawText(body_rect, Qt.AlignmentFlag.AlignLeft | Qt.TextFlag.TextWordWrap, sd.body)

                img_x = ox + pad_x + left_w + 24 * scale
                img_w = (target_w - 2 * pad_x) * 0.43
                scaled = self._pixmap_cache.scaled(
                    int(img_w),
                    int(body_h),
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation,
                )
                draw_x = img_x + (img_w - scaled.width()) / 2
                draw_y = body_top + (body_h - scaled.height()) / 2
                painter.drawPixmap(int(draw_x), int(draw_y), scaled)
            else:
                body_rect = QRectF(ox + pad_x, body_top, target_w - 2 * pad_x, body_h)
                painter.drawText(body_rect, Qt.AlignmentFlag.AlignLeft | Qt.TextFlag.TextWordWrap, sd.body)

        # Slayt numarası
        num_font = QFont("Calibri", max(8, int(11 * scale)))
        painter.setFont(num_font)
        painter.setPen(accent)
        num_rect = QRectF(ox + target_w - 64 * scale, oy + target_h - 32 * scale, 48 * scale, 22 * scale)
        painter.drawText(num_rect, Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter, str(self._slide_number))


class _SlideshowDialog(QDialog):
    """Tam ekran sunum gösterim penceresi (Sağ/Sol/Boşluk/ESC)."""

    def __init__(self, slides: List[SlideData], start_index: int = 0, parent: Optional[QWidget] = None) -> None:
        super().__init__(parent)
        self.slides = slides
        self.index = max(0, min(start_index, len(slides) - 1))
        self.setWindowTitle("MiniOfis — Sunum Modu")
        self.setStyleSheet("background-color: #000000;")

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        self.canvas = _SlideCanvas(self)
        layout.addWidget(self.canvas, 1)

        self._show_current()
        self.showFullScreen()

    def _show_current(self) -> None:
        self.canvas.set_slide(self.slides[self.index], self.index + 1)

    def keyPressEvent(self, event: QKeyEvent) -> None:
        key = event.key()
        if key in (Qt.Key.Key_Right, Qt.Key.Key_Space, Qt.Key.Key_Down, Qt.Key.Key_Return):
            if self.index < len(self.slides) - 1:
                self.index += 1
                self._show_current()
            else:
                self.accept()
        elif key in (Qt.Key.Key_Left, Qt.Key.Key_Up, Qt.Key.Key_Backspace):
            if self.index > 0:
                self.index -= 1
                self._show_current()
        elif key == Qt.Key.Key_Escape:
            self.accept()
        else:
            super().keyPressEvent(event)


class SlideEditorTab(QWidget):
    """PowerPoint (.pptx) düzenleyici ve tam ekran sunum sekmesi."""

    modified_changed = pyqtSignal(bool)
    status_message = pyqtSignal(str)

    def __init__(self, filepath: Optional[str] = None, parent: Optional[QWidget] = None) -> None:
        super().__init__(parent)
        self.filepath: Optional[str] = filepath
        self._is_modified = False
        self._loading_ui = False
        # Kaydederken temel alınacak orijinal .pptx (grafik, resim, tema korunur)
        self._source_path: Optional[str] = None
        self.slides: List[SlideData] = [
            SlideData(
                title="Sunum Başlığı",
                body="Alt başlık veya sunucu adı",
                layout="title",
            )
        ]

        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 8)
        root.setSpacing(6)
        root.addWidget(self._build_toolbar())

        splitter = QSplitter(Qt.Orientation.Horizontal)

        # Sol: Slayt listesi
        left_panel = QWidget()
        left_layout = QVBoxLayout(left_panel)
        left_layout.setContentsMargins(0, 0, 0, 0)
        left_layout.addWidget(QLabel("Slaytlar"))
        self.slide_list = QListWidget()
        self.slide_list.setMaximumWidth(220)
        self.slide_list.currentRowChanged.connect(self._on_slide_selected)
        left_layout.addWidget(self.slide_list, 1)
        splitter.addWidget(left_panel)

        # Orta + Sağ: Önizleme ve düzenleme alanları
        right_splitter = QSplitter(Qt.Orientation.Vertical)

        self.canvas = _SlideCanvas()
        right_splitter.addWidget(self.canvas)

        editor_panel = QWidget()
        form = QFormLayout(editor_panel)
        form.setContentsMargins(6, 6, 6, 6)

        self.title_input = QLineEdit()
        self.title_input.textChanged.connect(self._on_fields_edited)
        form.addRow("Slayt Başlığı:", self.title_input)

        body_row = QHBoxLayout()
        self.body_input = QTextEdit()
        self.body_input.setPlaceholderText("Madde işaretleri veya içerik metni (her satır yeni paragraf)...")
        self.body_input.setMaximumHeight(120)
        self.body_input.textChanged.connect(self._on_fields_edited)
        body_row.addWidget(self.body_input, 1)

        self.right_body_input = QTextEdit()
        self.right_body_input.setPlaceholderText("Sağ sütun metni (İki Sütun düzeni için)...")
        self.right_body_input.setMaximumHeight(120)
        self.right_body_input.textChanged.connect(self._on_fields_edited)
        self.right_body_input.setVisible(False)
        body_row.addWidget(self.right_body_input, 1)
        form.addRow("İçerik:", body_row)

        self.notes_input = QLineEdit()
        self.notes_input.setPlaceholderText("Konuşmacı notları (slaytta görünmez)...")
        self.notes_input.textChanged.connect(self._on_fields_edited)
        form.addRow("Notlar:", self.notes_input)

        right_splitter.addWidget(editor_panel)
        right_splitter.setStretchFactor(0, 3)
        right_splitter.setStretchFactor(1, 2)

        splitter.addWidget(right_splitter)
        splitter.setStretchFactor(0, 1)
        splitter.setStretchFactor(1, 4)
        root.addWidget(splitter, 1)

        if filepath and os.path.exists(filepath):
            self.load_file(filepath)
        else:
            self._rebuild_slide_list(select_index=0)

    @property
    def is_modified(self) -> bool:
        return self._is_modified

    def display_title(self) -> str:
        base = os.path.basename(self.filepath) if self.filepath else "Yeni Sunu.pptx"
        return f"📽️ {base}{'*' if self._is_modified else ''}"

    def _build_toolbar(self) -> QToolBar:
        tb = QToolBar()
        tb.setMovable(False)

        add_btn = QPushButton("+ Slayt")
        add_btn.clicked.connect(self.add_slide)
        tb.addWidget(add_btn)

        dup_btn = QPushButton("Çoğalt")
        dup_btn.clicked.connect(self.duplicate_slide)
        tb.addWidget(dup_btn)

        del_btn = QPushButton("Sil")
        del_btn.clicked.connect(self.delete_slide)
        tb.addWidget(del_btn)

        up_btn = QPushButton("↑")
        up_btn.setToolTip("Slaytı Yukarı Taşı")
        up_btn.clicked.connect(lambda: self.move_slide(-1))
        tb.addWidget(up_btn)

        down_btn = QPushButton("↓")
        down_btn.setToolTip("Slaytı Aşağı Taşı")
        down_btn.clicked.connect(lambda: self.move_slide(1))
        tb.addWidget(down_btn)

        tb.addSeparator()

        self.layout_box = QComboBox()
        for label, code in LAYOUT_LABELS:
            self.layout_box.addItem(label, code)
        self.layout_box.currentIndexChanged.connect(self._on_layout_changed)
        tb.addWidget(self.layout_box)

        self.theme_box = QComboBox()
        self.theme_box.addItems(list(SLIDE_THEMES.keys()))
        self.theme_box.currentTextChanged.connect(self._apply_theme_preset)
        tb.addWidget(self.theme_box)

        bg_btn = QPushButton("Arka Plan")
        bg_btn.clicked.connect(self._pick_bg)
        tb.addWidget(bg_btn)

        img_btn = QPushButton("+ Görsel")
        img_btn.clicked.connect(self._pick_image)
        tb.addWidget(img_btn)

        clear_img_btn = QPushButton("Görseli Kaldır")
        clear_img_btn.clicked.connect(self._clear_image)
        tb.addWidget(clear_img_btn)

        tb.addSeparator()

        present_btn = QPushButton("▶ Sunumu Başlat (F5)")
        present_btn.setObjectName("primaryBtn")
        present_btn.clicked.connect(self.start_slideshow)
        tb.addWidget(present_btn)

        return tb

    def _current_index(self) -> int:
        idx = self.slide_list.currentRow()
        if idx < 0 or idx >= len(self.slides):
            return 0
        return idx

    def _rebuild_slide_list(self, select_index: int = 0) -> None:
        self.slide_list.blockSignals(True)
        self.slide_list.clear()
        for i, sd in enumerate(self.slides):
            short_title = (sd.title or "Başlıksız").strip()[:24]
            self.slide_list.addItem(f"{i + 1}. {short_title}")
        target = max(0, min(select_index, len(self.slides) - 1))
        self.slide_list.setCurrentRow(target)
        self.slide_list.blockSignals(False)
        self._on_slide_selected(target)

    def _on_slide_selected(self, row: int) -> None:
        if row < 0 or row >= len(self.slides):
            return
        self._loading_ui = True
        try:
            sd = self.slides[row]
            self.title_input.setText(sd.title)
            self.body_input.setPlainText(sd.body)
            self.right_body_input.setPlainText(sd.right_body)
            self.notes_input.setText(sd.notes)

            idx_layout = next((i for i, (_, code) in enumerate(LAYOUT_LABELS) if code == sd.layout), 1)
            self.layout_box.setCurrentIndex(idx_layout)
            self.right_body_input.setVisible(sd.layout == "two_col")
            self.canvas.set_slide(sd, row + 1)
        finally:
            self._loading_ui = False

    def _on_fields_edited(self) -> None:
        if self._loading_ui or not self.slides:
            return
        idx = self._current_index()
        sd = self.slides[idx]
        sd.title = self.title_input.text()
        sd.body = self.body_input.toPlainText()
        sd.right_body = self.right_body_input.toPlainText()
        sd.notes = self.notes_input.text()

        item = self.slide_list.item(idx)
        if item:
            item.setText(f"{idx + 1}. {(sd.title or 'Başlıksız')[:24]}")
        self.canvas.set_slide(sd, idx + 1)
        self._mark_modified()

    def _on_layout_changed(self) -> None:
        if self._loading_ui or not self.slides:
            return
        idx = self._current_index()
        code = self.layout_box.currentData()
        self.slides[idx].layout = code
        self.right_body_input.setVisible(code == "two_col")
        self.canvas.set_slide(self.slides[idx], idx + 1)
        self._mark_modified()

    def _apply_theme_preset(self, name: str) -> None:
        if self._loading_ui or name not in SLIDE_THEMES:
            return
        preset = SLIDE_THEMES[name]
        for sd in self.slides:
            sd.bg_color = preset["bg_color"]
            sd.text_color = preset["text_color"]
            sd.accent_color = preset["accent_color"]
        idx = self._current_index()
        self.canvas.set_slide(self.slides[idx], idx + 1)
        self._mark_modified()

    def _pick_bg(self) -> None:
        idx = self._current_index()
        sd = self.slides[idx]
        color = QColorDialog.getColor(QColor(sd.bg_color), self, "Slayt Arka Plan Rengi")
        if color.isValid():
            sd.bg_color = color.name()
            lum = 0.299 * color.red() + 0.587 * color.green() + 0.114 * color.blue()
            sd.text_color = "#F8FAFC" if lum < 128 else "#0F172A"
            self.canvas.set_slide(sd, idx + 1)
            self._mark_modified()

    def _pick_image(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self, "Slayta Görsel Ekle", "", "Görseller (*.png *.jpg *.jpeg *.bmp *.webp)"
        )
        if not path:
            return
        idx = self._current_index()
        self.slides[idx].image_path = path
        if self.slides[idx].layout == "title":
            self.slides[idx].layout = "content"
            self.layout_box.setCurrentIndex(1)
        self.canvas.set_slide(self.slides[idx], idx + 1)
        self._mark_modified()

    def _clear_image(self) -> None:
        idx = self._current_index()
        self.slides[idx].image_path = ""
        self.canvas.set_slide(self.slides[idx], idx + 1)
        self._mark_modified()

    def add_slide(self) -> None:
        cur = self.slides[self._current_index()]
        new_sd = SlideData(
            title=f"Slayt {len(self.slides) + 1}",
            body="• Birinci madde\n• İkinci madde",
            layout="content",
            bg_color=cur.bg_color,
            text_color=cur.text_color,
            accent_color=cur.accent_color,
        )
        insert_at = self._current_index() + 1
        self.slides.insert(insert_at, new_sd)
        self._rebuild_slide_list(select_index=insert_at)
        self._mark_modified()

    def duplicate_slide(self) -> None:
        idx = self._current_index()
        cloned = copy.deepcopy(self.slides[idx])
        self.slides.insert(idx + 1, cloned)
        self._rebuild_slide_list(select_index=idx + 1)
        self._mark_modified()

    def delete_slide(self) -> None:
        if len(self.slides) <= 1:
            QMessageBox.warning(self, "Uyarı", "Sunuda en az bir slayt bulunmalıdır.")
            return
        idx = self._current_index()
        self.slides.pop(idx)
        self._rebuild_slide_list(select_index=max(0, idx - 1))
        self._mark_modified()

    def move_slide(self, delta: int) -> None:
        idx = self._current_index()
        target = idx + delta
        if target < 0 or target >= len(self.slides):
            return
        self.slides[idx], self.slides[target] = self.slides[target], self.slides[idx]
        self._rebuild_slide_list(select_index=target)
        self._mark_modified()

    def start_slideshow(self) -> None:
        dlg = _SlideshowDialog(self.slides, start_index=self._current_index(), parent=self)
        dlg.exec()

    def _mark_modified(self) -> None:
        if not self._is_modified:
            self._is_modified = True
            self.modified_changed.emit(True)

    def load_file(self, filepath: str) -> None:
        self.slides = load_pptx(filepath)
        self.filepath = filepath
        self._source_path = filepath
        self._is_modified = False
        self._rebuild_slide_list(select_index=0)
        self.modified_changed.emit(False)
        self.status_message.emit(f"Sunu açıldı: {os.path.basename(filepath)} ({len(self.slides)} slayt)")

    def save_file(self, force_dialog: bool = False) -> bool:
        target = self.filepath
        if force_dialog or not target:
            target, _ = QFileDialog.getSaveFileName(
                self,
                "Sunuyu Kaydet",
                target or "Yeni Sunu.pptx",
                "PowerPoint Sunusu (*.pptx)",
            )
            if not target:
                return False
            if not target.lower().endswith(".pptx"):
                target += ".pptx"
        try:
            save_pptx(self.slides, target, source_path=self._source_path)
        except Exception as exc:  # noqa: BLE001
            QMessageBox.critical(self, "Kaydetme hatası", f"Sunu kaydedilemedi:\n{exc}")
            return False
        # Kaydedilen dosyayı yeni temel kabul et (slayt sırası/kimlikleri güncellenir)
        current = self._current_index()
        try:
            self.slides = load_pptx(target)
            self._source_path = target
            self._rebuild_slide_list(select_index=min(current, len(self.slides) - 1))
        except Exception:  # noqa: BLE001
            pass
        self.filepath = target
        self._is_modified = False
        self.modified_changed.emit(False)
        self.status_message.emit(f"Sunu kaydedildi: {os.path.basename(target)}")
        return True
