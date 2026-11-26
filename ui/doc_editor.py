"""MiniOfis Belge Düzenleyici (.docx ve PDF dışa aktarma)."""

from __future__ import annotations

import os
import tempfile
from typing import Optional

from PyQt6.QtCore import Qt, pyqtSignal
from PyQt6.QtGui import (
    QColor,
    QFont,
    QTextCharFormat,
    QTextCursor,
    QTextListFormat,
    QTextTableFormat,
)
from PyQt6.QtPrintSupport import QPrinter
from PyQt6.QtWidgets import (
    QColorDialog,
    QComboBox,
    QDialog,
    QFileDialog,
    QFontComboBox,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QSpinBox,
    QTextEdit,
    QToolBar,
    QVBoxLayout,
    QWidget,
)

from core.docx_engine import load_docx_to_html, save_html_to_docx, unsupported_parts
from ui.save_guard import confirm_lossy_overwrite


class _FindReplaceDialog(QDialog):
    def __init__(self, editor: QTextEdit, parent: Optional[QWidget] = None) -> None:
        super().__init__(parent)
        self.editor = editor
        self.setWindowTitle("Bul ve Değiştir")
        self.setMinimumWidth(360)

        layout = QFormLayout(self)
        self.find_input = QLineEdit()
        self.replace_input = QLineEdit()
        layout.addRow("Aranan:", self.find_input)
        layout.addRow("Yeni Metin:", self.replace_input)

        btn_row = QHBoxLayout()
        find_btn = QPushButton("Sonrakini Bul")
        replace_btn = QPushButton("Değiştir")
        replace_all_btn = QPushButton("Tümünü Değiştir")
        find_btn.clicked.connect(self.find_next)
        replace_btn.clicked.connect(self.replace_one)
        replace_all_btn.clicked.connect(self.replace_all)
        btn_row.addWidget(find_btn)
        btn_row.addWidget(replace_btn)
        btn_row.addWidget(replace_all_btn)
        layout.addRow(btn_row)

    def find_next(self) -> bool:
        term = self.find_input.text()
        if not term:
            return False
        found = self.editor.find(term)
        if not found:
            cursor = self.editor.textCursor()
            cursor.movePosition(QTextCursor.MoveOperation.Start)
            self.editor.setTextCursor(cursor)
            found = self.editor.find(term)
        if not found:
            QMessageBox.information(self, "Bul", f"'{term}' bulunamadı.")
        return found

    def replace_one(self) -> None:
        cursor = self.editor.textCursor()
        if cursor.hasSelection() and cursor.selectedText() == self.find_input.text():
            cursor.insertText(self.replace_input.text())
        self.find_next()

    def replace_all(self) -> None:
        term = self.find_input.text()
        if not term:
            return
        replacement = self.replace_input.text()
        cursor = self.editor.textCursor()
        cursor.beginEditBlock()
        cursor.movePosition(QTextCursor.MoveOperation.Start)
        self.editor.setTextCursor(cursor)
        count = 0
        while self.editor.find(term):
            self.editor.textCursor().insertText(replacement)
            count += 1
        cursor.endEditBlock()
        QMessageBox.information(self, "Değiştir", f"{count} adet eşleşme değiştirildi.")


class DocumentEditorTab(QWidget):
    """Tek bir .docx belgesini düzenleyen sekme bileşeni."""

    modified_changed = pyqtSignal(bool)
    status_message = pyqtSignal(str)

    def __init__(self, filepath: Optional[str] = None, parent: Optional[QWidget] = None) -> None:
        super().__init__(parent)
        self.filepath: Optional[str] = filepath
        self._is_modified: bool = False
        # Kaydederken temel alınacak orijinal .docx (üstbilgi, sayfa yapısı, stiller)
        self._source_path: Optional[str] = None
        self._lossy_parts: list = []
        self._image_dir = tempfile.mkdtemp(prefix="efos_belge_")

        layout = QVBoxLayout(self)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(6)

        self.toolbar = self._build_toolbar()
        layout.addWidget(self.toolbar)

        self.editor = QTextEdit()
        self.editor.setAcceptRichText(True)
        self.editor.setFont(QFont("Calibri", 11))
        self.editor.setPlaceholderText("Belgenizi buraya yazın...")
        layout.addWidget(self.editor, 1)

        footer = QHBoxLayout()
        self.stats_label = QLabel("0 kelime | 0 karakter")
        footer.addWidget(self.stats_label)
        footer.addStretch(1)
        layout.addLayout(footer)

        self.editor.textChanged.connect(self._on_text_changed)
        self.editor.cursorPositionChanged.connect(self._sync_toolbar_state)

        if filepath and os.path.exists(filepath):
            self.load_file(filepath)

    @property
    def is_modified(self) -> bool:
        return self._is_modified

    def display_title(self) -> str:
        base = os.path.basename(self.filepath) if self.filepath else "Yeni Belge.docx"
        return f"📄 {base}{'*' if self._is_modified else ''}"

    def _build_toolbar(self) -> QToolBar:
        tb = QToolBar()
        tb.setMovable(False)

        self.font_box = QFontComboBox()
        self.font_box.setCurrentFont(QFont("Calibri"))
        self.font_box.setMaximumWidth(160)
        self.font_box.currentFontChanged.connect(self._set_font_family)
        tb.addWidget(self.font_box)

        self.size_box = QComboBox()
        self.size_box.addItems(["9", "10", "11", "12", "14", "16", "18", "22", "26", "32"])
        self.size_box.setCurrentText("11")
        self.size_box.setEditable(True)
        self.size_box.setMaximumWidth(64)
        self.size_box.currentTextChanged.connect(self._set_font_size)
        tb.addWidget(self.size_box)

        tb.addSeparator()

        self.bold_btn = QPushButton("K")
        self.bold_btn.setCheckable(True)
        self.bold_btn.setToolTip("Kalın (Ctrl+B)")
        self.bold_btn.setStyleSheet("font-weight: bold;")
        self.bold_btn.clicked.connect(self.toggle_bold)
        tb.addWidget(self.bold_btn)

        self.italic_btn = QPushButton("İ")
        self.italic_btn.setCheckable(True)
        self.italic_btn.setToolTip("İtalik (Ctrl+I)")
        self.italic_btn.setStyleSheet("font-style: italic;")
        self.italic_btn.clicked.connect(self.toggle_italic)
        tb.addWidget(self.italic_btn)

        self.underline_btn = QPushButton("A")
        self.underline_btn.setCheckable(True)
        self.underline_btn.setToolTip("Altı Çizili (Ctrl+U)")
        self.underline_btn.setStyleSheet("text-decoration: underline;")
        self.underline_btn.clicked.connect(self.toggle_underline)
        tb.addWidget(self.underline_btn)

        color_btn = QPushButton("Renk")
        color_btn.setToolTip("Yazı Rengi")
        color_btn.clicked.connect(self.pick_text_color)
        tb.addWidget(color_btn)

        tb.addSeparator()

        for label, align in [
            ("Sol", Qt.AlignmentFlag.AlignLeft),
            ("Orta", Qt.AlignmentFlag.AlignCenter),
            ("Sağ", Qt.AlignmentFlag.AlignRight),
            ("Yasla", Qt.AlignmentFlag.AlignJustify),
        ]:
            btn = QPushButton(label)
            btn.clicked.connect(lambda _=False, a=align: self.editor.setAlignment(a))
            tb.addWidget(btn)

        tb.addSeparator()

        bullet_btn = QPushButton("• Liste")
        bullet_btn.clicked.connect(lambda: self._insert_list(QTextListFormat.Style.ListDisc))
        tb.addWidget(bullet_btn)

        num_btn = QPushButton("1. Liste")
        num_btn.clicked.connect(lambda: self._insert_list(QTextListFormat.Style.ListDecimal))
        tb.addWidget(num_btn)

        table_btn = QPushButton("+ Tablo")
        table_btn.clicked.connect(self.insert_table)
        tb.addWidget(table_btn)

        find_btn = QPushButton("Bul/Değiştir")
        find_btn.clicked.connect(self.open_find_replace)
        tb.addWidget(find_btn)

        pdf_btn = QPushButton("PDF Aktar")
        pdf_btn.setObjectName("primaryBtn")
        pdf_btn.clicked.connect(self.export_pdf)
        tb.addWidget(pdf_btn)

        return tb

    def _merge_format(self, fmt: QTextCharFormat) -> None:
        cursor = self.editor.textCursor()
        if not cursor.hasSelection():
            cursor.select(QTextCursor.SelectionType.WordUnderCursor)
        cursor.mergeCharFormat(fmt)
        self.editor.mergeCurrentCharFormat(fmt)

    def _set_font_family(self, font: QFont) -> None:
        fmt = QTextCharFormat()
        fmt.setFontFamily(font.family())
        self._merge_format(fmt)

    def _set_font_size(self, size_text: str) -> None:
        try:
            pt = float(size_text)
        except ValueError:
            return
        if pt <= 0:
            return
        fmt = QTextCharFormat()
        fmt.setFontPointSize(pt)
        self._merge_format(fmt)

    def toggle_bold(self) -> None:
        fmt = QTextCharFormat()
        weight = QFont.Weight.Bold if self.bold_btn.isChecked() else QFont.Weight.Normal
        fmt.setFontWeight(weight)
        self._merge_format(fmt)

    def toggle_italic(self) -> None:
        fmt = QTextCharFormat()
        fmt.setFontItalic(self.italic_btn.isChecked())
        self._merge_format(fmt)

    def toggle_underline(self) -> None:
        fmt = QTextCharFormat()
        fmt.setFontUnderline(self.underline_btn.isChecked())
        self._merge_format(fmt)

    def pick_text_color(self) -> None:
        color = QColorDialog.getColor(QColor("#0f172a"), self, "Yazı Rengi Seç")
        if color.isValid():
            fmt = QTextCharFormat()
            fmt.setForeground(color)
            self._merge_format(fmt)

    def _insert_list(self, style: QTextListFormat.Style) -> None:
        cursor = self.editor.textCursor()
        list_fmt = QTextListFormat()
        list_fmt.setStyle(style)
        cursor.createList(list_fmt)

    def insert_table(self) -> None:
        dlg = QDialog(self)
        dlg.setWindowTitle("Tablo Ekle")
        form = QFormLayout(dlg)
        rows_spin = QSpinBox()
        rows_spin.setRange(1, 50)
        rows_spin.setValue(3)
        cols_spin = QSpinBox()
        cols_spin.setRange(1, 20)
        cols_spin.setValue(3)
        form.addRow("Satır Sayısı:", rows_spin)
        form.addRow("Sütun Sayısı:", cols_spin)

        ok_btn = QPushButton("Ekle")
        ok_btn.setObjectName("primaryBtn")
        ok_btn.clicked.connect(dlg.accept)
        form.addRow(ok_btn)

        if dlg.exec() == QDialog.DialogCode.Accepted:
            fmt = QTextTableFormat()
            fmt.setBorder(1)
            fmt.setCellPadding(6)
            fmt.setCellSpacing(0)
            self.editor.textCursor().insertTable(rows_spin.value(), cols_spin.value(), fmt)

    def open_find_replace(self) -> None:
        dlg = _FindReplaceDialog(self.editor, self)
        dlg.exec()

    def _sync_toolbar_state(self) -> None:
        fmt = self.editor.currentCharFormat()
        self.bold_btn.setChecked(fmt.fontWeight() >= QFont.Weight.Bold)
        self.italic_btn.setChecked(fmt.fontItalic())
        self.underline_btn.setChecked(fmt.fontUnderline())

    def _on_text_changed(self) -> None:
        plain = self.editor.toPlainText()
        words = len(plain.split()) if plain.strip() else 0
        self.stats_label.setText(f"{words} kelime | {len(plain)} karakter")
        if not self._is_modified:
            self._is_modified = True
            self.modified_changed.emit(True)

    def load_file(self, filepath: str) -> None:
        html_content = load_docx_to_html(filepath, image_dir=self._image_dir)
        self._source_path = filepath
        self._lossy_parts = unsupported_parts(filepath)
        self.editor.blockSignals(True)
        self.editor.setHtml(html_content)
        self.editor.blockSignals(False)
        self.filepath = filepath
        self._is_modified = False
        self._on_text_changed()
        self._is_modified = False
        self.modified_changed.emit(False)
        note = ""
        if self._lossy_parts:
            note = f" — dikkat: {', '.join(self._lossy_parts)} düzenleyicide korunamaz"
        self.status_message.emit(f"Belge açıldı: {os.path.basename(filepath)}{note}")

    def save_file(self, force_dialog: bool = False) -> bool:
        target = self.filepath
        if force_dialog or not target:
            target, _ = QFileDialog.getSaveFileName(
                self,
                "Belgeyi Kaydet",
                target or "Yeni Belge.docx",
                "Word Belgesi (*.docx)",
            )
            if not target:
                return False
            if not target.lower().endswith(".docx"):
                target += ".docx"
        decision = confirm_lossy_overwrite(self, target, self._source_path, self._lossy_parts)
        if decision == "cancel":
            return False
        if decision == "saveas":
            return self.save_file(force_dialog=True)
        try:
            save_html_to_docx(self.editor.toHtml(), target, source_path=self._source_path)
        except Exception as exc:  # noqa: BLE001
            QMessageBox.critical(self, "Kaydetme hatası", f"Belge kaydedilemedi:\n{exc}")
            return False
        self._source_path = target
        self._lossy_parts = unsupported_parts(target)
        self.filepath = target
        self._is_modified = False
        self.modified_changed.emit(False)
        self.status_message.emit(f"Belge kaydedildi: {os.path.basename(target)}")
        return True

    def export_pdf(self) -> None:
        default_name = (
            os.path.splitext(self.filepath)[0] + ".pdf"
            if self.filepath
            else "Belge.pdf"
        )
        target, _ = QFileDialog.getSaveFileName(
            self, "PDF Olarak Dışa Aktar", default_name, "PDF Dosyası (*.pdf)"
        )
        if not target:
            return
        if not target.lower().endswith(".pdf"):
            target += ".pdf"
        printer = QPrinter(QPrinter.PrinterMode.HighResolution)
        printer.setOutputFormat(QPrinter.OutputFormat.PdfFormat)
        printer.setOutputFileName(target)
        self.editor.document().print(printer)
        self.status_message.emit(f"PDF oluşturuldu: {os.path.basename(target)}")
