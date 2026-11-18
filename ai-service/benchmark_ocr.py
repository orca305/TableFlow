"""Small reproducible synthetic-sample check, not an accuracy claim for arbitrary documents."""
import json
import time
from datetime import datetime, timezone, timedelta
from pathlib import Path
from app.worker import extract

if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    report = {"measured_at": datetime.now(timezone(timedelta(hours=9))).isoformat(), "provider": "local", "samples": []}
    for filename in ["table-en.png", "table-en.jpg", "table-ko.png", "table-scan.pdf"]:
        korean = filename == "table-ko.png"
        expected = [["이름", "금액", "일자"], ["김하늘", "15000", "2026-11-18"], ["이도윤", "24000", "2026-11-19"]] if korean else [["Name", "Amount", "Date"], ["Alice", "15000", "2026-11-18"], ["Bob", "24000", "2026-11-19"]]
        started = time.perf_counter()
        table = extract((root / "frontend/public/samples" / filename).read_bytes(), filename, [], "local").tables[0]
        actual = [[column.name for column in table.columns]] + [[row.cells[column.id].value for column in table.columns] for row in table.rows]
        matched = sum(a == e for row_a, row_e in zip(actual, expected) for a, e in zip(row_a, row_e))
        report["samples"].append({"file": filename, "seconds": round(time.perf_counter() - started, 3), "matching_cells": matched, "expected_cells": 9, "exact_table_match": actual == expected})
    path = root / "docs/ocr-benchmark.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True))
    if not all(sample["exact_table_match"] for sample in report["samples"]): raise SystemExit(1)
