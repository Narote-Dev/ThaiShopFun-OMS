#!/usr/bin/env python3
"""Verify DATA-DICTIONARY against columns TSV and schema FK count."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "docs/tools"))
from field_meta.all_fields import FIELDS  # noqa: E402

COLS = Path("/home/ubuntu/.cursor/projects/workspace/uploads/columns-ee4e425_4276.tsv")
SCHEMA = Path("/home/ubuntu/.cursor/projects/workspace/uploads/schema-ee4e425_39c6.sql")
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
    assert not missing, missing[:5]
    assert not extra, extra[:5]
    assert not blank, blank[:5]

    fk = count_fks(SCHEMA.read_text())
    assert fk == 59, fk

    dd = DD.read_text(encoding="utf-8")
    assert "CREATE TRIGGER audit_log_append_only" not in dd
    assert dd.count("```mermaid") >= 7

    print(f"OK columns={len(cols)} fields={len(FIELDS)} fks={fk} blank_meanings=0")


if __name__ == "__main__":
    main()
