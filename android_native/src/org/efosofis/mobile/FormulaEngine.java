package org.efosofis.mobile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EFOS-Ofis Türkçe/İngilizce tablo formül motoru (masaüstü core/formula_engine.py ile aynı kurallar).
 *
 * <ul>
 *   <li>Formülde ';' varsa veya fonksiyon yoksa virgül ondalık ayırıcıdır: =YUVARLA(2,345;2)</li>
 *   <li>Excel'den gelen =ROUND(A1,2) yazımında virgül argüman ayırıcıdır.</li>
 *   <li>Yuvarlama Excel gibi yarımı sıfırdan uzağa yapılır: YUVARLA(2,5;0)=3, YUVARLA(1,005;2)=1,01.</li>
 *   <li>^ soldan sağa (2^3^2=64), metin karşılaştırması büyük/küçük harf duyarsız, &amp; ve % desteklenir.</li>
 *   <li>Başında sıfır olan veya 15 haneden uzun rakam dizileri metin olarak korunur.</li>
 * </ul>
 */
public final class FormulaEngine {

    public interface CellProvider {
        String getRawCell(int row, int col);
    }

    /** Tam sütun aralıkları (A:A) için tablonun satır sayısı. */
    public interface BoundsProvider {
        int rowCount();
    }

    /** Tarih/saat değeri (Excel seri numarası). */
    public static final class DateVal {
        public final double serial;
        public final boolean hasTime;

        public DateVal(double serial, boolean hasTime) {
            this.serial = serial;
            this.hasTime = hasTime;
        }

        @Override
        public String toString() {
            return formatDate(serial, hasTime);
        }
    }

    /** Aralık değeri: satır/sütun boyutuyla birlikte. */
    public static final class RangeVal {
        public final List<Object> values;
        public final int rows;
        public final int cols;

        RangeVal(List<Object> values, int rows, int cols) {
            this.values = values;
            this.rows = rows;
            this.cols = cols;
        }

        Object at(int r, int c) {
            return values.get(r * cols + c);
        }
    }

    public static final Set<String> ERROR_CODES = new HashSet<String>();

    static {
        Collections.addAll(ERROR_CODES, "#HATA!", "#DÖNGÜ!", "#SAYI/0!", "#DEĞER!", "#AD?", "#BAŞV!", "#YOK", "#SAYI!");
    }

    private static final Locale TR = new Locale("tr", "TR");
    private static final Pattern CELL_REF_PATTERN = Pattern.compile("^\\$?([A-Za-z]{1,3})\\$?(\\d+)$");
    private static final Pattern COL_ONLY_PATTERN = Pattern.compile("^\\$?([A-Za-z]{1,3})$");
    private static final Pattern TR_THOUSANDS = Pattern.compile("^[+-]?\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?$");
    private static final Pattern EN_THOUSANDS = Pattern.compile("^[+-]?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?$");
    private static final Pattern PLAIN_NUM = Pattern.compile("^[+-]?(?:\\d+(?:[.,]\\d+)?|[.,]\\d+)(?:[eE][+-]?\\d+)?$");
    private static final Pattern DATE_TR = Pattern.compile(
            "^(\\d{1,2})[./](\\d{1,2})[./](\\d{4})(?:\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?$");
    private static final Pattern DATE_ISO = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})(?:[ T](\\d{2}):(\\d{2})(?::(\\d{2}))?)?$");
    private static final Pattern CURRENCY = Pattern.compile("(₺|\\$|€|£|\\bTL\\b|\\bTRY\\b|\\bUSD\\b|\\bEUR\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_SAFE_DIGITS = 15;

    private final CellProvider provider;
    private final BoundsProvider bounds;

    public FormulaEngine(CellProvider provider) {
        this(provider, null);
    }

    public FormulaEngine(CellProvider provider, BoundsProvider bounds) {
        this.provider = provider;
        this.bounds = bounds;
    }

    // ===================================================================
    // Hücre adresleri
    // ===================================================================

    public static int colLettersToIndex(String letters) {
        int idx = 0;
        String up = letters.toUpperCase(Locale.ROOT);
        for (int i = 0; i < up.length(); i++) {
            idx = idx * 26 + (up.charAt(i) - 'A' + 1);
        }
        return idx - 1;
    }

    public static String indexToColLetters(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            n = (n - 1) / 26;
            sb.append((char) ('A' + rem));
        }
        return sb.reverse().toString();
    }

    public static int[] parseCellRef(String ref) {
        if (ref == null) return null;
        Matcher m = CELL_REF_PATTERN.matcher(ref.trim());
        if (!m.matches()) return null;
        int col = colLettersToIndex(m.group(1));
        int row;
        try {
            row = Integer.parseInt(m.group(2)) - 1;
        } catch (NumberFormatException ex) {
            return null;
        }
        if (row < 0 || col < 0) return null;
        return new int[]{row, col};
    }

    public static String cellKey(int row, int col) {
        return row + ":" + col;
    }

    // ===================================================================
    // Metin / sayı / tarih dönüşümleri
    // ===================================================================

    public static String trLower(String s) {
        return s.toLowerCase(TR);
    }

    public static String trUpper(String s) {
        return s.toUpperCase(TR);
    }

    /** Başında sıfır olan (00123) veya 15 haneden uzun rakam dizileri metin kalmalıdır. */
    public static boolean isTextNumber(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        for (int i = 0; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) return false;
        }
        if (t.length() > 1 && t.charAt(0) == '0') return true;
        return t.length() > MAX_SAFE_DIGITS;
    }

    private static Calendar utcCalendar() {
        Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        cal.clear();
        return cal;
    }

    private static final long EPOCH_MS;

    static {
        Calendar c = utcCalendar();
        c.set(1899, Calendar.DECEMBER, 30, 0, 0, 0);
        EPOCH_MS = c.getTimeInMillis();
    }

    public static double toSerial(int y, int m, int d, int hh, int mm, int ss) {
        Calendar c = utcCalendar();
        c.setLenient(true);
        c.set(y, m - 1, d, hh, mm, ss);
        return (c.getTimeInMillis() - EPOCH_MS) / 86400000.0;
    }

    public static int[] serialToParts(double serial) {
        Calendar c = utcCalendar();
        c.setTimeInMillis(EPOCH_MS + Math.round(serial * 86400000.0));
        return new int[]{c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)};
    }

    public static String formatDate(double serial, boolean withTime) {
        int[] p = serialToParts(serial);
        String d = String.format(Locale.ROOT, "%02d.%02d.%04d", p[2], p[1], p[0]);
        if (withTime && (p[3] != 0 || p[4] != 0)) {
            d += String.format(Locale.ROOT, " %02d:%02d", p[3], p[4]);
        }
        return d;
    }

    /** '05.01.2026', '5/1/2026 14:30' veya '2026-01-05' metnini tarihe çevirir. */
    public static DateVal parseDateText(String text) {
        if (text == null) return null;
        String t = text.trim();
        Matcher m = DATE_TR.matcher(t);
        int y, mo, d, hh = 0, mi = 0, ss = 0;
        if (m.matches()) {
            d = Integer.parseInt(m.group(1));
            mo = Integer.parseInt(m.group(2));
            y = Integer.parseInt(m.group(3));
            if (m.group(4) != null) {
                hh = Integer.parseInt(m.group(4));
                mi = Integer.parseInt(m.group(5));
                if (m.group(6) != null) ss = Integer.parseInt(m.group(6));
            }
        } else {
            m = DATE_ISO.matcher(t);
            if (!m.matches()) return null;
            y = Integer.parseInt(m.group(1));
            mo = Integer.parseInt(m.group(2));
            d = Integer.parseInt(m.group(3));
            if (m.group(4) != null) {
                hh = Integer.parseInt(m.group(4));
                mi = Integer.parseInt(m.group(5));
                if (m.group(6) != null) ss = Integer.parseInt(m.group(6));
            }
        }
        if (mo < 1 || mo > 12 || d < 1 || d > 31 || hh > 23 || mi > 59 || ss > 59) return null;
        int[] check = serialToParts(toSerial(y, mo, d, 0, 0, 0));
        if (check[2] != d) return null; // 31.02 gibi geçersiz tarih
        return new DateVal(toSerial(y, mo, d, hh, mi, ss), hh != 0 || mi != 0 || ss != 0);
    }

    /** Türkçe (1.234,56), İngilizce (1,234.56), yüzde (%12), para birimi ve tarih yazımlarını tanır. */
    public static Double toNumber(Object val) {
        if (val == null) return null;
        if (val instanceof Boolean) return ((Boolean) val) ? 1.0 : 0.0;
        if (val instanceof DateVal) return ((DateVal) val).serial;
        if (val instanceof Number) {
            double d = ((Number) val).doubleValue();
            return (Double.isNaN(d) || Double.isInfinite(d)) ? null : d;
        }
        if (val instanceof RangeVal) return null;
        String text = val.toString().trim();
        if (text.isEmpty()) return null;
        if (text.startsWith("'")) {
            text = text.substring(1).trim();
            if (text.isEmpty()) return null;
        }
        DateVal dv = parseDateText(text);
        if (dv != null) return dv.serial;

        boolean percent = false;
        if (text.endsWith("%")) {
            percent = true;
            text = text.substring(0, text.length() - 1).trim();
        } else if (text.startsWith("%")) {
            percent = true;
            text = text.substring(1).trim();
        }
        text = CURRENCY.matcher(text).replaceAll("").replace(" ", "").replace(" ", "");
        if (text.isEmpty()) return null;
        String candidate;
        if (TR_THOUSANDS.matcher(text).matches()) {
            candidate = text.replace(".", "").replace(',', '.');
        } else if (EN_THOUSANDS.matcher(text).matches() && text.indexOf('.') >= 0) {
            candidate = text.replace(",", "");
        } else if (PLAIN_NUM.matcher(text).matches()) {
            candidate = text.replace(',', '.');
        } else {
            return null;
        }
        try {
            double d = Double.parseDouble(candidate);
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return percent ? d / 100.0 : d;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Ekranda Türkçe gösterim (ondalık virgül). */
    public static String formatDisplayValue(Object val) {
        if (val == null) return "";
        if (val instanceof Boolean) return ((Boolean) val) ? "DOĞRU" : "YANLIŞ";
        if (val instanceof DateVal) return val.toString();
        if (val instanceof RangeVal) {
            RangeVal rv = (RangeVal) val;
            return rv.values.isEmpty() ? "" : formatDisplayValue(rv.values.get(0));
        }
        if (val instanceof Double) {
            double d = (Double) val;
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return Long.toString((long) d);
            }
            String s = new BigDecimal(Double.toString(d)).setScale(10, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
            return s.replace('.', ',');
        }
        return val.toString();
    }

    /** Excel ile aynı yuvarlama (ondalık tabanlı). */
    public static double excelRound(double value, int digits, RoundingMode mode) {
        BigDecimal bd = new BigDecimal(Double.toString(value));
        return bd.setScale(digits, mode).doubleValue();
    }

    /** Formül olmayan hücre metninin hesaplamadaki değeri. */
    public static Object literalValue(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty()) return "";
        if (raw.startsWith("'")) return raw.substring(1);
        if (isTextNumber(text)) return text;
        DateVal dv = parseDateText(text);
        if (dv != null) return dv;
        String up = trUpper(text);
        if ("DOĞRU".equals(up) || "TRUE".equals(up)) return Boolean.TRUE;
        if ("YANLIŞ".equals(up) || "FALSE".equals(up)) return Boolean.FALSE;
        Double n = toNumber(text);
        return n != null ? (Object) n : raw;
    }

    static boolean isError(Object v) {
        return v instanceof String && ERROR_CODES.contains(v);
    }

    // ===================================================================
    // Fonksiyon adları (Türkçe <-> Excel)
    // ===================================================================

    private static final Map<String, String> TR_TO_EN = new HashMap<String, String>();
    private static final Map<String, String> EN_TO_TR = new HashMap<String, String>();
    private static final Map<String, String> ALIASES = new HashMap<String, String>();

    private static void fn(String tr, String en) {
        TR_TO_EN.put(tr, en);
        EN_TO_TR.put(en, tr);
    }

    static {
        fn("TOPLA", "SUM");
        fn("ORTALAMA", "AVERAGE");
        fn("MİN", "MIN");
        fn("MAK", "MAX");
        fn("SAY", "COUNT");
        fn("BAĞ_DEĞ_DOLU_SAY", "COUNTA");
        fn("EĞERSAY", "COUNTIF");
        fn("ETOPLA", "SUMIF");
        fn("EĞER", "IF");
        fn("EĞERHATA", "IFERROR");
        fn("VE", "AND");
        fn("YADA", "OR");
        fn("DEĞİL", "NOT");
        fn("YUVARLA", "ROUND");
        fn("YUKARIYUVARLA", "ROUNDUP");
        fn("AŞAĞIYUVARLA", "ROUNDDOWN");
        fn("TAMSAYI", "INT");
        fn("MUTLAK", "ABS");
        fn("MOD", "MOD");
        fn("KUVVET", "POWER");
        fn("KAREKÖK", "SQRT");
        fn("ÇARPIM", "PRODUCT");
        fn("DÜŞEYARA", "VLOOKUP");
        fn("YATAYARA", "HLOOKUP");
        fn("İNDİS", "INDEX");
        fn("KAÇINCI", "MATCH");
        fn("BİRLEŞTİR", "CONCATENATE");
        fn("UZUNLUK", "LEN");
        fn("SOLDAN", "LEFT");
        fn("SAĞDAN", "RIGHT");
        fn("PARÇAAL", "MID");
        fn("BÜYÜKHARF", "UPPER");
        fn("KÜÇÜKHARF", "LOWER");
        fn("KIRP", "TRIM");
        fn("BUGÜN", "TODAY");
        fn("TARİH", "DATE");
        fn("YIL", "YEAR");
        fn("AY", "MONTH");
        fn("GÜN", "DAY");
        fn("EBOŞSA", "ISBLANK");
        fn("ESAYIYSA", "ISNUMBER");
        ALIASES.put("EGER", "IF");
        ALIASES.put("EGERHATA", "IFERROR");
        ALIASES.put("EGERSAY", "COUNTIF");
        ALIASES.put("MAKS", "MAX");
        ALIASES.put("DUSEYARA", "VLOOKUP");
        ALIASES.put("INDIS", "INDEX");
        ALIASES.put("KACINCI", "MATCH");
        ALIASES.put("BUGUN", "TODAY");
        ALIASES.put("BIRLESTIR", "CONCATENATE");
        ALIASES.put("CONCAT", "CONCATENATE");
        ALIASES.put("KAREKOK", "SQRT");
        ALIASES.put("DEGIL", "NOT");
    }

    public static String canonicalFunctionName(String name) {
        String up = trUpper(name.trim());
        if (TR_TO_EN.containsKey(up)) return TR_TO_EN.get(up);
        String plain = name.trim().toUpperCase(Locale.ROOT);
        if (ALIASES.containsKey(plain)) return ALIASES.get(plain);
        if (ALIASES.containsKey(up)) return ALIASES.get(up);
        if (plain.startsWith("_XLFN.")) plain = plain.substring(6);
        return plain;
    }

    // ===================================================================
    // Sözcük çözümleyici
    // ===================================================================

    private enum TokenKind { NUMBER, STRING, RANGE, IDENT, OP, LPAREN, RPAREN, SEP }

    private static final class Token {
        final TokenKind kind;
        final String text;

        Token(TokenKind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    static final class FormulaException extends RuntimeException {
        final String code;

        FormulaException(String code) {
            super(code);
            this.code = code;
        }
    }

    private static String stripStrings(String expr) {
        StringBuilder sb = new StringBuilder();
        boolean inStr = false;
        for (int i = 0; i < expr.length(); i++) {
            char ch = expr.charAt(i);
            if (ch == '"') {
                inStr = !inStr;
                continue;
            }
            if (!inStr) sb.append(ch);
        }
        return sb.toString();
    }

    public static boolean isTurkishSyntax(String expr) {
        String bare = stripStrings(expr);
        return bare.indexOf(';') >= 0 || bare.indexOf('(') < 0;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
    }

    private static List<Token> tokenize(String expr, boolean turkish) {
        List<Token> tokens = new ArrayList<Token>();
        int i = 0;
        int n = expr.length();
        while (i < n) {
            char ch = expr.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
            } else if (ch == '(') {
                tokens.add(new Token(TokenKind.LPAREN, "("));
                i++;
            } else if (ch == ')') {
                tokens.add(new Token(TokenKind.RPAREN, ")"));
                i++;
            } else if (ch == ';' || (ch == ',' && !turkish)) {
                tokens.add(new Token(TokenKind.SEP, String.valueOf(ch)));
                i++;
            } else if (ch == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char c = expr.charAt(i);
                    if (c == '"') {
                        if (i + 1 < n && expr.charAt(i + 1) == '"') {
                            sb.append('"');
                            i += 2;
                            continue;
                        }
                        closed = true;
                        i++;
                        break;
                    }
                    sb.append(c);
                    i++;
                }
                if (!closed) throw new FormulaException("#HATA!");
                tokens.add(new Token(TokenKind.STRING, sb.toString()));
            } else if (Character.isDigit(ch) || ((ch == '.' || ch == ',') && i + 1 < n
                    && Character.isDigit(expr.charAt(i + 1)) && (turkish || ch == '.'))) {
                int start = i;
                while (i < n && Character.isDigit(expr.charAt(i))) i++;
                if (i < n && (expr.charAt(i) == '.' || (turkish && expr.charAt(i) == ','))
                        && i + 1 < n && Character.isDigit(expr.charAt(i + 1))) {
                    i++;
                    while (i < n && Character.isDigit(expr.charAt(i))) i++;
                }
                if (i < n && (expr.charAt(i) == 'e' || expr.charAt(i) == 'E')) {
                    int j = i + 1;
                    if (j < n && (expr.charAt(j) == '+' || expr.charAt(j) == '-')) j++;
                    if (j < n && Character.isDigit(expr.charAt(j))) {
                        i = j;
                        while (i < n && Character.isDigit(expr.charAt(i))) i++;
                    }
                }
                tokens.add(new Token(TokenKind.NUMBER, expr.substring(start, i)));
            } else if ("<>!=+-*/^&%".indexOf(ch) >= 0) {
                if (i + 1 < n) {
                    String two = expr.substring(i, i + 2);
                    if ("<>".equals(two) || "!=".equals(two) || "<=".equals(two) || ">=".equals(two)) {
                        tokens.add(new Token(TokenKind.OP, two));
                        i += 2;
                        continue;
                    }
                }
                if (ch == '!') throw new FormulaException("#HATA!");
                tokens.add(new Token(TokenKind.OP, String.valueOf(ch)));
                i++;
            } else if (isIdentStart(ch)) {
                int start = i;
                while (i < n && isIdentPart(expr.charAt(i))) i++;
                String word = expr.substring(start, i);
                if (i < n && expr.charAt(i) == ':') {
                    int j = i + 1;
                    while (j < n && (Character.isLetterOrDigit(expr.charAt(j)) || expr.charAt(j) == '$')) j++;
                    String range = word + ":" + expr.substring(i + 1, j);
                    tokens.add(new Token(TokenKind.RANGE, range));
                    i = j;
                } else {
                    tokens.add(new Token(TokenKind.IDENT, word));
                }
            } else {
                throw new FormulaException("#HATA!");
            }
        }
        return tokens;
    }

    private static String render(List<Token> tokens, boolean toExcel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            switch (t.kind) {
                case NUMBER:
                    sb.append(toExcel ? t.text.replace(',', '.') : t.text.replace('.', ','));
                    break;
                case SEP:
                    sb.append(toExcel ? "," : ";");
                    break;
                case STRING:
                    sb.append('"').append(t.text.replace("\"", "\"\"")).append('"');
                    break;
                case IDENT: {
                    boolean isFunc = i + 1 < tokens.size() && tokens.get(i + 1).kind == TokenKind.LPAREN;
                    String up = trUpper(t.text);
                    if (isFunc) {
                        String canon = canonicalFunctionName(t.text);
                        if (toExcel) sb.append(canon);
                        else sb.append(EN_TO_TR.containsKey(canon) ? EN_TO_TR.get(canon) : t.text);
                    } else if ("TRUE".equals(up) || "DOĞRU".equals(up)) {
                        sb.append(toExcel ? "TRUE" : "DOĞRU");
                    } else if ("FALSE".equals(up) || "YANLIŞ".equals(up)) {
                        sb.append(toExcel ? "FALSE" : "YANLIŞ");
                    } else {
                        sb.append(t.text);
                    }
                    break;
                }
                default:
                    sb.append(t.text);
            }
        }
        return sb.toString();
    }

    /** '=YUVARLA(A1*1,18;2)' -> '=ROUND(A1*1.18,2)' (Excel dosya biçimi). */
    public static String toExcelFormula(String raw) {
        if (raw == null || !raw.startsWith("=")) return raw;
        String body = raw.substring(1);
        try {
            return "=" + render(tokenize(body, isTurkishSyntax(body)), true);
        } catch (FormulaException ex) {
            return raw;
        }
    }

    /** '=ROUND(A1*1.18,2)' -> '=YUVARLA(A1*1,18;2)' (ekranda Türkçe gösterim). */
    public static String fromExcelFormula(String raw) {
        if (raw == null || !raw.startsWith("=")) return raw;
        try {
            String rendered = render(tokenize(raw.substring(1), false), false);
            if (stripStrings(rendered).indexOf(',') >= 0 && !isTurkishSyntax(rendered)) return raw;
            return "=" + rendered;
        } catch (FormulaException ex) {
            return raw;
        }
    }

    // ===================================================================
    // Hesaplama
    // ===================================================================

    public Object evaluateCell(int row, int col) {
        return evaluateCell(row, col, new HashSet<String>());
    }

    Object evaluateCell(int row, int col, Set<String> visited) {
        String key = cellKey(row, col);
        if (visited.contains(key)) throw new FormulaException("#DÖNGÜ!");
        String raw = provider.getRawCell(row, col);
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty()) return "";
        if (!text.startsWith("=")) return literalValue(raw);
        Set<String> next = new HashSet<String>(visited);
        next.add(key);
        return evaluateFormula(text.substring(1), next);
    }

    public Object evaluateFormula(String expr) {
        return evaluateFormula(expr, new HashSet<String>());
    }

    public Object evaluateFormula(String expr, Set<String> visited) {
        try {
            String e = expr.trim();
            List<Token> tokens = tokenize(e, isTurkishSyntax(e));
            if (tokens.isEmpty()) return "";
            Parser parser = new Parser(tokens, visited);
            Object result = parser.parseExpression();
            if (parser.pos < tokens.size()) throw new FormulaException("#HATA!");
            if (result instanceof RangeVal) {
                RangeVal rv = (RangeVal) result;
                result = rv.values.size() == 1 ? rv.values.get(0) : "#DEĞER!";
            }
            if (result instanceof Double && (((Double) result).isNaN() || ((Double) result).isInfinite())) {
                return "#SAYI!";
            }
            return result;
        } catch (FormulaException ex) {
            return ex.code;
        } catch (ArithmeticException ex) {
            return "#SAYI/0!";
        } catch (StackOverflowError ex) {
            return "#DÖNGÜ!";
        } catch (RuntimeException ex) {
            return "#HATA!";
        }
    }

    RangeVal getRangeValues(String rangeStr, Set<String> visited) {
        String[] parts = rangeStr.split(":");
        if (parts.length != 2) throw new FormulaException("#BAŞV!");
        int[] start = parseCellRef(parts[0]);
        int[] end = parseCellRef(parts[1]);
        if (start == null && end == null) {
            Matcher a = COL_ONLY_PATTERN.matcher(parts[0].trim());
            Matcher b = COL_ONLY_PATTERN.matcher(parts[1].trim());
            if (!a.matches() || !b.matches()) throw new FormulaException("#BAŞV!");
            int maxRows = bounds != null ? bounds.rowCount() : 1000;
            start = new int[]{0, colLettersToIndex(a.group(1))};
            end = new int[]{Math.max(0, maxRows - 1), colLettersToIndex(b.group(1))};
        }
        if (start == null || end == null) throw new FormulaException("#BAŞV!");
        int r1 = Math.min(start[0], end[0]);
        int r2 = Math.max(start[0], end[0]);
        int c1 = Math.min(start[1], end[1]);
        int c2 = Math.max(start[1], end[1]);
        long size = (long) (r2 - r1 + 1) * (c2 - c1 + 1);
        if (size > 200000) throw new FormulaException("#BAŞV!");
        List<Object> values = new ArrayList<Object>();
        for (int r = r1; r <= r2; r++) {
            for (int c = c1; c <= c2; c++) {
                values.add(evaluateCell(r, c, visited));
            }
        }
        return new RangeVal(values, r2 - r1 + 1, c2 - c1 + 1);
    }

    private final class Parser {
        final List<Token> tokens;
        final Set<String> visited;
        int pos = 0;

        Parser(List<Token> tokens, Set<String> visited) {
            this.tokens = tokens;
            this.visited = visited;
        }

        Token peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }

        boolean peekOp(String... ops) {
            Token t = peek();
            if (t == null || t.kind != TokenKind.OP) return false;
            for (String o : ops) if (o.equals(t.text)) return true;
            return false;
        }

        Token consume(TokenKind expected) {
            Token tok = peek();
            if (tok == null) throw new FormulaException("#HATA!");
            if (expected != null && tok.kind != expected) throw new FormulaException("#HATA!");
            pos++;
            return tok;
        }

        Object check(Object v) {
            if (isError(v)) throw new FormulaException((String) v);
            return v;
        }

        Object parseExpression() {
            return parseComparison();
        }

        Object parseComparison() {
            Object left = parseConcat();
            while (peekOp("=", "<>", "!=", "<", ">", "<=", ">=")) {
                String op = consume(null).text;
                Object right = parseConcat();
                left = compare(left, op, right);
            }
            return left;
        }

        Object parseConcat() {
            Object left = parseAdditive();
            while (peekOp("&")) {
                consume(null);
                Object right = parseAdditive();
                left = asText(left) + asText(right);
            }
            return left;
        }

        Object parseAdditive() {
            Object left = parseMultiplicative();
            while (peekOp("+", "-")) {
                String op = consume(null).text;
                Object right = parseMultiplicative();
                double l = num(left);
                double r = num(right);
                left = "+".equals(op) ? (l + r) : (l - r);
            }
            return left;
        }

        Object parseMultiplicative() {
            Object left = parsePower();
            while (peekOp("*", "/")) {
                String op = consume(null).text;
                Object right = parsePower();
                double l = num(left);
                double r = num(right);
                if ("*".equals(op)) {
                    left = l * r;
                } else {
                    if (r == 0.0) throw new FormulaException("#SAYI/0!");
                    left = l / r;
                }
            }
            return left;
        }

        Object parsePower() {
            Object base = parseUnary();
            while (peekOp("^")) {
                consume(null);
                Object exp = parseUnary();
                double b = num(base);
                double e = num(exp);
                if (b == 0 && e < 0) throw new FormulaException("#SAYI/0!");
                double r = Math.pow(b, e);
                if (Double.isNaN(r)) throw new FormulaException("#SAYI!");
                base = r;
            }
            return base;
        }

        Object parseUnary() {
            if (peekOp("+", "-")) {
                String op = consume(null).text;
                double v = num(parseUnary());
                return "-".equals(op) ? -v : v;
            }
            return parsePostfix();
        }

        Object parsePostfix() {
            Object v = parsePrimary();
            while (peekOp("%")) {
                consume(null);
                v = num(v) / 100.0;
            }
            return v;
        }

        Object parsePrimary() {
            Token tok = peek();
            if (tok == null) throw new FormulaException("#HATA!");
            switch (tok.kind) {
                case NUMBER:
                    consume(null);
                    return Double.parseDouble(tok.text.replace(',', '.'));
                case STRING:
                    consume(null);
                    return tok.text;
                case LPAREN: {
                    consume(TokenKind.LPAREN);
                    Object val = parseExpression();
                    consume(TokenKind.RPAREN);
                    return val;
                }
                case RANGE:
                    consume(null);
                    return getRangeValues(tok.text, visited);
                case IDENT: {
                    consume(null);
                    Token next = peek();
                    if (next != null && next.kind == TokenKind.LPAREN) {
                        return callFunction(tok.text);
                    }
                    String up = trUpper(tok.text);
                    if ("TRUE".equals(up) || "DOĞRU".equals(up)) return Boolean.TRUE;
                    if ("FALSE".equals(up) || "YANLIŞ".equals(up)) return Boolean.FALSE;
                    int[] coord = parseCellRef(tok.text);
                    if (coord != null) return check(evaluateCell(coord[0], coord[1], visited));
                    throw new FormulaException("#AD?");
                }
                default:
                    throw new FormulaException("#HATA!");
            }
        }

        // ---------------- fonksiyonlar (tembel argümanlar) ----------------

        List<int[]> splitArgs() {
            consume(TokenKind.LPAREN);
            List<int[]> spans = new ArrayList<int[]>();
            int depth = 0;
            int start = pos;
            while (true) {
                Token t = peek();
                if (t == null) throw new FormulaException("#HATA!");
                if (t.kind == TokenKind.LPAREN) {
                    depth++;
                } else if (t.kind == TokenKind.RPAREN) {
                    if (depth == 0) {
                        if (pos > start || !spans.isEmpty()) spans.add(new int[]{start, pos});
                        pos++;
                        return spans;
                    }
                    depth--;
                } else if (t.kind == TokenKind.SEP && depth == 0) {
                    spans.add(new int[]{start, pos});
                    start = pos + 1;
                }
                pos++;
            }
        }

        Object evalSpan(int[] span) {
            if (span[0] == span[1]) return "";
            Parser sub = new Parser(tokens.subList(span[0], span[1]), visited);
            Object v = sub.parseExpression();
            if (sub.pos != span[1] - span[0]) throw new FormulaException("#HATA!");
            return v;
        }

        Object arg(List<int[]> s, int i) {
            Object v = evalSpan(s.get(i));
            if (v instanceof RangeVal) return v;
            return check(v);
        }

        void need(List<int[]> s, int lo, int hi) {
            if (s.size() < lo || s.size() > hi) throw new FormulaException("#DEĞER!");
        }

        List<Object> flatten(List<Object> args) {
            List<Object> out = new ArrayList<Object>();
            for (Object a : args) {
                if (a instanceof RangeVal) out.addAll(((RangeVal) a).values);
                else out.add(a);
            }
            return out;
        }

        List<Object> allArgs(List<int[]> s) {
            List<Object> out = new ArrayList<Object>();
            for (int i = 0; i < s.size(); i++) out.add(arg(s, i));
            return out;
        }

        List<Double> numbers(List<Object> args) {
            List<Double> nums = new ArrayList<Double>();
            for (Object item : args) {
                if (item instanceof RangeVal) {
                    for (Object sub : ((RangeVal) item).values) {
                        check(sub);
                        if (sub instanceof Boolean || sub == null || "".equals(sub)) continue;
                        if (sub instanceof Double || sub instanceof DateVal) {
                            nums.add(toNumber(sub));
                        } else if (sub instanceof String && !isTextNumber((String) sub)
                                && !((String) sub).startsWith("'")) {
                            Double d = toNumber(sub);
                            if (d != null) nums.add(d);
                        }
                    }
                } else {
                    Double d = toNumber(item);
                    if (d != null) nums.add(d);
                    else if (item != null && !"".equals(item)) throw new FormulaException("#DEĞER!");
                }
            }
            return nums;
        }

        double num(Object v) {
            if (v instanceof RangeVal) {
                RangeVal rv = (RangeVal) v;
                if (rv.values.size() != 1) throw new FormulaException("#DEĞER!");
                v = rv.values.get(0);
            }
            check(v);
            if (v == null || "".equals(v)) return 0.0;
            Double d = toNumber(v);
            if (d == null) throw new FormulaException("#DEĞER!");
            return d;
        }

        int intArg(Object v) {
            return (int) Math.floor(num(v));
        }

        String asText(Object v) {
            if (v instanceof RangeVal) {
                RangeVal rv = (RangeVal) v;
                if (rv.values.size() != 1) throw new FormulaException("#DEĞER!");
                v = rv.values.get(0);
            }
            check(v);
            return formatDisplayValue(v);
        }

        boolean asBool(Object v) {
            if (v instanceof RangeVal) {
                RangeVal rv = (RangeVal) v;
                v = rv.values.isEmpty() ? "" : rv.values.get(0);
            }
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof String) {
                String up = trUpper(((String) v).trim());
                if ("DOĞRU".equals(up) || "TRUE".equals(up)) return true;
                if ("YANLIŞ".equals(up) || "FALSE".equals(up) || up.isEmpty()) return false;
                Double d = toNumber(v);
                if (d == null) throw new FormulaException("#DEĞER!");
                return d != 0.0;
            }
            Double d = toNumber(v);
            return d != null && d != 0.0;
        }

        /** Excel sıralaması: sayılar &lt; metinler &lt; mantıksal değerler. */
        Object[] key(Object v) {
            if (v instanceof Boolean) return new Object[]{2, ((Boolean) v) ? 1.0 : 0.0};
            if (v instanceof Double || v instanceof DateVal) return new Object[]{0, toNumber(v)};
            if (v == null || "".equals(v)) return new Object[]{0, 0.0};
            String s = v.toString();
            if (!s.startsWith("'") && !isTextNumber(s)) {
                Double d = toNumber(s);
                if (d != null) return new Object[]{0, d};
            }
            return new Object[]{1, trLower(s.startsWith("'") ? s.substring(1) : s)};
        }

        int compareKeys(Object[] a, Object[] b) {
            int ta = (Integer) a[0];
            int tb = (Integer) b[0];
            if (ta != tb) return ta < tb ? -1 : 1;
            if (a[1] instanceof Double) return Double.compare((Double) a[1], (Double) b[1]);
            return ((String) a[1]).compareTo((String) b[1]);
        }

        boolean compare(Object left, String op, Object right) {
            if (left instanceof RangeVal) left = singleOf((RangeVal) left);
            if (right instanceof RangeVal) right = singleOf((RangeVal) right);
            check(left);
            check(right);
            int c = compareKeys(key(left), key(right));
            if ("=".equals(op)) return c == 0;
            if ("<>".equals(op) || "!=".equals(op)) return c != 0;
            if ("<".equals(op)) return c < 0;
            if (">".equals(op)) return c > 0;
            if ("<=".equals(op)) return c <= 0;
            if (">=".equals(op)) return c >= 0;
            throw new FormulaException("#HATA!");
        }

        Object singleOf(RangeVal rv) {
            if (rv.values.size() != 1) throw new FormulaException("#DEĞER!");
            return rv.values.get(0);
        }

        boolean matchesCriteria(Object value, Object criteria) {
            if (criteria instanceof RangeVal) criteria = ((RangeVal) criteria).values.isEmpty() ? "" : ((RangeVal) criteria).values.get(0);
            if (!(criteria instanceof String)) return compare(value, "=", criteria);
            String crit = (String) criteria;
            String op = "=";
            for (String o : new String[]{"<>", "<=", ">=", "=", "<", ">"}) {
                if (crit.startsWith(o)) {
                    op = o;
                    crit = crit.substring(o.length());
                    break;
                }
            }
            if ("=".equals(op) && (crit.indexOf('*') >= 0 || crit.indexOf('?') >= 0)) {
                StringBuilder rx = new StringBuilder("^");
                for (char ch : trLower(crit).toCharArray()) {
                    if (ch == '*') rx.append(".*");
                    else if (ch == '?') rx.append('.');
                    else rx.append(Pattern.quote(String.valueOf(ch)));
                }
                rx.append('$');
                return Pattern.compile(rx.toString(), Pattern.DOTALL).matcher(trLower(formatDisplayValue(value))).matches();
            }
            Object target = crit.isEmpty() ? "" : literalValue(crit);
            return compare(value, op, target);
        }

        int approxIndex(List<Object> items, Object target) {
            Object[] k = key(target);
            int found = -1;
            for (int i = 0; i < items.size(); i++) {
                Object v = items.get(i);
                if (v == null || "".equals(v) || isError(v)) continue;
                Object[] kv = key(v);
                if (!kv[0].equals(k[0])) continue;
                if (compareKeys(kv, k) <= 0) found = i;
                else break;
            }
            return found;
        }

        boolean lookupEqual(Object a, Object b) {
            if (b instanceof String && (((String) b).indexOf('*') >= 0 || ((String) b).indexOf('?') >= 0)) {
                return matchesCriteria(a, b);
            }
            return compareKeys(key(a), key(b)) == 0;
        }

        Object lookup(List<int[]> s, boolean horizontal) {
            need(s, 3, 4);
            Object target = arg(s, 0);
            if (target instanceof RangeVal) target = singleOf((RangeVal) target);
            Object tableObj = evalSpan(s.get(1));
            if (!(tableObj instanceof RangeVal)) throw new FormulaException("#DEĞER!");
            RangeVal table = (RangeVal) tableObj;
            int index = intArg(arg(s, 2));
            boolean approx = s.size() == 4 ? asBool(arg(s, 3)) : true;
            int limit = horizontal ? table.rows : table.cols;
            if (index < 1) throw new FormulaException("#DEĞER!");
            if (index > limit) throw new FormulaException("#BAŞV!");
            List<Object> keys = new ArrayList<Object>();
            int count = horizontal ? table.cols : table.rows;
            for (int i = 0; i < count; i++) keys.add(horizontal ? table.at(0, i) : table.at(i, 0));
            int p = -1;
            if (approx) {
                p = approxIndex(keys, target);
            } else {
                for (int i = 0; i < keys.size(); i++) {
                    if (!isError(keys.get(i)) && lookupEqual(keys.get(i), target)) {
                        p = i;
                        break;
                    }
                }
            }
            if (p < 0) throw new FormulaException("#YOK");
            return check(horizontal ? table.at(index - 1, p) : table.at(p, index - 1));
        }

        int[] dateParts(Object v) {
            if (v instanceof RangeVal) v = singleOf((RangeVal) v);
            return serialToParts(num(v));
        }

        Object callFunction(String funcName) {
            List<int[]> s = splitArgs();
            String name = canonicalFunctionName(funcName);
            if ("SUM".equals(name)) {
                double sum = 0.0;
                for (double d : numbers(allArgs(s))) sum += d;
                return sum;
            }
            if ("AVERAGE".equals(name)) {
                List<Double> nums = numbers(allArgs(s));
                if (nums.isEmpty()) throw new FormulaException("#SAYI/0!");
                double sum = 0.0;
                for (double d : nums) sum += d;
                return sum / nums.size();
            }
            if ("MIN".equals(name) || "MAX".equals(name)) {
                List<Double> nums = numbers(allArgs(s));
                if (nums.isEmpty()) return 0.0;
                return "MIN".equals(name) ? Collections.min(nums) : Collections.max(nums);
            }
            if ("PRODUCT".equals(name)) {
                List<Double> nums = numbers(allArgs(s));
                if (nums.isEmpty()) return 0.0;
                double p = 1.0;
                for (double d : nums) p *= d;
                return p;
            }
            if ("COUNT".equals(name)) {
                int count = 0;
                for (Object item : allArgs(s)) {
                    if (item instanceof RangeVal) {
                        for (Object v : ((RangeVal) item).values) {
                            if (v instanceof Double || v instanceof DateVal) count++;
                        }
                    } else if (!(item instanceof Boolean) && toNumber(item) != null) {
                        count++;
                    }
                }
                return (double) count;
            }
            if ("COUNTA".equals(name)) {
                List<Object> raw = new ArrayList<Object>();
                for (int[] sp : s) raw.add(evalSpan(sp));
                int count = 0;
                for (Object v : flatten(raw)) if (v != null && !"".equals(v)) count++;
                return (double) count;
            }
            if ("COUNTIF".equals(name)) {
                need(s, 2, 2);
                Object rng = evalSpan(s.get(0));
                List<Object> values = rng instanceof RangeVal ? ((RangeVal) rng).values : Collections.singletonList(rng);
                Object crit = arg(s, 1);
                int count = 0;
                for (Object v : values) if (!isError(v) && matchesCriteria(v, crit)) count++;
                return (double) count;
            }
            if ("SUMIF".equals(name)) {
                need(s, 2, 3);
                Object rng = evalSpan(s.get(0));
                List<Object> values = rng instanceof RangeVal ? ((RangeVal) rng).values : Collections.singletonList(rng);
                Object crit = arg(s, 1);
                List<Object> sumVals = values;
                if (s.size() == 3) {
                    Object sr = evalSpan(s.get(2));
                    sumVals = sr instanceof RangeVal ? ((RangeVal) sr).values : Collections.singletonList(sr);
                }
                double total = 0.0;
                for (int i = 0; i < values.size() && i < sumVals.size(); i++) {
                    Object v = values.get(i);
                    if (!isError(v) && matchesCriteria(v, crit)) {
                        Object sv = sumVals.get(i);
                        if (sv instanceof Boolean) continue;
                        if (sv instanceof String && isTextNumber((String) sv)) continue;
                        Double d = toNumber(sv);
                        if (d != null) total += d;
                    }
                }
                return total;
            }
            if ("IF".equals(name)) {
                need(s, 2, 3);
                if (asBool(arg(s, 0))) return arg(s, 1);
                return s.size() == 3 ? arg(s, 2) : Boolean.FALSE;
            }
            if ("IFERROR".equals(name)) {
                need(s, 2, 2);
                try {
                    Object v = evalSpan(s.get(0));
                    if (v instanceof RangeVal && ((RangeVal) v).values.size() == 1) v = ((RangeVal) v).values.get(0);
                    if (isError(v)) throw new FormulaException((String) v);
                    if (v instanceof Double && (((Double) v).isNaN() || ((Double) v).isInfinite())) {
                        throw new FormulaException("#SAYI!");
                    }
                    return v;
                } catch (FormulaException ex) {
                    return arg(s, 1);
                } catch (ArithmeticException ex) {
                    return arg(s, 1);
                }
            }
            if ("AND".equals(name) || "OR".equals(name)) {
                List<Object> vals = new ArrayList<Object>();
                for (Object v : flatten(allArgs(s))) if (v != null && !"".equals(v)) vals.add(v);
                if (vals.isEmpty()) throw new FormulaException("#DEĞER!");
                boolean and = "AND".equals(name);
                for (Object v : vals) {
                    boolean b = asBool(v);
                    if (and && !b) return Boolean.FALSE;
                    if (!and && b) return Boolean.TRUE;
                }
                return and;
            }
            if ("NOT".equals(name)) {
                need(s, 1, 1);
                return !asBool(arg(s, 0));
            }
            if ("ROUND".equals(name) || "ROUNDUP".equals(name) || "ROUNDDOWN".equals(name)) {
                need(s, 1, 2);
                double v = num(arg(s, 0));
                int digits = s.size() == 2 ? intArg(arg(s, 1)) : 0;
                RoundingMode mode = "ROUND".equals(name) ? RoundingMode.HALF_UP
                        : ("ROUNDUP".equals(name) ? RoundingMode.UP : RoundingMode.DOWN);
                return excelRound(v, digits, mode);
            }
            if ("INT".equals(name)) {
                need(s, 1, 1);
                return Math.floor(num(arg(s, 0)));
            }
            if ("ABS".equals(name)) {
                need(s, 1, 1);
                return Math.abs(num(arg(s, 0)));
            }
            if ("MOD".equals(name)) {
                need(s, 2, 2);
                double a = num(arg(s, 0));
                double b = num(arg(s, 1));
                if (b == 0.0) throw new FormulaException("#SAYI/0!");
                return a - b * Math.floor(a / b);
            }
            if ("POWER".equals(name)) {
                need(s, 2, 2);
                double r = Math.pow(num(arg(s, 0)), num(arg(s, 1)));
                if (Double.isNaN(r)) throw new FormulaException("#SAYI!");
                return r;
            }
            if ("SQRT".equals(name)) {
                need(s, 1, 1);
                double v = num(arg(s, 0));
                if (v < 0) throw new FormulaException("#SAYI!");
                return Math.sqrt(v);
            }
            if ("VLOOKUP".equals(name)) return lookup(s, false);
            if ("HLOOKUP".equals(name)) return lookup(s, true);
            if ("INDEX".equals(name)) {
                need(s, 2, 3);
                Object t = evalSpan(s.get(0));
                RangeVal table = t instanceof RangeVal ? (RangeVal) t
                        : new RangeVal(Collections.singletonList(t), 1, 1);
                int r = intArg(arg(s, 1));
                int c = s.size() == 3 ? intArg(arg(s, 2)) : 1;
                if (table.rows == 1 && s.size() == 2) {
                    c = r;
                    r = 1;
                }
                if (r < 1 || c < 1 || r > table.rows || c > table.cols) throw new FormulaException("#BAŞV!");
                return check(table.at(r - 1, c - 1));
            }
            if ("MATCH".equals(name)) {
                need(s, 2, 3);
                Object target = arg(s, 0);
                Object rng = evalSpan(s.get(1));
                List<Object> items = rng instanceof RangeVal ? ((RangeVal) rng).values : Collections.singletonList(rng);
                int mode = s.size() == 3 ? intArg(arg(s, 2)) : 1;
                int p = -1;
                if (mode == 0) {
                    for (int i = 0; i < items.size(); i++) {
                        if (lookupEqual(items.get(i), target)) {
                            p = i;
                            break;
                        }
                    }
                } else if (mode == 1) {
                    p = approxIndex(items, target);
                } else {
                    Object[] k = key(target);
                    for (int i = 0; i < items.size(); i++) {
                        if (compareKeys(key(items.get(i)), k) >= 0) p = i;
                        else break;
                    }
                }
                if (p < 0) throw new FormulaException("#YOK");
                return (double) (p + 1);
            }
            if ("CONCATENATE".equals(name)) {
                StringBuilder sb = new StringBuilder();
                for (Object v : flatten(allArgs(s))) sb.append(asText(v));
                return sb.toString();
            }
            if ("LEN".equals(name)) {
                need(s, 1, 1);
                return (double) asText(arg(s, 0)).length();
            }
            if ("LEFT".equals(name) || "RIGHT".equals(name)) {
                need(s, 1, 2);
                String text = asText(arg(s, 0));
                int n = s.size() == 2 ? intArg(arg(s, 1)) : 1;
                if (n < 0) throw new FormulaException("#DEĞER!");
                n = Math.min(n, text.length());
                return "LEFT".equals(name) ? text.substring(0, n) : text.substring(text.length() - n);
            }
            if ("MID".equals(name)) {
                need(s, 3, 3);
                String text = asText(arg(s, 0));
                int start = intArg(arg(s, 1));
                int n = intArg(arg(s, 2));
                if (start < 1 || n < 0) throw new FormulaException("#DEĞER!");
                if (start - 1 >= text.length()) return "";
                return text.substring(start - 1, Math.min(text.length(), start - 1 + n));
            }
            if ("UPPER".equals(name)) {
                need(s, 1, 1);
                return trUpper(asText(arg(s, 0)));
            }
            if ("LOWER".equals(name)) {
                need(s, 1, 1);
                return trLower(asText(arg(s, 0)));
            }
            if ("TRIM".equals(name)) {
                need(s, 1, 1);
                return asText(arg(s, 0)).trim().replaceAll("\\s+", " ");
            }
            if ("TODAY".equals(name)) {
                need(s, 0, 0);
                Calendar now = Calendar.getInstance();
                return new DateVal(toSerial(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                        now.get(Calendar.DAY_OF_MONTH), 0, 0, 0), false);
            }
            if ("DATE".equals(name)) {
                need(s, 3, 3);
                return new DateVal(toSerial(intArg(arg(s, 0)), intArg(arg(s, 1)), intArg(arg(s, 2)), 0, 0, 0), false);
            }
            if ("YEAR".equals(name)) {
                need(s, 1, 1);
                return (double) dateParts(arg(s, 0))[0];
            }
            if ("MONTH".equals(name)) {
                need(s, 1, 1);
                return (double) dateParts(arg(s, 0))[1];
            }
            if ("DAY".equals(name)) {
                need(s, 1, 1);
                return (double) dateParts(arg(s, 0))[2];
            }
            if ("ISBLANK".equals(name) || "ISNUMBER".equals(name)) {
                need(s, 1, 1);
                Object v;
                try {
                    v = evalSpan(s.get(0));
                } catch (FormulaException ex) {
                    v = ex.code;
                }
                if (v instanceof RangeVal) v = ((RangeVal) v).values.isEmpty() ? "" : ((RangeVal) v).values.get(0);
                if ("ISBLANK".equals(name)) return v == null || "".equals(v);
                return v instanceof Double || v instanceof DateVal;
            }
            throw new FormulaException("#AD?");
        }
    }
}
