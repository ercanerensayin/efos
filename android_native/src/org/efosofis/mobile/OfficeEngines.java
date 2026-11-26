package org.efosofis.mobile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * EFOS-Ofis saf Java OOXML (.docx, .xlsx, .pptx), CSV ve güvenli ZIP motoru.
 *
 * <p>Kaydetme ilkesi: açılmış bir dosya kaydedilirken paket sıfırdan yazılmaz. Orijinal
 * ZIP paketindeki tüm parçalar (stiller, resimler, grafikler, üstbilgiler, diğer sayfalar)
 * bayt bayt kopyalanır; yalnızca değişen paragraflar, hücreler veya slayt metinleri
 * XML içinde yerinde güncellenir. Böylece Word/Excel/PowerPoint biçimleri korunur.</p>
 */
public final class OfficeEngines {

    public static final class SlideItem {
        public String title;
        public String body;
        public String notes;

        public SlideItem(String title, String body, String notes) {
            this.title = title != null ? title : "";
            this.body = body != null ? body : "";
            this.notes = notes != null ? notes : "";
        }
    }

    public static final class ZipEntryInfo {
        public final String name;
        public final boolean isDir;
        public final long size;

        public ZipEntryInfo(String name, boolean isDir, long size) {
            this.name = name;
            this.isDir = isDir;
            this.size = size;
        }
    }

    private OfficeEngines() {}

    static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String S_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static final String P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main";
    static final String R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    static final String CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types";
    static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    // ==================== Ortak yardımcılar ====================

    private static String xmlEscape(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(ch); break;
            }
        }
        return sb.toString();
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) >= 0) baos.write(buf, 0, len);
        return baos.toByteArray();
    }

    static Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // Android ayrıştırıcısı bu özelliği desteklemeyebilir; DOCTYPE zaten kullanılmıyor
        }
        dbf.setExpandEntityReferences(false);
        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    static byte[] serializeXml(Document doc) throws Exception {
        doc.setXmlStandalone(true);
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        t.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }

    private static byte[] zipBytes(ZipFile zf, String name) throws IOException {
        ZipEntry e = zf.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zf.getInputStream(e)) {
            return readAllBytes(in);
        }
    }

    private static void putZip(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static void putZipText(ZipOutputStream zos, String name, String content) throws IOException {
        putZip(zos, name, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Şablon paketi kopyalar; {@code replaced} içindekileri yeni içerikle yazar,
     * {@code removed} içindekileri atlar. Hedef şablonla aynı dosya olabilir.
     */
    static void rewriteZip(File template, File target, Map<String, byte[]> replaced, Set<String> removed)
            throws IOException {
        if (target.getParentFile() != null) target.getParentFile().mkdirs();
        File tmp = new File(target.getAbsolutePath() + ".efos-tmp");
        Set<String> written = new HashSet<String>();
        try (ZipFile zf = new ZipFile(template);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            byte[] buf = new byte[16384];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (removed.contains(name) || written.contains(name)) continue;
                written.add(name);
                if (replaced.containsKey(name)) {
                    putZip(zos, name, replaced.get(name));
                    continue;
                }
                zos.putNextEntry(new ZipEntry(name));
                try (InputStream in = zf.getInputStream(e)) {
                    int n;
                    while ((n = in.read(buf)) >= 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
            for (Map.Entry<String, byte[]> r : replaced.entrySet()) {
                if (!written.contains(r.getKey())) putZip(zos, r.getKey(), r.getValue());
            }
        } catch (IOException ex) {
            tmp.delete();
            throw ex;
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("Dosya kaydedilemedi: " + target.getName());
            }
        }
    }

    public static String readUtf8(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            String s = new String(readAllBytes(fis), StandardCharsets.UTF_8);
            return s.startsWith("﻿") ? s.substring(1) : s;
        }
    }

    public static void writeUtf8(File file, String content) throws IOException {
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        }
    }

    static List<Element> children(Element parent, String ns, String local) {
        List<Element> out = new ArrayList<Element>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && local.equals(n.getLocalName()) && (ns == null || ns.equals(n.getNamespaceURI()))) {
                out.add((Element) n);
            }
        }
        return out;
    }

    static Element firstChild(Element parent, String ns, String local) {
        List<Element> l = children(parent, ns, local);
        return l.isEmpty() ? null : l.get(0);
    }

    static List<Element> descendants(Element root, String ns, String local) {
        List<Element> out = new ArrayList<Element>();
        NodeList nl = root.getElementsByTagNameNS(ns, local);
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        return out;
    }

    static boolean hasAncestor(Node n, String ns, String local, Node stopAt) {
        for (Node p = n.getParentNode(); p != null && p != stopAt; p = p.getParentNode()) {
            if (p instanceof Element && local.equals(p.getLocalName()) && ns.equals(p.getNamespaceURI())) return true;
        }
        return false;
    }

    static void insertAfter(Node newNode, Node ref) {
        Node parent = ref.getParentNode();
        Node next = ref.getNextSibling();
        if (next == null) parent.appendChild(newNode);
        else parent.insertBefore(newNode, next);
    }

    /** Satır listeleri arasındaki en uzun ortak alt dizinin eşleşen (i, j) çiftleri. */
    static List<int[]> lcsPairs(List<String> a, List<String> b) {
        List<int[]> pairs = new ArrayList<int[]>();
        int n = a.size();
        int m = b.size();
        if ((long) n * m > 4000000L) {
            // Çok büyük belgede: baştan ve sondan eşit satırları eşleştir
            int i = 0;
            while (i < n && i < m && a.get(i).equals(b.get(i))) {
                pairs.add(new int[]{i, i});
                i++;
            }
            List<int[]> tail = new ArrayList<int[]>();
            int x = n - 1;
            int y = m - 1;
            while (x >= i && y >= i && a.get(x).equals(b.get(y))) {
                tail.add(0, new int[]{x, y});
                x--;
                y--;
            }
            pairs.addAll(tail);
            return pairs;
        }
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a.get(i).equals(b.get(j)) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                pairs.add(new int[]{i, j});
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return pairs;
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) text = "";
        Collections.addAll(out, text.split("\r?\n", -1));
        return out;
    }

    // ==================== WORD (.docx) ====================

    /** Gövdedeki düzenlenebilir paragraflar (metin kutularının içindekiler hariç), belge sırasıyla. */
    static List<Element> docxParagraphs(Document doc) {
        Element body = firstChild(doc.getDocumentElement(), W_NS, "body");
        List<Element> out = new ArrayList<Element>();
        if (body == null) return out;
        for (Element p : descendants(body, W_NS, "p")) {
            if (!hasAncestor(p, W_NS, "txbxContent", body)) out.add(p);
        }
        return out;
    }

    private static void collectText(Node node, StringBuilder sb) {
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            String ln = n.getLocalName();
            if (!W_NS.equals(n.getNamespaceURI())) {
                collectText(n, sb);
                continue;
            }
            if ("t".equals(ln)) {
                sb.append(n.getTextContent());
            } else if ("tab".equals(ln) && "r".equals(n.getParentNode().getLocalName())) {
                sb.append('\t');
            } else if ("br".equals(ln) || "cr".equals(ln)) {
                sb.append(' ');
            } else if ("txbxContent".equals(ln) || "delText".equals(ln) || "instrText".equals(ln)
                    || "pPr".equals(ln) || "rPr".equals(ln)) {
                // metin kutusu, silinmiş metin ve alan kodları düzenleyicide gösterilmez
            } else {
                collectText(n, sb);
            }
        }
    }

    static String paragraphText(Element p) {
        StringBuilder sb = new StringBuilder();
        collectText(p, sb);
        return sb.toString();
    }

    public static String readDocx(File file) throws Exception {
        try (ZipFile zf = new ZipFile(file)) {
            byte[] xml = zipBytes(zf, "word/document.xml");
            if (xml == null) {
                throw new IllegalArgumentException("Geçersiz .docx dosyası: word/document.xml bulunamadı");
            }
            StringBuilder out = new StringBuilder();
            List<Element> paras = docxParagraphs(parseXml(xml));
            for (int i = 0; i < paras.size(); i++) {
                if (i > 0) out.append('\n');
                out.append(paragraphText(paras.get(i)));
            }
            return out.toString();
        }
    }

    private static boolean hasAny(Element el, String... locals) {
        for (String l : locals) {
            if (el.getElementsByTagNameNS(W_NS, l).getLength() > 0) return true;
        }
        return false;
    }

    /** Run'ı (w:r) korunması gereken bir içerik taşıyor mu (resim, alan, dipnot başvurusu...)? */
    private static boolean runHasSpecial(Element r) {
        if (hasAny(r, "drawing", "pict", "object", "fldChar", "instrText", "footnoteReference",
                "endnoteReference", "commentReference", "sym", "ptab")) return true;
        return r.getElementsByTagNameNS("*", "AlternateContent").getLength() > 0;
    }

    private static void stripRunText(Element r) {
        for (Element c : children(r, W_NS, "t")) r.removeChild(c);
        for (Element c : children(r, W_NS, "tab")) r.removeChild(c);
        for (Element c : children(r, W_NS, "br")) r.removeChild(c);
        for (Element c : children(r, W_NS, "cr")) r.removeChild(c);
    }

    private static void appendRunText(Document doc, Element r, String text) {
        String[] parts = text.split("\t", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) r.appendChild(doc.createElementNS(W_NS, "w:tab"));
            if (parts[i].isEmpty() && parts.length > 1) continue;
            Element t = doc.createElementNS(W_NS, "w:t");
            t.setAttributeNS(XML_NS, "xml:space", "preserve");
            t.setTextContent(parts[i]);
            r.appendChild(t);
        }
    }

    private static List<Element> paragraphRuns(Element p) {
        List<Element> out = new ArrayList<Element>();
        for (Element r : descendants(p, W_NS, "r")) {
            if (!hasAncestor(r, W_NS, "txbxContent", p)) out.add(r);
        }
        return out;
    }

    /** Paragraf metnini değiştirir; ilk metin run'ının biçimi (yazı tipi, kalınlık, renk) korunur. */
    static void setParagraphText(Document doc, Element p, String text) {
        List<Element> runs = paragraphRuns(p);
        Element first = null;
        for (Element r : runs) {
            if (!children(r, W_NS, "t").isEmpty()) {
                first = r;
                break;
            }
        }
        if (first == null && !runs.isEmpty()) {
            for (Element r : runs) {
                if (!runHasSpecial(r)) {
                    first = r;
                    break;
                }
            }
        }
        for (Element r : runs) {
            if (r == first) continue;
            if (runHasSpecial(r)) {
                stripRunText(r);
            } else if (r.getParentNode() != null) {
                r.getParentNode().removeChild(r);
            }
        }
        if (first == null) {
            if (text.isEmpty()) return;
            first = doc.createElementNS(W_NS, "w:r");
            p.appendChild(first);
        } else {
            stripRunText(first);
        }
        if (!text.isEmpty()) appendRunText(doc, first, text);
    }

    /** Referans paragrafın biçimiyle (stil, liste, hizalama, yazı tipi) yeni paragraf üretir. */
    static Element newParagraphLike(Document doc, Element ref, String text) {
        Element p = doc.createElementNS(W_NS, "w:p");
        if (ref != null) {
            Element pPr = firstChild(ref, W_NS, "pPr");
            if (pPr != null) {
                Element clone = (Element) pPr.cloneNode(true);
                for (Element s : children(clone, W_NS, "sectPr")) clone.removeChild(s);
                p.appendChild(clone);
            }
        }
        Element r = doc.createElementNS(W_NS, "w:r");
        if (ref != null) {
            for (Element rr : paragraphRuns(ref)) {
                if (!children(rr, W_NS, "t").isEmpty()) {
                    Element rPr = firstChild(rr, W_NS, "rPr");
                    if (rPr != null) r.appendChild(rPr.cloneNode(true));
                    break;
                }
            }
        }
        if (!text.isEmpty()) appendRunText(doc, r, text);
        p.appendChild(r);
        return p;
    }

    private static boolean canRemoveParagraph(Element p, Element body) {
        if (hasAny(p, "drawing", "pict", "object", "sectPr")) return false;
        Node parent = p.getParentNode();
        if (parent instanceof Element && "tc".equals(parent.getLocalName())) {
            if (children((Element) parent, W_NS, "p").size() <= 1) return false;
        }
        return docxParagraphCount(body) > 1;
    }

    private static int docxParagraphCount(Element body) {
        return children(body, W_NS, "p").size() + children(body, W_NS, "tbl").size();
    }

    /** Var olan .docx'i temel alarak kaydeder; şablon yoksa yeni belge oluşturur. */
    public static void saveDocx(File target, String text, File template) throws Exception {
        if (template == null || !template.isFile()) {
            writeDocx(target, text);
            return;
        }
        byte[] xml;
        try (ZipFile zf = new ZipFile(template)) {
            xml = zipBytes(zf, "word/document.xml");
        }
        if (xml == null) throw new IllegalArgumentException("Geçersiz .docx dosyası");
        Document doc = parseXml(xml);
        Element body = firstChild(doc.getDocumentElement(), W_NS, "body");
        List<Element> paras = docxParagraphs(doc);
        List<String> oldLines = new ArrayList<String>();
        for (Element p : paras) oldLines.add(paragraphText(p));
        List<String> newLines = splitLines(text);

        List<int[]> anchors = lcsPairs(oldLines, newLines);
        anchors.add(new int[]{oldLines.size(), newLines.size()});
        int oi = 0;
        int nj = 0;
        Element last = null;
        for (int[] anc : anchors) {
            int oldCount = anc[0] - oi;
            int newCount = anc[1] - nj;
            int common = Math.min(oldCount, newCount);
            for (int k = 0; k < common; k++) {
                Element p = paras.get(oi + k);
                setParagraphText(doc, p, newLines.get(nj + k));
                last = p;
            }
            for (int k = common; k < newCount; k++) {
                Element ref = last != null ? last : (oi < paras.size() ? paras.get(oi) : (paras.isEmpty() ? null : paras.get(paras.size() - 1)));
                Element np = newParagraphLike(doc, ref, newLines.get(nj + k));
                if (last != null) {
                    insertAfter(np, last);
                } else if (oi < paras.size()) {
                    paras.get(oi).getParentNode().insertBefore(np, paras.get(oi));
                } else if (body != null) {
                    Element sect = firstChild(body, W_NS, "sectPr");
                    if (sect != null) body.insertBefore(np, sect);
                    else body.appendChild(np);
                }
                last = np;
            }
            for (int k = common; k < oldCount; k++) {
                Element p = paras.get(oi + k);
                if (body != null && canRemoveParagraph(p, body)) {
                    p.getParentNode().removeChild(p);
                } else {
                    setParagraphText(doc, p, "");
                }
            }
            if (anc[0] < paras.size()) last = paras.get(anc[0]);
            oi = anc[0] + 1;
            nj = anc[1] + 1;
        }
        Map<String, byte[]> replaced = new HashMap<String, byte[]>();
        replaced.put("word/document.xml", serializeXml(doc));
        rewriteZip(template, target, replaced, Collections.<String>emptySet());
    }

    /** Yeni, boş bir Word belgesi oluşturup metni yazar. */
    public static void writeDocx(File file, String text) throws Exception {
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"" + CT_NS + "\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"" + PKG_REL_NS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";
        StringBuilder body = new StringBuilder();
        for (String line : splitLines(text)) {
            body.append("<w:p><w:r><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr>")
                    .append("<w:t xml:space=\"preserve\">").append(xmlEscape(line)).append("</w:t></w:r></w:p>");
        }
        String docXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:document xmlns:w=\"" + W_NS + "\"><w:body>" + body
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1417\" w:right=\"1417\" w:bottom=\"1417\" w:left=\"1417\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>"
                + "</w:sectPr></w:body></w:document>";
        File tmp = new File(file.getAbsolutePath() + ".efos-tmp");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
            putZipText(zos, "[Content_Types].xml", contentTypes);
            putZipText(zos, "_rels/.rels", rels);
            putZipText(zos, "word/document.xml", docXml);
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            if (!tmp.renameTo(file)) throw new IOException("Dosya kaydedilemedi: " + file.getName());
        }
    }

    // ==================== EXCEL (.xlsx / .csv) ====================

    /** Çalışma kitabının ilk sayfası ve biçim bilgileri. */
    static final class XlsxBook {
        String sheetPath = "xl/worksheets/sheet1.xml";
        String sheetName = "Sayfa1";
        final List<String> shared = new ArrayList<String>();
        final List<Integer> xfNumFmt = new ArrayList<Integer>();
        final Map<Integer, String> customFormats = new HashMap<Integer, String>();

        boolean isDateStyle(int s) {
            if (s < 0 || s >= xfNumFmt.size()) return false;
            int id = xfNumFmt.get(s);
            if ((id >= 14 && id <= 22) || (id >= 45 && id <= 47)) return true;
            String code = customFormats.get(id);
            if (code == null) return false;
            String bare = code.replaceAll("\"[^\"]*\"", "").replaceAll("\\[[^\\]]*\\]", "").replaceAll("\\\\.", "");
            return bare.matches("(?i).*[dy].*") || (bare.matches("(?i).*m.*") && !bare.contains("0") && !bare.contains("#"));
        }

        boolean isPercentStyle(int s) {
            if (s < 0 || s >= xfNumFmt.size()) return false;
            int id = xfNumFmt.get(s);
            if (id == 9 || id == 10) return true;
            String code = customFormats.get(id);
            return code != null && code.replaceAll("\"[^\"]*\"", "").contains("%");
        }
    }

    private static String resolveTarget(String baseDir, String target) {
        if (target.startsWith("/")) return target.substring(1);
        String path = baseDir + target;
        List<String> parts = new ArrayList<String>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(seg);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        return sb.toString();
    }

    private static Map<String, String[]> readRels(ZipFile zf, String relsPath, String baseDir) throws Exception {
        Map<String, String[]> out = new LinkedHashMap<String, String[]>();
        byte[] b = zipBytes(zf, relsPath);
        if (b == null) return out;
        for (Element r : descendants(parseXml(b).getDocumentElement(), PKG_REL_NS, "Relationship")) {
            String target = r.getAttribute("Target");
            boolean external = "External".equals(r.getAttribute("TargetMode"));
            out.put(r.getAttribute("Id"), new String[]{external ? target : resolveTarget(baseDir, target), r.getAttribute("Type")});
        }
        return out;
    }

    private static String textOf(Element el) {
        StringBuilder sb = new StringBuilder();
        for (Element t : descendants(el, S_NS, "t")) {
            if (!hasAncestor(t, S_NS, "rPh", el)) sb.append(t.getTextContent());
        }
        return sb.toString();
    }

    static XlsxBook loadBook(ZipFile zf) throws Exception {
        XlsxBook book = new XlsxBook();
        byte[] wb = zipBytes(zf, "xl/workbook.xml");
        if (wb != null) {
            Element sheets = firstChild(parseXml(wb).getDocumentElement(), S_NS, "sheets");
            Element first = sheets == null ? null : firstChild(sheets, S_NS, "sheet");
            if (first != null) {
                book.sheetName = first.getAttribute("name");
                String rid = first.getAttributeNS(R_NS, "id");
                String[] rel = readRels(zf, "xl/_rels/workbook.xml.rels", "xl/").get(rid);
                if (rel != null) book.sheetPath = rel[0];
            }
        }
        if (zf.getEntry(book.sheetPath) == null) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("xl/worksheets/") && n.endsWith(".xml")) {
                    book.sheetPath = n;
                    break;
                }
            }
        }
        byte[] ss = zipBytes(zf, "xl/sharedStrings.xml");
        if (ss != null) {
            for (Element si : children(parseXml(ss).getDocumentElement(), S_NS, "si")) book.shared.add(textOf(si));
        }
        byte[] st = zipBytes(zf, "xl/styles.xml");
        if (st != null) {
            Element root = parseXml(st).getDocumentElement();
            Element numFmts = firstChild(root, S_NS, "numFmts");
            if (numFmts != null) {
                for (Element nf : children(numFmts, S_NS, "numFmt")) {
                    try {
                        book.customFormats.put(Integer.parseInt(nf.getAttribute("numFmtId")), nf.getAttribute("formatCode"));
                    } catch (NumberFormatException ignored) {}
                }
            }
            Element cellXfs = firstChild(root, S_NS, "cellXfs");
            if (cellXfs != null) {
                for (Element xf : children(cellXfs, S_NS, "xf")) {
                    int id = 0;
                    try {
                        id = Integer.parseInt(xf.getAttribute("numFmtId"));
                    } catch (NumberFormatException ignored) {}
                    book.xfNumFmt.add(id);
                }
            }
        }
        return book;
    }

    private static String plainNumber(String v) {
        try {
            BigDecimal bd = new BigDecimal(v.trim());
            String s = bd.stripTrailingZeros().toPlainString();
            return s.replace('.', ',');
        } catch (NumberFormatException ex) {
            return v;
        }
    }

    /** Sayı/tarih gibi görünen metinler ' önekiyle metin olarak gösterilir (Excel'deki gibi). */
    private static String protectText(String text) {
        if (text.isEmpty() || FormulaEngine.isTextNumber(text)) return text;
        String up = FormulaEngine.trUpper(text.trim());
        if (FormulaEngine.toNumber(text) != null || FormulaEngine.parseDateText(text) != null
                || "DOĞRU".equals(up) || "YANLIŞ".equals(up) || "TRUE".equals(up) || "FALSE".equals(up)
                || text.startsWith("'")) {
            return "'" + text;
        }
        return text;
    }

    static int styleIndex(Element c) {
        String s = c.getAttribute("s");
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** Hücrenin düzenleyicide gösterilecek ham metni. */
    static String cellDisplay(Element c, XlsxBook book) {
        Element f = firstChild(c, S_NS, "f");
        if (f != null && !f.getTextContent().trim().isEmpty()) {
            String ft = f.getTextContent().trim();
            return FormulaEngine.fromExcelFormula(ft.startsWith("=") ? ft : "=" + ft);
        }
        String type = c.getAttribute("t");
        Element v = firstChild(c, S_NS, "v");
        String vText = v == null ? null : v.getTextContent();
        if ("s".equals(type)) {
            if (vText == null) return "";
            try {
                int idx = Integer.parseInt(vText.trim());
                return idx >= 0 && idx < book.shared.size() ? protectText(book.shared.get(idx)) : "";
            } catch (NumberFormatException ex) {
                return "";
            }
        }
        if ("inlineStr".equals(type)) {
            Element is = firstChild(c, S_NS, "is");
            return is == null ? "" : protectText(textOf(is));
        }
        if ("str".equals(type)) return vText == null ? "" : protectText(vText);
        if ("b".equals(type)) return "1".equals(vText) ? "DOĞRU" : ("0".equals(vText) ? "YANLIŞ" : "");
        if ("e".equals(type)) return vText == null ? "" : vText;
        if (vText == null || vText.trim().isEmpty()) return "";
        int s = styleIndex(c);
        try {
            double d = Double.parseDouble(vText.trim());
            if (book.isDateStyle(s)) return FormulaEngine.formatDate(d, d != Math.floor(d));
            if (book.isPercentStyle(s)) return plainNumber(new BigDecimal(vText.trim()).movePointRight(2).toPlainString()) + "%";
        } catch (NumberFormatException ignored) {}
        return plainNumber(vText);
    }

    private static Map<String, String> sheetCells(Document sheet, XlsxBook book, Map<String, Element> elements) {
        Map<String, String> cells = new HashMap<String, String>();
        for (Element c : descendants(sheet.getDocumentElement(), S_NS, "c")) {
            int[] coord = FormulaEngine.parseCellRef(c.getAttribute("r"));
            if (coord == null) continue;
            String key = FormulaEngine.cellKey(coord[0], coord[1]);
            if (elements != null) elements.put(key, c);
            String disp = cellDisplay(c, book);
            if (!disp.isEmpty()) cells.put(key, disp);
        }
        return cells;
    }

    public static Map<String, String> readXlsx(File file) throws Exception {
        try (ZipFile zf = new ZipFile(file)) {
            XlsxBook book = loadBook(zf);
            byte[] sheet = zipBytes(zf, book.sheetPath);
            if (sheet == null) throw new IllegalArgumentException("Geçersiz .xlsx dosyası: çalışma sayfası bulunamadı");
            return sheetCells(parseXml(sheet), book, null);
        }
    }

    /** Dosyadaki sayfa sayısı (Android yalnızca ilkini düzenler, diğerlerini korur). */
    public static int xlsxSheetCount(File file) {
        try (ZipFile zf = new ZipFile(file)) {
            byte[] wb = zipBytes(zf, "xl/workbook.xml");
            if (wb == null) return 1;
            Element sheets = firstChild(parseXml(wb).getDocumentElement(), S_NS, "sheets");
            return sheets == null ? 1 : Math.max(1, children(sheets, S_NS, "sheet").size());
        } catch (Exception ex) {
            return 1;
        }
    }

    private static int[] keyToRc(String key) {
        String[] p = key.split(":");
        return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
    }

    private static Element findOrCreateCell(Document doc, Element sheetData, int r, int c) {
        Element row = null;
        Element rowAfter = null;
        for (Element rr : children(sheetData, S_NS, "row")) {
            int rn;
            try {
                rn = Integer.parseInt(rr.getAttribute("r"));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (rn == r + 1) {
                row = rr;
                break;
            }
            if (rn > r + 1) {
                rowAfter = rr;
                break;
            }
        }
        if (row == null) {
            row = doc.createElementNS(S_NS, "row");
            row.setAttribute("r", String.valueOf(r + 1));
            if (rowAfter != null) sheetData.insertBefore(row, rowAfter);
            else sheetData.appendChild(row);
        }
        row.removeAttribute("spans");
        String ref = FormulaEngine.indexToColLetters(c) + (r + 1);
        Element after = null;
        for (Element cc : children(row, S_NS, "c")) {
            int[] rc = FormulaEngine.parseCellRef(cc.getAttribute("r"));
            if (rc == null) continue;
            if (rc[1] == c) return cc;
            if (rc[1] > c) {
                after = cc;
                break;
            }
        }
        Element cell = doc.createElementNS(S_NS, "c");
        cell.setAttribute("r", ref);
        if (after != null) row.insertBefore(cell, after);
        else row.appendChild(cell);
        return cell;
    }

    /** styles.xml içinde tarih biçimli (numFmtId=14) bir hücre stili bulur veya ekler. */
    private static int ensureDateStyle(Document styles, XlsxBook book) {
        for (int i = 0; i < book.xfNumFmt.size(); i++) {
            if (book.xfNumFmt.get(i) == 14) return i;
        }
        Element cellXfs = firstChild(styles.getDocumentElement(), S_NS, "cellXfs");
        if (cellXfs == null) return 0;
        List<Element> xfs = children(cellXfs, S_NS, "xf");
        Element xf = xfs.isEmpty() ? styles.createElementNS(S_NS, "xf") : (Element) xfs.get(0).cloneNode(true);
        xf.setAttribute("numFmtId", "14");
        xf.setAttribute("applyNumberFormat", "1");
        if (!xf.hasAttribute("fontId")) xf.setAttribute("fontId", "0");
        if (!xf.hasAttribute("fillId")) xf.setAttribute("fillId", "0");
        if (!xf.hasAttribute("borderId")) xf.setAttribute("borderId", "0");
        cellXfs.appendChild(xf);
        cellXfs.setAttribute("count", String.valueOf(xfs.size() + 1));
        book.xfNumFmt.add(14);
        return xfs.size();
    }

    private static void setCellRaw(Document doc, Element c, String raw, XlsxBook book, Document styles, boolean[] stylesChanged) {
        for (String n : new String[]{"f", "v", "is"}) {
            for (Element e : children(c, S_NS, n)) c.removeChild(e);
        }
        c.removeAttribute("t");
        if (raw == null || raw.isEmpty()) return;
        String trimmed = raw.trim();
        if (trimmed.startsWith("=")) {
            Element f = doc.createElementNS(S_NS, "f");
            f.setTextContent(FormulaEngine.toExcelFormula(trimmed).substring(1));
            c.appendChild(f);
            return;
        }
        if (raw.startsWith("'") || FormulaEngine.isTextNumber(trimmed)) {
            inlineString(doc, c, raw.startsWith("'") ? raw.substring(1) : raw);
            return;
        }
        String up = FormulaEngine.trUpper(trimmed);
        if ("DOĞRU".equals(up) || "TRUE".equals(up) || "YANLIŞ".equals(up) || "FALSE".equals(up)) {
            c.setAttribute("t", "b");
            Element v = doc.createElementNS(S_NS, "v");
            v.setTextContent(("DOĞRU".equals(up) || "TRUE".equals(up)) ? "1" : "0");
            c.appendChild(v);
            return;
        }
        FormulaEngine.DateVal dv = FormulaEngine.parseDateText(trimmed);
        if (dv != null) {
            Element v = doc.createElementNS(S_NS, "v");
            v.setTextContent(new BigDecimal(Double.toString(dv.serial)).stripTrailingZeros().toPlainString());
            c.appendChild(v);
            if (!book.isDateStyle(styleIndex(c)) && styles != null) {
                c.setAttribute("s", String.valueOf(ensureDateStyle(styles, book)));
                stylesChanged[0] = true;
            }
            return;
        }
        Double num = FormulaEngine.toNumber(trimmed);
        if (num != null) {
            Element v = doc.createElementNS(S_NS, "v");
            v.setTextContent(new BigDecimal(Double.toString(num)).stripTrailingZeros().toPlainString());
            c.appendChild(v);
            return;
        }
        inlineString(doc, c, raw);
    }

    private static void inlineString(Document doc, Element c, String text) {
        c.setAttribute("t", "inlineStr");
        Element is = doc.createElementNS(S_NS, "is");
        Element t = doc.createElementNS(S_NS, "t");
        t.setAttributeNS(XML_NS, "xml:space", "preserve");
        t.setTextContent(text);
        is.appendChild(t);
        c.appendChild(is);
    }

    /** Ana hücresi değişen paylaşımlı formüllerin bağımlı hücrelerini sabit değere çevirir. */
    private static void detachSharedFormula(Element sheetRoot, Element master) {
        Element f = firstChild(master, S_NS, "f");
        if (f == null || !"shared".equals(f.getAttribute("t")) || !f.hasAttribute("ref")) return;
        String si = f.getAttribute("si");
        for (Element other : descendants(sheetRoot, S_NS, "f")) {
            if (other != f && "shared".equals(other.getAttribute("t")) && si.equals(other.getAttribute("si"))) {
                other.getParentNode().removeChild(other);
            }
        }
    }

    private static final String[] CALC_PR_FOLLOWERS = {"oleSize", "customWorkbookViews", "pivotCaches",
            "smartTagPr", "smartTagTypes", "webPublishing", "fileRecoveryPr", "webPublishObjects", "extLst"};

    /** Tablonun ilk sayfasını, orijinal dosyadaki yalnızca değişen hücreleri güncelleyerek kaydeder. */
    public static void saveXlsx(File target, Map<String, String> cells, File template) throws Exception {
        if (template == null || !template.isFile()) {
            writeXlsx(target, cells, "Sayfa1");
            return;
        }
        Map<String, byte[]> replaced = new HashMap<String, byte[]>();
        Set<String> removed = new HashSet<String>();
        try (ZipFile zf = new ZipFile(template)) {
            XlsxBook book = loadBook(zf);
            byte[] sheetBytes = zipBytes(zf, book.sheetPath);
            if (sheetBytes == null) throw new IllegalArgumentException("Çalışma sayfası bulunamadı");
            Document sheet = parseXml(sheetBytes);
            Map<String, Element> elements = new HashMap<String, Element>();
            Map<String, String> old = sheetCells(sheet, book, elements);

            Set<String> keys = new HashSet<String>(old.keySet());
            keys.addAll(cells.keySet());
            List<String> changed = new ArrayList<String>();
            for (String k : keys) {
                String a = old.get(k);
                String b = cells.get(k);
                if (!(a == null ? "" : a).equals(b == null ? "" : b)) changed.add(k);
            }
            if (changed.isEmpty()) {
                if (!template.getCanonicalPath().equals(target.getCanonicalPath())) {
                    rewriteZip(template, target, replaced, removed);
                }
                return;
            }

            Element root = sheet.getDocumentElement();
            Element sheetData = firstChild(root, S_NS, "sheetData");
            if (sheetData == null) {
                sheetData = sheet.createElementNS(S_NS, "sheetData");
                root.appendChild(sheetData);
            }
            byte[] stylesBytes = zipBytes(zf, "xl/styles.xml");
            Document styles = stylesBytes == null ? null : parseXml(stylesBytes);
            boolean[] stylesChanged = {false};
            int maxR = 0;
            int maxC = 0;
            for (String k : changed) {
                int[] rc = keyToRc(k);
                maxR = Math.max(maxR, rc[0]);
                maxC = Math.max(maxC, rc[1]);
                Element c = elements.get(k);
                if (c != null) detachSharedFormula(root, c);
                if (c == null) {
                    String v = cells.get(k);
                    if (v == null || v.isEmpty()) continue;
                    c = findOrCreateCell(sheet, sheetData, rc[0], rc[1]);
                }
                setCellRaw(sheet, c, cells.get(k), book, styles, stylesChanged);
            }
            Element dim = firstChild(root, S_NS, "dimension");
            if (dim != null) {
                String ref = dim.getAttribute("ref");
                String[] parts = ref.split(":");
                int[] end = FormulaEngine.parseCellRef(parts[parts.length - 1]);
                int[] start = FormulaEngine.parseCellRef(parts[0]);
                if (end != null && start != null) {
                    int er = Math.max(end[0], maxR);
                    int ec = Math.max(end[1], maxC);
                    dim.setAttribute("ref", FormulaEngine.indexToColLetters(start[1]) + (start[0] + 1) + ":"
                            + FormulaEngine.indexToColLetters(ec) + (er + 1));
                }
            }
            replaced.put(book.sheetPath, serializeXml(sheet));
            if (stylesChanged[0] && styles != null) replaced.put("xl/styles.xml", serializeXml(styles));

            // Formül önbelleği (calcChain) artık geçersiz: kaldır ve açılışta yeniden hesaplat
            if (zf.getEntry("xl/calcChain.xml") != null) {
                removed.add("xl/calcChain.xml");
                byte[] ct = zipBytes(zf, "[Content_Types].xml");
                if (ct != null) {
                    Document ctDoc = parseXml(ct);
                    for (Element o : descendants(ctDoc.getDocumentElement(), CT_NS, "Override")) {
                        if ("/xl/calcChain.xml".equals(o.getAttribute("PartName"))) o.getParentNode().removeChild(o);
                    }
                    replaced.put("[Content_Types].xml", serializeXml(ctDoc));
                }
                byte[] wr = zipBytes(zf, "xl/_rels/workbook.xml.rels");
                if (wr != null) {
                    Document wrDoc = parseXml(wr);
                    for (Element r : descendants(wrDoc.getDocumentElement(), PKG_REL_NS, "Relationship")) {
                        if (r.getAttribute("Type").endsWith("/calcChain")) r.getParentNode().removeChild(r);
                    }
                    replaced.put("xl/_rels/workbook.xml.rels", serializeXml(wrDoc));
                }
            }
            byte[] wb = zipBytes(zf, "xl/workbook.xml");
            if (wb != null) {
                Document wbDoc = parseXml(wb);
                Element wroot = wbDoc.getDocumentElement();
                Element calcPr = firstChild(wroot, S_NS, "calcPr");
                if (calcPr == null) {
                    calcPr = wbDoc.createElementNS(S_NS, "calcPr");
                    Element before = null;
                    for (String f : CALC_PR_FOLLOWERS) {
                        before = firstChild(wroot, S_NS, f);
                        if (before != null) break;
                    }
                    if (before != null) wroot.insertBefore(calcPr, before);
                    else wroot.appendChild(calcPr);
                }
                calcPr.setAttribute("fullCalcOnLoad", "1");
                replaced.put("xl/workbook.xml", serializeXml(wbDoc));
            }
        }
        rewriteZip(template, target, replaced, removed);
    }

    /** Yeni bir çalışma kitabı oluşturur (tek sayfa) ve hücreleri yazar. */
    public static void writeXlsx(File file, Map<String, String> cells, String sheetName) throws Exception {
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        String safeName = (sheetName == null || sheetName.trim().isEmpty()) ? "Sayfa1" : sheetName.trim();
        if (safeName.length() > 31) safeName = safeName.substring(0, 31);
        String ct = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Types xmlns=\"" + CT_NS + "\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "</Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"" + PKG_REL_NS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
        String wb = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<workbook xmlns=\"" + S_NS + "\" xmlns:r=\"" + R_NS + "\">"
                + "<sheets><sheet name=\"" + xmlEscape(safeName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "<calcPr fullCalcOnLoad=\"1\"/></workbook>";
        String wbRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"" + PKG_REL_NS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>";
        String styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<styleSheet xmlns=\"" + S_NS + "\">"
                + "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"14\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
        String sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<worksheet xmlns=\"" + S_NS + "\"><sheetData/></worksheet>";
        File tmp = new File(file.getAbsolutePath() + ".efos-new");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
            putZipText(zos, "[Content_Types].xml", ct);
            putZipText(zos, "_rels/.rels", rels);
            putZipText(zos, "xl/workbook.xml", wb);
            putZipText(zos, "xl/_rels/workbook.xml.rels", wbRels);
            putZipText(zos, "xl/styles.xml", styles);
            putZipText(zos, "xl/worksheets/sheet1.xml", sheet);
        }
        try {
            saveXlsx(file, cells, tmp);
        } finally {
            tmp.delete();
        }
    }

    public static Map<String, String> readCsv(File file) throws IOException {
        String text = readUtf8(file);
        int semi = 0;
        int comma = 0;
        int tab = 0;
        int limit = Math.min(text.length(), 4096);
        for (int i = 0; i < limit; i++) {
            char ch = text.charAt(i);
            if (ch == ';') semi++;
            else if (ch == ',') comma++;
            else if (ch == '\t') tab++;
        }
        char delim = tab > semi && tab > comma ? '\t' : (semi >= comma && semi > 0 ? ';' : ',');
        Map<String, String> out = new HashMap<String, String>();
        int r = 0;
        int c = 0;
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i <= text.length(); i++) {
            char ch = i < text.length() ? text.charAt(i) : '\n';
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            if (ch == '"' && field.length() == 0) {
                inQuotes = true;
            } else if (ch == delim) {
                if (field.length() > 0) out.put(FormulaEngine.cellKey(r, c), field.toString());
                field.setLength(0);
                c++;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (field.length() > 0) out.put(FormulaEngine.cellKey(r, c), field.toString());
                field.setLength(0);
                if (i < text.length()) {
                    r++;
                    c = 0;
                }
            } else {
                field.append(ch);
            }
        }
        return out;
    }

    public static void writeCsv(File file, Map<String, String> cells, char delimiter) throws IOException {
        int maxR = -1;
        int maxC = -1;
        for (Map.Entry<String, String> e : cells.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            int[] rc = keyToRc(e.getKey());
            maxR = Math.max(maxR, rc[0]);
            maxC = Math.max(maxC, rc[1]);
        }
        StringBuilder sb = new StringBuilder("﻿");
        for (int r = 0; r <= maxR; r++) {
            for (int c = 0; c <= maxC; c++) {
                if (c > 0) sb.append(delimiter);
                String v = cells.get(FormulaEngine.cellKey(r, c));
                if (v == null) v = "";
                if (v.startsWith("'")) v = v.substring(1);
                if (v.indexOf(delimiter) >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
                    sb.append('"').append(v.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(v);
                }
            }
            sb.append("\r\n");
        }
        writeUtf8(file, sb.toString());
    }

    // ==================== POWERPOINT (.pptx) ====================

    /** Sunudaki slayt parçalarının, sunum sırasına göre yolları. */
    static List<String> pptxSlidePaths(ZipFile zf) throws Exception {
        List<String> paths = new ArrayList<String>();
        byte[] pres = zipBytes(zf, "ppt/presentation.xml");
        if (pres != null) {
            Map<String, String[]> rels = readRels(zf, "ppt/_rels/presentation.xml.rels", "ppt/");
            Element lst = firstChild(parseXml(pres).getDocumentElement(), P_NS, "sldIdLst");
            if (lst != null) {
                for (Element id : children(lst, P_NS, "sldId")) {
                    String[] rel = rels.get(id.getAttributeNS(R_NS, "id"));
                    if (rel != null && zf.getEntry(rel[0]) != null) paths.add(rel[0]);
                }
            }
        }
        if (paths.isEmpty()) {
            final Pattern slidePat = Pattern.compile("^ppt/slides/slide(\\d+)\\.xml$");
            List<String> found = new ArrayList<String>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (slidePat.matcher(n).matches()) found.add(n);
            }
            Collections.sort(found, new java.util.Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    Matcher ma = slidePat.matcher(a);
                    Matcher mb = slidePat.matcher(b);
                    ma.matches();
                    mb.matches();
                    return Integer.compare(Integer.parseInt(ma.group(1)), Integer.parseInt(mb.group(1)));
                }
            });
            paths.addAll(found);
        }
        return paths;
    }

    /** Slayttaki metin şekilleri (slayt no/tarih/altbilgi yer tutucuları hariç). */
    static List<Element> slideTextShapes(Document slide) {
        List<Element> out = new ArrayList<Element>();
        for (Element sp : descendants(slide.getDocumentElement(), P_NS, "sp")) {
            Element txBody = firstChild(sp, P_NS, "txBody");
            if (txBody == null) continue;
            boolean footer = false;
            for (Element ph : descendants(sp, P_NS, "ph")) {
                String t = ph.getAttribute("type");
                if ("sldNum".equals(t) || "dt".equals(t) || "ftr".equals(t)) footer = true;
            }
            if (footer) continue;
            if (shapeText(sp).trim().isEmpty()) continue;
            out.add(sp);
        }
        return out;
    }

    static String shapeText(Element sp) {
        Element txBody = firstChild(sp, P_NS, "txBody");
        if (txBody == null) return "";
        StringBuilder sb = new StringBuilder();
        List<Element> paras = children(txBody, A_NS, "p");
        for (int i = 0; i < paras.size(); i++) {
            if (i > 0) sb.append('\n');
            for (Node n = paras.get(i).getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element)) continue;
                String ln = n.getLocalName();
                if ("r".equals(ln) || "fld".equals(ln)) {
                    Element t = firstChild((Element) n, A_NS, "t");
                    if (t != null) sb.append(t.getTextContent());
                } else if ("br".equals(ln)) {
                    sb.append(' ');
                }
            }
        }
        return sb.toString().replaceAll("\\s+$", "");
    }

    private static SlideItem slideItemFrom(Document slide, int index) {
        List<String> blocks = new ArrayList<String>();
        for (Element sp : slideTextShapes(slide)) blocks.add(shapeText(sp));
        String title = blocks.isEmpty() ? ("Slayt " + (index + 1)) : blocks.get(0);
        StringBuilder body = new StringBuilder();
        for (int i = 1; i < blocks.size(); i++) {
            if (body.length() > 0) body.append("\n\n");
            body.append(blocks.get(i));
        }
        return new SlideItem(title, body.toString(), "");
    }

    public static List<SlideItem> readPptx(File file) throws Exception {
        List<SlideItem> slides = new ArrayList<SlideItem>();
        try (ZipFile zf = new ZipFile(file)) {
            List<String> paths = pptxSlidePaths(zf);
            for (int s = 0; s < paths.size(); s++) {
                slides.add(slideItemFrom(parseXml(zipBytes(zf, paths.get(s))), s));
            }
        }
        if (slides.isEmpty()) slides.add(new SlideItem("EFOS-Ofis Sunu", "", ""));
        return slides;
    }

    /** Şeklin metnini değiştirir; paragraf düzeyi (madde işareti) ve ilk run biçimi korunur. */
    static void setShapeText(Document doc, Element sp, String text) {
        Element txBody = firstChild(sp, P_NS, "txBody");
        if (txBody == null) return;
        List<String> lines = splitLines(text);
        List<Element> paras = children(txBody, A_NS, "p");
        if (paras.isEmpty()) {
            Element p = doc.createElementNS(A_NS, "a:p");
            txBody.appendChild(p);
            paras.add(p);
        }
        while (paras.size() < lines.size()) {
            Element clone = (Element) paras.get(paras.size() - 1).cloneNode(true);
            insertAfter(clone, paras.get(paras.size() - 1));
            paras.add(clone);
        }
        for (int i = 0; i < lines.size(); i++) {
            Element p = paras.get(i);
            Element firstRun = null;
            for (Node n = p.getFirstChild(); n != null; ) {
                Node next = n.getNextSibling();
                if (n instanceof Element) {
                    String ln = n.getLocalName();
                    if ("r".equals(ln) && firstRun == null) {
                        firstRun = (Element) n;
                    } else if ("r".equals(ln) || "br".equals(ln) || "fld".equals(ln)) {
                        p.removeChild(n);
                    }
                }
                n = next;
            }
            String line = lines.get(i);
            if (firstRun == null) {
                if (line.isEmpty()) continue;
                firstRun = doc.createElementNS(A_NS, "a:r");
                Element endPr = firstChild(p, A_NS, "endParaRPr");
                if (endPr != null) {
                    Element rPr = doc.createElementNS(A_NS, "a:rPr");
                    org.w3c.dom.NamedNodeMap attrs = endPr.getAttributes();
                    for (int k = 0; k < attrs.getLength(); k++) {
                        Node at = attrs.item(k);
                        rPr.setAttributeNS(at.getNamespaceURI(), at.getNodeName(), at.getNodeValue());
                    }
                    for (Node ch = endPr.getFirstChild(); ch != null; ch = ch.getNextSibling()) {
                        rPr.appendChild(ch.cloneNode(true));
                    }
                    firstRun.appendChild(rPr);
                    p.insertBefore(firstRun, endPr);
                } else {
                    p.appendChild(firstRun);
                }
                firstRun.appendChild(doc.createElementNS(A_NS, "a:t"));
            }
            Element t = firstChild(firstRun, A_NS, "t");
            if (t == null) {
                t = doc.createElementNS(A_NS, "a:t");
                firstRun.appendChild(t);
            }
            t.setTextContent(line);
        }
        for (int i = lines.size(); i < paras.size(); i++) txBody.removeChild(paras.get(i));
    }

    private static int maxShapeId(Document slide) {
        int max = 1;
        for (Element c : descendants(slide.getDocumentElement(), P_NS, "cNvPr")) {
            try {
                max = Math.max(max, Integer.parseInt(c.getAttribute("id")));
            } catch (NumberFormatException ignored) {}
        }
        return max;
    }

    private static String textBoxXml(int id, String name, long x, long y, long cx, long cy, String text,
                                     int sizePt, boolean bold, String color) {
        return "<p:sp xmlns:p=\"" + P_NS + "\" xmlns:a=\"" + A_NS + "\">"
                + "<p:nvSpPr><p:cNvPr id=\"" + id + "\" name=\"" + xmlEscape(name) + "\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>"
                + "<p:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>"
                + "<p:txBody><a:bodyPr wrap=\"square\"><a:normAutofit/></a:bodyPr><a:lstStyle/>"
                + paragraphsXml(text, sizePt, bold, color) + "</p:txBody></p:sp>";
    }

    private static String paragraphsXml(String text, int sizePt, boolean bold, String colorHex) {
        StringBuilder sb = new StringBuilder();
        for (String line : splitLines(text)) {
            sb.append("<a:p><a:r><a:rPr lang=\"tr-TR\" sz=\"").append(sizePt * 100)
                    .append("\" b=\"").append(bold ? "1" : "0")
                    .append("\" dirty=\"0\"><a:solidFill><a:srgbClr val=\"").append(colorHex)
                    .append("\"/></a:solidFill><a:latin typeface=\"Calibri\"/></a:rPr><a:t>").append(xmlEscape(line))
                    .append("</a:t></a:r></a:p>");
        }
        return sb.toString();
    }

    private static void appendShape(Document slide, String xml) throws Exception {
        Element spTree = firstChild(firstChild(slide.getDocumentElement(), P_NS, "cSld"), P_NS, "spTree");
        Document frag = parseXml(xml.getBytes(StandardCharsets.UTF_8));
        spTree.appendChild(slide.importNode(frag.getDocumentElement(), true));
    }

    private static long[] slideSize(Document pres) {
        Element sz = firstChild(pres.getDocumentElement(), P_NS, "sldSz");
        long cx = 12192000L;
        long cy = 6858000L;
        if (sz != null) {
            try {
                cx = Long.parseLong(sz.getAttribute("cx"));
                cy = Long.parseLong(sz.getAttribute("cy"));
            } catch (NumberFormatException ignored) {}
        }
        return new long[]{cx, cy};
    }

    private static void updateSlide(Document slide, SlideItem item, SlideItem orig, long[] size) throws Exception {
        List<Element> shapes = slideTextShapes(slide);
        long cx = size[0];
        long cy = size[1];
        if (!item.title.equals(orig.title)) {
            if (!shapes.isEmpty()) {
                setShapeText(slide, shapes.get(0), item.title);
            } else if (!item.title.isEmpty()) {
                appendShape(slide, textBoxXml(maxShapeId(slide) + 1, "Başlık", cx / 16, cy / 15, cx * 7 / 8, cy / 7,
                        item.title, 30, true, "1F2937"));
            }
        }
        if (!item.body.equals(orig.body)) {
            List<Element> bodies = shapes.size() > 1 ? shapes.subList(1, shapes.size()) : new ArrayList<Element>();
            if (bodies.isEmpty()) {
                if (!item.body.isEmpty()) {
                    appendShape(slide, textBoxXml(maxShapeId(slide) + 1, "İçerik", cx / 16, cy / 4, cx * 7 / 8, cy * 2 / 3,
                            item.body, 20, false, "111827"));
                }
            } else {
                String[] parts = item.body.split("\n\n", -1);
                if (parts.length == bodies.size()) {
                    for (int i = 0; i < parts.length; i++) {
                        if (!shapeText(bodies.get(i)).equals(parts[i])) setShapeText(slide, bodies.get(i), parts[i]);
                    }
                } else {
                    setShapeText(slide, bodies.get(0), item.body);
                    for (int i = 1; i < bodies.size(); i++) setShapeText(slide, bodies.get(i), "");
                }
            }
        }
    }

    /** Var olan sunuyu temel alarak kaydeder: değişen slayt metinleri güncellenir, yeni slaytlar eklenir. */
    public static void savePptx(File target, List<SlideItem> slides, File template) throws Exception {
        if (template == null || !template.isFile()) {
            writePptx(target, slides);
            return;
        }
        Map<String, byte[]> replaced = new HashMap<String, byte[]>();
        Set<String> removed = new HashSet<String>();
        try (ZipFile zf = new ZipFile(template)) {
            List<String> paths = pptxSlidePaths(zf);
            Document pres = parseXml(zipBytes(zf, "ppt/presentation.xml"));
            long[] size = slideSize(pres);
            int common = Math.min(paths.size(), slides.size());
            for (int i = 0; i < common; i++) {
                Document slide = parseXml(zipBytes(zf, paths.get(i)));
                SlideItem orig = slideItemFrom(slide, i);
                SlideItem now = slides.get(i);
                if (orig.title.equals(now.title) && orig.body.equals(now.body)) continue;
                updateSlide(slide, now, orig, size);
                replaced.put(paths.get(i), serializeXml(slide));
            }

            Element sldIdLst = firstChild(pres.getDocumentElement(), P_NS, "sldIdLst");
            Document presRels = parseXml(zipBytes(zf, "ppt/_rels/presentation.xml.rels"));
            Document ct = parseXml(zipBytes(zf, "[Content_Types].xml"));
            boolean presChanged = false;

            if (slides.size() < paths.size() && sldIdLst != null) {
                // Fazla slaytları sunumdan çıkar
                Map<String, String[]> rels = readRels(zf, "ppt/_rels/presentation.xml.rels", "ppt/");
                Set<String> drop = new HashSet<String>(paths.subList(slides.size(), paths.size()));
                for (Element id : children(sldIdLst, P_NS, "sldId")) {
                    String rid = id.getAttributeNS(R_NS, "id");
                    String[] rel = rels.get(rid);
                    if (rel != null && drop.contains(rel[0])) {
                        sldIdLst.removeChild(id);
                        for (Element r : descendants(presRels.getDocumentElement(), PKG_REL_NS, "Relationship")) {
                            if (rid.equals(r.getAttribute("Id"))) r.getParentNode().removeChild(r);
                        }
                    }
                }
                presChanged = true;
            }

            if (slides.size() > paths.size()) {
                if (sldIdLst == null) {
                    sldIdLst = pres.createElementNS(P_NS, "p:sldIdLst");
                    Element sz = firstChild(pres.getDocumentElement(), P_NS, "sldSz");
                    Element master = firstChild(pres.getDocumentElement(), P_NS, "sldMasterIdLst");
                    Element notesMaster = firstChild(pres.getDocumentElement(), P_NS, "notesMasterIdLst");
                    Element handout = firstChild(pres.getDocumentElement(), P_NS, "handoutMasterIdLst");
                    Element after = handout != null ? handout : (notesMaster != null ? notesMaster : master);
                    if (after != null) insertAfter(sldIdLst, after);
                    else pres.getDocumentElement().insertBefore(sldIdLst, sz);
                }
                String layoutTarget = findLayoutTarget(zf, paths);
                long maxId = 255;
                for (Element id : children(sldIdLst, P_NS, "sldId")) {
                    try {
                        maxId = Math.max(maxId, Long.parseLong(id.getAttribute("id")));
                    } catch (NumberFormatException ignored) {}
                }
                int maxRid = 0;
                Set<String> usedRids = new HashSet<String>();
                for (Element r : descendants(presRels.getDocumentElement(), PKG_REL_NS, "Relationship")) {
                    usedRids.add(r.getAttribute("Id"));
                    Matcher m = Pattern.compile("rId(\\d+)").matcher(r.getAttribute("Id"));
                    if (m.matches()) maxRid = Math.max(maxRid, Integer.parseInt(m.group(1)));
                }
                int slideNum = 0;
                Enumeration<? extends ZipEntry> en = zf.entries();
                Pattern sp = Pattern.compile("^ppt/slides/slide(\\d+)\\.xml$");
                while (en.hasMoreElements()) {
                    Matcher m = sp.matcher(en.nextElement().getName());
                    if (m.matches()) slideNum = Math.max(slideNum, Integer.parseInt(m.group(1)));
                }
                for (int i = paths.size(); i < slides.size(); i++) {
                    slideNum++;
                    maxId++;
                    maxRid++;
                    String rid = "rId" + maxRid;
                    while (usedRids.contains(rid)) {
                        maxRid++;
                        rid = "rId" + maxRid;
                    }
                    usedRids.add(rid);
                    String part = "ppt/slides/slide" + slideNum + ".xml";
                    replaced.put(part, newSlideXml(slides.get(i), size).getBytes(StandardCharsets.UTF_8));
                    replaced.put("ppt/slides/_rels/slide" + slideNum + ".xml.rels", (
                            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                                    + "<Relationships xmlns=\"" + PKG_REL_NS + "\">"
                                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\""
                                    + xmlEscape(layoutTarget) + "\"/></Relationships>").getBytes(StandardCharsets.UTF_8));
                    Element rel = presRels.createElementNS(PKG_REL_NS, "Relationship");
                    rel.setAttribute("Id", rid);
                    rel.setAttribute("Type", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide");
                    rel.setAttribute("Target", "slides/slide" + slideNum + ".xml");
                    presRels.getDocumentElement().appendChild(rel);
                    Element id = pres.createElementNS(P_NS, "p:sldId");
                    id.setAttribute("id", String.valueOf(maxId));
                    id.setAttributeNS(R_NS, "r:id", rid);
                    sldIdLst.appendChild(id);
                    Element ov = ct.createElementNS(CT_NS, "Override");
                    ov.setAttribute("PartName", "/" + part);
                    ov.setAttribute("ContentType", "application/vnd.openxmlformats-officedocument.presentationml.slide+xml");
                    ct.getDocumentElement().appendChild(ov);
                }
                replaced.put("[Content_Types].xml", serializeXml(ct));
                presChanged = true;
            }
            if (presChanged) {
                replaced.put("ppt/presentation.xml", serializeXml(pres));
                replaced.put("ppt/_rels/presentation.xml.rels", serializeXml(presRels));
            }
        }
        if (replaced.isEmpty() && template.getCanonicalPath().equals(target.getCanonicalPath())) return;
        rewriteZip(template, target, replaced, removed);
    }

    /** Yeni slaytlar için kullanılacak düzen: tercihen boş düzen, yoksa son slaydınki. */
    private static String findLayoutTarget(ZipFile zf, List<String> paths) throws Exception {
        String fallback = null;
        Enumeration<? extends ZipEntry> en = zf.entries();
        Pattern lp = Pattern.compile("^ppt/slideLayouts/slideLayout\\d+\\.xml$");
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (!lp.matcher(n).matches()) continue;
            if (fallback == null) fallback = n;
            Element root = parseXml(zipBytes(zf, n)).getDocumentElement();
            if ("blank".equals(root.getAttribute("type"))) return "../slideLayouts/" + n.substring(n.lastIndexOf('/') + 1);
        }
        if (!paths.isEmpty()) {
            String last = paths.get(paths.size() - 1);
            String relsPath = last.substring(0, last.lastIndexOf('/')) + "/_rels/" + last.substring(last.lastIndexOf('/') + 1) + ".rels";
            for (String[] rel : readRels(zf, relsPath, last.substring(0, last.lastIndexOf('/') + 1)).values()) {
                if (rel[1].endsWith("/slideLayout")) return "../slideLayouts/" + rel[0].substring(rel[0].lastIndexOf('/') + 1);
            }
        }
        if (fallback != null) return "../slideLayouts/" + fallback.substring(fallback.lastIndexOf('/') + 1);
        return "../slideLayouts/slideLayout1.xml";
    }

    private static String newSlideXml(SlideItem sd, long[] size) {
        long cx = size[0];
        long cy = size[1];
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<p:sld xmlns:a=\"" + A_NS + "\" xmlns:r=\"" + R_NS + "\" xmlns:p=\"" + P_NS + "\">"
                + "<p:cSld><p:spTree>"
                + "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
                + textBoxXml(2, "Başlık", cx / 16, cy / 15, cx * 7 / 8, cy / 7, sd.title, 30, true, "2563EB")
                        .replace(" xmlns:p=\"" + P_NS + "\" xmlns:a=\"" + A_NS + "\"", "")
                + textBoxXml(3, "İçerik", cx / 16, cy / 4, cx * 7 / 8, cy * 2 / 3, sd.body, 20, false, "0F172A")
                        .replace(" xmlns:p=\"" + P_NS + "\" xmlns:a=\"" + A_NS + "\"", "")
                + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>";
    }

    /** Yeni, 16:9 bir sunu oluşturur (tema, ana slayt ve boş düzen dahil). */
    public static void writePptx(File file, List<SlideItem> slidesInput) throws Exception {
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        List<SlideItem> slides = (slidesInput == null || slidesInput.isEmpty())
                ? Collections.singletonList(new SlideItem("EFOS-Ofis Sunu", "", ""))
                : slidesInput;
        String nsDecl = "xmlns:a=\"" + A_NS + "\" xmlns:r=\"" + R_NS + "\" xmlns:p=\"" + P_NS + "\"";
        String emptyTree = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                + "<p:grpSpPr/></p:spTree></p:cSld>";
        String hdr = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
        String ct = hdr + "<Types xmlns=\"" + CT_NS + "\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>"
                + "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>"
                + "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>"
                + "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>"
                + "</Types>";
        String rootRels = hdr + "<Relationships xmlns=\"" + PKG_REL_NS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>"
                + "</Relationships>";
        String pres = hdr + "<p:presentation " + nsDecl + ">"
                + "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>"
                + "<p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>";
        String presRels = hdr + "<Relationships xmlns=\"" + PKG_REL_NS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>"
                + "</Relationships>";
        String master = hdr + "<p:sldMaster " + nsDecl + ">" + emptyTree
                + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" "
                + "accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
                + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst></p:sldMaster>";
        String masterRels = hdr + "<Relationships xmlns=\"" + PKG_REL_NS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>"
                + "</Relationships>";
        String layout = hdr + "<p:sldLayout " + nsDecl + " type=\"blank\" preserve=\"1\">"
                + "<p:cSld name=\"Boş\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>"
                + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>";
        String layoutRels = hdr + "<Relationships xmlns=\"" + PKG_REL_NS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>"
                + "</Relationships>";
        File tmp = new File(file.getAbsolutePath() + ".efos-new");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
            putZipText(zos, "[Content_Types].xml", ct);
            putZipText(zos, "_rels/.rels", rootRels);
            putZipText(zos, "ppt/presentation.xml", pres);
            putZipText(zos, "ppt/_rels/presentation.xml.rels", presRels);
            putZipText(zos, "ppt/slideMasters/slideMaster1.xml", master);
            putZipText(zos, "ppt/slideMasters/_rels/slideMaster1.xml.rels", masterRels);
            putZipText(zos, "ppt/slideLayouts/slideLayout1.xml", layout);
            putZipText(zos, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", layoutRels);
            putZipText(zos, "ppt/theme/theme1.xml", themeXml());
        }
        try {
            savePptx(file, slides, tmp);
        } finally {
            tmp.delete();
        }
    }

    private static String themeXml() {
        StringBuilder fills = new StringBuilder();
        StringBuilder lines = new StringBuilder();
        StringBuilder effects = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            fills.append("<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>");
            lines.append("<a:ln w=\"").append(6350 * (i + 1)).append("\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>");
            effects.append("<a:effectStyle><a:effectLst/></a:effectStyle>");
        }
        String[][] colors = {{"dk1", "000000"}, {"lt1", "FFFFFF"}, {"dk2", "0F172A"}, {"lt2", "E2E8F0"},
                {"accent1", "2563EB"}, {"accent2", "16A34A"}, {"accent3", "F59E0B"}, {"accent4", "DC2626"},
                {"accent5", "7C3AED"}, {"accent6", "0891B2"}, {"hlink", "1D4ED8"}, {"folHlink", "6D28D9"}};
        StringBuilder clr = new StringBuilder();
        for (String[] c : colors) {
            clr.append("<a:").append(c[0]).append("><a:srgbClr val=\"").append(c[1]).append("\"/></a:").append(c[0]).append(">");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<a:theme xmlns:a=\"" + A_NS + "\" name=\"EFOS\"><a:themeElements>"
                + "<a:clrScheme name=\"EFOS\">" + clr + "</a:clrScheme>"
                + "<a:fontScheme name=\"EFOS\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>"
                + "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>"
                + "<a:fmtScheme name=\"EFOS\"><a:fillStyleLst>" + fills + "</a:fillStyleLst>"
                + "<a:lnStyleLst>" + lines + "</a:lnStyleLst>"
                + "<a:effectStyleLst>" + effects + "</a:effectStyleLst>"
                + "<a:bgFillStyleLst>" + fills + "</a:bgFillStyleLst></a:fmtScheme>"
                + "</a:themeElements></a:theme>";
    }

    // ==================== ZIP / ARŞİV ====================

    /** Sıkıştırma bombasına karşı sınırlar. */
    public static final long MAX_EXTRACT_BYTES = 2L * 1024 * 1024 * 1024;
    public static final int MAX_ZIP_ENTRIES = 50000;

    public static List<ZipEntryInfo> listZip(File archive) throws IOException {
        List<ZipEntryInfo> list = new ArrayList<ZipEntryInfo>();
        try (ZipFile zf = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                list.add(new ZipEntryInfo(e.getName(), e.isDirectory(), Math.max(0L, e.getSize())));
            }
        }
        return list;
    }

    public static List<File> extractZip(File archive, File outputDir) throws IOException {
        return extractZip(archive, outputDir, MAX_EXTRACT_BYTES);
    }

    /**
     * ZIP'i güvenli biçimde ayıklar: önce tüm girişler denetlenir (Zip Slip, giriş sayısı,
     * toplam boyut); sorun varsa hiçbir dosya yazılmaz. Ayıklama sırasında hata olursa
     * o ana kadar yazılan dosyalar geri silinir.
     */
    public static List<File> extractZip(File archive, File outputDir, long maxBytes) throws IOException {
        String canonicalBase = outputDir.getCanonicalPath();
        List<File> extracted = new ArrayList<File>();
        List<File> createdDirs = new ArrayList<File>();
        byte[] buf = new byte[16384];
        try (ZipFile zf = new ZipFile(archive)) {
            List<ZipEntry> entries = new ArrayList<ZipEntry>();
            long declared = 0;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                entries.add(entry);
                if (entries.size() > MAX_ZIP_ENTRIES) {
                    throw new SecurityException("Güvenlik sınırı: arşivde çok fazla dosya var");
                }
                String canonicalTarget = new File(outputDir, entry.getName()).getCanonicalPath();
                if (!canonicalTarget.equals(canonicalBase)
                        && !canonicalTarget.startsWith(canonicalBase + File.separator)) {
                    throw new SecurityException("Zip Slip engellendi: " + entry.getName());
                }
                if (entry.getSize() > 0) declared += entry.getSize();
            }
            if (declared > maxBytes) {
                throw new SecurityException("Güvenlik sınırı: arşivin açılmış boyutu çok büyük (sıkıştırma bombası olabilir)");
            }
            if (!outputDir.isDirectory()) {
                mkdirsTracked(outputDir, createdDirs);
            }
            long written = 0;
            for (ZipEntry entry : entries) {
                File target = new File(outputDir, entry.getName());
                if (entry.isDirectory()) {
                    mkdirsTracked(target, createdDirs);
                    continue;
                }
                if (target.getParentFile() != null) mkdirsTracked(target.getParentFile(), createdDirs);
                extracted.add(target);
                try (InputStream in = zf.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(target)) {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        written += n;
                        if (written > maxBytes) {
                            throw new SecurityException("Güvenlik sınırı: arşivin açılmış boyutu sınırı aştı");
                        }
                        out.write(buf, 0, n);
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            for (int i = extracted.size() - 1; i >= 0; i--) extracted.get(i).delete();
            for (int i = createdDirs.size() - 1; i >= 0; i--) createdDirs.get(i).delete();
            throw ex;
        }
        return extracted;
    }

    private static void mkdirsTracked(File dir, List<File> created) {
        List<File> missing = new ArrayList<File>();
        for (File d = dir; d != null && !d.exists(); d = d.getParentFile()) missing.add(0, d);
        for (File d : missing) {
            if (d.mkdir()) created.add(d);
        }
    }

    public static int createZip(List<File> sources, File outputZip, boolean excludeZipFiles) throws IOException {
        if (outputZip.getParentFile() != null) outputZip.getParentFile().mkdirs();
        String outCanonical = outputZip.getCanonicalPath();
        int[] count = new int[]{0};
        byte[] buf = new byte[16384];

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputZip))) {
            for (File src : sources) {
                if (src == null || !src.exists()) continue;
                if (src.isFile()) {
                    addFileToZip(zos, src, src.getName(), outCanonical, excludeZipFiles, buf, count);
                } else if (src.isDirectory()) {
                    walkAndAdd(zos, src, src, outCanonical, excludeZipFiles, buf, count);
                }
            }
        }
        return count[0];
    }

    private static void walkAndAdd(
            ZipOutputStream zos,
            File baseDir,
            File current,
            String outCanonical,
            boolean excludeZipFiles,
            byte[] buf,
            int[] count
    ) throws IOException {
        File[] children = current.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                walkAndAdd(zos, baseDir, child, outCanonical, excludeZipFiles, buf, count);
            } else if (child.isFile()) {
                String rel = baseDir.toURI().relativize(child.toURI()).getPath();
                addFileToZip(zos, child, rel, outCanonical, excludeZipFiles, buf, count);
            }
        }
    }

    private static void addFileToZip(
            ZipOutputStream zos,
            File file,
            String entryName,
            String outCanonical,
            boolean excludeZipFiles,
            byte[] buf,
            int[] count
    ) throws IOException {
        if (file.getCanonicalPath().equals(outCanonical)) return;
        if (excludeZipFiles && file.getName().toLowerCase().endsWith(".zip")) return;
        zos.putNextEntry(new ZipEntry(entryName.replace('\\', '/')));
        try (FileInputStream fis = new FileInputStream(file)) {
            int n;
            while ((n = fis.read(buf)) >= 0) zos.write(buf, 0, n);
        }
        zos.closeEntry();
        count[0]++;
    }
}
