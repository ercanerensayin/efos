"""EFOS-Ofis Dahili PDF Okuyucu ve PDF Birleştirme/Ayırma Araçları sekmesi."""

from __future__ import annotations

import os
from typing import List, Optional, Tuple

from PyQt6.QtCore import Qt, pyqtSignal
from PyQt6.QtGui import QPixmap
from PyQt6.QtWidgets import (
    QApplication,
    QDialog,
    QFileDialog,
    QFormLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QMessageBox,
    QPushButton,
    QScrollArea,
    QSpinBox,
    QSplitter,
    QToolBar,
    QVBoxLayout,
    QWidget,
)

from core.pdf_engine import PdfDocumentReader, merge_pdfs, split_pdf


class PdfViewerTab(QWidget):
    """Bir .pdf dosyasını görüntüleyen, arayan, birleştiren ve sayfa ayıran sekme."""

    modified_changed = pyqtSignal(bool)
    status_message = pyqtSignal(str)

    def __init__(self, filepath: str, parent: Optional[QWidget] = None) -> None:
        super().__init__(parent)
        self.filepath = filepath
        self._is_modified = False
        self._zoom = 1.4
        self._rotation = 0
        self._current_page = 0
        self._search_results: List[Tuple[int, str]] = []
        self._reader = PdfDocumentReader(filepath)

        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 8)
        root.setSpacing(6)
        root.addWidget(self._build_toolbar())

        splitter = QSplitter(Qt.Orientation.Horizontal)

        side = QWidget()
        side_layout = QVBoxLayout(side)
        side_layout.setContentsMargins(0, 0, 0, 0)
        self.side_header = QLabel(f"Sayfalar ({self._reader.page_count})")
        side_layout.addWidget(self.side_header)
        self.page_list = QListWidget()
        self.page_list.setMaximumWidth(230)
        self.page_list.currentRowChanged.connect(self._on_sidebar_click)
        side_layout.addWidget(self.page_list, 1)
        splitter.addWidget(side)

        self.scroll_area = QScrollArea()
        self.scroll_area.setWidgetResizable(True)
        self.scroll_area.setAlignment(Qt.AlignmentFlag.AlignCenter)

        self.page_label = QLabel()
        self.page_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.scroll_area.setWidget(self.page_label)
        splitter.addWidget(self.scroll_area)

        splitter.setStretchFactor(0, 1)
        splitter.setStretchFactor(1, 5)
        root.addWidget(splitter, 1)

        self._populate_page_list()
        self._render_current_page()

    @property
    def is_modified(self) -> bool:
        return self._is_modified

    def display_title(self) -> str:
        return f"📕 {os.path.basename(self.filepath)}"

    def _build_toolbar(self) -> QToolBar:
        tb = QToolBar()
        tb.setMovable(False)

        prev_btn = QPushButton("◀ Önceki")
        prev_btn.clicked.connect(lambda: self.go_to_page(self._current_page - 1))
        tb.addWidget(prev_btn)

        self.page_spin = QSpinBox()
        self.page_spin.setRange(1, max(1, self._reader.page_count))
        self.page_spin.setValue(1)
        self.page_spin.valueChanged.connect(lambda v: self.go_to_page(v - 1))
        tb.addWidget(self.page_spin)

        tb.addWidget(QLabel(f" / {self._reader.page_count} "))

        next_btn = QPushButton("Sonraki ▶")
        next_btn.clicked.connect(lambda: self.go_to_page(self._current_page + 1))
        tb.addWidget(next_btn)

        tb.addSeparator()

        zoom_out = QPushButton("−")
        zoom_out.clicked.connect(lambda: self._change_zoom(-0.2))
        tb.addWidget(zoom_out)

        self.zoom_label = QLabel(" 100% ")
        tb.addWidget(self.zoom_label)

        zoom_in = QPushButton("+")
        zoom_in.clicked.connect(lambda: self._change_zoom(0.2))
        tb.addWidget(zoom_in)

        rot_btn = QPushButton("↻ Döndür")
        rot_btn.clicked.connect(self._rotate_90)
        tb.addWidget(rot_btn)

        tb.addSeparator()

        self.search_input = QLineEdit()
        self.search_input.setPlaceholderText("PDF içinde ara...")
        self.search_input.setMaximumWidth(180)
        self.search_input.returnPressed.connect(self._run_search)
        tb.addWidget(self.search_input)

        search_btn = QPushButton("Ara")
        search_btn.clicked.connect(self._run_search)
        tb.addWidget(search_btn)

        copy_btn = QPushButton("Metni Kopyala")
        copy_btn.clicked.connect(self._copy_page_text)
        tb.addWidget(copy_btn)

        split_btn = QPushButton("Sayfa Ayır")
        split_btn.clicked.connect(self._split_current_pdf)
        tb.addWidget(split_btn)

        merge_btn = QPushButton("PDF Birleştir")
        merge_btn.clicked.connect(self._merge_with_pdfs)
        tb.addWidget(merge_btn)

        return tb

    def _populate_page_list(self) -> None:
        self._search_results = []
        self.side_header.setText(f"Sayfalar ({self._reader.page_count})")
        self.page_list.blockSignals(True)
        self.page_list.clear()
        for i in range(self._reader.page_count):
            self.page_list.addItem(f"Sayfa {i + 1}")
        self.page_list.setCurrentRow(self._current_page)
        self.page_list.blockSignals(False)

    def _on_sidebar_click(self, row: int) -> None:
        if row < 0:
            return
        if self._search_results:
            if row < len(self._search_results):
                self.go_to_page(self._search_results[row][0])
        else:
            self.go_to_page(row)

    def go_to_page(self, page_index: int) -> None:
        if page_index < 0 or page_index >= self._reader.page_count:
            return
        self._current_page = page_index
        self.page_spin.blockSignals(True)
        self.page_spin.setValue(page_index + 1)
        self.page_spin.blockSignals(False)
        if not self._search_results:
            self.page_list.blockSignals(True)
            self.page_list.setCurrentRow(page_index)
            self.page_list.blockSignals(False)
        self._render_current_page()

    def _change_zoom(self, delta: float) -> None:
        self._zoom = max(0.5, min(3.5, self._zoom + delta))
        self.zoom_label.setText(f" {int((self._zoom / 1.4) * 100)}% ")
        self._render_current_page()

    def _rotate_90(self) -> None:
        self._rotation = (self._rotation + 90) % 360
        self._render_current_page()

    def _render_current_page(self) -> None:
        try:
            png_bytes = self._reader.render_page_png(
                self._current_page, scale=self._zoom, rotation=self._rotation
            )
            pixmap = QPixmap()
            pixmap.loadFromData(png_bytes, "PNG")
            self.page_label.setPixmap(pixmap)
            self.status_message.emit(
                f"PDF: {os.path.basename(self.filepath)} — Sayfa {self._current_page + 1}/{self._reader.page_count}"
            )
        except Exception as exc:
            QMessageBox.critical(self, "PDF Hatası", f"Sayfa görüntülenemedi:\n{exc}")

    def _run_search(self) -> None:
        query = self.search_input.text().strip()
        if not query:
            self._populate_page_list()
            return
        results = self._reader.search_text(query)
        if not results:
            QMessageBox.information(self, "Arama", f"'{query}' için eşleşme bulunamadı.")
            return
        self._search_results = results
        self.side_header.setText(f"Arama Sonuçları ({len(results)})")
        self.page_list.blockSignals(True)
        self.page_list.clear()
        for page_idx, snippet in results:
            self.page_list.addItem(f"S.{page_idx + 1}: {snippet[:42]}...")
        self.page_list.setCurrentRow(0)
        self.page_list.blockSignals(False)
        self.go_to_page(results[0][0])

    def _copy_page_text(self) -> None:
        text = self._reader.get_page_text(self._current_page)
        if not text:
            QMessageBox.information(self, "Metin", "Bu sayfada seçilebilir metin bulunamadı.")
            return
        QApplication.clipboard().setText(text)
        self.status_message.emit(f"Sayfa {self._current_page + 1} metni panoya kopyalandı.")

    def _split_current_pdf(self) -> None:
        dlg = QDialog(self)
        dlg.setWindowTitle("PDF Sayfa Ayır")
        form = QFormLayout(dlg)
        start_spin = QSpinBox()
        start_spin.setRange(1, self._reader.page_count)
        start_spin.setValue(self._current_page + 1)
        end_spin = QSpinBox()
        end_spin.setRange(1, self._reader.page_count)
        end_spin.setValue(self._current_page + 1)
        form.addRow("Başlangıç Sayfası:", start_spin)
        form.addRow("Bitiş Sayfası:", end_spin)
        ok_btn = QPushButton("Ayır ve Kaydet")
        ok_btn.setObjectName("primaryBtn")
        ok_btn.clicked.connect(dlg.accept)
        form.addRow(ok_btn)
        if dlg.exec() != QDialog.DialogCode.Accepted:
            return
        out_path, _ = QFileDialog.getSaveFileName(
            self, "Ayrılan PDF'yi Kaydet", "ayrilmis.pdf", "PDF Dosyası (*.pdf)"
        )
        if not out_path:
            return
        if not out_path.lower().endswith(".pdf"):
            out_path += ".pdf"
        try:
            count = split_pdf(self.filepath, out_path, start_spin.value(), end_spin.value())
            self.status_message.emit(f"{count} sayfa kaydedildi: {os.path.basename(out_path)}")
        except Exception as exc:
            QMessageBox.critical(self, "Hata", str(exc))

    def _merge_with_pdfs(self) -> None:
        others, _ = QFileDialog.getOpenFileNames(
            self, "Eklenecek PDF Dosyalarını Seçin", "", "PDF Dosyası (*.pdf)"
        )
        if not others:
            return
        out_path, _ = QFileDialog.getSaveFileName(
            self, "Birleştirilmiş PDF'yi Kaydet", "birlestirilmis.pdf", "PDF Dosyası (*.pdf)"
        )
        if not out_path:
            return
        if not out_path.lower().endswith(".pdf"):
            out_path += ".pdf"
        try:
            total = merge_pdfs([self.filepath, *others], out_path)
            self.status_message.emit(f"Toplam {total} sayfa birleştirildi: {os.path.basename(out_path)}")
        except Exception as exc:
            QMessageBox.critical(self, "Hata", str(exc))

    def closeEvent(self, event) -> None:
        self._reader.close()
        super().closeEvent(event)
