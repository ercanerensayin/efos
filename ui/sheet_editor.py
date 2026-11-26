"""MiniOfis Tablo ve Hesaplama Düzenleyici (.xlsx ve .csv)."""

from __future__ import annotations

import os
from typing import Dict, List, Optional, Tuple

from PyQt6.QtCore import Qt, pyqtSignal
from PyQt6.QtGui import QBrush, QColor, QFont
from PyQt6.QtWidgets import (
    QAbstractItemView,
    QColorDialog,
    QFileDialog,
    QHBoxLayout,
    QInputDialog,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QTabWidget,
    QToolBar,
    QVBoxLayout,
    QWidget,
)

from core.formula_engine import (
    FormulaEngine,
    format_cell_coord,
    format_display_tr,
    index_to_col_letters,
    literal_value,
    to_number,
)
from core.xlsx_engine import (
    CellData,
    SheetData,
    load_csv,
    load_xlsx,
    mark_saved,
    save_csv,
    save_xlsx,
    unsupported_parts,
)
from ui.save_guard import confirm_lossy_overwrite


class _SingleSheetView(QTableWidget):
    """Tek bir çalışma sayfası ızgarası ve canlı formül yönetimi."""

    data_changed = pyqtSignal()
    selection_summary = pyqtSignal(str)
    active_cell_changed = pyqtSignal(int, int, str)

    def __init__(self, sheet: SheetData, parent: Optional[QWidget] = None) -> None:
        super().__init__(sheet.rows, sheet.cols, parent)
        self.sheet_name = sheet.name
        self.source_title = sheet.source_title
        self.cell_store: Dict[Tuple[int, int], CellData] = dict(sheet.cells)
        self._updating = False

        self.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        self._refresh_headers()
        self.engine = FormulaEngine(
            self._get_raw_value, lambda: (self.rowCount(), self.columnCount())
        )

        self.cellChanged.connect(self._on_cell_edited)
        self.currentCellChanged.connect(self._on_current_cell_changed)
        self.itemSelectionChanged.connect(self._on_selection_changed)

        self.recalculate_all()

    def _refresh_headers(self) -> None:
        col_labels = [index_to_col_letters(c) for c in range(self.columnCount())]
        self.setHorizontalHeaderLabels(col_labels)
        self.setVerticalHeaderLabels([str(r + 1) for r in range(self.rowCount())])

    def _get_raw_value(self, row: int, col: int) -> str:
        cd = self.cell_store.get((row, col))
        return cd.raw if cd else ""

    def get_cell_data(self, row: int, col: int) -> CellData:
        if (row, col) not in self.cell_store:
            self.cell_store[(row, col)] = CellData()
        return self.cell_store[(row, col)]

    def set_raw_value(self, row: int, col: int, raw_text: str) -> None:
        cd = self.get_cell_data(row, col)
        cd.raw = raw_text
        self.recalculate_all()
        self.data_changed.emit()

    def _on_cell_edited(self, row: int, col: int) -> None:
        if self._updating:
            return
        item = self.item(row, col)
        raw_text = item.text() if item else ""
        cd = self.get_cell_data(row, col)
        cd.raw = raw_text
        self.recalculate_all()
        self.data_changed.emit()
        self.active_cell_changed.emit(row, col, cd.raw)

    def _on_current_cell_changed(
        self, cur_row: int, cur_col: int, _prev_r: int, _prev_c: int
    ) -> None:
        if cur_row < 0 or cur_col < 0:
            return
        raw = self._get_raw_value(cur_row, cur_col)
        self.active_cell_changed.emit(cur_row, cur_col, raw)

    def _on_selection_changed(self) -> None:
        nums: List[float] = []
        count_non_empty = 0
        for item in self.selectedItems():
            txt = item.text().strip()
            if txt:
                count_non_empty += 1
                n = to_number(txt)
                if n is not None:
                    nums.append(n)
        if nums:
            total = sum(nums)
            avg = total / len(nums)
            self.selection_summary.emit(
                f"Seçili: {count_non_empty} hücre | Toplam: {format_display_tr(total)} | "
                f"Ortalama: {format_display_tr(avg)} | Sayısal: {len(nums)}"
            )
        elif count_non_empty:
            self.selection_summary.emit(f"Seçili: {count_non_empty} dolu hücre")
        else:
            self.selection_summary.emit("Hazır")

    def recalculate_all(self) -> None:
        """Tüm hücreleri formül motoruyla yeniden hesaplar ve biçimleri uygular."""
        self._updating = True
        try:
            for (r, c), cd in list(self.cell_store.items()):
                if r >= self.rowCount() or c >= self.columnCount():
                    continue
                if cd.raw.startswith("="):
                    value = self.engine.evaluate_cell(r, c)
                    display = format_display_tr(value)
                else:
                    # Yazılan değer olduğu gibi görünür (Excel gibi); ' öneki gizlenir
                    value = literal_value(cd.raw)
                    display = cd.raw[1:] if cd.raw.startswith("'") else cd.raw
                numeric = not isinstance(value, (str, bool)) and value is not None
                item = self.item(r, c)
                if item is None:
                    item = QTableWidgetItem()
                    self.setItem(r, c, item)
                item.setText(display)
                self._apply_cell_style(item, cd, is_numeric=numeric)
        finally:
            self._updating = False

    def _apply_cell_style(
        self, item: QTableWidgetItem, cd: CellData, is_numeric: bool
    ) -> None:
        font = QFont("Calibri", 10)
        font.setBold(cd.bold)
        font.setItalic(cd.italic)
        item.setFont(font)

        if cd.align == "left":
            item.setTextAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)
        elif cd.align == "center":
            item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
        elif cd.align == "right":
            item.setTextAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        elif is_numeric:
            item.setTextAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        else:
            item.setTextAlignment(Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter)

        if cd.bg_color:
            item.setBackground(QBrush(QColor(cd.bg_color)))
        if cd.fg_color:
            item.setForeground(QBrush(QColor(cd.fg_color)))

    def apply_style_to_selection(self, **kwargs) -> None:
        ranges = self.selectedRanges()
        if not ranges:
            return
        for rng in ranges:
            for r in range(rng.topRow(), rng.bottomRow() + 1):
                for c in range(rng.leftColumn(), rng.rightColumn() + 1):
                    cd = self.get_cell_data(r, c)
                    if kwargs.get("toggle_bold"):
                        cd.bold = not cd.bold
                    if kwargs.get("toggle_italic"):
                        cd.italic = not cd.italic
                    if "align" in kwargs:
                        cd.align = kwargs["align"]
                    if "bg_color" in kwargs:
                        cd.bg_color = kwargs["bg_color"]
                    if "fg_color" in kwargs:
                        cd.fg_color = kwargs["fg_color"]
        self.recalculate_all()
        self.data_changed.emit()

    def add_row(self) -> None:
        self.insertRow(self.rowCount())
        self._refresh_headers()
        self.data_changed.emit()

    def add_column(self) -> None:
        self.insertColumn(self.columnCount())
        self._refresh_headers()
        self.data_changed.emit()

    def to_sheet_data(self) -> SheetData:
        clean_cells = {
            pos: cd
            for pos, cd in self.cell_store.items()
            # Dosyadan gelip silinen hücreler de gönderilir (dosyada da silinsin)
            if not cd.is_empty() or cd.was_loaded()
        }
        return SheetData(
            name=self.sheet_name,
            rows=self.rowCount(),
            cols=self.columnCount(),
            cells=clean_cells,
            source_title=self.source_title,
        )


class SpreadsheetEditorTab(QWidget):
    """Çoklu sayfa destekli .xlsx / .csv çalışma kitabı sekmesi."""

    modified_changed = pyqtSignal(bool)
    status_message = pyqtSignal(str)

    def __init__(self, filepath: Optional[str] = None, parent: Optional[QWidget] = None) -> None:
        super().__init__(parent)
        self.filepath: Optional[str] = filepath
        self._is_modified: bool = False
        # Kaydederken temel alınacak orijinal .xlsx (biçimler, birleşik hücreler...)
        self._source_path: Optional[str] = None
        self._lossy_parts: list = []

        layout = QVBoxLayout(self)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(6)

        layout.addWidget(self._build_toolbar())
        layout.addLayout(self._build_formula_bar())

        self.sheet_tabs = QTabWidget()
        self.sheet_tabs.setTabPosition(QTabWidget.TabPosition.South)
        self.sheet_tabs.setTabsClosable(True)
        self.sheet_tabs.tabCloseRequested.connect(self._close_sheet)
        layout.addWidget(self.sheet_tabs, 1)

        footer = QHBoxLayout()
        self.summary_label = QLabel("Hazır")
        footer.addWidget(self.summary_label)
        footer.addStretch(1)
        layout.addLayout(footer)

        if filepath and os.path.exists(filepath):
            self.load_file(filepath)
        else:
            self._add_sheet_widget(SheetData(name="Sayfa1"))

    @property
    def is_modified(self) -> bool:
        return self._is_modified

    def display_title(self) -> str:
        base = os.path.basename(self.filepath) if self.filepath else "Yeni Tablo.xlsx"
        return f"📊 {base}{'*' if self._is_modified else ''}"

    def _current_sheet(self) -> Optional[_SingleSheetView]:
        w = self.sheet_tabs.currentWidget()
        return w if isinstance(w, _SingleSheetView) else None

    def _build_toolbar(self) -> QToolBar:
        tb = QToolBar()
        tb.setMovable(False)

        bold_btn = QPushButton("K")
        bold_btn.setStyleSheet("font-weight: bold;")
        bold_btn.setToolTip("Seçili Hücreleri Kalın Yap")
        bold_btn.clicked.connect(lambda: self._style_sel(toggle_bold=True))
        tb.addWidget(bold_btn)

        italic_btn = QPushButton("İ")
        italic_btn.setStyleSheet("font-style: italic;")
        italic_btn.setToolTip("Seçili Hücreleri İtalik Yap")
        italic_btn.clicked.connect(lambda: self._style_sel(toggle_italic=True))
        tb.addWidget(italic_btn)

        tb.addSeparator()

        for label, align in [("Sol", "left"), ("Orta", "center"), ("Sağ", "right")]:
            btn = QPushButton(label)
            btn.clicked.connect(lambda _=False, a=align: self._style_sel(align=a))
            tb.addWidget(btn)

        bg_btn = QPushButton("Dolgu")
        bg_btn.clicked.connect(self._pick_bg_color)
        tb.addWidget(bg_btn)

        fg_btn = QPushButton("Yazı Rengi")
        fg_btn.clicked.connect(self._pick_fg_color)
        tb.addWidget(fg_btn)

        tb.addSeparator()

        sum_btn = QPushButton("Σ Otomatik Toplam")
        sum_btn.setToolTip("Seçili hücrenin üstündeki sayıları TOPLA formülüyle toplar")
        sum_btn.clicked.connect(self._auto_sum)
        tb.addWidget(sum_btn)

        add_row_btn = QPushButton("+ Satır")
        add_row_btn.clicked.connect(lambda: self._current_sheet() and self._current_sheet().add_row())
        tb.addWidget(add_row_btn)

        add_col_btn = QPushButton("+ Sütun")
        add_col_btn.clicked.connect(lambda: self._current_sheet() and self._current_sheet().add_column())
        tb.addWidget(add_col_btn)

        add_sheet_btn = QPushButton("+ Sayfa")
        add_sheet_btn.clicked.connect(self._new_sheet_dialog)
        tb.addWidget(add_sheet_btn)

        csv_btn = QPushButton("CSV Aktar")
        csv_btn.clicked.connect(self.export_csv)
        tb.addWidget(csv_btn)

        return tb

    def _build_formula_bar(self) -> QHBoxLayout:
        row = QHBoxLayout()
        self.coord_label = QLabel("A1")
        self.coord_label.setMinimumWidth(52)
        self.coord_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.coord_label.setStyleSheet(
            "font-weight: bold; border: 1px solid #cbd5e1; border-radius: 4px; padding: 4px;"
        )
        row.addWidget(self.coord_label)

        fx_label = QLabel("fx")
        fx_label.setStyleSheet("font-style: italic; font-weight: bold; padding: 0 4px;")
        row.addWidget(fx_label)

        self.formula_input = QLineEdit()
        self.formula_input.setPlaceholderText(
            "Değer veya formül (örn. =TOPLA(A1:A10), =YUVARLA(B2*1,18;2), =DÜŞEYARA(A2;D:E;2;0)). Metin için başa ' koyun."
        )
        self.formula_input.returnPressed.connect(self._commit_formula_bar)
        row.addWidget(self.formula_input, 1)

        apply_btn = QPushButton("Uygula")
        apply_btn.setObjectName("primaryBtn")
        apply_btn.clicked.connect(self._commit_formula_bar)
        row.addWidget(apply_btn)
        return row

    def _add_sheet_widget(self, sheet: SheetData) -> _SingleSheetView:
        view = _SingleSheetView(sheet, self)
        view.data_changed.connect(self._mark_modified)
        view.selection_summary.connect(self.summary_label.setText)
        view.active_cell_changed.connect(self._on_active_cell)
        self.sheet_tabs.addTab(view, sheet.name)
        return view

    def _on_active_cell(self, row: int, col: int, raw: str) -> None:
        self.coord_label.setText(format_cell_coord(row, col))
        self.formula_input.setText(raw)

    def _commit_formula_bar(self) -> None:
        sheet = self._current_sheet()
        if not sheet:
            return
        r, c = sheet.currentRow(), sheet.currentColumn()
        if r < 0 or c < 0:
            r, c = 0, 0
        sheet.set_raw_value(r, c, self.formula_input.text())

    def _style_sel(self, **kwargs) -> None:
        sheet = self._current_sheet()
        if sheet:
            sheet.apply_style_to_selection(**kwargs)

    def _pick_bg_color(self) -> None:
        color = QColorDialog.getColor(QColor("#fef08a"), self, "Hücre Dolgu Rengi")
        if color.isValid():
            self._style_sel(bg_color=color.name())

    def _pick_fg_color(self) -> None:
        color = QColorDialog.getColor(QColor("#0f172a"), self, "Hücre Yazı Rengi")
        if color.isValid():
            self._style_sel(fg_color=color.name())

    def _auto_sum(self) -> None:
        sheet = self._current_sheet()
        if not sheet:
            return
        r, c = sheet.currentRow(), sheet.currentColumn()
        if r <= 0 or c < 0:
            QMessageBox.information(
                self, "Otomatik Toplam", "Toplam yazmak için sayıların altındaki bir hücreyi seçin."
            )
            return
        start_r = r - 1
        while start_r > 0 and to_number(sheet._get_raw_value(start_r - 1, c)) is not None:
            start_r -= 1
        col_let = index_to_col_letters(c)
        formula = f"=TOPLA({col_let}{start_r + 1}:{col_let}{r})"
        sheet.set_raw_value(r, c, formula)
        self.formula_input.setText(formula)

    def _new_sheet_dialog(self) -> None:
        default_name = f"Sayfa{self.sheet_tabs.count() + 1}"
        name, ok = QInputDialog.getText(self, "Yeni Sayfa", "Sayfa adı:", text=default_name)
        if ok and name.strip():
            view = self._add_sheet_widget(SheetData(name=name.strip()))
            self.sheet_tabs.setCurrentWidget(view)
            self._mark_modified()

    def _close_sheet(self, index: int) -> None:
        if self.sheet_tabs.count() <= 1:
            QMessageBox.warning(self, "Uyarı", "Çalışma kitabında en az bir sayfa bulunmalıdır.")
            return
        self.sheet_tabs.removeTab(index)
        self._mark_modified()

    def _mark_modified(self) -> None:
        if not self._is_modified:
            self._is_modified = True
            self.modified_changed.emit(True)

    def load_file(self, filepath: str) -> None:
        self.sheet_tabs.clear()
        ext = os.path.splitext(filepath)[1].lower()
        sheets = load_csv(filepath) if ext == ".csv" else load_xlsx(filepath)
        self._source_path = filepath if ext in (".xlsx", ".xlsm") else None
        self._lossy_parts = unsupported_parts(filepath) if self._source_path else []
        for sh in sheets:
            self._add_sheet_widget(sh)
        self.filepath = filepath
        self._is_modified = False
        self.modified_changed.emit(False)
        note = ""
        if self._lossy_parts:
            note = f" — dikkat: {', '.join(self._lossy_parts)} kaydederken korunamaz"
        self.status_message.emit(f"Tablo açıldı: {os.path.basename(filepath)}{note}")

    def save_file(self, force_dialog: bool = False) -> bool:
        target = self.filepath
        if force_dialog or not target:
            target, _ = QFileDialog.getSaveFileName(
                self,
                "Tabloyu Kaydet",
                target or "Yeni Tablo.xlsx",
                "Excel Çalışma Kitabı (*.xlsx);;Makrolu Çalışma Kitabı (*.xlsm);;CSV Dosyası (*.csv)",
            )
            if not target:
                return False
            if not target.lower().endswith((".xlsx", ".xlsm", ".csv")):
                target += ".xlsx"

        all_sheets: List[SheetData] = []
        for idx in range(self.sheet_tabs.count()):
            w = self.sheet_tabs.widget(idx)
            if isinstance(w, _SingleSheetView):
                all_sheets.append(w.to_sheet_data())

        try:
            if target.lower().endswith(".csv"):
                active = self._current_sheet()
                save_csv(active.to_sheet_data() if active else all_sheets[0], target)
            else:
                decision = confirm_lossy_overwrite(self, target, self._source_path, self._lossy_parts)
                if decision == "cancel":
                    return False
                if decision == "saveas":
                    return self.save_file(force_dialog=True)
                save_xlsx(all_sheets, target, source_path=self._source_path)
                mark_saved(all_sheets)
                for idx in range(self.sheet_tabs.count()):
                    w = self.sheet_tabs.widget(idx)
                    if isinstance(w, _SingleSheetView):
                        w.source_title = w.sheet_name[:31]
                self._source_path = target
                self._lossy_parts = unsupported_parts(target)
        except Exception as exc:  # noqa: BLE001
            QMessageBox.critical(self, "Kaydetme hatası", f"Tablo kaydedilemedi:\n{exc}")
            return False

        self.filepath = target
        self._is_modified = False
        self.modified_changed.emit(False)
        self.status_message.emit(f"Tablo kaydedildi: {os.path.basename(target)}")
        return True

    def export_csv(self) -> None:
        sheet = self._current_sheet()
        if not sheet:
            return
        target, _ = QFileDialog.getSaveFileName(
            self, "Aktif Sayfayı CSV Olarak Aktar", f"{sheet.sheet_name}.csv", "CSV Dosyası (*.csv)"
        )
        if not target:
            return
        if not target.lower().endswith(".csv"):
            target += ".csv"
        save_csv(sheet.to_sheet_data(), target)
        self.status_message.emit(f"CSV aktarıldı: {os.path.basename(target)}")
