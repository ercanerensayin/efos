"""EFOS-Ofis güvenli tablo formül hesaplama motoru.

Türkçe ve İngilizce Excel fonksiyonlarını, hücre ve aralık referanslarını
(A1, B2:B10, A:A), dört işlemi, üs (^), yüzde (%), metin birleştirme (&) ve
karşılaştırmaları destekler. ``eval`` kullanılmaz.

Türkçe yazım kuralları:
* Formülde ``;`` varsa (ör. ``=YUVARLA(2,345;2)``) virgül ondalık ayırıcıdır.
* Formülde fonksiyon yoksa (ör. ``=2,5*2``) virgül yine ondalık ayırıcıdır.
* Aksi halde (Excel'den gelen ``=ROUND(A1,2)``) virgül argüman ayırıcıdır.

Yuvarlama Excel gibi "yarımı sıfırdan uzağa" yapılır: YUVARLA(2,5;0)=3,
YUVARLA(1,005;2)=1,01.
"""

from __future__ import annotations

import datetime as _dt
import math
import re
from decimal import ROUND_DOWN, ROUND_HALF_UP, ROUND_UP, Decimal, InvalidOperation
from typing import Any, Callable, Dict, List, Optional, Sequence, Set, Tuple


CELL_REF_RE = re.compile(r"^\$?([A-Za-z]{1,3})\$?(\d+)$")
COL_ONLY_RE = re.compile(r"^\$?([A-Za-z]{1,3})$")

ERROR_CODES = {"#HATA!", "#DÖNGÜ!", "#SAYI/0!", "#DEĞER!", "#AD?", "#BAŞV!", "#YOK", "#SAYI!"}

EXCEL_EPOCH = _dt.date(1899, 12, 30)

# Excel'in sayı olarak saklarken tam koruyabildiği en uzun rakam dizisi
MAX_SAFE_DIGITS = 15


def col_letters_to_index(letters: str) -> int:
    """Sütun harfini (A -> 0, B -> 1, AA -> 26) sıfır tabanlı indekse çevirir."""
    idx = 0
    for ch in letters.upper():
        idx = idx * 26 + (ord(ch) - ord("A") + 1)
    return idx - 1


def index_to_col_letters(index: int) -> str:
    """Sıfır tabanlı sütun indeksini harfe (0 -> A, 26 -> AA) çevirir."""
    result = []
    n = index + 1
    while n > 0:
        n, rem = divmod(n - 1, 26)
        result.append(chr(ord("A") + rem))
    return "".join(reversed(result))


def parse_cell_ref(ref: str) -> Optional[Tuple[int, int]]:
    """'B3' veya '$B$3' ifadesini (satır, sütun) sıfır tabanlı tuple'a çevirir."""
    match = CELL_REF_RE.match(ref.strip())
    if not match:
        return None
    col = col_letters_to_index(match.group(1))
    row = int(match.group(2)) - 1
    if row < 0 or col < 0:
        return None
    return (row, col)


def format_cell_coord(row: int, col: int) -> str:
    """(0, 0) -> 'A1' biçiminde hücre koordinatı döndürür."""
    return f"{index_to_col_letters(col)}{row + 1}"


# ---------------------------------------------------------------------------
# Sayı / tarih / metin dönüşümleri
# ---------------------------------------------------------------------------

_TR_THOUSANDS_RE = re.compile(r"^[+-]?\d{1,3}(?:\.\d{3})+(?:,\d+)?$")
_EN_THOUSANDS_RE = re.compile(r"^[+-]?\d{1,3}(?:,\d{3})+(?:\.\d+)?$")
_PLAIN_NUM_RE = re.compile(r"^[+-]?(?:\d+(?:[.,]\d+)?|[.,]\d+)(?:[eE][+-]?\d+)?$")
_DATE_RE = re.compile(
    r"^(\d{1,2})[./](\d{1,2})[./](\d{4})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?$"
)
_ISO_DATE_RE = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?)?$"
)
_CURRENCY_RE = re.compile(r"(?:₺|\$|€|£|\bTL\b|\bTRY\b|\bUSD\b|\bEUR\b)", re.IGNORECASE)


def tr_lower(text: str) -> str:
    """Türkçe kurallarıyla küçük harfe çevirir (I -> ı, İ -> i)."""
    return text.replace("I", "ı").replace("İ", "i").lower()


def tr_upper(text: str) -> str:
    """Türkçe kurallarıyla büyük harfe çevirir (i -> İ, ı -> I)."""
    return text.replace("i", "İ").replace("ı", "I").upper()


def is_text_number(text: str) -> bool:
    """Sayı gibi görünse de metin olarak saklanması gereken değerleri tanır.

    Başında sıfır olan rakam dizileri (hesap no, TCKN, posta kodu: ``00123``) ve
    15 haneden uzun rakam dizileri (IBAN parçası, kart/ref no) sayıya çevrilirse
    bilgi kaybolur; bunlar metin olarak kalır.
    """
    t = text.strip()
    if not t.isdigit():
        return False
    if len(t) > 1 and t[0] == "0":
        return True
    return len(t) > MAX_SAFE_DIGITS


def parse_date_text(text: str) -> Optional[_dt.datetime]:
    """'05.01.2026', '5/1/2026 14:30' veya '2026-01-05' metnini tarihe çevirir."""
    t = text.strip()
    m = _DATE_RE.match(t)
    try:
        if m:
            d, mo, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
            hh = int(m.group(4) or 0)
            mm = int(m.group(5) or 0)
            ss = int(m.group(6) or 0)
            return _dt.datetime(y, mo, d, hh, mm, ss)
        m = _ISO_DATE_RE.match(t)
        if m:
            y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
            hh = int(m.group(4) or 0)
            mm = int(m.group(5) or 0)
            ss = int(m.group(6) or 0)
            return _dt.datetime(y, mo, d, hh, mm, ss)
    except ValueError:
        return None
    return None


def date_to_serial(value: Any) -> float:
    """Tarih/saat değerini Excel seri numarasına çevirir."""
    if isinstance(value, _dt.datetime):
        delta = value - _dt.datetime.combine(EXCEL_EPOCH, _dt.time())
        return delta.days + delta.seconds / 86400.0
    if isinstance(value, _dt.date):
        return float((value - EXCEL_EPOCH).days)
    if isinstance(value, _dt.time):
        return (value.hour * 3600 + value.minute * 60 + value.second) / 86400.0
    raise TypeError(value)


def serial_to_date(serial: float) -> _dt.datetime:
    return _dt.datetime.combine(EXCEL_EPOCH, _dt.time()) + _dt.timedelta(days=float(serial))


def format_date(value: Any) -> str:
    if isinstance(value, _dt.datetime):
        if value.hour or value.minute or value.second:
            return value.strftime("%d.%m.%Y %H:%M")
        return value.strftime("%d.%m.%Y")
    if isinstance(value, _dt.date):
        return value.strftime("%d.%m.%Y")
    if isinstance(value, _dt.time):
        return value.strftime("%H:%M")
    return str(value)


def to_number(val: Any) -> Optional[float]:
    """Değeri mümkünse float sayıya dönüştürür.

    Türkçe (``1.234,56``), İngilizce (``1,234.56``), yüzde (``%12`` / ``12%``),
    para birimli (``1.250 ₺``) ve tarih (``05.01.2026``) yazımlarını tanır.
    """
    if isinstance(val, bool):
        return 1.0 if val else 0.0
    if isinstance(val, (int, float)):
        if isinstance(val, float) and (math.isnan(val) or math.isinf(val)):
            return None
        return float(val)
    if isinstance(val, (_dt.date, _dt.datetime, _dt.time)):
        return date_to_serial(val)
    if isinstance(val, Decimal):
        return float(val)
    if val is None:
        return None
    text = str(val).strip()
    if not text:
        return None
    if text.startswith("'"):
        text = text[1:].strip()
        if not text:
            return None

    dt = parse_date_text(text)
    if dt is not None:
        return date_to_serial(dt)

    percent = False
    if text.endswith("%"):
        percent, text = True, text[:-1].strip()
    elif text.startswith("%"):
        percent, text = True, text[1:].strip()
    text = _CURRENCY_RE.sub("", text).replace(" ", "").replace(" ", "")
    if not text:
        return None

    if _TR_THOUSANDS_RE.match(text):
        candidate = text.replace(".", "").replace(",", ".")
    elif _EN_THOUSANDS_RE.match(text) and "." in text:
        candidate = text.replace(",", "")
    elif _PLAIN_NUM_RE.match(text):
        candidate = text.replace(",", ".")
    else:
        return None
    try:
        num = float(candidate)
    except ValueError:
        return None
    if math.isnan(num) or math.isinf(num):
        return None
    return num / 100.0 if percent else num


def format_display_value(val: Any) -> str:
    """Hesaplanan değeri ekranda temiz göstermek için metne çevirir."""
    if val is None:
        return ""
    if isinstance(val, bool):
        return "DOĞRU" if val else "YANLIŞ"
    if isinstance(val, (_dt.date, _dt.datetime, _dt.time)):
        return format_date(val)
    if isinstance(val, RangeValue):
        return format_display_value(val[0]) if val else ""
    if isinstance(val, float):
        if val.is_integer() and abs(val) < 1e16:
            return str(int(val))
        text = f"{val:.10f}".rstrip("0").rstrip(".")
        return text
    return str(val)


def format_display_tr(val: Any) -> str:
    """Ekranda Türkçe gösterim: ondalık ayırıcı virgül (1456,71)."""
    text = format_display_value(val)
    if isinstance(val, float) and not isinstance(val, bool):
        return text.replace(".", ",")
    return text


def excel_round(value: float, digits: int, mode: str = ROUND_HALF_UP) -> float:
    """Excel ile aynı sonucu veren yuvarlama (ondalık tabanlı, sıfırdan uzağa)."""
    try:
        d = Decimal(repr(float(value)))
        q = Decimal(1).scaleb(-digits)
        return float(d.quantize(q, rounding=mode))
    except (InvalidOperation, ValueError):
        raise FormulaError("#SAYI!")


class FormulaError(Exception):
    """Formül değerlendirme hatası."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class RangeValue(list):
    """Bir aralığın değerleri; satır/sütun boyutunu da taşır."""

    def __init__(self, values: Sequence[Any], n_rows: int, n_cols: int) -> None:
        super().__init__(values)
        self.n_rows = n_rows
        self.n_cols = n_cols

    def row(self, r: int) -> List[Any]:
        return list(self[r * self.n_cols:(r + 1) * self.n_cols])

    def col(self, c: int) -> List[Any]:
        return [self[r * self.n_cols + c] for r in range(self.n_rows)]

    def at(self, r: int, c: int) -> Any:
        return self[r * self.n_cols + c]


# ---------------------------------------------------------------------------
# Sözcük çözümleyici
# ---------------------------------------------------------------------------

_IDENT_CHARS = "A-Za-zÇĞİÖŞÜçğıöşü_"


def _build_token_re(turkish: bool) -> "re.Pattern[str]":
    if turkish:
        number = r"(?:\d+(?:[.,]\d+)?|[.,]\d+)(?:[eE][+-]?\d+)?"
        sep = r";"
    else:
        number = r"(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?"
        sep = r"[;,]"
    spec = [
        ("STRING", r'"(?:[^"]|"")*"'),
        ("RANGE", r"\$?[A-Za-z]{1,3}\$?\d+:\$?[A-Za-z]{1,3}\$?\d+|\$?[A-Za-z]{1,3}:\$?[A-Za-z]{1,3}(?![A-Za-z0-9(])"),
        ("NUMBER", number),
        ("IDENT", rf"\$?[{_IDENT_CHARS}][{_IDENT_CHARS}0-9_.\$]*"),
        ("OP", r"<>|!=|<=|>=|[+\-*/^=<>&%]"),
        ("LPAREN", r"\("),
        ("RPAREN", r"\)"),
        ("SEP", sep),
        ("WS", r"\s+"),
    ]
    return re.compile("|".join(f"(?P<{n}>{p})" for n, p in spec))


_TOKEN_RE_TR = _build_token_re(True)
_TOKEN_RE_EN = _build_token_re(False)


def _strip_strings(expr: str) -> str:
    return re.sub(r'"(?:[^"]|"")*"', '""', expr)


def is_turkish_syntax(expr: str) -> bool:
    """Formülde virgülün ondalık ayırıcı olup olmadığını belirler."""
    bare = _strip_strings(expr)
    if ";" in bare:
        return True
    return "(" not in bare


class _Tokenizer:
    @classmethod
    def tokenize(cls, expr: str, turkish: Optional[bool] = None) -> List[Tuple[str, str]]:
        if turkish is None:
            turkish = is_turkish_syntax(expr)
        regex = _TOKEN_RE_TR if turkish else _TOKEN_RE_EN
        tokens: List[Tuple[str, str]] = []
        pos = 0
        while pos < len(expr):
            m = regex.match(expr, pos)
            if not m:
                raise FormulaError("#HATA!")
            kind = m.lastgroup
            text = m.group()
            if kind != "WS":
                tokens.append((kind, text))
            pos = m.end()
        return tokens


# ---------------------------------------------------------------------------
# Fonksiyon adları (Türkçe <-> Excel İngilizcesi)
# ---------------------------------------------------------------------------

# Türkçe ad -> Excel'in dosyada sakladığı İngilizce ad
TR_TO_EN: Dict[str, str] = {
    "TOPLA": "SUM",
    "ORTALAMA": "AVERAGE",
    "MİN": "MIN",
    "MAK": "MAX",
    "SAY": "COUNT",
    "BAĞ_DEĞ_DOLU_SAY": "COUNTA",
    "BOŞLUKSAY": "COUNTBLANK",
    "EĞERSAY": "COUNTIF",
    "ETOPLA": "SUMIF",
    "EĞERORTALAMA": "AVERAGEIF",
    "EĞER": "IF",
    "EĞERHATA": "IFERROR",
    "EĞERYOKSA": "IFNA",
    "VE": "AND",
    "YADA": "OR",
    "DEĞİL": "NOT",
    "YUVARLA": "ROUND",
    "YUKARIYUVARLA": "ROUNDUP",
    "AŞAĞIYUVARLA": "ROUNDDOWN",
    "TAMSAYI": "INT",
    "MUTLAK": "ABS",
    "MOD": "MOD",
    "KUVVET": "POWER",
    "KAREKÖK": "SQRT",
    "DÜŞEYARA": "VLOOKUP",
    "YATAYARA": "HLOOKUP",
    "İNDİS": "INDEX",
    "KAÇINCI": "MATCH",
    "BİRLEŞTİR": "CONCATENATE",
    "UZUNLUK": "LEN",
    "SOLDAN": "LEFT",
    "SAĞDAN": "RIGHT",
    "PARÇAAL": "MID",
    "BÜYÜKHARF": "UPPER",
    "KÜÇÜKHARF": "LOWER",
    "KIRP": "TRIM",
    "BUGÜN": "TODAY",
    "ŞİMDİ": "NOW",
    "TARİH": "DATE",
    "YIL": "YEAR",
    "AY": "MONTH",
    "GÜN": "DAY",
    "EBOŞSA": "ISBLANK",
    "ESAYIYSA": "ISNUMBER",
    "EMETİNSE": "ISTEXT",
    "EHATALIYSA": "ISERROR",
    "ÇARPIM": "PRODUCT",
    "TOPLA.ÇARPIM": "SUMPRODUCT",
    "METNEÇEVİR": "TEXT",
    "SAYIYAÇEVİR": "VALUE",
}
EN_TO_TR: Dict[str, str] = {en: tr for tr, en in TR_TO_EN.items()}
# Aksansız yazımlar ve eş anlamlılar
_ALIASES: Dict[str, str] = {
    "EGER": "IF",
    "EGERHATA": "IFERROR",
    "EGERSAY": "COUNTIF",
    "MAKS": "MAX",
    "DUSEYARA": "VLOOKUP",
    "YATAYARA": "HLOOKUP",
    "INDIS": "INDEX",
    "KACINCI": "MATCH",
    "BUGUN": "TODAY",
    "SIMDI": "NOW",
    "BIRLESTIR": "CONCATENATE",
    "CONCAT": "CONCATENATE",
    "KAREKOK": "SQRT",
    "DEGIL": "NOT",
    "ORT": "AVERAGE",
}


def canonical_function_name(name: str) -> str:
    """Kullanıcının yazdığı fonksiyon adını Excel İngilizce adına çevirir."""
    up = tr_upper(name.strip())
    if up in TR_TO_EN:
        return TR_TO_EN[up]
    plain = name.strip().upper()
    if plain in _ALIASES:
        return _ALIASES[plain]
    if up in _ALIASES:
        return _ALIASES[up]
    if plain.startswith("_XLFN."):
        plain = plain[6:]
    return plain


def _render_number(text: str, decimal_sep: str) -> str:
    return text.replace(",", ".").replace(".", decimal_sep) if decimal_sep != "." else text.replace(",", ".")


def _render_tokens(
    tokens: List[Tuple[str, str]],
    sep: str,
    decimal_sep: str,
    name_map: Callable[[str], str],
    bool_names: Tuple[str, str] = ("TRUE", "FALSE"),
) -> str:
    out: List[str] = []
    for i, (kind, text) in enumerate(tokens):
        if kind == "NUMBER":
            out.append(_render_number(text, decimal_sep))
        elif kind == "SEP":
            out.append(sep)
        elif kind == "IDENT" and i + 1 < len(tokens) and tokens[i + 1][0] == "LPAREN":
            out.append(name_map(text))
        elif kind == "IDENT" and tr_upper(text) in ("TRUE", "DOĞRU"):
            out.append(bool_names[0])
        elif kind == "IDENT" and tr_upper(text) in ("FALSE", "YANLIŞ"):
            out.append(bool_names[1])
        else:
            out.append(text)
    return "".join(out)


def to_excel_formula(raw: str) -> str:
    """'=YUVARLA(A1*1,18;2)' -> '=ROUND(A1*1.18,2)' (Excel'in dosya biçimi)."""
    if not raw.startswith("="):
        return raw
    try:
        tokens = _Tokenizer.tokenize(raw[1:])
    except FormulaError:
        return raw
    return "=" + _render_tokens(tokens, ",", ".", canonical_function_name)


def from_excel_formula(raw: str) -> str:
    """'=ROUND(A1*1.18,2)' -> '=YUVARLA(A1*1,18;2)' (ekranda Türkçe gösterim)."""
    if not raw.startswith("="):
        return raw
    try:
        tokens = _Tokenizer.tokenize(raw[1:], turkish=False)
    except FormulaError:
        return raw

    def tr_name(name: str) -> str:
        canon = canonical_function_name(name)
        return EN_TO_TR.get(canon, name)

    rendered = _render_tokens(tokens, ";", ",", tr_name, ("DOĞRU", "YANLIŞ"))
    # Tek argümanlı ve fonksiyonsuz formüllerde ';' olmayabilir; ondalık virgül
    # ancak Türkçe sözdizimi olarak algılanacaksa güvenlidir.
    if "," in _strip_strings(rendered) and not is_turkish_syntax(rendered):
        return raw
    return "=" + rendered


# ---------------------------------------------------------------------------
# Hesaplama motoru
# ---------------------------------------------------------------------------


def _is_error(val: Any) -> bool:
    return isinstance(val, str) and val in ERROR_CODES


def literal_value(raw: Any) -> Any:
    """Formül olmayan bir hücre metninin hesaplamadaki değerini döndürür."""
    if raw is None:
        return ""
    if not isinstance(raw, str):
        return raw
    text = raw.strip()
    if not text:
        return ""
    if raw.startswith("'"):
        return raw[1:]
    if is_text_number(text):
        return text
    dt = parse_date_text(text)
    if dt is not None:
        return dt
    upper = tr_upper(text)
    if upper in ("DOĞRU", "TRUE"):
        return True
    if upper in ("YANLIŞ", "FALSE"):
        return False
    num = to_number(text)
    return num if num is not None else raw


class FormulaEngine:
    """Hücre değer okuyucusu ile formülleri güvenli biçimde hesaplar."""

    def __init__(
        self,
        get_raw_cell: Callable[[int, int], str],
        get_bounds: Optional[Callable[[], Tuple[int, int]]] = None,
    ) -> None:
        self._get_raw_cell = get_raw_cell
        self._get_bounds = get_bounds

    def bounds(self) -> Tuple[int, int]:
        if self._get_bounds:
            try:
                return self._get_bounds()
            except Exception:
                pass
        return (1000, 52)

    def evaluate_cell(
        self, row: int, col: int, visited: Optional[Set[Tuple[int, int]]] = None
    ) -> Any:
        if visited is None:
            visited = set()
        if (row, col) in visited:
            raise FormulaError("#DÖNGÜ!")

        raw = self._get_raw_cell(row, col)
        if raw is None:
            return ""
        text = str(raw).strip()
        if not text:
            return ""
        if not text.startswith("="):
            return literal_value(str(raw))

        visited = visited | {(row, col)}
        return self.evaluate_formula(text[1:], visited)

    def evaluate_formula(
        self, expr: str, visited: Optional[Set[Tuple[int, int]]] = None
    ) -> Any:
        if visited is None:
            visited = set()
        try:
            tokens = _Tokenizer.tokenize(expr.strip())
            if not tokens:
                return ""
            parser = _FormulaParser(tokens, self, visited)
            result = parser.parse_expression()
            if parser.pos < len(tokens):
                raise FormulaError("#HATA!")
            if isinstance(result, RangeValue):
                result = result[0] if len(result) == 1 else "#DEĞER!"
            if isinstance(result, float) and (math.isnan(result) or math.isinf(result)):
                return "#SAYI!"
            return result
        except FormulaError as err:
            return err.code
        except ZeroDivisionError:
            return "#SAYI/0!"
        except (OverflowError, ValueError):
            return "#SAYI!"
        except RecursionError:
            return "#DÖNGÜ!"
        except Exception:
            return "#HATA!"

    def get_range_values(
        self, range_str: str, visited: Set[Tuple[int, int]]
    ) -> RangeValue:
        start_s, end_s = range_str.split(":")
        start = parse_cell_ref(start_s)
        end = parse_cell_ref(end_s)
        if start is None and end is None:
            c1m, c2m = COL_ONLY_RE.match(start_s.strip()), COL_ONLY_RE.match(end_s.strip())
            if not c1m or not c2m:
                raise FormulaError("#BAŞV!")
            max_r, _ = self.bounds()
            start = (0, col_letters_to_index(c1m.group(1)))
            end = (max_r - 1, col_letters_to_index(c2m.group(1)))
        if not start or not end:
            raise FormulaError("#BAŞV!")
        r1, c1 = min(start[0], end[0]), min(start[1], end[1])
        r2, c2 = max(start[0], end[0]), max(start[1], end[1])
        if (r2 - r1 + 1) * (c2 - c1 + 1) > 200_000:
            raise FormulaError("#BAŞV!")
        values: List[Any] = []
        for r in range(r1, r2 + 1):
            for c in range(c1, c2 + 1):
                values.append(self.evaluate_cell(r, c, visited))
        return RangeValue(values, r2 - r1 + 1, c2 - c1 + 1)


_Span = Tuple[int, int]


class _FormulaParser:
    def __init__(
        self,
        tokens: List[Tuple[str, str]],
        engine: FormulaEngine,
        visited: Set[Tuple[int, int]],
    ) -> None:
        self.tokens = tokens
        self.engine = engine
        self.visited = visited
        self.pos = 0

    # -- yardımcılar -------------------------------------------------------
    def peek(self) -> Optional[Tuple[str, str]]:
        return self.tokens[self.pos] if self.pos < len(self.tokens) else None

    def consume(self, expected_kind: Optional[str] = None) -> Tuple[str, str]:
        tok = self.peek()
        if tok is None:
            raise FormulaError("#HATA!")
        if expected_kind and tok[0] != expected_kind:
            raise FormulaError("#HATA!")
        self.pos += 1
        return tok

    @staticmethod
    def _check(val: Any) -> Any:
        if _is_error(val):
            raise FormulaError(val)
        return val

    # -- dilbilgisi (Excel önceliği) ----------------------------------------
    # karşılaştırma < & < + - < * / < ^ < % < tekli -
    def parse_expression(self) -> Any:
        return self.parse_comparison()

    def parse_comparison(self) -> Any:
        left = self.parse_concat()
        while True:
            tok = self.peek()
            if tok and tok[0] == "OP" and tok[1] in ("=", "<>", "!=", "<", ">", "<=", ">="):
                op = self.consume()[1]
                right = self.parse_concat()
                left = self._apply_comparison(left, op, right)
            else:
                return left

    def parse_concat(self) -> Any:
        left = self.parse_additive()
        while True:
            tok = self.peek()
            if tok and tok == ("OP", "&"):
                self.consume()
                right = self.parse_additive()
                left = self._as_text(left) + self._as_text(right)
            else:
                return left

    def parse_additive(self) -> Any:
        left = self.parse_multiplicative()
        while True:
            tok = self.peek()
            if tok and tok[0] == "OP" and tok[1] in ("+", "-"):
                op = self.consume()[1]
                right = self.parse_multiplicative()
                l_num, r_num = self._require_number(left), self._require_number(right)
                left = l_num + r_num if op == "+" else l_num - r_num
            else:
                return left

    def parse_multiplicative(self) -> Any:
        left = self.parse_power()
        while True:
            tok = self.peek()
            if tok and tok[0] == "OP" and tok[1] in ("*", "/"):
                op = self.consume()[1]
                right = self.parse_power()
                l_num, r_num = self._require_number(left), self._require_number(right)
                if op == "*":
                    left = l_num * r_num
                else:
                    if r_num == 0:
                        raise FormulaError("#SAYI/0!")
                    left = l_num / r_num
            else:
                return left

    def parse_power(self) -> Any:
        # Excel'de ^ soldan sağa işlenir: 2^3^2 = 64
        base = self.parse_unary()
        while True:
            tok = self.peek()
            if tok and tok == ("OP", "^"):
                self.consume()
                exp = self.parse_unary()
                b, e = self._require_number(base), self._require_number(exp)
                if b == 0 and e < 0:
                    raise FormulaError("#SAYI/0!")
                result = b ** e
                if isinstance(result, complex):
                    raise FormulaError("#SAYI!")
                base = result
            else:
                return base

    def parse_unary(self) -> Any:
        tok = self.peek()
        if tok and tok[0] == "OP" and tok[1] in ("+", "-"):
            op = self.consume()[1]
            val = self.parse_unary()
            num = self._require_number(val)
            return -num if op == "-" else num
        return self.parse_postfix()

    def parse_postfix(self) -> Any:
        val = self.parse_primary()
        while self.peek() == ("OP", "%"):
            self.consume()
            val = self._require_number(val) / 100.0
        return val

    def parse_primary(self) -> Any:
        tok = self.peek()
        if tok is None:
            raise FormulaError("#HATA!")
        kind, text = tok

        if kind == "NUMBER":
            self.consume()
            return float(text.replace(",", "."))

        if kind == "STRING":
            self.consume()
            return text[1:-1].replace('""', '"')

        if kind == "LPAREN":
            self.consume("LPAREN")
            val = self.parse_expression()
            self.consume("RPAREN")
            return val

        if kind == "RANGE":
            self.consume()
            return self.engine.get_range_values(text, self.visited)

        if kind == "IDENT":
            self.consume()
            next_tok = self.peek()
            if next_tok and next_tok[0] == "LPAREN":
                return self._parse_function(text)
            upper = tr_upper(text)
            if upper in ("TRUE", "DOĞRU"):
                return True
            if upper in ("FALSE", "YANLIŞ"):
                return False
            coord = parse_cell_ref(text)
            if coord:
                return self._check(self.engine.evaluate_cell(coord[0], coord[1], self.visited))
            raise FormulaError("#AD?")

        raise FormulaError("#HATA!")

    # -- fonksiyonlar ------------------------------------------------------
    def _split_args(self) -> List[_Span]:
        """Parantez içindeki argümanların token aralıklarını bulur (tembel hesap için)."""
        self.consume("LPAREN")
        spans: List[_Span] = []
        depth = 0
        start = self.pos
        while True:
            tok = self.peek()
            if tok is None:
                raise FormulaError("#HATA!")
            kind = tok[0]
            if kind == "LPAREN":
                depth += 1
            elif kind == "RPAREN":
                if depth == 0:
                    if self.pos > start or spans:
                        spans.append((start, self.pos))
                    self.pos += 1
                    return spans
                depth -= 1
            elif kind == "SEP" and depth == 0:
                spans.append((start, self.pos))
                start = self.pos + 1
            self.pos += 1

    def _eval_span(self, span: _Span) -> Any:
        s, e = span
        if s == e:
            return ""
        sub = _FormulaParser(self.tokens[s:e], self.engine, self.visited)
        val = sub.parse_expression()
        if sub.pos != e - s:
            raise FormulaError("#HATA!")
        return val

    def _parse_function(self, func_name: str) -> Any:
        spans = self._split_args()
        name = canonical_function_name(func_name)
        handler = _FUNCTIONS.get(name)
        if handler is None:
            raise FormulaError("#AD?")
        return handler(self, spans)

    def arg(self, spans: List[_Span], i: int) -> Any:
        return self._check_deep(self._eval_span(spans[i]))

    def args(self, spans: List[_Span]) -> List[Any]:
        return [self.arg(spans, i) for i in range(len(spans))]

    def _check_deep(self, val: Any) -> Any:
        if isinstance(val, RangeValue):
            return val
        return self._check(val)

    def _flatten(self, args: List[Any]) -> List[Any]:
        out: List[Any] = []
        for item in args:
            if isinstance(item, RangeValue):
                out.extend(item)
            else:
                out.append(item)
        return out

    def _flatten_numbers(self, args: List[Any]) -> List[float]:
        """Aralıklarda yalnızca sayılar, doğrudan argümanlarda sayıya çevrilebilenler."""
        nums: List[float] = []
        for item in args:
            if isinstance(item, RangeValue):
                for sub in item:
                    self._check(sub)
                    if isinstance(sub, bool) or sub == "" or sub is None:
                        continue
                    if isinstance(sub, (int, float, _dt.date, _dt.time)):
                        nums.append(to_number(sub))  # type: ignore[arg-type]
                    elif isinstance(sub, str) and not is_text_number(sub):
                        n = to_number(sub)
                        if n is not None and not sub.startswith("'"):
                            nums.append(n)
            else:
                n = to_number(item)
                if n is not None:
                    nums.append(n)
                elif item not in ("", None):
                    raise FormulaError("#DEĞER!")
        return nums

    def _require_number(self, val: Any) -> float:
        if isinstance(val, RangeValue):
            if len(val) != 1:
                raise FormulaError("#DEĞER!")
            val = val[0]
        self._check(val)
        if val == "" or val is None:
            return 0.0
        n = to_number(val)
        if n is None:
            raise FormulaError("#DEĞER!")
        return n

    def _require_int(self, val: Any) -> int:
        return int(math.floor(self._require_number(val)))

    def _as_text(self, val: Any) -> str:
        if isinstance(val, RangeValue):
            if len(val) != 1:
                raise FormulaError("#DEĞER!")
            val = val[0]
        self._check(val)
        if isinstance(val, float):
            return format_display_value(val).replace(".", ",")
        return format_display_value(val)

    @staticmethod
    def _as_bool(val: Any) -> bool:
        if isinstance(val, RangeValue):
            val = val[0] if val else ""
        if isinstance(val, bool):
            return val
        if isinstance(val, str):
            up = tr_upper(val.strip())
            if up in ("DOĞRU", "TRUE"):
                return True
            if up in ("YANLIŞ", "FALSE", ""):
                return False
            n = to_number(val)
            if n is None:
                raise FormulaError("#DEĞER!")
            return n != 0
        n = to_number(val)
        return bool(n) if n is not None else bool(val)

    @staticmethod
    def _compare_key(val: Any) -> Tuple[int, Any]:
        """Excel sıralaması: sayılar < metinler < mantıksal değerler."""
        if isinstance(val, bool):
            return (2, val)
        if isinstance(val, (int, float, _dt.date, _dt.time)):
            return (0, to_number(val))
        if val is None or val == "":
            return (0, 0.0)
        s = str(val)
        if not s.startswith("'") and not is_text_number(s):
            n = to_number(s)
            if n is not None:
                return (0, n)
        return (1, tr_lower(s.lstrip("'")))

    def _apply_comparison(self, left: Any, op: str, right: Any) -> bool:
        if isinstance(left, RangeValue):
            left = left[0] if len(left) == 1 else self._check("#DEĞER!")
        if isinstance(right, RangeValue):
            right = right[0] if len(right) == 1 else self._check("#DEĞER!")
        self._check(left)
        self._check(right)
        # Boş hücre, karşısındakinin türünde sıfır/boş metin sayılır
        if left in ("", None) and isinstance(right, str) and to_number(right) is None:
            left = ""
        if right in ("", None) and isinstance(left, str) and to_number(left) is None:
            right = ""
        a, b = self._compare_key(left), self._compare_key(right)
        if op == "=":
            return a == b
        if op in ("<>", "!="):
            return a != b
        if op == "<":
            return a < b
        if op == ">":
            return a > b
        if op == "<=":
            return a <= b
        if op == ">=":
            return a >= b
        raise FormulaError("#HATA!")

    # -- ölçüt (EĞERSAY/ETOPLA) ------------------------------------------
    def _criteria_matcher(self, criteria: Any) -> Callable[[Any], bool]:
        if isinstance(criteria, RangeValue):
            criteria = criteria[0] if criteria else ""
        if not isinstance(criteria, str):
            target = criteria
            return lambda v: self._apply_comparison(v, "=", target)
        m = re.match(r"^(<>|<=|>=|=|<|>)?(.*)$", criteria, re.S)
        op = (m.group(1) if m else None) or "="
        rest = m.group(2) if m else criteria
        if op == "=" and ("*" in rest or "?" in rest):
            pattern = "^" + re.escape(tr_lower(rest)).replace(r"\*", ".*").replace(r"\?", ".") + "$"
            rx = re.compile(pattern, re.S)
            return lambda v: bool(rx.match(tr_lower(format_display_value(v))))
        target: Any = literal_value(rest) if rest != "" else ""
        return lambda v: self._apply_comparison(v, op, target)


# Fonksiyon gerçeklemeleri ----------------------------------------------------

def _need(spans: List[_Span], lo: int, hi: int) -> None:
    if not (lo <= len(spans) <= hi):
        raise FormulaError("#DEĞER!")


def _f_sum(p: _FormulaParser, s: List[_Span]) -> Any:
    return float(math.fsum(p._flatten_numbers(p.args(s))))


def _f_average(p: _FormulaParser, s: List[_Span]) -> Any:
    nums = p._flatten_numbers(p.args(s))
    if not nums:
        raise FormulaError("#SAYI/0!")
    return math.fsum(nums) / len(nums)


def _f_min(p: _FormulaParser, s: List[_Span]) -> Any:
    nums = p._flatten_numbers(p.args(s))
    return min(nums) if nums else 0.0


def _f_max(p: _FormulaParser, s: List[_Span]) -> Any:
    nums = p._flatten_numbers(p.args(s))
    return max(nums) if nums else 0.0


def _f_count(p: _FormulaParser, s: List[_Span]) -> Any:
    count = 0
    for item in p.args(s):
        values = item if isinstance(item, RangeValue) else [item]
        for v in values:
            if isinstance(v, bool) or _is_error(v):
                continue
            if isinstance(v, (int, float, _dt.date, _dt.time)):
                count += 1
            elif isinstance(v, str) and v and not isinstance(item, RangeValue) and to_number(v) is not None:
                count += 1
    return float(count)


def _f_counta(p: _FormulaParser, s: List[_Span]) -> Any:
    vals = p._flatten([p._eval_span(sp) for sp in s])
    return float(sum(1 for v in vals if v not in ("", None)))


def _f_countblank(p: _FormulaParser, s: List[_Span]) -> Any:
    vals = p._flatten([p._eval_span(sp) for sp in s])
    return float(sum(1 for v in vals if v in ("", None)))


def _f_countif(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 2, 2)
    rng = p._eval_span(s[0])
    values = rng if isinstance(rng, RangeValue) else [rng]
    match = p._criteria_matcher(p.arg(s, 1))
    return float(sum(1 for v in values if not _is_error(v) and match(v)))


def _f_sumif(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 2, 3)
    rng = p._eval_span(s[0])
    values = list(rng) if isinstance(rng, RangeValue) else [rng]
    match = p._criteria_matcher(p.arg(s, 1))
    sum_vals = values
    if len(s) == 3:
        sr = p._eval_span(s[2])
        sum_vals = list(sr) if isinstance(sr, RangeValue) else [sr]
    total = []
    for i, v in enumerate(values):
        if not _is_error(v) and match(v) and i < len(sum_vals):
            n = to_number(sum_vals[i]) if not isinstance(sum_vals[i], str) or not is_text_number(sum_vals[i]) else None
            if n is not None and not isinstance(sum_vals[i], bool):
                total.append(n)
    return math.fsum(total)


def _f_averageif(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 2, 3)
    rng = p._eval_span(s[0])
    values = list(rng) if isinstance(rng, RangeValue) else [rng]
    match = p._criteria_matcher(p.arg(s, 1))
    avg_vals = values
    if len(s) == 3:
        ar = p._eval_span(s[2])
        avg_vals = list(ar) if isinstance(ar, RangeValue) else [ar]
    nums = []
    for i, v in enumerate(values):
        if not _is_error(v) and match(v) and i < len(avg_vals):
            n = to_number(avg_vals[i])
            if n is not None and not isinstance(avg_vals[i], bool):
                nums.append(n)
    if not nums:
        raise FormulaError("#SAYI/0!")
    return math.fsum(nums) / len(nums)


def _f_if(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 2, 3)
    if p._as_bool(p.arg(s, 0)):
        return p.arg(s, 1)
    return p.arg(s, 2) if len(s) == 3 else False


def _f_iferror(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 2, 2)
    try:
        val = p._eval_span(s[0])
        if isinstance(val, RangeValue) and len(val) == 1:
            val = val[0]
        if _is_error(val):
            raise FormulaError(val)
        if isinstance(val, float) and (math.isnan(val) or math.isinf(val)):
            raise FormulaError("#SAYI!")
        return val
    except (FormulaError, ZeroDivisionError, OverflowError, ValueError):
        return p.arg(s, 1)


def _f_ifna(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 2, 2)
    try:
        val = p._eval_span(s[0])
        if val == "#YOK":
            raise FormulaError("#YOK")
        return val
    except FormulaError as err:
        if err.code == "#YOK":
            return p.arg(s, 1)
        raise


def _f_and(p: _FormulaParser, s: List[_Span]) -> Any:
    vals = p._flatten(p.args(s))
    vals = [v for v in vals if v not in ("", None)]
    if not vals:
        raise FormulaError("#DEĞER!")
    return all(p._as_bool(v) for v in vals)


def _f_or(p: _FormulaParser, s: List[_Span]) -> Any:
    vals = p._flatten(p.args(s))
    vals = [v for v in vals if v not in ("", None)]
    if not vals:
        raise FormulaError("#DEĞER!")
    return any(p._as_bool(v) for v in vals)


def _f_not(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 1, 1)
    return not p._as_bool(p.arg(s, 0))


def _round_factory(mode: str) -> Callable[[_FormulaParser, List[_Span]], Any]:
    def _f(p: _FormulaParser, s: List[_Span]) -> Any:
        _need(s, 1, 2)
        val = p._require_number(p.arg(s, 0))
        digits = p._require_int(p.arg(s, 1)) if len(s) == 2 else 0
        return excel_round(val, digits, mode)

    return _f


def _f_int(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 1, 1)
    return float(math.floor(p._require_number(p.arg(s, 0))))


def _f_abs(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 1, 1)
    return abs(p._require_number(p.arg(s, 0)))


def _f_mod(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 2, 2)
    a, b = p._require_number(p.arg(s, 0)), p._require_number(p.arg(s, 1))
    if b == 0:
        raise FormulaError("#SAYI/0!")
    return a - b * math.floor(a / b)


def _f_power(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 2, 2)
    b, e = p._require_number(p.arg(s, 0)), p._require_number(p.arg(s, 1))
    result = b ** e
    if isinstance(result, complex):
        raise FormulaError("#SAYI!")
    return float(result)


def _f_sqrt(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 1, 1)
    v = p._require_number(p.arg(s, 0))
    if v < 0:
        raise FormulaError("#SAYI!")
    return math.sqrt(v)


def _f_product(p: _FormulaParser, s: List[_Span]) -> Any:
    nums = p._flatten_numbers(p.args(s))
    return float(math.prod(nums)) if nums else 0.0


def _f_sumproduct(p: _FormulaParser, s: List[_Span]) -> Any:
    arrays = [p.arg(s, i) for i in range(len(s))]
    lists = [list(a) if isinstance(a, RangeValue) else [a] for a in arrays]
    if not lists or any(len(x) != len(lists[0]) for x in lists):
        raise FormulaError("#DEĞER!")
    total = 0.0
    for items in zip(*lists):
        prod = 1.0
        for v in items:
            p._check(v)
            n = to_number(v) if not isinstance(v, (bool, str)) else None
            prod *= n if n is not None else 0.0
        total += prod
    return total


def _lookup_equal(p: _FormulaParser, a: Any, b: Any) -> bool:
    if isinstance(b, str) and ("*" in b or "?" in b):
        return p._criteria_matcher(b)(a)
    return p._compare_key(a) == p._compare_key(b)


def _approx_index(p: _FormulaParser, items: List[Any], target: Any) -> int:
    """Sıralı listede hedeften küçük/eşit son öğenin indeksi (yoksa -1)."""
    key = p._compare_key(target)
    found = -1
    for i, v in enumerate(items):
        if v in ("", None) or _is_error(v):
            continue
        k = p._compare_key(v)
        if k[0] != key[0]:
            continue
        if k <= key:
            found = i
        else:
            break
    return found


def _f_vlookup(p: _FormulaParser, s: List[_Span], horizontal: bool = False) -> Any:
    _need(s, 3, 4)
    target = p.arg(s, 0)
    if isinstance(target, RangeValue):
        target = target[0] if target else ""
    table = p._eval_span(s[1])
    if not isinstance(table, RangeValue):
        raise FormulaError("#DEĞER!")
    index = p._require_int(p.arg(s, 2))
    approx = p._as_bool(p.arg(s, 3)) if len(s) == 4 else True
    limit = table.n_rows if horizontal else table.n_cols
    if index < 1:
        raise FormulaError("#DEĞER!")
    if index > limit:
        raise FormulaError("#BAŞV!")
    keys = table.row(0) if horizontal else table.col(0)
    if approx:
        pos = _approx_index(p, keys, target)
    else:
        pos = next((i for i, v in enumerate(keys) if not _is_error(v) and _lookup_equal(p, v, target)), -1)
    if pos < 0:
        raise FormulaError("#YOK")
    result = table.at(index - 1, pos) if horizontal else table.at(pos, index - 1)
    return p._check(result)


def _f_hlookup(p: _FormulaParser, s: List[_Span]) -> Any:
    return _f_vlookup(p, s, horizontal=True)


def _f_index(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 2, 3)
    table = p._eval_span(s[0])
    if not isinstance(table, RangeValue):
        table = RangeValue([table], 1, 1)
    r = p._require_int(p.arg(s, 1))
    c = p._require_int(p.arg(s, 2)) if len(s) == 3 else 1
    if table.n_rows == 1 and len(s) == 2:
        r, c = 1, r
    if r < 1 or c < 1 or r > table.n_rows or c > table.n_cols:
        raise FormulaError("#BAŞV!")
    return p._check(table.at(r - 1, c - 1))


def _f_match(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 2, 3)
    target = p.arg(s, 0)
    rng = p._eval_span(s[1])
    items = list(rng) if isinstance(rng, RangeValue) else [rng]
    mode = p._require_int(p.arg(s, 2)) if len(s) == 3 else 1
    if mode == 0:
        pos = next((i for i, v in enumerate(items) if _lookup_equal(p, v, target)), -1)
    elif mode == 1:
        pos = _approx_index(p, items, target)
    else:
        key = p._compare_key(target)
        pos = -1
        for i, v in enumerate(items):
            if p._compare_key(v) >= key:
                pos = i
            else:
                break
    if pos < 0:
        raise FormulaError("#YOK")
    return float(pos + 1)


def _f_concat(p: _FormulaParser, s: List[_Span]) -> Any:
    return "".join(p._as_text(v) for v in p._flatten(p.args(s)))


def _f_len(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 1, 1)
    return float(len(p._as_text(p.arg(s, 0))))


def _f_left(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 1, 2)
    n = p._require_int(p.arg(s, 1)) if len(s) == 2 else 1
    if n < 0:
        raise FormulaError("#DEĞER!")
    return p._as_text(p.arg(s, 0))[:n]


def _f_right(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 1, 2)
    n = p._require_int(p.arg(s, 1)) if len(s) == 2 else 1
    if n < 0:
        raise FormulaError("#DEĞER!")
    text = p._as_text(p.arg(s, 0))
    return text[-n:] if n else ""


def _f_mid(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 3, 3)
    text = p._as_text(p.arg(s, 0))
    start = p._require_int(p.arg(s, 1))
    n = p._require_int(p.arg(s, 2))
    if start < 1 or n < 0:
        raise FormulaError("#DEĞER!")
    return text[start - 1:start - 1 + n]


def _f_upper(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 1, 1)
    return tr_upper(p._as_text(p.arg(s, 0)))


def _f_lower(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 1, 1)
    return tr_lower(p._as_text(p.arg(s, 0)))


def _f_trim(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 1, 1)
    return " ".join(p._as_text(p.arg(s, 0)).split())


def _f_today(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 0, 0)
    return _dt.date.today()


def _f_now(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 0, 0)
    return _dt.datetime.now().replace(microsecond=0)


def _f_date(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 3, 3)
    y, m, d = (p._require_int(p.arg(s, i)) for i in range(3))
    try:
        y += (m - 1) // 12
        m = (m - 1) % 12 + 1
        return _dt.date(y, m, 1) + _dt.timedelta(days=d - 1)
    except (ValueError, OverflowError):
        raise FormulaError("#SAYI!")


def _date_arg(p: _FormulaParser, s: List[_Span]) -> _dt.datetime:
    _need(s, 1, 1)
    v = p.arg(s, 0)
    if isinstance(v, RangeValue):
        v = v[0] if v else ""
    if isinstance(v, _dt.datetime):
        return v
    if isinstance(v, _dt.date):
        return _dt.datetime.combine(v, _dt.time())
    return serial_to_date(p._require_number(v))


def _f_year(p: _FormulaParser, s: List[_Span]) -> Any:
    return float(_date_arg(p, s).year)


def _f_month(p: _FormulaParser, s: List[_Span]) -> Any:
    return float(_date_arg(p, s).month)


def _f_day(p: _FormulaParser, s: List[_Span]) -> Any:
    return float(_date_arg(p, s).day)


def _is_factory(check: Callable[[Any], bool]) -> Callable[[_FormulaParser, List[_Span]], Any]:
    def _f(p: _FormulaParser, s: List[_Span]) -> Any:
        _need(s, 1, 1)
        try:
            v = p._eval_span(s[0])
        except (FormulaError, ZeroDivisionError):
            v = "#HATA!"
        if isinstance(v, RangeValue):
            v = v[0] if v else ""
        return check(v)

    return _f


def _f_value(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 1, 1)
    v = p.arg(s, 0)
    if isinstance(v, RangeValue):
        v = v[0] if v else ""
    n = to_number(v)
    if n is None:
        raise FormulaError("#DEĞER!")
    return n


def _f_text(p: _FormulaParser, s: List[_Span]) -> Any:
    _need(s, 2, 2)
    v = p.arg(s, 0)
    fmt = p._as_text(p.arg(s, 1))
    n = to_number(v)
    if n is None:
        return p._as_text(v)
    low = fmt.lower()
    if any(x in low for x in ("gg", "aa", "yy", "dd", "mm")):
        d = serial_to_date(n)
        out = fmt
        for pat, rep in (("yyyy", "%Y"), ("YYYY", "%Y"), ("gg", "%d"), ("GG", "%d"), ("dd", "%d"),
                         ("aa", "%m"), ("AA", "%m"), ("mm", "%m"), ("MM", "%m"), ("yy", "%y")):
            out = out.replace(pat, rep)
        return d.strftime(out)
    if "," in fmt:
        int_part, dec_part = fmt.rsplit(",", 1)
        grouped = "." in int_part
    elif "." in fmt and set(fmt.split(".")[-1]) <= set("0#"):
        int_part, dec_part = fmt.rsplit(".", 1)
        grouped = False
    else:
        int_part, dec_part, grouped = fmt, "", "." in fmt
    decimals = sum(1 for c in dec_part if c in "0#")
    rounded = excel_round(n, decimals)
    text = f"{rounded:,.{decimals}f}" if grouped else f"{rounded:.{decimals}f}"
    return text.replace(",", "\u0000").replace(".", ",").replace("\u0000", ".")


_FUNCTIONS: Dict[str, Callable[[_FormulaParser, List[_Span]], Any]] = {
    "SUM": _f_sum,
    "AVERAGE": _f_average,
    "MIN": _f_min,
    "MAX": _f_max,
    "COUNT": _f_count,
    "COUNTA": _f_counta,
    "COUNTBLANK": _f_countblank,
    "COUNTIF": _f_countif,
    "SUMIF": _f_sumif,
    "AVERAGEIF": _f_averageif,
    "IF": _f_if,
    "IFERROR": _f_iferror,
    "IFNA": _f_ifna,
    "AND": _f_and,
    "OR": _f_or,
    "NOT": _f_not,
    "ROUND": _round_factory(ROUND_HALF_UP),
    "ROUNDUP": _round_factory(ROUND_UP),
    "ROUNDDOWN": _round_factory(ROUND_DOWN),
    "INT": _f_int,
    "ABS": _f_abs,
    "MOD": _f_mod,
    "POWER": _f_power,
    "SQRT": _f_sqrt,
    "PRODUCT": _f_product,
    "SUMPRODUCT": _f_sumproduct,
    "VLOOKUP": _f_vlookup,
    "HLOOKUP": _f_hlookup,
    "INDEX": _f_index,
    "MATCH": _f_match,
    "CONCATENATE": _f_concat,
    "LEN": _f_len,
    "LEFT": _f_left,
    "RIGHT": _f_right,
    "MID": _f_mid,
    "UPPER": _f_upper,
    "LOWER": _f_lower,
    "TRIM": _f_trim,
    "TODAY": _f_today,
    "NOW": _f_now,
    "DATE": _f_date,
    "YEAR": _f_year,
    "MONTH": _f_month,
    "DAY": _f_day,
    "ISBLANK": _is_factory(lambda v: v in ("", None)),
    "ISNUMBER": _is_factory(lambda v: isinstance(v, (int, float, _dt.date)) and not isinstance(v, bool)),
    "ISTEXT": _is_factory(lambda v: isinstance(v, str) and v != "" and not _is_error(v)),
    "ISERROR": _is_factory(_is_error),
    "VALUE": _f_value,
    "TEXT": _f_text,
}

SUPPORTED_FUNCTIONS: List[str] = sorted(
    {EN_TO_TR.get(name, name) for name in _FUNCTIONS} | set(_FUNCTIONS)
)
