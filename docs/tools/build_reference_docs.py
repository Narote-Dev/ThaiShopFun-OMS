#!/usr/bin/env python3
"""Build OMS reference markdown from schema dump + field_meta."""
from __future__ import annotations

import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SCHEMA = ROOT / "docs/schema/schema-ee4e425_39c6.sql"
DEFAULT_COLS = ROOT / "docs/schema/columns-ee4e425_4276.tsv"
REF = "อ้างอิง main @ ee4e425 (Flyway V13)"

sys.path.insert(0, str(ROOT / "docs/tools"))
from field_meta.all_fields import FIELDS  # noqa: E402
from field_meta.field_usage import USAGE  # noqa: E402

BUSINESS_TABLES = [
    "tenant", "app_user", "tenant_membership", "channel_account", "product", "sku",
    "sku_bundle_component", "channel_listing", "warehouse", "inventory", "inventory_ledger",
    "stock_reservation", "stock_document", "stock_document_line", "sales_order", "order_hold_retry",
    "order_recipient", "order_line", "order_status_history", "shipment", "return_request",
    "return_line", "refund", "payment_status_snapshot", "inbox_event", "outbox_event", "sync_cursor",
    "idempotency_key", "shadow_diff", "reconciliation_issue", "audit_log",
]

DOMAINS = {
    "Tenant / Identity": ["tenant", "app_user", "tenant_membership"],
    "Channel / Catalog": ["channel_account", "product", "sku", "sku_bundle_component", "channel_listing"],
    "Inventory / Stock": ["warehouse", "inventory", "inventory_ledger", "stock_reservation", "stock_document", "stock_document_line"],
    "Orders / Fulfillment": ["sales_order", "order_hold_retry", "order_recipient", "order_line", "order_status_history", "shipment"],
    "Payment": ["payment_status_snapshot", "refund"],
    "Returns": ["return_request", "return_line"],
    "Sync / Platform / Audit": ["inbox_event", "outbox_event", "sync_cursor", "idempotency_key", "shadow_diff", "reconciliation_issue", "audit_log"],
}

TABLE_META: dict[str, dict[str, str]] = {
    "tenant": {
        "purpose": "ร้าน (tenant) หนึ่งแถวต่อ TSF shop — tier, entitlement, ent_ver",
        "phase": "T02 JIT, T11 membership.changed",
        "live": "ใช้งานจริง",
        "writers": "`IdentityProvisioner` / `provision_tenant` (V2), `MembershipChangedHandler`",
        "readers": "`MeService`, `TenantSessionService`, `InboxEntitlementPolicy`, entitlement gate",
    },
    "app_user": {
        "purpose": "ผู้ใช้ OMS ต่อ `tsf_user_id` (ไม่มี password)",
        "phase": "T02",
        "live": "ใช้งานจริง",
        "writers": "`upsert_app_user` (SECURITY DEFINER) จาก `IdentityProvisioner`",
        "readers": "join ผ่าน `tenant_membership` (ไม่มี RLS)",
    },
    "audit_log": {
        "purpose": "audit append-only",
        "phase": "T02+",
        "live": "ใช้งานจริง",
        "writers": "`TenantSessionService` (auth.login + ip), `CatalogAudit`, `OrderAudit`, `ListingAudit`, `MembershipChangedHandler`, `OutboxAdminService`",
        "readers": "ops/SQL (ยังไม่มี UI)",
    },
}

MONEY_COLS = {
    ("sales_order", "subtotal"), ("sales_order", "shipping_fee"), ("sales_order", "discount"), ("sales_order", "grand_total"),
    ("order_line", "unit_price"), ("order_line", "discount"), ("order_line", "line_total"),
    ("payment_status_snapshot", "amount"), ("payment_status_snapshot", "refunded_amount"),
    ("refund", "amount"),
}

APPEND_ONLY = {"audit_log", "inventory_ledger", "order_status_history"}

ENUM_FROM_CHECK: dict[tuple[str, str], str] = {}

CROSS_DOMAIN_ER = """
### Cross-domain FK (อ้างอิงระหว่าง domain)

```mermaid
erDiagram
  tenant ||--o{ channel_account : tenant_id
  tenant ||--o{ sales_order : tenant_id
  channel_account ||--o{ sales_order : channel_account_id
  sales_order ||--o{ order_line : order_id
  sku ||--o{ order_line : sku_id
  sku ||--o{ inventory : sku_id
  warehouse ||--o{ inventory : warehouse_id
  sku ||--o{ stock_document_line : sku_id
  warehouse ||--o{ stock_document_line : warehouse_id
  sales_order ||--o| shipment : order_id
  warehouse ||--o{ shipment : warehouse_id
  sales_order ||--o{ payment_status_snapshot : order_id
  sales_order ||--o{ refund : order_id
  sales_order ||--o{ return_request : order_id
  return_request ||--o{ refund : return_id
  return_request ||--o{ return_line : return_id
  order_line ||--o{ return_line : order_line_id
  sales_order ||--o{ reconciliation_issue : order_id
  channel_account ||--o{ shadow_diff : channel_account_id
  channel_account ||--o{ sync_cursor : channel_account_id
```

หมายเหตุ: ทุกตารางธุรกิจยกเว้น `app_user` มี `tenant_id` FK → `tenant` และใช้ RLS `tenant_isolation` บน `app.tenant_id` (ENABLE + FORCE 30 ตาราง)
"""


def balanced_paren_expr(text: str, open_paren: int) -> tuple[str, int]:
    """Return CHECK expression inside parens starting at open_paren, and index after closing ')'."""
    if text[open_paren] != "(":
        raise ValueError("expected '('")
    depth = 0
    for i in range(open_paren, len(text)):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren + 1 : i], i + 1
    raise ValueError("unbalanced parens in CHECK")


def parse_schema(text: str):
    fks = defaultdict(list)
    for m in re.finditer(
        r"ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT (\w+) FOREIGN KEY \(([^)]+)\)"
        r" REFERENCES public\.(\w+)\(([^)]+)\)([^;]*);",
        text,
        re.I,
    ):
        tbl, cname, cols, ref, ref_cols, rest = m.groups()
        on_del = "NO ACTION"
        if "ON DELETE" in rest.upper():
            on_del = re.search(r"ON DELETE (\w+)", rest, re.I).group(1)
        fks[tbl].append((cname, cols.strip(), ref, ref_cols.strip(), on_del))

    indexes = defaultdict(list)
    for m in re.finditer(
        r"CREATE (UNIQUE )?INDEX (\w+) ON public\.(\w+) USING \w+ \(([^;]+)\)([^;]*);",
        text,
        re.I,
    ):
        unique, iname, tbl, cols, rest = m.groups()
        where = ""
        wm = re.search(r"WHERE (.+)$", rest.strip(), re.I)
        if wm:
            where = wm.group(1)
        indexes[tbl].append((iname, "UNIQUE" if unique else "INDEX", cols.strip(), where))

    pks = {}
    for m in re.finditer(
        r"ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT (\w+) PRIMARY KEY \(([^)]+)\);",
        text,
    ):
        pks[m.group(1)] = m.group(3).strip()

    uniques = defaultdict(list)
    for m in re.finditer(
        r"ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT (\w+) UNIQUE \(([^)]+)\);",
        text,
    ):
        uniques[m.group(1)].append((m.group(2), m.group(3).strip()))

    checks = defaultdict(list)
    for m in re.finditer(r"CREATE TABLE public\.(\w+) \((.*?)\n\);", text, re.S):
        tbl, body = m.group(1), m.group(2)
        for cm in re.finditer(r"CONSTRAINT (\w+) CHECK \(", body):
            expr, _ = balanced_paren_expr(body, cm.end() - 1)
            checks[tbl].append((cm.group(1), re.sub(r"\s+", " ", expr.strip())))
    for m in re.finditer(
        r"ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT (\w+) CHECK \(",
        text,
    ):
        tbl, cname = m.group(1), m.group(2)
        expr, _ = balanced_paren_expr(text, m.end() - 1)
        checks[tbl].append((cname, re.sub(r"\s+", " ", expr.strip())))
    for tbl in list(checks):
        seen: set[str] = set()
        deduped = []
        for name, expr in checks[tbl]:
            if name in seen:
                continue
            seen.add(name)
            deduped.append((name, expr))
        checks[tbl] = deduped

    triggers = defaultdict(list)
    for m in re.finditer(
        r"CREATE TRIGGER (\w+)\s+(.+?)\s+ON public\.(\w+)\s+",
        text,
        re.I,
    ):
        triggers[m.group(3)].append(f"`{m.group(1)}` — {m.group(2).strip()}")

    force_rls = set(re.findall(r"ALTER TABLE public\.(\w+) FORCE ROW LEVEL SECURITY;", text))

    functions = re.findall(
        r"CREATE FUNCTION public\.(\w+)\([^)]*\)[^;]*SECURITY DEFINER",
        text,
        re.I,
    )

    return fks, indexes, pks, uniques, checks, triggers, force_rls, sorted(set(functions))


def parse_columns(cols_path: Path):
    tables = defaultdict(list)
    with cols_path.open() as f:
        f.readline()
        for line in f:
            if not line.strip() or line.startswith("("):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 6:
                continue
            t, ord_, name, dtype, nullable, default, *rest = parts
            tables[t].append((int(ord_), name, dtype, nullable, default, rest[0] if rest else ""))
    return tables


def table_meta(name: str) -> dict[str, str]:
    if name in TABLE_META:
        return TABLE_META[name]
    # defaults from old generator — compact
    return {
        "purpose": f"ตาราง `{name}` (ดู 03-data-model.md)",
        "phase": "ดู Flyway / task list",
        "live": "ดูโค้ด main",
        "writers": "ดู repository/service ที่อ้างชื่อตาราง",
        "readers": "API/UI ที่ query ตารางนี้",
    }


def field_row(table: str, col: str, dtype: str, nullable: str, default: str, meta: dict):
    key = (table, col)
    if key not in FIELDS:
        raise KeyError(f"Missing field meta: {table}.{col}")
    fm = FIELDS[key]
    meaning = fm["meaning"].strip()
    if not meaning or meaning == "—":
        raise ValueError(f"Blank meaning: {table}.{col}")
    usage = fm.get("usage", "").strip() or USAGE.get(key, "").strip()
    if not usage:
        raise ValueError(f"Missing usage: {table}.{col}")
    notes_parts = []
    if fm.get("notes"):
        notes_parts.append(fm["notes"])
    if key in MONEY_COLS and "THB" not in (fm.get("notes") or "") and "numeric" not in meaning:
        notes_parts.append("หน่วย: THB `numeric(14,2)` (บาท ไม่ใช่ satang)")
    if table in APPEND_ONLY and col not in ("id", "tenant_id"):
        append_note = "append-only — ห้าม UPDATE/DELETE"
        if not any("append-only" in p for p in notes_parts):
            notes_parts.append(append_note)
    if key in ENUM_FROM_CHECK:
        notes_parts.append(ENUM_FROM_CHECK[key])
    notes = "; ".join(notes_parts) if notes_parts else "—"
    return [
        f"`{col}`",
        f"`{dtype}`",
        "YES" if nullable == "YES" else "NO",
        f"`{default}`" if default else "—",
        meaning,
        usage.replace("|", "\\|"),
        notes.replace("|", "\\|"),
    ]


def render_table(name, columns, parsed, meta):
    fks, indexes, pks, uniques, checks, triggers, force_rls, _funcs = parsed
    lines = [f"### `{name}`", ""]
    lines += [
        f"**วัตถุประสงค์:** {meta['purpose']}",
        f"**Phase / feature:** {meta['phase']} — **สถานะ:** {meta['live']}",
        "",
    ]
    lines.append(f"- **PK:** `{pks.get(name, '—')}`")
    if fks.get(name):
        lines.append("- **FK:**")
        for cname, cols_fk, ref, ref_cols, on_del in fks[name]:
            lines.append(f"  - `{cols_fk}` → `{ref}`(`{ref_cols}`) ON DELETE {on_del} (`{cname}`)")
    else:
        lines.append("- **FK:** —")
    if uniques.get(name):
        lines.append("- **UNIQUE:**")
        for cname, cols_u in uniques[name]:
            lines.append(f"  - `{cols_u}` (`{cname}`)")
    if checks.get(name):
        lines.append("- **CHECK:**")
        for cname, expr in checks[name]:
            lines.append(f"  - `{cname}`: `{expr[:200]}{'…' if len(expr) > 200 else ''}`")
    if indexes.get(name):
        lines.append("- **Indexes:**")
        for iname, kind, cols_i, where in indexes[name]:
            w = f" WHERE {where}" if where else ""
            lines.append(f"  - `{iname}` ({kind}) on `{cols_i}`{w}")
    if name == "app_user":
        lines.append("- **RLS:** ไม่มี (`tenant_id` ไม่มี — ดู V1)")
    elif name in force_rls:
        lines.append("- **RLS:** ENABLE + **FORCE** — policy `tenant_isolation` บน `tenant_id = current_setting('app.tenant_id')::uuid`")
    elif name == "tenant":
        lines.append("- **RLS:** ENABLE + **FORCE** — policy `tenant_isolation` บน `id = app.tenant_id`")
    if triggers.get(name):
        lines.append("- **Triggers:** " + "; ".join(triggers[name]))
    else:
        lines.append("- **Triggers:** —")
    lines += ["", "**Fields:**", ""]
    rows = [field_row(name, c[1], c[2], c[3], c[4], meta) for c in sorted(columns, key=lambda x: x[0])]
    lines.append(md_table(rows))
    return "\n".join(lines)


def md_table(rows):
    out = [
        "| field | type | null | default | ความหมาย | ใช้ทำอะไร / ใครเขียน-ใครอ่าน | หมายเหตุ |",
        "|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        out.append("| " + " | ".join(row) + " |")
    return "\n".join(out) + "\n"


def mermaid_er(domain_tables, fks, pks):
    domain_set = set(domain_tables)
    lines = ["```mermaid", "erDiagram"]
    for t in domain_tables:
        lines.append(f"  {t} {{")
        pk_raw = pks.get(t, "id")
        for pk_col in pk_raw.split(","):
            pk_col = pk_col.strip()
            # mermaid erDiagram requires "type name" lines
            lines.append(f"    uuid {pk_col}")
        lines.append("  }")
    seen = set()
    for t in domain_tables:
        for _, cols_fk, ref, _, on_del in fks.get(t, []):
            if ref not in domain_set:
                continue
            rel = f'  {ref} ||--o{{ {t} : "{cols_fk} ON DELETE {on_del}"'
            if rel not in seen:
                lines.append(rel)
                seen.add(rel)
    lines.append("```")
    return "\n".join(lines)


def enum_section() -> str:
    return open(ROOT / "docs/tools/enum_section.md", encoding="utf-8").read()


def roles_section() -> str:
    return open(ROOT / "docs/tools/roles_section.md", encoding="utf-8").read()


def build_data_dictionary(schema_path: Path, cols_path: Path):
    text = schema_path.read_text()
    parsed = parse_schema(text)
    columns = parse_columns(cols_path)
    parts = [
        f"# OMS Data Dictionary\n\n{REF}\n",
        "พจนานุกรมข้อมูลทุกคอลัมน์ธุรกิจ (329) จาก `docs/schema/schema-ee4e425_39c6.sql` / `columns-ee4e425_4276.tsv` "
        "cross-check กับ Flyway V1–V13 และ Java. หลักการ RLS/PII: [03-data-model.md](../plan/03-data-model.md).\n",
        "## ภาพรวมตาม domain\n",
    ]
    for domain, tbls in DOMAINS.items():
        parts.append(f"- **{domain}:** " + ", ".join(f"`{t}`" for t in tbls))
    parts.append("\n`flyway_schema_history` — ตาราง Flyway (ไม่ใช่ domain ธุรกิจ).\n")
    parts.append("## ER diagrams\n")
    for domain, tbls in DOMAINS.items():
        parts.append(f"### {domain}\n")
        parts.append(mermaid_er(tbls, parsed[0], parsed[2]) + "\n")
    parts.append(CROSS_DOMAIN_ER + "\n")
    parts.append("## ค่า enum / status\n" + enum_section())
    parts.append("## DB roles, RLS, Flyway\n" + roles_section())
    parts.append("## ตาราง\n")
    for t in BUSINESS_TABLES:
        parts.append(render_table(t, columns[t], parsed, table_meta(t)))
    return "\n".join(parts)


def build_modules():
    return (ROOT / "docs/tools/modules_body.md").read_text(encoding="utf-8")


def build_pages():
    return (ROOT / "docs/tools/pages_body.md").read_text(encoding="utf-8")


def build_docs_readme():
    body = (ROOT / "docs/tools/docs_readme_body.md").read_text(encoding="utf-8")
    return f"# ดัชนีเอกสาร OMS\n\n{REF}\n\n{body}"


def verify_mermaid(md_path: Path) -> int:
    blocks = re.findall(r"```mermaid\n(.*?)```", md_path.read_text(encoding="utf-8"), re.S)
    ok = 0
    for i, block in enumerate(blocks):
        tmp = Path(f"/tmp/mermaid_{i}.mmd")
        tmp.write_text(block, encoding="utf-8")
        try:
            subprocess.run(
                ["npx", "-y", "@mermaid-js/mermaid-cli", "-i", str(tmp), "-o", f"/tmp/mermaid_{i}.svg"],
                check=True,
                capture_output=True,
                timeout=120,
            )
            ok += 1
        except (subprocess.CalledProcessError, FileNotFoundError) as e:
            print("mermaid render failed", i, e)
    return ok


def main():
    import argparse
    import json

    parser = argparse.ArgumentParser(description="Regenerate OMS reference docs")
    parser.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA, help="pg_dump schema SQL")
    parser.add_argument("--columns", type=Path, default=DEFAULT_COLS, help="columns TSV from Flyway DB")
    parser.add_argument("--skip-mermaid", action="store_true")
    args = parser.parse_args()

    global COLS, SCHEMA  # noqa: PLW0603 — legacy hook for tests
    SCHEMA = args.schema
    COLS = args.columns

    meta_path = ROOT / "docs/tools/table_meta.json"
    if meta_path.exists():
        TABLE_META.update(json.loads(meta_path.read_text(encoding="utf-8")))

    dd = build_data_dictionary(args.schema, args.columns)
    (ROOT / "docs/db/DATA-DICTIONARY.md").write_text(dd, encoding="utf-8")
    (ROOT / "docs/backend/MODULES.md").write_text(build_modules(), encoding="utf-8")
    (ROOT / "docs/frontend/PAGES.md").write_text(build_pages(), encoding="utf-8")
    (ROOT / "docs/README.md").write_text(build_docs_readme(), encoding="utf-8")

    blank = [f"{t}.{c}" for (t, c), v in FIELDS.items() if not v.get("meaning", "").strip()]
    assert not blank, f"blank meanings: {blank[:5]}"
    assert len(FIELDS) == 329

    mermaid_ok = 0
    if not args.skip_mermaid:
        mermaid_ok = verify_mermaid(ROOT / "docs/db/DATA-DICTIONARY.md")
    checks = sum(len(v) for v in parse_schema(args.schema.read_text())[4].values())
    print(
        f"Wrote docs. Fields={len(FIELDS)} usage={len(USAGE)} checks={checks} mermaid_rendered={mermaid_ok}"
    )


if __name__ == "__main__":
    main()
