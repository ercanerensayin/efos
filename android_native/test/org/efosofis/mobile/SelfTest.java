package org.efosofis.mobile;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Masaüstü JVM'de çalışan çekirdek testleri (apk_derle betikleri derlemeden önce çalıştırır). */
public final class SelfTest {
    private static int passed = 0;

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError("Test başarısız: " + msg);
        passed++;
    }

    private static String ev(FormulaEngine eng, String f) {
        return FormulaEngine.formatDisplayValue(eng.evaluateFormula(f));
    }

    public static void main(String[] args) throws Exception {
        // 1. Formül motoru
        final Map<String, String> grid = new HashMap<String, String>();
        grid.put("0:0", "100");
        grid.put("1:0", "250");
        grid.put("2:0", "50");
        grid.put("3:0", "=TOPLA(A1:A3)");
        grid.put("4:0", "=ORTALAMA(A1:A3)");
        grid.put("5:0", "=EĞER(A4>300;\"BAŞARILI\";\"DÜŞÜK\")");
        grid.put("6:0", "=A1/0");
        grid.put("0:1", "Elma");
        grid.put("1:1", "Armut");
        grid.put("0:2", "5");
        grid.put("1:2", "7");
        grid.put("7:0", "00123");

        FormulaEngine eng = new FormulaEngine(new FormulaEngine.CellProvider() {
            @Override
            public String getRawCell(int row, int col) {
                String v = grid.get(FormulaEngine.cellKey(row, col));
                return v != null ? v : "";
            }
        });
        check("400".equals(FormulaEngine.formatDisplayValue(eng.evaluateCell(3, 0))), "TOPLA");
        check("133,3333333333".equals(FormulaEngine.formatDisplayValue(eng.evaluateCell(4, 0))), "ORTALAMA");
        check("BAŞARILI".equals(FormulaEngine.formatDisplayValue(eng.evaluateCell(5, 0))), "EĞER");
        check("#SAYI/0!".equals(FormulaEngine.formatDisplayValue(eng.evaluateCell(6, 0))), "SAYI/0");
        check("2,35".equals(ev(eng, "YUVARLA(2,345;2)")), "Türkçe ondalık virgül");
        check("3".equals(ev(eng, "YUVARLA(2,5;0)")), "Excel yuvarlama");
        check("1,01".equals(ev(eng, "YUVARLA(1,005;2)")), "Ondalık yuvarlama");
        check("64".equals(ev(eng, "2^3^2")), "Üs önceliği");
        check("DOĞRU".equals(ev(eng, "\"a\"=\"A\"")), "Büyük/küçük harf");
        check("0".equals(ev(eng, "EĞERHATA(A7;0)")), "EĞERHATA");
        check("7".equals(ev(eng, "DÜŞEYARA(\"armut\";B1:C2;2;0)")), "DÜŞEYARA");
        check("00123".equals(ev(eng, "A8")), "Başında sıfır olan metin");
        check("1234,5".equals(FormulaEngine.formatDisplayValue(FormulaEngine.toNumber("1.234,5"))), "Türkçe binlik");
        check("=ROUND(A1*1.18,2)".equals(FormulaEngine.toExcelFormula("=YUVARLA(A1*1,18;2)")), "Excel'e çeviri");
        check("=EĞER(A1>5;\"a,b\";B2)".equals(FormulaEngine.fromExcelFormula("=IF(A1>5,\"a,b\",B2)")), "Türkçeye çeviri");

        File tmp = Files.createTempDirectory("efos_apk_selftest").toFile();
        try {
            // 2. Word: yeni belge + orijinali koruyarak düzenleme
            File docx = new File(tmp, "test.docx");
            OfficeEngines.writeDocx(docx, "EFOS Başlık\nTürkçe: ŞİĞÜÖÇ\nSilinecek");
            String docTxt = OfficeEngines.readDocx(docx);
            check(docTxt.contains("EFOS Başlık") && docTxt.contains("ŞİĞÜÖÇ"), "DOCX okuma/yazma");
            addEntry(docx, "word/media/koru.bin", "resim");
            OfficeEngines.saveDocx(docx, "EFOS Başlık (güncel)\nTürkçe: ŞİĞÜÖÇ\nYeni satır", docx);
            check("EFOS Başlık (güncel)\nTürkçe: ŞİĞÜÖÇ\nYeni satır".equals(OfficeEngines.readDocx(docx)), "DOCX düzenleme");
            check(hasEntry(docx, "word/media/koru.bin"), "DOCX diğer parçaları korur");

            // 3. Excel: yeni + düzenleme (biçim/diğer parçalar korunur)
            File xlsx = new File(tmp, "test.xlsx");
            Map<String, String> cells = new HashMap<String, String>();
            cells.put("0:0", "Kalem");
            cells.put("0:1", "Tutar");
            cells.put("1:0", "Kira");
            cells.put("1:1", "12000");
            cells.put("2:0", "Toplam");
            cells.put("2:1", "=TOPLA(B2:B2)");
            cells.put("3:0", "00123");
            cells.put("4:0", "05.01.2026");
            OfficeEngines.writeXlsx(xlsx, cells, "Bütçe");
            Map<String, String> loadedXlsx = OfficeEngines.readXlsx(xlsx);
            check("Kalem".equals(loadedXlsx.get("0:0")), "XLSX metin");
            check("=TOPLA(B2:B2)".equals(loadedXlsx.get("2:1")), "XLSX formül");
            check("00123".equals(loadedXlsx.get("3:0")), "XLSX baştaki sıfır");
            check("05.01.2026".equals(loadedXlsx.get("4:0")), "XLSX tarih");
            addEntry(xlsx, "xl/media/grafik.bin", "grafik");
            loadedXlsx.put("1:1", "13.500,75");
            loadedXlsx.remove("0:1");
            OfficeEngines.saveXlsx(xlsx, loadedXlsx, xlsx);
            Map<String, String> again = OfficeEngines.readXlsx(xlsx);
            check("13500,75".equals(again.get("1:1")), "XLSX hücre güncelleme");
            check(!again.containsKey("0:1"), "XLSX hücre silme");
            check(hasEntry(xlsx, "xl/media/grafik.bin"), "XLSX diğer parçaları korur");

            File csv = new File(tmp, "test.csv");
            OfficeEngines.writeCsv(csv, cells, ';');
            Map<String, String> loadedCsv = OfficeEngines.readCsv(csv);
            check("Kira".equals(loadedCsv.get("1:0")), "CSV okuma/yazma");

            // 4. PowerPoint: yeni + düzenleme + slayt ekleme
            File pptx = new File(tmp, "test.pptx");
            OfficeEngines.writePptx(pptx, Collections.singletonList(
                    new OfficeEngines.SlideItem("EFOS Sunu", "Madde 1\nMadde 2", "")));
            List<OfficeEngines.SlideItem> loadedSlides = OfficeEngines.readPptx(pptx);
            check(loadedSlides.size() == 1 && "EFOS Sunu".equals(loadedSlides.get(0).title), "PPTX okuma/yazma");
            addEntry(pptx, "ppt/media/resim.bin", "resim");
            List<OfficeEngines.SlideItem> edited = new ArrayList<OfficeEngines.SlideItem>(loadedSlides);
            edited.get(0).title = "EFOS Sunu 2";
            edited.add(new OfficeEngines.SlideItem("İkinci", "a\nb", ""));
            OfficeEngines.savePptx(pptx, edited, pptx);
            List<OfficeEngines.SlideItem> again2 = OfficeEngines.readPptx(pptx);
            check(again2.size() == 2 && "EFOS Sunu 2".equals(again2.get(0).title)
                    && "İkinci".equals(again2.get(1).title), "PPTX düzenleme/ekleme");
            check(hasEntry(pptx, "ppt/media/resim.bin"), "PPTX diğer parçaları korur");

            // 5. ZIP
            File zipSrc = new File(tmp, "kaynak");
            zipSrc.mkdirs();
            for (String n : Arrays.asList("a.txt", "b.txt", "c.txt", "d.txt")) {
                OfficeEngines.writeUtf8(new File(zipSrc, n), n);
            }
            File zip = new File(tmp, "paket.zip");
            int count = OfficeEngines.createZip(Collections.singletonList(zipSrc), zip, true);
            check(count == 4, "ZIP oluşturma (" + count + ")");
            check(OfficeEngines.listZip(zip).size() == 4, "ZIP listeleme");
            File outDir = new File(tmp, "unzip");
            List<File> ext = OfficeEngines.extractZip(zip, outDir);
            check(ext.size() == 4, "ZIP ayıklama");

            File evil = new File(tmp, "evil.zip");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(evil))) {
                zos.putNextEntry(new ZipEntry("iyi.txt"));
                zos.write("iyi".getBytes());
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("../kacis.txt"));
                zos.write("zararli".getBytes());
                zos.closeEntry();
            }
            File evilOut = new File(tmp, "evil_out");
            boolean blocked = false;
            try {
                OfficeEngines.extractZip(evil, evilOut);
            } catch (SecurityException ex) {
                blocked = true;
            }
            check(blocked, "Zip Slip engelleme");
            check(!new File(evilOut, "iyi.txt").exists(), "Zip Slip: hiçbir dosya yazılmaz");

            boolean bomb = false;
            try {
                OfficeEngines.extractZip(zip, new File(tmp, "kucuk"), 3);
            } catch (SecurityException ex) {
                bomb = true;
            }
            check(bomb, "Boyut sınırı");
            check(!new File(tmp, "kucuk").exists(), "Boyut sınırı: yarım dosya kalmaz");
        } finally {
            deleteRec(tmp);
        }
        System.out.println("[BASARILI] Tum EFOS-Ofis Android cekirdek testleri gecti (" + passed + " kontrol).");
    }

    private static void addEntry(File zipFile, String name, String content) throws Exception {
        File tmp = new File(zipFile.getAbsolutePath() + ".add");
        try (ZipFile zf = new ZipFile(zipFile);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                zos.putNextEntry(new ZipEntry(e.getName()));
                zos.write(readAll(zf, e));
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content.getBytes("UTF-8"));
            zos.closeEntry();
        }
        zipFile.delete();
        tmp.renameTo(zipFile);
    }

    private static byte[] readAll(ZipFile zf, ZipEntry e) throws Exception {
        java.io.InputStream in = zf.getInputStream(e);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) >= 0) out.write(b, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static boolean hasEntry(File zipFile, String name) throws Exception {
        try (ZipFile zf = new ZipFile(zipFile)) {
            return zf.getEntry(name) != null;
        }
    }

    private static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        f.delete();
    }
}
