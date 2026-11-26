"""EFOS-Ofis Hızlı Notlar ve Markdown (.md / .txt) Düzenleyici sekmesi."""

from __future__ import annotations

import os
from typing import Optional

from PyQt6.QtCore import Qt, pyqtSignal
from PyQt6.QtWidgets import (
    QFileDialog,
    QLabel,
    QPlainTextEdit,
    QPushButton,
    QSplitter,
    QTextBrowser,
    QToolBar,
    QVBoxLayout,
    QWidget,
)


class NotesEditorTab(QWidget):
    """Canlı Markdown önizlemeli hızlı not ve metin düzenleyici."""

    modified_changed = pyqtSignal(bool)
    status_message = pyqtSignal(str)

    def __init__(self, filepath: Optional[str] = None, parent: Optional[QWidget] = None) -> None:
        super().__init__(parent)
        self.filepath = filepath
        self._is_modified = False

        root = QVBoxLayout(self)
        root.setContentsMargins(8, 8, 8, 8)
        root.setSpacing(6)

        tb = QToolBar()
        tb.setMovable(False)
        for label, snippet in [
            ("H1", "# Başlık\n"),
            ("Kalın", "**kalın metin**"),
            ("Liste", "- Madde 1\n- Madde 2\n"),
            ("Görev", "- [ ] Yapılacak iş\n"),
            ("Tablo", "| Sütun 1 | Sütun 2 |\n| --- | --- |\n| Değer 1 | Değer 2 |\n"),
        ]:
            btn = QPushButton(label)
            btn.clicked.connect(lambda _=False, s=snippet: self.editor.insertPlainText(s))
            tb.addWidget(btn)

        self.preview_btn = QPushButton("Önizlemeyi Gizle")
        self.preview_btn.setCheckable(True)
        self.preview_btn.clicked.connect(self._toggle_preview)
        tb.addWidget(self.preview_btn)
        root.addWidget(tb)

        splitter = QSplitter(Qt.Orientation.Horizontal)
        self.editor = QPlainTextEdit()
        self.editor.setPlaceholderText("# Toplantı Notu veya Hızlı Notlar\n\nMarkdown ile yazabilirsiniz...")
        self.editor.textChanged.connect(self._on_changed)
        splitter.addWidget(self.editor)

        self.preview = QTextBrowser()
        self.preview.setOpenExternalLinks(True)
        splitter.addWidget(self.preview)
        splitter.setStretchFactor(0, 1)
        splitter.setStretchFactor(1, 1)
        root.addWidget(splitter, 1)

        self.stats_label = QLabel("0 satır | 0 karakter")
        root.addWidget(self.stats_label)

        if filepath and os.path.isfile(filepath):
            self.load_file(filepath)

    @property
    def is_modified(self) -> bool:
        return self._is_modified

    def display_title(self) -> str:
        base = os.path.basename(self.filepath) if self.filepath else "Yeni Not.md"
        return f"📝 {base}{'*' if self._is_modified else ''}"

    def _toggle_preview(self) -> None:
        visible = not self.preview_btn.isChecked()
        self.preview.setVisible(visible)
        self.preview_btn.setText("Önizlemeyi Gizle" if visible else "Önizlemeyi Göster")

    def _on_changed(self) -> None:
        text = self.editor.toPlainText()
        self.preview.setMarkdown(text)
        lines = text.count("\n") + 1 if text else 0
        self.stats_label.setText(f"{lines} satır | {len(text)} karakter")
        if not self._is_modified:
            self._is_modified = True
            self.modified_changed.emit(True)

    def load_file(self, filepath: str) -> None:
        with open(filepath, "r", encoding="utf-8-sig") as f:
            content = f.read()
        self.editor.blockSignals(True)
        self.editor.setPlainText(content)
        self.editor.blockSignals(False)
        self.preview.setMarkdown(content)
        self.filepath = filepath
        self._is_modified = False
        self.modified_changed.emit(False)
        self.status_message.emit(f"Not açıldı: {os.path.basename(filepath)}")

    def save_file(self, force_dialog: bool = False) -> bool:
        target = self.filepath
        if force_dialog or not target:
            target, _ = QFileDialog.getSaveFileName(
                self,
                "Notu Kaydet",
                target or "Yeni Not.md",
                "Markdown (*.md);;Metin Dosyası (*.txt)",
            )
            if not target:
                return False
            if not target.lower().endswith((".md", ".txt")):
                target += ".md"
        with open(target, "w", encoding="utf-8") as f:
            f.write(self.editor.toPlainText())
        self.filepath = target
        self._is_modified = False
        self.modified_changed.emit(False)
        self.status_message.emit(f"Not kaydedildi: {os.path.basename(target)}")
        return True
