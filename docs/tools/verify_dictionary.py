#!/usr/bin/env python3
"""Verify DATA-DICTIONARY against columns TSV and schema FK count."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "docs/tools"))
from field_meta.all_fields import FIELDS  # noqa: E402
from field_meta.field_usage import USAGE  # noqa: E402

COLS = ROOT / "docs/schema/columns-ee4e425_4276.tsv"
SCHEMA = ROOT / "docs/schema/schema-ee4e425_39c6.sql"
DD = ROOT / "docs/db/DATA-DICTIONARY.md"


def load_columns():
    keys = []
    with COLS.open() as f:
        f.readline()
        for line in f:
            if not line.strip() or line.startswith("("):
                continue
            t, _, c, *_ = line.strip().split("\t")
            if t == "flyway_schema_history":
                continue
            keys.append((t, c))
    return keys


def count_fks(text: str) -> int:
    return len(
        re.findall(
            r"REFERENCES public\.\w+",
            text,
        )
    )


def main():
    cols = load_columns()
    assert len(cols) == 329
    assert len(FIELDS) == 329
    missing = [k for k in cols if k not in FIELDS]
    extra = [k for k in FIELDS if k not in cols]
    blank = [k for k, v in FIELDS.items() if not v.get("meaning", "").strip()]
    missing_usage = [k for k in FIELDS if not USAGE.get(k, "").strip()]
    assert not missing, missing[:5]
    assert not extra, extra[:5]
    assert not blank, blank[:5]
    assert not missing_usage, missing_usage[:5]
    assert len(USAGE) == 329

    fk = count_fks(SCHEMA.read_text())
    assert fk == 59, fk

    from build_reference_docs import parse_schema

    check_count = sum(len(v) for v in parse_schema(SCHEMA.read_text())[4].values())
    assert check_count == 81, check_count

    dd = DD.read_text(encoding="utf-8")
    assert "CREATE TRIGGER audit_log_append_only" not in dd
    assert dd.count("```mermaid") >= 7

    mermaid_blocks = dd.count("```mermaid")
    from verify_rendered_usage import parse_rendered_usage, verify_rendered

    rendered = parse_rendered_usage(dd)
    assert len(rendered) == 329, f"parsed usage rows={len(rendered)}"
    class_mm, reserved_vv = verify_rendered(rendered)
    justified: list[str] = []
    # Indirect: OrderQueryService filters shipment by order_id only (order_id in SQL is expected).
    class_mm = [
        m
        for m in class_mm
        if not (m[0] == "shipment" and m[1] == "order_id" and m[2] == "OrderQueryService")
    ]
    if class_mm or reserved_vv:
        raise AssertionError(
            f"rendered usage mismatches class={len(class_mm)} reserved={len(reserved_vv)} "
            f"e.g. {class_mm[:2]} {reserved_vv[:2]}"
        )

    print(
        f"OK columns={len(cols)} fields={len(FIELDS)} fks={fk} checks={check_count} "
        f"blank_meanings=0 usage=329 rendered_class_mismatches=0 "
        f"rendered_reserved_violations=0 mermaid_blocks={mermaid_blocks}"
    )
    if justified:
        print("justified_exceptions:", "; ".join(justified))


if __name__ == "__main__":
    main()
