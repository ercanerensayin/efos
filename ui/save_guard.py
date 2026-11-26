"""Kaydederken veri kaybı riskini kullanıcıya soran ortak yardımcı."""

from __future__ import annotations

import os
from typing import List, Optional

from PyQt6.QtWidgets import QMessageBox, QWidget


def confirm_lossy_overwrite(
    parent: Optional[QWidget], target: str, source: Optional[str], parts: List[str]
) -> str:
    """Orijinal dosyanın üzerine yazmak bazı öğeleri kaybettirecekse sorar.

    Dönüş: "save" (devam et), "saveas" (farklı kaydet), "cancel".
    """
    if not parts or not source:
        return "save"
    try:
        same_file = os.path.exists(target) and os.path.samefile(target, source)
    except OSError:
        same_file = os.path.abspath(target) == os.path.abspath(source)
    if not same_file:
        return "save"

    box = QMessageBox(parent)
    box.setIcon(QMessageBox.Icon.Warning)
    box.setWindowTitle("Kaydetmeden önce")
    box.setText(
        f"<b>{os.path.basename(target)}</b> dosyasında EFOS-Ofis'in koruyamadığı öğeler var:"
        f"<br><b>{', '.join(parts)}</b>"
    )
    box.setInformativeText(
        "Orijinal dosyanın üzerine kaydederseniz bu öğeler kaybolur. "
        "Orijinali korumak için farklı bir adla kaydetmeniz önerilir."
    )
    save_as_btn = box.addButton("Farklı Kaydet…", QMessageBox.ButtonRole.AcceptRole)
    overwrite_btn = box.addButton("Yine de Üzerine Yaz", QMessageBox.ButtonRole.DestructiveRole)
    box.addButton("İptal", QMessageBox.ButtonRole.RejectRole)
    box.setDefaultButton(save_as_btn)
    box.exec()
    clicked = box.clickedButton()
    if clicked is save_as_btn:
        return "saveas"
    if clicked is overwrite_btn:
        return "save"
    return "cancel"
