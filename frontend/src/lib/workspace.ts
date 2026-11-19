import type { Table } from "./types";

export function suggestTableName(name: string): string {
  const clean = name.replace(/\.[^.]+$/, "").normalize("NFKD").replace(/[\u0300-\u036f]/g, "").replace(/[^a-zA-Z0-9]+/g, "_").replace(/^_+|_+$/g, "").toUpperCase();
  return (`TB_${clean || "DATA"}`).slice(0, 63);
}

export function suggestColumns(table: Table): Table {
  const aliases: Record<string, string> = { "사원번호": "EMP_NO", "이름": "EMP_NM", "성명": "NAME", "입사일": "HIRE_DT", "급여": "SALARY", "거래일자": "TRADE_DATE", "금액": "AMOUNT", "설명": "DESCRIPTION" };
  const used = new Set<string>();
  return {...table, columns: table.columns.map((column, index) => {
    const candidate = aliases[column.name] || column.name.replace(/([a-z0-9])([A-Z])/g, "$1_$2").replace(/[^A-Za-z0-9_]+/g, "_").replace(/^_+|_+$/g, "").toUpperCase();
    let target = /^[A-Z_][A-Z0-9_]*$/.test(candidate) ? candidate.slice(0, 55) : `COL_${index + 1}`;
    const base = target; let suffix = 2;
    while (used.has(target)) target = `${base}_${suffix++}`;
    used.add(target);
    return {...column, target_name: target};
  })};
}
