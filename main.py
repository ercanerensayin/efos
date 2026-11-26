"""EFOS-Ofis — Windows için Belge, Tablo, Sunu, PDF, Notlar ve Antigravity MCP Ofis Paketi."""

from __future__ import annotations

import json
import os
import sys
from typing import Optional

# "EFOS-Ofis.exe --mcp" veya klasör sürümündeki "efos-mcp.exe": arayüz açmadan
# MCP sunucusu olarak çalış (Antigravity için)
_EXE_NAME = os.path.basename(sys.executable).lower() if getattr(sys, "frozen", False) else ""
if __name__ == "__main__" and ("--mcp" in sys.argv[1:] or _EXE_NAME.startswith("efos-mcp")):
    from mcp_server.efos_mcp_server import serve_stdio

    serve_stdio()
    raise SystemExit(0)

from PyQt6.QtGui import QAction, QKeySequence
from PyQt6.QtWidgets import (
    QApplication,
    QDialog,
    QFileDialog,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QPushButton,
    QStatusBar,
    QTabWidget,
    QToolBar,
    QVBoxLayout,
)

from core.archive_engine import create_zip, extract_archive
from core.pdf_engine import merge_pdfs
from mcp_server.register_antigravity import (
    build_mcp_entry,
    default_allowed_dirs,
    get_antigravity_config_path,
    register_with_report,
)
from ui.doc_editor import DocumentEditorTab
from ui.notes_editor import NotesEditorTab
from ui.pdf_viewer import PdfViewerTab
from ui.sheet_editor import SpreadsheetEditorTab
from ui.slide_editor import SlideEditorTab
from ui.theme import DARK_THEME, LIGHT_THEME


APP_VERSION = "2.2.0"


class _AntigravityDialog(QDialog):
    """EFOS-Ofis MCP sunucusunu Google Antigravity'ye tek tıkla bağlar."""

    def __init__(self, parent: Optional[QMainWindow] = None) -> None:
        super().__init__(parent)
        self.setWindowTitle("EFOS-Ofis — Google Antigravity (MCP) Bağlantısı")
        self.resize(620, 420)

        layout = QVBoxLayout(self)
        cfg_path = get_antigravity_config_path()
        info = QLabel(
            "EFOS-Ofis, standart Model Context Protocol (MCP) sunucusu içerir.\n"
            "Antigravity IDE / Desktop App / CLI ajanları bu sunucu üzerinden yerel\n"
            "Excel (.xlsx/.csv), Word (.docx), PowerPoint (.pptx), PDF, ZIP/arşiv,\n"
            "Markdown ve görsel dosyalarını doğrudan okuyup yazabilir (23 ofis aracı).\n"
            "Güvenlik için yalnızca aşağıda izin verdiğiniz klasörlerde çalışır; üzerine\n"
            "yazılan dosyaların önce .efos-yedek kopyası alınır.\n\n"
            f"Hedef yapılandırma dosyası: {cfg_path}"
        )
        info.setWordWrap(True)
        layout.addWidget(info)

        dirs_row = QHBoxLayout()
        dirs_row.addWidget(QLabel("İzinli klasörler:"))
        self.dirs_input = QLineEdit(os.pathsep.join(default_allowed_dirs()))
        self.dirs_input.setToolTip(
            "Antigravity ajanları yalnızca bu klasörlerdeki dosyaları okuyup yazabilir. "
            f"Birden fazla klasörü '{os.pathsep}' ile ayırın."
        )
        self.dirs_input.textChanged.connect(self._refresh_preview)
        dirs_row.addWidget(self.dirs_input, 1)
        add_dir_btn = QPushButton("Klasör Ekle…")
        add_dir_btn.clicked.connect(self._add_dir)
        dirs_row.addWidget(add_dir_btn)
        layout.addLayout(dirs_row)

        self.preview = QPlainTextEdit()
        self.preview.setReadOnly(True)
        layout.addWidget(self.preview, 1)
        self._refresh_preview()

        connect_btn = QPushButton("Antigravity Yapılandırmasına Otomatik Kaydet")
        connect_btn.setObjectName("primaryBtn")
        connect_btn.clicked.connect(self._register)
        layout.addWidget(connect_btn)

    def _dirs(self) -> list:
        return [d.strip() for d in self.dirs_input.text().split(os.pathsep) if d.strip()]

    def _refresh_preview(self) -> None:
        sample_cfg = {"mcpServers": {"efos-ofis": build_mcp_entry(allowed_dirs=self._dirs() or None)}}
        self.preview.setPlainText(json.dumps(sample_cfg, ensure_ascii=False, indent=2))

    def _add_dir(self) -> None:
        folder = QFileDialog.getExistingDirectory(self, "MCP için izinli klasör seç")
        if folder:
            dirs = self._dirs()
            if folder not in dirs:
                dirs.append(folder)
            self.dirs_input.setText(os.pathsep.join(dirs))

    def _register(self) -> None:
        dirs = self._dirs()
        missing = [d for d in dirs if not os.path.isdir(d)]
        if not dirs or missing:
            QMessageBox.warning(
                self, "Klasör bulunamadı", "Geçerli en az bir klasör girin.\n" + "\n".join(missing)
            )
            return
        try:
            report = register_with_report(allowed_dirs=dirs)
        except Exception as exc:  # noqa: BLE001
            QMessageBox.critical(self, "Bağlantı Hatası", f"Kayıt yapılamadı:\n{exc}")
            return
        extra = ""
        if report["broken_backup"]:
            extra = (
                "\n\nUyarı: Eski yapılandırma dosyası bozuktu ve okunamadı. "
                f"Yedeği alındı:\n{report['broken_backup']}\n"
                "Oradaki diğer MCP sunucularını bu yedekten geri ekleyebilirsiniz."
            )
        elif report["other_servers"]:
            extra = "\n\nKorunan diğer sunucular: " + ", ".join(report["other_servers"])
        QMessageBox.information(
            self,
            "Antigravity Bağlantısı Hazır",
            f"EFOS-Ofis MCP sunucusu kaydedildi:\n{report['config_path']}\n\n"
            f"Komut: {report['entry']['command']}{extra}\n\n"
            "Değişikliklerin aktif olması için Antigravity uygulamasını yeniden başlatın.",
        )
        self.accept()


class EfosOfisWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("EFOS-Ofis — Belge, Tablo, Sunu, PDF ve Antigravity")
        self.resize(1300, 820)
        self._dark_mode = False

        self.tabs = QTabWidget()
        self.tabs.setTabsClosable(True)
        self.tabs.setMovable(True)
        self.tabs.tabCloseRequested.connect(self.close_tab)
        self.setCentralWidget(self.tabs)

        self.status_bar = QStatusBar()
        self.setStatusBar(self.status_bar)

        self._build_menu_and_toolbar()
        self.apply_theme(dark=False)

        self.new_document()
        self.new_spreadsheet()
        self.new_presentation()
        self.tabs.setCurrentIndex(0)
        self.status_bar.showMessage(
            "EFOS-Ofis hazır. Word, Excel, PowerPoint, PDF, Markdown ve Antigravity MCP desteklenir."
        )

    def _build_menu_and_toolbar(self) -> None:
        menubar = self.menuBar()
        file_menu = menubar.addMenu("Dosya")

        act_new_doc = QAction("Yeni Belge (.docx)", self)
        act_new_doc.setShortcut(QKeySequence("Ctrl+N"))
        act_new_doc.triggered.connect(lambda: self.new_document())
        file_menu.addAction(act_new_doc)

        act_new_sheet = QAction("Yeni Tablo (.xlsx)", self)
        act_new_sheet.setShortcut(QKeySequence("Ctrl+Shift+N"))
        act_new_sheet.triggered.connect(lambda: self.new_spreadsheet())
        file_menu.addAction(act_new_sheet)

        act_new_slide = QAction("Yeni Sunu (.pptx)", self)
        act_new_slide.setShortcut(QKeySequence("Ctrl+Alt+N"))
        act_new_slide.triggered.connect(lambda: self.new_presentation())
        file_menu.addAction(act_new_slide)

        act_new_note = QAction("Yeni Not / Markdown (.md)", self)
        act_new_note.triggered.connect(lambda: self.new_note())
        file_menu.addAction(act_new_note)

        act_open = QAction("Dosya Aç...", self)
        act_open.setShortcut(QKeySequence.StandardKey.Open)
        act_open.triggered.connect(self.open_file)
        file_menu.addAction(act_open)

        file_menu.addSeparator()

        act_save = QAction("Kaydet", self)
        act_save.setShortcut(QKeySequence.StandardKey.Save)
        act_save.triggered.connect(lambda: self.save_current(False))
        file_menu.addAction(act_save)

        act_save_as = QAction("Farklı Kaydet...", self)
        act_save_as.setShortcut(QKeySequence("Ctrl+Shift+S"))
        act_save_as.triggered.connect(lambda: self.save_current(True))
        file_menu.addAction(act_save_as)

        file_menu.addSeparator()

        act_exit = QAction("Çıkış", self)
        act_exit.setShortcut(QKeySequence("Alt+F4"))
        act_exit.triggered.connect(self.close)
        file_menu.addAction(act_exit)

        tools_menu = menubar.addMenu("Araçlar")
        act_merge_pdf = QAction("PDF Dosyalarını Birleştir...", self)
        act_merge_pdf.triggered.connect(self.merge_pdf_tool)
        tools_menu.addAction(act_merge_pdf)

        act_zip_extract = QAction("ZIP / Arşiv Ayıkla...", self)
        act_zip_extract.triggered.connect(self.extract_zip_tool)
        tools_menu.addAction(act_zip_extract)

        act_zip_create = QAction("ZIP Arşivi Oluştur...", self)
        act_zip_create.triggered.connect(self.create_zip_tool)
        tools_menu.addAction(act_zip_create)

        tools_menu.addSeparator()

        act_ag = QAction("Antigravity'ye Bağlan (MCP Kurulumu)...", self)
        act_ag.triggered.connect(self.open_antigravity_dialog)
        tools_menu.addAction(act_ag)

        view_menu = menubar.addMenu("Görünüm")
        act_theme = QAction("Açık / Koyu Tema Değiştir", self)
        act_theme.setShortcut(QKeySequence("Ctrl+T"))
        act_theme.triggered.connect(self.toggle_theme)
        view_menu.addAction(act_theme)

        act_present = QAction("Sunumu Başlat (F5)", self)
        act_present.setShortcut(QKeySequence("F5"))
        act_present.triggered.connect(self._start_active_slideshow)
        view_menu.addAction(act_present)

        help_menu = menubar.addMenu("Yardım")
        act_about = QAction("EFOS-Ofis Hakkında", self)
        act_about.triggered.connect(self._show_about)
        help_menu.addAction(act_about)

        top_bar = QToolBar("Ana Araç Çubuğu")
        top_bar.setMovable(False)
        self.addToolBar(top_bar)

        for label, cb in [
            ("+ Belge", lambda: self.new_document()),
            ("+ Tablo", lambda: self.new_spreadsheet()),
            ("+ Sunu", lambda: self.new_presentation()),
            ("+ Not", lambda: self.new_note()),
            ("📕 PDF Aç", self.open_pdf_dialog),
        ]:
            btn = QPushButton(label)
            btn.clicked.connect(cb)
            top_bar.addWidget(btn)

        top_bar.addSeparator()

        btn_open = QPushButton("Dosya Aç")
        btn_open.clicked.connect(self.open_file)
        top_bar.addWidget(btn_open)

        btn_save = QPushButton("Kaydet")
        btn_save.setObjectName("primaryBtn")
        btn_save.clicked.connect(lambda: self.save_current(False))
        top_bar.addWidget(btn_save)

        top_bar.addSeparator()

        btn_ag = QPushButton("⚡ Antigravity MCP")
        btn_ag.clicked.connect(self.open_antigravity_dialog)
        top_bar.addWidget(btn_ag)

        self.theme_btn = QPushButton("🌙 Koyu Tema")
        self.theme_btn.clicked.connect(self.toggle_theme)
        top_bar.addWidget(self.theme_btn)

    def _register_tab(self, widget) -> None:
        idx = self.tabs.addTab(widget, widget.display_title())
        self.tabs.setCurrentIndex(idx)
        widget.modified_changed.connect(lambda _=False, w=widget: self._refresh_tab_title(w))
        widget.status_message.connect(self.status_bar.showMessage)

    def _refresh_tab_title(self, widget) -> None:
        idx = self.tabs.indexOf(widget)
        if idx >= 0:
            self.tabs.setTabText(idx, widget.display_title())

    def new_document(self, filepath: Optional[str] = None) -> None:
        self._register_tab(DocumentEditorTab(filepath=filepath, parent=self))

    def new_spreadsheet(self, filepath: Optional[str] = None) -> None:
        self._register_tab(SpreadsheetEditorTab(filepath=filepath, parent=self))

    def new_presentation(self, filepath: Optional[str] = None) -> None:
        self._register_tab(SlideEditorTab(filepath=filepath, parent=self))

    def new_note(self, filepath: Optional[str] = None) -> None:
        self._register_tab(NotesEditorTab(filepath=filepath, parent=self))

    def open_pdf(self, filepath: str) -> None:
        self._register_tab(PdfViewerTab(filepath=filepath, parent=self))

    def open_pdf_dialog(self) -> None:
        filepath, _ = QFileDialog.getOpenFileName(
            self, "PDF Dosyası Aç", "", "PDF Dosyası (*.pdf)"
        )
        if filepath:
            try:
                self.open_pdf(filepath)
            except Exception as exc:
                QMessageBox.critical(self, "PDF Hatası", f"PDF açılamadı:\n{exc}")

    def merge_pdf_tool(self) -> None:
        files, _ = QFileDialog.getOpenFileNames(
            self, "Birleştirilecek PDF Dosyalarını Seçin", "", "PDF Dosyası (*.pdf)"
        )
        if len(files) < 2:
            return
        out, _ = QFileDialog.getSaveFileName(
            self, "Birleştirilmiş PDF'yi Kaydet", "birlestirilmis.pdf", "PDF Dosyası (*.pdf)"
        )
        if not out:
            return
        if not out.lower().endswith(".pdf"):
            out += ".pdf"
        try:
            total = merge_pdfs(files, out)
            self.status_bar.showMessage(f"{len(files)} PDF ({total} sayfa) birleştirildi: {os.path.basename(out)}")
            self.open_pdf(out)
        except Exception as exc:
            QMessageBox.critical(self, "Birleştirme Hatası", str(exc))

    def extract_zip_tool(self) -> None:
        archive, _ = QFileDialog.getOpenFileName(
            self,
            "Ayıklanacak Arşiv Dosyasını Seçin",
            "",
            "Arşiv Dosyaları (*.zip *.tar *.tar.gz *.tgz *.tar.bz2 *.tar.xz)",
        )
        if not archive:
            return
        out_dir = QFileDialog.getExistingDirectory(self, "Ayıklanacak Hedef Klasörü Seçin")
        if not out_dir:
            return
        try:
            res = extract_archive(archive, out_dir)
            self.status_bar.showMessage(
                f"Arşiv ayıklandı ({res['extracted_count']} dosya): {out_dir}"
            )
            QMessageBox.information(
                self,
                "Arşiv Ayıklandı",
                f"{res['extracted_count']} dosya başarıyla ayıklandı:\n{out_dir}",
            )
        except Exception as exc:
            QMessageBox.critical(self, "Arşiv Ayıklama Hatası", str(exc))

    def create_zip_tool(self) -> None:
        files, _ = QFileDialog.getOpenFileNames(
            self, "ZIP İçine Eklenecek Dosyaları Seçin", "", "Tüm Dosyalar (*.*)"
        )
        if not files:
            return
        out_zip, _ = QFileDialog.getSaveFileName(
            self, "ZIP Dosyasını Kaydet", "arsiv.zip", "ZIP Arşivi (*.zip)"
        )
        if not out_zip:
            return
        if not out_zip.lower().endswith(".zip"):
            out_zip += ".zip"
        try:
            res = create_zip(files, out_zip)
            self.status_bar.showMessage(
                f"ZIP oluşturuldu ({res['file_count']} dosya): {os.path.basename(out_zip)}"
            )
            QMessageBox.information(
                self,
                "ZIP Oluşturuldu",
                f"{res['file_count']} dosya sıkıştırıldı:\n{out_zip}",
            )
        except Exception as exc:
            QMessageBox.critical(self, "ZIP Oluşturma Hatası", str(exc))

    def open_antigravity_dialog(self) -> None:
        dlg = _AntigravityDialog(self)
        dlg.exec()

    def open_file(self) -> None:
        filepath, _ = QFileDialog.getOpenFileName(
            self,
            "Ofis Dosyası Aç",
            "",
            "Tüm EFOS-Ofis Dosyaları (*.docx *.xlsx *.xlsm *.csv *.pptx *.pdf *.md *.txt);;"
            "Word (*.docx);;Excel (*.xlsx *.xlsm *.csv);;PowerPoint (*.pptx);;PDF (*.pdf);;Notlar (*.md *.txt)",
        )
        if filepath:
            self.open_by_path(filepath)

    def open_by_path(self, filepath: str) -> None:
        ext = os.path.splitext(filepath)[1].lower()
        try:
            if ext == ".docx":
                self.new_document(filepath)
            elif ext in (".xlsx", ".xlsm", ".csv"):
                self.new_spreadsheet(filepath)
            elif ext == ".pptx":
                self.new_presentation(filepath)
            elif ext == ".pdf":
                self.open_pdf(filepath)
            elif ext in (".md", ".txt"):
                self.new_note(filepath)
            else:
                QMessageBox.warning(self, "Desteklenmeyen Format", f"Desteklenmeyen dosya türü: {ext}")
        except Exception as exc:
            QMessageBox.critical(self, "Açma Hatası", f"Dosya açılamadı:\n{exc}")

    def save_current(self, force_dialog: bool = False) -> None:
        w = self.tabs.currentWidget()
        if w is None or not hasattr(w, "save_file"):
            self.status_bar.showMessage("Bu sekme salt okunurdur.")
            return
        try:
            w.save_file(force_dialog=force_dialog)
            self._refresh_tab_title(w)
        except Exception as exc:
            QMessageBox.critical(self, "Kaydetme Hatası", f"Dosya kaydedilemedi:\n{exc}")

    def _start_active_slideshow(self) -> None:
        w = self.tabs.currentWidget()
        if isinstance(w, SlideEditorTab):
            w.start_slideshow()

    def close_tab(self, index: int) -> None:
        w = self.tabs.widget(index)
        if w is None:
            return
        if getattr(w, "is_modified", False):
            reply = QMessageBox.question(
                self,
                "Kaydedilmemiş Değişiklikler",
                f"'{self.tabs.tabText(index)}' kaydedilmedi. Kaydetmek ister misiniz?",
                QMessageBox.StandardButton.Yes
                | QMessageBox.StandardButton.No
                | QMessageBox.StandardButton.Cancel,
            )
            if reply == QMessageBox.StandardButton.Cancel:
                return
            if reply == QMessageBox.StandardButton.Yes:
                if not w.save_file(force_dialog=False):
                    return
        if isinstance(w, PdfViewerTab):
            w._reader.close()
        self.tabs.removeTab(index)

    def apply_theme(self, dark: bool) -> None:
        self._dark_mode = dark
        QApplication.instance().setStyleSheet(DARK_THEME if dark else LIGHT_THEME)
        self.theme_btn.setText("☀️ Açık Tema" if dark else "🌙 Koyu Tema")

    def toggle_theme(self) -> None:
        self.apply_theme(not self._dark_mode)

    def _show_about(self) -> None:
        QMessageBox.information(
            self,
            "EFOS-Ofis Hakkında",
            f"EFOS-Ofis v{APP_VERSION}\n\n"
            "• Belge Düzenleyici (.docx / PDF aktarma)\n"
            "• Tablo ve Hesaplama (.xlsx / CSV, Türkçe-İngilizce formüller)\n"
            "• Sunu Düzenleyici (.pptx ve F5 Tam Ekran Sunum)\n"
            "• Dahili PDF Okuyucu + Birleştirme/Ayırma\n"
            "• Markdown / Hızlı Notlar (.md / .txt)\n"
            "• Google Antigravity MCP Sunucusu (Excel/Word/PPTX/PDF okuma-yazma)",
        )

    def closeEvent(self, event) -> None:
        for i in range(self.tabs.count() - 1, -1, -1):
            w = self.tabs.widget(i)
            if getattr(w, "is_modified", False):
                self.tabs.setCurrentIndex(i)
                reply = QMessageBox.question(
                    self,
                    "Çıkış Onayı",
                    f"'{self.tabs.tabText(i)}' kaydedilmedi. Kapatmadan önce kaydetmek ister misiniz?",
                    QMessageBox.StandardButton.Yes
                    | QMessageBox.StandardButton.No
                    | QMessageBox.StandardButton.Cancel,
                )
                if reply == QMessageBox.StandardButton.Cancel:
                    event.ignore()
                    return
                if reply == QMessageBox.StandardButton.Yes:
                    if not w.save_file(force_dialog=False):
                        event.ignore()
                        return
            if isinstance(w, PdfViewerTab):
                w._reader.close()
        event.accept()


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("EFOS-Ofis")
    window = EfosOfisWindow()
    window.show()

    for arg in sys.argv[1:]:
        if os.path.isfile(arg):
            window.open_by_path(arg)

    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
