"""MiniOfis modern Açık ve Koyu Windows 11 tarzı QSS temaları."""

LIGHT_THEME = """
QMainWindow, QWidget {
    background-color: #f3f5f9;
    color: #1e293b;
    font-family: "Segoe UI", "Calibri", sans-serif;
    font-size: 10pt;
}
QMenuBar {
    background-color: #ffffff;
    border-bottom: 1px solid #e2e8f0;
    padding: 3px 6px;
}
QMenuBar::item:selected {
    background-color: #e2e8f0;
    border-radius: 4px;
}
QMenu {
    background-color: #ffffff;
    border: 1px solid #cbd5e1;
    padding: 4px;
}
QMenu::item {
    padding: 6px 24px 6px 12px;
    border-radius: 4px;
}
QMenu::item:selected {
    background-color: #2563eb;
    color: #ffffff;
}
QToolBar {
    background-color: #ffffff;
    border-bottom: 1px solid #e2e8f0;
    spacing: 4px;
    padding: 4px 8px;
}
QToolButton, QPushButton {
    background-color: #f8fafc;
    color: #1e293b;
    border: 1px solid #cbd5e1;
    border-radius: 6px;
    padding: 5px 10px;
}
QToolButton:hover, QPushButton:hover {
    background-color: #e2e8f0;
}
QToolButton:checked, QPushButton:checked {
    background-color: #dbeafe;
    border-color: #2563eb;
    color: #1d4ed8;
}
QPushButton#primaryBtn {
    background-color: #2563eb;
    color: #ffffff;
    border: 1px solid #1d4ed8;
    font-weight: 600;
}
QPushButton#primaryBtn:hover {
    background-color: #1d4ed8;
}
QTabWidget::pane {
    border: 1px solid #cbd5e1;
    background-color: #ffffff;
    border-radius: 6px;
}
QTabBar::tab {
    background-color: #e2e8f0;
    color: #475569;
    padding: 8px 16px;
    margin-right: 2px;
    border-top-left-radius: 6px;
    border-top-right-radius: 6px;
}
QTabBar::tab:selected {
    background-color: #ffffff;
    color: #0f172a;
    font-weight: 600;
    border: 1px solid #cbd5e1;
    border-bottom: none;
}
QLineEdit, QComboBox, QSpinBox, QFontComboBox {
    background-color: #ffffff;
    color: #0f172a;
    border: 1px solid #cbd5e1;
    border-radius: 5px;
    padding: 4px 8px;
}
QTextEdit {
    background-color: #ffffff;
    color: #0f172a;
    border: 1px solid #cbd5e1;
    border-radius: 6px;
    padding: 18px 24px;
}
QTableWidget {
    background-color: #ffffff;
    color: #0f172a;
    gridline-color: #e2e8f0;
    selection-background-color: #dbeafe;
    selection-color: #0f172a;
    border: 1px solid #cbd5e1;
}
QHeaderView::section {
    background-color: #f1f5f9;
    color: #334155;
    padding: 4px;
    border: 1px solid #cbd5e1;
    font-weight: 600;
}
QStatusBar {
    background-color: #ffffff;
    border-top: 1px solid #e2e8f0;
    color: #475569;
}
"""

DARK_THEME = """
QMainWindow, QWidget {
    background-color: #0f172a;
    color: #e2e8f0;
    font-family: "Segoe UI", "Calibri", sans-serif;
    font-size: 10pt;
}
QMenuBar {
    background-color: #1e293b;
    border-bottom: 1px solid #334155;
    padding: 3px 6px;
}
QMenuBar::item:selected {
    background-color: #334155;
    border-radius: 4px;
}
QMenu {
    background-color: #1e293b;
    border: 1px solid #475569;
    padding: 4px;
}
QMenu::item {
    padding: 6px 24px 6px 12px;
    border-radius: 4px;
}
QMenu::item:selected {
    background-color: #3b82f6;
    color: #ffffff;
}
QToolBar {
    background-color: #1e293b;
    border-bottom: 1px solid #334155;
    spacing: 4px;
    padding: 4px 8px;
}
QToolButton, QPushButton {
    background-color: #1e293b;
    color: #e2e8f0;
    border: 1px solid #475569;
    border-radius: 6px;
    padding: 5px 10px;
}
QToolButton:hover, QPushButton:hover {
    background-color: #334155;
}
QToolButton:checked, QPushButton:checked {
    background-color: #1d4ed8;
    border-color: #60a5fa;
    color: #ffffff;
}
QPushButton#primaryBtn {
    background-color: #2563eb;
    color: #ffffff;
    border: 1px solid #3b82f6;
    font-weight: 600;
}
QPushButton#primaryBtn:hover {
    background-color: #3b82f6;
}
QTabWidget::pane {
    border: 1px solid #334155;
    background-color: #1e293b;
    border-radius: 6px;
}
QTabBar::tab {
    background-color: #0f172a;
    color: #94a3b8;
    padding: 8px 16px;
    margin-right: 2px;
    border-top-left-radius: 6px;
    border-top-right-radius: 6px;
}
QTabBar::tab:selected {
    background-color: #1e293b;
    color: #f8fafc;
    font-weight: 600;
    border: 1px solid #334155;
    border-bottom: none;
}
QLineEdit, QComboBox, QSpinBox, QFontComboBox {
    background-color: #0f172a;
    color: #f8fafc;
    border: 1px solid #475569;
    border-radius: 5px;
    padding: 4px 8px;
}
QTextEdit {
    background-color: #1e293b;
    color: #f8fafc;
    border: 1px solid #334155;
    border-radius: 6px;
    padding: 18px 24px;
}
QTableWidget {
    background-color: #1e293b;
    color: #f8fafc;
    gridline-color: #334155;
    selection-background-color: #1d4ed8;
    selection-color: #ffffff;
    border: 1px solid #334155;
}
QHeaderView::section {
    background-color: #0f172a;
    color: #cbd5e1;
    padding: 4px;
    border: 1px solid #334155;
    font-weight: 600;
}
QStatusBar {
    background-color: #1e293b;
    border-top: 1px solid #334155;
    color: #94a3b8;
}
"""
