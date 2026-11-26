"""EFOS-Ofis çekirdek motorları ve Google Antigravity MCP sunucusu test paketi."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from PIL import Image

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from core.archive_engine import create_zip, extract_archive, list_archive
from core.docx_engine import load_docx_to_html, save_html_to_docx
from core.formula_engine import FormulaEngine
from core.pdf_engine import PdfDocumentReader, merge_pdfs, split_pdf
from core.pptx_engine import SlideData, load_pptx, save_pptx
from core.xlsx_engine import CellData, SheetData, load_xlsx, save_xlsx
from mcp_server.efos_mcp_server import handle_rpc_request
from mcp_server.register_antigravity import register_in_antigravity


def _create_sample_pdf(filepath: str, text_lines: list[str]) -> None:
    content_ops = ["BT", "/F1 16 Tf", "72 720 Td"]
    for i, line in enumerate(text_lines):
        escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        if i > 0:
            content_ops.append("0 -24 Td")
        content_ops.append(f"({escaped}) Tj")
    content_ops.append("ET")
    stream = "\n".join(content_ops).encode("latin-1")
    objs = [
        b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
        b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
        b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n",
        b"4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n",
        f"5 0 obj\n<< /Length {len(stream)} >>\nstream\n".encode("latin-1")
        + stream
        + b"\nendstream\nendobj\n",
    ]
    out = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for obj in objs:
        offsets.append(len(out))
        out.extend(obj)
    xref_pos = len(out)
    out.extend(f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode("latin-1"))
    for off in offsets[1:]:
        out.extend(f"{off:010d} 00000 n \n".encode("latin-1"))
    out.extend(
        f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref_pos}\n%%EOF\n".encode("latin-1")
    )
    with open(filepath, "wb") as f:
        f.write(out)


def _call_tool(tool_name: str, arguments: dict, req_id: int = 10) -> dict:
    resp = handle_rpc_request(
        {
            "jsonrpc": "2.0",
            "id": req_id,
            "method": "tools/call",
            "params": {"name": tool_name, "arguments": arguments},
        }
    )
    assert resp is not None
    if resp["result"].get("isError"):
        raise AssertionError(f"{tool_name} hata döndürdü: {resp['result']['content'][0]['text']}")
    return json.loads(resp["result"]["content"][0]["text"])


class TestEfosCoreAndPdfTools(unittest.TestCase):
    def test_docx_xlsx_pptx_engines(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            doc_p = os.path.join(tmp, "a.docx")
            save_html_to_docx("<p><b>EFOS</b> Raporu</p>", doc_p)
            self.assertIn("EFOS", load_docx_to_html(doc_p))

            xls_p = os.path.join(tmp, "b.xlsx")
            sh = SheetData(
                name="Gelir",
                cells={(0, 0): CellData(raw="100"), (1, 0): CellData(raw="=A1*3")},
            )
            save_xlsx([sh], xls_p)
            loaded_sh = load_xlsx(xls_p)[0]
            eng = FormulaEngine(lambda r, c: loaded_sh.cells[(r, c)].raw if (r, c) in loaded_sh.cells else "")
            self.assertEqual(eng.evaluate_cell(1, 0), 300.0)

            ppt_p = os.path.join(tmp, "c.pptx")
            save_pptx([SlideData(title="EFOS Sunu", body="Test", layout="title")], ppt_p)
            self.assertEqual(load_pptx(ppt_p)[0].title, "EFOS Sunu")

    def test_pdf_merge_and_split(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            p1 = os.path.join(tmp, "p1.pdf")
            p2 = os.path.join(tmp, "p2.pdf")
            merged = os.path.join(tmp, "merged.pdf")
            split_out = os.path.join(tmp, "page2.pdf")
            _create_sample_pdf(p1, ["Sayfa Bir"])
            _create_sample_pdf(p2, ["Sayfa Iki"])

            self.assertEqual(merge_pdfs([p1, p2], merged), 2)
            self.assertEqual(split_pdf(merged, split_out, 2, 2), 1)
            reader = PdfDocumentReader(split_out)
            try:
                self.assertEqual(reader.page_count, 1)
                self.assertIn("Sayfa Iki", reader.get_page_text(0))
            finally:
                reader.close()

    def test_archive_engine_and_zip_slip_protection(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            src_dir = os.path.join(tmp, "belgeler")
            os.makedirs(src_dir)
            f1 = os.path.join(src_dir, "rapor.txt")
            f2 = os.path.join(src_dir, "gecici.tmp")
            Path(f1).write_text("EFOS arşiv testi", encoding="utf-8")
            Path(f2).write_text("hariç tutulacak", encoding="utf-8")

            zip_path = os.path.join(tmp, "paket.zip")
            created = create_zip([src_dir], zip_path, exclude_patterns=["*.tmp"])
            self.assertEqual(created["file_count"], 1)

            listed = list_archive(zip_path)
            self.assertEqual(listed["entry_count"], 1)
            self.assertEqual(listed["entries"][0]["name"], "belgeler/rapor.txt")

            out_dir = os.path.join(tmp, "ayiklanan")
            extracted = extract_archive(zip_path, out_dir, pattern="*.txt")
            self.assertEqual(extracted["extracted_count"], 1)
            self.assertTrue(os.path.isfile(os.path.join(out_dir, "belgeler", "rapor.txt")))

            # Zip Slip güvenlik kontrolü
            evil_zip = os.path.join(tmp, "evil.zip")
            with zipfile.ZipFile(evil_zip, "w") as zf:
                zf.writestr("../kacis.txt", "zararli")
            with self.assertRaises(ValueError):
                extract_archive(evil_zip, out_dir)


class TestAntigravityMcpServer(unittest.TestCase):
    def test_mcp_protocol_and_excel_word_pptx_pdf_tools(self) -> None:
        init_resp = handle_rpc_request({"jsonrpc": "2.0", "id": 1, "method": "initialize"})
        self.assertEqual(init_resp["result"]["serverInfo"]["name"], "efos-ofis")

        ping_resp = handle_rpc_request({"jsonrpc": "2.0", "id": 99, "method": "ping"})
        self.assertEqual(ping_resp["result"], {})

        tools_resp = handle_rpc_request({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        tool_names = {t["name"] for t in tools_resp["result"]["tools"]}
        expected_tools = {
            "excel_read",
            "excel_write",
            "csv_to_excel",
            "excel_to_csv",
            "word_read",
            "word_write",
            "word_find_replace",
            "pptx_read",
            "pptx_write",
            "pdf_read_and_search",
            "pdf_write",
            "pdf_merge",
            "pdf_split",
            "pdf_rotate",
            "pdf_to_images",
            "pdf_metadata",
            "zip_extract",
            "zip_create",
            "zip_list",
            "office_to_pdf",
            "office_search_folder",
            "markdown_note",
            "image_office_tool",
        }
        self.assertTrue(expected_tools.issubset(tool_names))

        with tempfile.TemporaryDirectory() as tmp:
            os.environ["EFOS_MCP_ALLOWED_DIRS"] = tmp
            xls_path = os.path.join(tmp, "mcp_test.xlsx")
            _call_tool(
                "excel_write",
                {
                    "filepath": xls_path,
                    "sheet_name": "Bütçe",
                    "rows": [["Kalem", "Tutar"], ["Kira", 12000], ["Fatura", 3000]],
                    "cell_updates": {"A4": "Toplam", "B4": "=TOPLA(B2:B3)"},
                },
            )
            payload = _call_tool("excel_read", {"filepath": xls_path})
            self.assertEqual(payload["sheets"][0]["rows"][3][1], "15000")

            # Excel <-> CSV
            csv_path = os.path.join(tmp, "butce.csv")
            _call_tool("excel_to_csv", {"input_xlsx": xls_path, "output_csv": csv_path})
            self.assertIn("15000", Path(csv_path).read_text(encoding="utf-8-sig"))

            xlsx_from_csv = os.path.join(tmp, "from_csv.xlsx")
            conv_res = _call_tool("csv_to_excel", {"input_csv": csv_path, "output_xlsx": xlsx_from_csv})
            self.assertEqual(conv_res["rows"], 4)

            # Word write, read, find/replace
            doc_path = os.path.join(tmp, "sozlesme.docx")
            _call_tool(
                "word_write",
                {
                    "filepath": doc_path,
                    "title": "Hizmet Sözleşmesi",
                    "paragraphs": ["Sayın {{MUSTERI}}, teklifimiz ektedir."],
                    "tables": [["Alan", "Değer"], ["Firma", "{{MUSTERI}}"]],
                },
            )
            rep_res = _call_tool(
                "word_find_replace",
                {"filepath": doc_path, "replacements": {"{{MUSTERI}}": "EFOS Teknoloji"}},
            )
            self.assertEqual(rep_res["replacements_made"], 2)
            word_data = _call_tool("word_read", {"filepath": doc_path})
            self.assertIn("EFOS Teknoloji", word_data["paragraphs"][1])
            self.assertEqual(word_data["tables"][0][1][1], "EFOS Teknoloji")

            # PowerPoint write & read
            pptx_path = os.path.join(tmp, "sunum.pptx")
            _call_tool(
                "pptx_write",
                {
                    "filepath": pptx_path,
                    "theme": "Kurumsal Lacivert",
                    "slides": [{"title": "Yıllık Özet", "body": "Büyüme %42", "layout": "title"}],
                },
            )
            pptx_data = _call_tool("pptx_read", {"filepath": pptx_path})
            self.assertEqual(pptx_data["slides"][0]["title"], "Yıllık Özet")

            # PDF write, read/search, metadata, rotate, render to images
            pdf_path = os.path.join(tmp, "rapor.pdf")
            w_pdf = _call_tool(
                "pdf_write",
                {
                    "filepath": pdf_path,
                    "title": "EFOS Türkçe Raporu ŞİĞÜÖÇ",
                    "paragraphs": ["Bütçe özeti başarıyla oluşturuldu."],
                    "tables": [["Kalem", "Tutar"], ["Toplam", "15000 TL"]],
                },
            )
            self.assertGreaterEqual(w_pdf["page_count"], 1)
            pdf_search = _call_tool("pdf_read_and_search", {"filepath": pdf_path, "query": "Türkçe"})
            self.assertGreaterEqual(
                len(pdf_search["matches"]), 1,
                "PDF'ten çıkan metin: " + repr(_call_tool("pdf_read_and_search", {"filepath": pdf_path}))[:600],
            )

            meta_upd = _call_tool(
                "pdf_metadata",
                {"filepath": pdf_path, "updates": {"author": "EFOS Ajan", "subject": "Mali Rapor"}},
            )
            self.assertEqual(meta_upd["metadata"]["author"], "EFOS Ajan")

            rotated_pdf = os.path.join(tmp, "rapor_90.pdf")
            rot_res = _call_tool("pdf_rotate", {"input_path": pdf_path, "output_path": rotated_pdf, "angle": 90})
            self.assertEqual(rot_res["rotated_pages"], w_pdf["page_count"])

            img_out_dir = os.path.join(tmp, "pdf_imgs")
            img_res = _call_tool("pdf_to_images", {"input_path": pdf_path, "output_dir": img_out_dir, "scale": 1.0})
            self.assertGreaterEqual(len(img_res["images"]), 1)
            self.assertTrue(os.path.isfile(img_res["images"][0]))

            # Office to PDF (.docx -> .pdf) & Image tool
            docx_pdf = os.path.join(tmp, "sozlesme.pdf")
            conv_pdf = _call_tool("office_to_pdf", {"input_path": doc_path, "output_pdf": docx_pdf})
            self.assertGreaterEqual(conv_pdf["page_count"], 1)

            sample_img = os.path.join(tmp, "logo.png")
            Image.new("RGB", (400, 200), color=(37, 99, 235)).save(sample_img)
            resized_img = os.path.join(tmp, "logo_small.jpg")
            img_tool = _call_tool(
                "image_office_tool",
                {
                    "input_path": sample_img,
                    "output_path": resized_img,
                    "action": "resize",
                    "max_width": 100,
                    "max_height": 100,
                    "target_format": "JPEG",
                },
            )
            self.assertEqual(img_tool["new_size"], [100, 50])

            # Markdown note
            note_path = os.path.join(tmp, "notlar.md")
            _call_tool("markdown_note", {"filepath": note_path, "action": "write", "content": "# Toplantı\n- [ ] Bütçe onayı"})
            _call_tool("markdown_note", {"filepath": note_path, "action": "append", "content": "- [x] Sunum hazır"})
            note_data = _call_tool("markdown_note", {"filepath": note_path, "action": "read"})
            self.assertEqual(note_data["open_tasks"], ["Bütçe onayı"])
            self.assertEqual(note_data["done_tasks"], ["Sunum hazır"])

            # ZIP create, list, extract via MCP
            zip_out = os.path.join(tmp, "ofis_paketi.zip")
            z_create = _call_tool("zip_create", {"source_paths": [doc_path, note_path], "output_zip": zip_out})
            self.assertEqual(z_create["file_count"], 2)
            z_list = _call_tool("zip_list", {"archive_path": zip_out})
            self.assertEqual(z_list["entry_count"], 2)
            extract_dir = os.path.join(tmp, "unzip_out")
            z_ext = _call_tool("zip_extract", {"archive_path": zip_out, "output_dir": extract_dir})
            self.assertEqual(z_ext["extracted_count"], 2)

            # Office folder search
            search_res = _call_tool("office_search_folder", {"folder_path": tmp, "query": "EFOS Teknoloji"})
            self.assertGreaterEqual(search_res["match_count"], 1)

            # Antigravity config kayıt testi
            fake_cfg = Path(tmp) / "mcp_config.json"
            register_in_antigravity(config_path=fake_cfg)
            cfg_data = json.loads(fake_cfg.read_text(encoding="utf-8"))
            self.assertIn("efos-ofis", cfg_data["mcpServers"])

    def test_mcp_stdio_subprocess(self) -> None:
        server_script = os.path.abspath(
            os.path.join(os.path.dirname(__file__), "..", "mcp_server", "efos_mcp_server.py")
        )
        requests = "\n".join(
            [
                json.dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize"}),
                json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}),
                json.dumps({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}),
            ]
        ) + "\n"
        env = dict(os.environ)
        env["PYTHONUTF8"] = "1"
        proc = subprocess.run(
            [sys.executable, server_script],
            input=requests,
            capture_output=True,
            text=True,
            encoding="utf-8",
            env=env,
            timeout=15,
            check=True,
        )
        lines = [ln for ln in proc.stdout.splitlines() if ln.strip()]
        self.assertEqual(len(lines), 2)
        init_msg = json.loads(lines[0])
        tools_msg = json.loads(lines[1])
        self.assertEqual(init_msg["result"]["serverInfo"]["name"], "efos-ofis")
        self.assertGreaterEqual(len(tools_msg["result"]["tools"]), 23)


class TestFormulaEngineTurkish(unittest.TestCase):
    def setUp(self) -> None:
        self.cells = {
            (0, 0): "10", (1, 0): "20", (2, 0): "00123", (3, 0): "1.234,5",
            (0, 1): "Elma", (1, 1): "Armut", (2, 1): "Kiraz",
            (0, 2): "5", (1, 2): "7", (2, 2): "9", (4, 0): "=1/0",
        }
        self.eng = FormulaEngine(lambda r, c: self.cells.get((r, c), ""), lambda: (20, 5))

    def ev(self, f):
        return self.eng.evaluate_formula(f)

    def test_turkish_decimal_and_rounding(self) -> None:
        self.assertEqual(self.ev("YUVARLA(2,345;2)"), 2.35)
        self.assertEqual(self.ev("YUVARLA(2,5;0)"), 3)
        self.assertEqual(self.ev("ROUND(-2.5,0)"), -3)
        self.assertEqual(self.ev("YUVARLA(1,005;2)"), 1.01)
        self.assertEqual(self.ev("2,5*2"), 5)
        self.assertEqual(self.ev("A4*2"), 2469)

    def test_excel_semantics(self) -> None:
        self.assertEqual(self.ev("2^3^2"), 64)
        self.assertEqual(self.ev("-2^2"), 4)
        self.assertTrue(self.ev('"a"="A"'))
        self.assertEqual(self.ev('A1&"x"'), "10x")
        self.assertEqual(self.ev("50%"), 0.5)
        self.assertEqual(self.ev("A3"), "00123")
        self.assertEqual(self.ev("A3+1"), 124)

    def test_new_functions(self) -> None:
        self.assertEqual(self.ev("EĞERHATA(A5;0)"), 0)
        self.assertEqual(self.ev('DÜŞEYARA("armut";B1:C3;2;0)'), 7)
        self.assertEqual(self.ev('VLOOKUP("Kiraz",B1:C3,2,FALSE)'), 9)
        self.assertEqual(self.ev('DÜŞEYARA("yok";B1:C3;2;0)'), "#YOK")
        self.assertEqual(self.ev("TOPLA(C:C)"), 21)
        self.assertEqual(self.ev('EĞERSAY(C1:C3;">6")'), 2)
        self.assertEqual(self.ev('EĞER(A1>5;"büyük";1/0)'), "büyük")

    def test_formula_translation(self) -> None:
        from core.formula_engine import from_excel_formula, to_excel_formula

        self.assertEqual(to_excel_formula("=YUVARLA(A1*1,18;2)"), "=ROUND(A1*1.18,2)")
        self.assertEqual(from_excel_formula("=IF(A1>5,\"a,b\",B2)"), '=EĞER(A1>5;"a,b";B2)')


class TestOfficeRoundTrip(unittest.TestCase):
    def test_xlsx_preserves_original(self) -> None:
        import datetime as dt

        import openpyxl

        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "banka.xlsx")
            wb = openpyxl.Workbook()
            ws = wb.active
            ws.title = "Hesap"
            ws["A1"] = "00123"
            ws["A2"] = "1234567890123456789"
            ws["A3"] = dt.datetime(2026, 1, 5)
            ws["A4"] = 1234.5
            ws["A4"].number_format = "#,##0.00 ₺"
            ws.merge_cells("C1:D2")
            ws.column_dimensions["A"].width = 33
            wb.save(src)

            sheets = load_xlsx(src)
            sheets[0].cells[(4, 0)] = CellData(raw="=YUVARLA(A4*1,18;2)")
            sheets[0].cells[(5, 0)] = CellData(raw="00456")
            save_xlsx(sheets, src, source_path=src)

            out = openpyxl.load_workbook(src)["Hesap"]
            self.assertEqual(out["A1"].value, "00123")
            self.assertEqual(out["A2"].value, "1234567890123456789")
            self.assertIsInstance(out["A3"].value, dt.datetime)
            self.assertEqual(out["A4"].number_format, "#,##0.00 ₺")
            self.assertEqual(out["A5"].value, "=ROUND(A4*1.18,2)")
            self.assertEqual(out["A6"].value, "00456")
            self.assertEqual(len(out.merged_cells.ranges), 1)
            self.assertEqual(out.column_dimensions["A"].width, 33)

    def test_docx_keeps_header_image_and_lists(self) -> None:
        from docx import Document
        from docx.shared import Inches

        with tempfile.TemporaryDirectory() as tmp:
            img = os.path.join(tmp, "r.png")
            Image.new("RGB", (40, 20), (200, 0, 0)).save(img)
            src = os.path.join(tmp, "a.docx")
            d = Document()
            d.sections[0].header.paragraphs[0].text = "ÜSTBİLGİ"
            d.add_paragraph("madde", style="List Bullet")
            d.add_picture(img, width=Inches(1))
            d.save(src)
            html = load_docx_to_html(src, image_dir=tmp)
            self.assertIn("<ul>", html)
            self.assertIn("<img", html)
            save_html_to_docx(html + "<p>yeni</p>", src, source_path=src)
            out = Document(src)
            self.assertEqual(out.sections[0].header.paragraphs[0].text, "ÜSTBİLGİ")
            self.assertEqual(len(out.inline_shapes), 1)
            self.assertEqual(out.paragraphs[0].style.name, "List Bullet")

    def test_pptx_keeps_chart_and_unchanged_slides(self) -> None:
        from pptx import Presentation
        from pptx.chart.data import CategoryChartData
        from pptx.enum.chart import XL_CHART_TYPE
        from pptx.util import Inches

        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "s.pptx")
            prs = Presentation()
            s1 = prs.slides.add_slide(prs.slide_layouts[1])
            s1.shapes.title.text = "Grafik"
            data = CategoryChartData()
            data.categories = ["A", "B"]
            data.add_series("S", (1, 2))
            s1.shapes.add_chart(XL_CHART_TYPE.COLUMN_CLUSTERED, Inches(1), Inches(2), Inches(4), Inches(3), data)
            s2 = prs.slides.add_slide(prs.slide_layouts[1])
            s2.shapes.title.text = "Silinecek"
            prs.save(src)

            slides = load_pptx(src)
            slides[0].title = "Grafik (güncel)"
            save_pptx([slides[0], SlideData(title="Yeni")], src, source_path=src)
            out = Presentation(src)
            self.assertEqual(len(out.slides), 2)
            self.assertEqual(out.slides[0].shapes.title.text, "Grafik (güncel)")
            self.assertTrue(any(sh.has_chart for sh in out.slides[0].shapes))


class TestMcpSafety(unittest.TestCase):
    def test_path_outside_allowed_dirs_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as allowed, tempfile.TemporaryDirectory() as other:
            os.environ["EFOS_MCP_ALLOWED_DIRS"] = allowed
            resp = handle_rpc_request(
                {
                    "jsonrpc": "2.0",
                    "id": 5,
                    "method": "tools/call",
                    "params": {"name": "markdown_note", "arguments": {"filepath": os.path.join(other, "x.md"), "action": "write", "content": "a"}},
                }
            )
            self.assertTrue(resp["result"].get("isError"))
            self.assertIn("İzin verilmeyen", resp["result"]["content"][0]["text"])
            self.assertFalse(os.path.exists(os.path.join(other, "x.md")))

    def test_find_replace_makes_backup(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            os.environ["EFOS_MCP_ALLOWED_DIRS"] = tmp
            doc = os.path.join(tmp, "s.docx")
            save_html_to_docx("<p>Merhaba AD</p>", doc)
            res = _call_tool("word_find_replace", {"filepath": doc, "find": "AD", "replace": "Ercan"})
            self.assertTrue(os.path.isfile(res["backup_path"]))

    def test_register_keeps_other_servers_and_backs_up_broken_config(self) -> None:
        from mcp_server.register_antigravity import register_with_report

        with tempfile.TemporaryDirectory() as tmp:
            cfg = Path(tmp) / "mcp_config.json"
            cfg.write_text(json.dumps({"mcpServers": {"baska": {"command": "x"}}}), encoding="utf-8")
            rep = register_with_report(config_path=cfg, allowed_dirs=[tmp])
            data = json.loads(cfg.read_text(encoding="utf-8"))
            self.assertIn("baska", data["mcpServers"])
            self.assertEqual(data["mcpServers"]["efos-ofis"]["env"]["EFOS_MCP_ALLOWED_DIRS"], tmp)
            self.assertIsNone(rep["broken_backup"])

            cfg.write_text("{bozuk json", encoding="utf-8")
            rep = register_with_report(config_path=cfg)
            self.assertIsNotNone(rep["broken_backup"])
            self.assertTrue(Path(rep["broken_backup"]).read_text(encoding="utf-8").startswith("{bozuk"))

    def test_frozen_entry_uses_console_exe(self) -> None:
        from mcp_server import register_antigravity as ra

        with tempfile.TemporaryDirectory() as tmp:
            fake_exe = os.path.join(tmp, "EFOS-Ofis.exe")
            Path(fake_exe).write_text("")
            old_exe, old_frozen = sys.executable, getattr(sys, "frozen", None)
            try:
                sys.executable = fake_exe
                sys.frozen = True
                entry = ra.build_mcp_entry()
                self.assertEqual(entry["args"], ["--mcp"])
                Path(os.path.join(tmp, "efos-mcp.exe")).write_text("")
                entry = ra.build_mcp_entry()
                self.assertTrue(entry["command"].endswith("efos-mcp.exe"))
            finally:
                sys.executable = old_exe
                if old_frozen is None:
                    del sys.frozen
                else:
                    sys.frozen = old_frozen


if __name__ == "__main__":
    unittest.main()
