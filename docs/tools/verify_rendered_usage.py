"""Validate usage column in generated DATA-DICTIONARY.md against backend Java."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "backend/src/main/java"

# SQL/prose identifiers that are not Java classes but valid writers (indirect OK if stated).
SQL_FUNCTIONS = frozenset(
    {
        "provision_tenant",
        "upsert_app_user",
        "claim_inbox_batch",
    }
)

_EXTRA_NEEDLES: dict[str, set[str]] = {
    "external_version": {"externalVersion"},
    "channel_account_id": {"channelAccountId"},
    "external_order_id": {"externalOrderId"},
    "hold_reason": {"holdReason"},
    "hold_note": {"holdNote"},
    "payment_method": {"paymentMethod"},
    "grand_total": {"grandTotal"},
    "ordered_at": {"orderedAt"},
    "ship_by": {"shipBy"},
    "line_total": {"lineTotal"},
    "unit_price": {"unitPrice"},
    "tracking_no": {"trackingNo"},
    "stock_version": {"stockVersion"},
    "reservation_group_id": {"reservationGroupId"},
}


def _camel(snake: str) -> str:
    parts = snake.split("_")
    return parts[0] + "".join(p.title() for p in parts[1:])


def parse_rendered_usage(dd_text: str) -> dict[tuple[str, str], str]:
    usage: dict[tuple[str, str], str] = {}
    current_table: str | None = None
    in_fields = False
    for line in dd_text.splitlines():
        m = re.match(r"^### `(\w+)`", line)
        if m:
            current_table = m.group(1)
            in_fields = False
            continue
        if line.strip() == "**Fields:**":
            in_fields = True
            continue
        if in_fields and line.startswith("| `"):
            parts = [p.strip() for p in line.split("|")]
            if len(parts) >= 8 and current_table:
                field = parts[1].strip("`")
                usage[(current_table, field)] = parts[6]
        if in_fields and line.startswith("### "):
            in_fields = False
    return usage


def java_class_names() -> set[str]:
    return {p.stem for p in JAVA.rglob("*.java")}


def _strip_caller_prose(text: str) -> str:
    return re.sub(r"\(caller [^)]+\)", "", text, flags=re.I)


def extract_classes(text: str, known: set[str]) -> list[str]:
    text = _strip_caller_prose(text)
    found: set[str] = set()
    for m in re.finditer(r"`([A-Z][A-Za-z0-9]+)`", text):
        found.add(m.group(1))
    for m in re.finditer(r"(?<![a-z0-9_/])([A-Z][A-Za-z0-9]{2,})(?![a-z0-9_])", text):
        name = m.group(1)
        if name in known:
            found.add(name)
    return sorted(found)


def _usage_role_classes(text: str, known: set[str]) -> list[tuple[str, str]]:
    """(class_name, 'write'|'read') from Thai usage line."""
    pairs: list[tuple[str, str]] = []
    if "อ่าน" in text:
        write_part, read_part = text.split("อ่าน", 1)
    else:
        write_part, read_part = text, ""
    write_part = _strip_caller_prose(write_part)
    write_markers = ("เขียน", "INSERT", "อัปเดต", "ตั้ง", "default", "UPDATE", "SELECT")
    if any(k in write_part for k in write_markers):
        for cls in extract_classes(write_part, known):
            pairs.append((cls, "write"))
    for cls in extract_classes(read_part, known):
        pairs.append((cls, "read"))
    return pairs


def column_in_file(path: Path, col: str) -> bool:
    body = path.read_text(encoding="utf-8")
    needles = {col, _camel(col)} | _EXTRA_NEEDLES.get(col, set())
    for n in needles:
        if re.search(r"\b" + re.escape(n) + r"\b", body):
            return True
    return False


def _sql_literals(java_text: str) -> list[str]:
    blocks = re.findall(r'"""([\s\S]*?)"""', java_text)
    blocks += re.findall(r'"(?:SELECT|INSERT|UPDATE|DELETE|FROM)[^"]*"', java_text, re.I)
    return blocks


def _column_in_sql(sql: str, col: str) -> bool:
    snake_pat = re.compile(r"\b" + re.escape(col) + r"\b")
    camel_pat = re.compile(r"\b" + re.escape(_camel(col)) + r"\b")
    return bool(snake_pat.search(sql) or camel_pat.search(sql))


def _table_in_sql(sql: str, table: str) -> bool:
    return bool(re.search(r"\b" + re.escape(table) + r"\b", sql, re.I))


def _is_select_sql(sql: str) -> bool:
    return bool(re.search(r"\bSELECT\b", sql, re.I))


def _is_write_sql(sql: str) -> bool:
    return bool(
        re.search(r"\bINSERT\s+INTO\b", sql, re.I)
        or re.search(r"\bUPDATE\b", sql, re.I)
        or re.search(r"\bDELETE\s+FROM\b", sql, re.I)
    )


def table_column_read_in_java(table: str, col: str) -> bool:
    for p in JAVA.rglob("*.java"):
        for sql in _sql_literals(p.read_text(encoding="utf-8")):
            if not _table_in_sql(sql, table):
                continue
            if not _is_select_sql(sql):
                continue
            if _column_in_sql(sql, col):
                return True
    return False


def table_column_write_in_java(table: str, col: str) -> bool:
    for p in JAVA.rglob("*.java"):
        for sql in _sql_literals(p.read_text(encoding="utf-8")):
            if not _table_in_sql(sql, table):
                continue
            if not _is_write_sql(sql):
                continue
            if _column_in_sql(sql, col):
                return True
    return False


def table_column_touched_in_java(table: str, col: str) -> bool:
    return table_column_read_in_java(table, col) or table_column_write_in_java(table, col)


def _reserved_checks(text: str, table: str, col: str) -> list[str]:
    """Return human reasons when rendered usage contradicts Java SQL touches."""
    reasons: list[str] = []
    full_reserved = "ไม่มี writer/reader" in text or (
        "reserved" in text.lower() and "ไม่ถูกอ้างอิง" in text
    )
    no_reader = "ไม่มี reader" in text
    no_writer = "ไม่มี writer" in text

    if full_reserved:
        if table_column_touched_in_java(table, col):
            reasons.append("reserved — column still referenced in Java SQL for this table")
        return reasons

    if no_reader and table_column_read_in_java(table, col):
        reasons.append("claims no reader but SELECT references column for this table")
    if no_writer and table_column_write_in_java(table, col):
        reasons.append("claims no writer but INSERT/UPDATE references column for this table")
    if "reserved" in text.lower() and no_reader and not no_writer and "อ่าน" not in text:
        if table_column_read_in_java(table, col):
            reasons.append("reserved no-reader cell but SELECT references column")
    return reasons


def verify_rendered(
    rendered: dict[tuple[str, str], str],
) -> tuple[list[tuple[str, str, str, str]], list[tuple[str, str, str]]]:
    """
    Returns (class_mismatches, reserved_violations).
    class_mismatches: (table, col, class, usage_snippet)
    reserved_violations: (table, col, reason)
    """
    known = java_class_names()
    class_mismatches: list[tuple[str, str, str, str]] = []
    reserved_violations: list[tuple[str, str, str]] = []

    skip_class = ("ops/SQL", "repository/API")

    for (table, col), text in rendered.items():
        for reason in _reserved_checks(text, table, col):
            reserved_violations.append((table, col, reason))

        if any(s in text for s in skip_class) and "อ่าน" not in text.split(";", 1)[0]:
            # Generic read hint — no concrete Java class to verify.
            pass
        elif text.strip().startswith("reserved") and "ไม่มี writer/reader" in text:
            continue
        elif "ไม่ถูกอ้างอิง" in text and "อ่าน" not in text:
            continue

        for sql_fn in SQL_FUNCTIONS:
            if re.search(r"\b" + re.escape(sql_fn) + r"\b", text):
                if not any(
                    re.search(r"\b" + re.escape(sql_fn) + r"\b", p.read_text(encoding="utf-8"))
                    for p in JAVA.rglob("*.java")
                ):
                    class_mismatches.append((table, col, sql_fn, text[:80]))

        for cls, _role in _usage_role_classes(text, known):
            if cls in SQL_FUNCTIONS:
                continue
            if cls not in known:
                continue
            if any(s in text for s in skip_class) and cls == text:
                continue
            paths = list(JAVA.rglob(f"{cls}.java"))
            if not paths:
                continue
            if not column_in_file(paths[0], col):
                class_mismatches.append((table, col, cls, text[:100]))

    return class_mismatches, reserved_violations
