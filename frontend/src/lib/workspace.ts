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

export interface TargetColumn { name: string; type: string; jdbcType?: number }
export interface MappingSuggestion {
  columnId: string; source: string; target: string | null; reason: "exact" | "alias" | "similar" | "none";
  typeOk: boolean; note: string;
}
const koreanAliases: Record<string, string[]> = {
  "사원번호": ["EMP_NO", "EMPNO", "EMPLOYEE_ID"], "이름": ["EMP_NM", "NAME", "EMP_NAME"], "성명": ["NAME", "EMP_NM"],
  "입사일": ["HIRE_DT", "HIRE_DATE", "HIREDATE"], "급여": ["SALARY", "SAL", "PAY"], "거래일자": ["TRADE_DATE", "TRADE_DT"],
  "금액": ["AMOUNT", "AMT"], "설명": ["DESCRIPTION", "DESC", "MEMO"],
};
const loose = (value: string) => value.normalize("NFKC").replace(/([a-z0-9])([A-Z])/g, "$1_$2").replace(/[\s_\-]+/g, "").toUpperCase();
function distance(a: string, b: string): number {
  const row = Array.from({length: b.length + 1}, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    let prev = row[0]; row[0] = i;
    for (let j = 1; j <= b.length; j++) { const keep = row[j]; row[j] = Math.min(row[j] + 1, row[j - 1] + 1, prev + (a[i - 1] === b[j - 1] ? 0 : 1)); prev = keep; }
  }
  return row[b.length];
}
const identifierOk = (name: string) => /^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(name);
function compatible(type: Table["columns"][number]["data_type"], target: TargetColumn): boolean {
  const t = target.jdbcType, name = target.type.toUpperCase();
  if (t === undefined) return true;
  const numeric = [-6, 5, 4, -5, 2, 3, 6, 7, 8].includes(t), text = [1, 12, -1, -8, -9, -15, -16, 2005, 2011].includes(t);
  switch (type) {
    case "integer": case "decimal": return numeric || (t === 2 && name === "NUMBER");
    case "date": return t === 91 || t === 93;
    case "datetime": return t === 93 || t === 2014 || t === 91;
    case "bool": return t === 16 || t === -7 || numeric;
    default: return text || numeric || t === 91 || t === 93 || t === 16 || t === -7; // 문자열은 대부분의 타입으로 변환 가능하므로 서버 검사에 맡김
  }
}
/** 원본 컬럼을 실제 DB 컬럼에 추천 매핑한다. 확신이 없으면 target=null로 남겨 임의로 확정하지 않는다. */
export function suggestMapping(table: Table, targets: TargetColumn[]): MappingSuggestion[] {
  const usable = targets.filter(target => identifierOk(target.name));
  const taken = new Set<string>();
  const result: MappingSuggestion[] = [];
  for (const column of table.columns.filter(c => c.included)) {
    const pick = (target: TargetColumn, reason: MappingSuggestion["reason"]) => {
      taken.add(target.name); const typeOk = compatible(column.data_type, target);
      result.push({columnId: column.id, source: column.name, target: target.name, reason, typeOk,
        note: typeOk ? "" : `자료형 확인 필요: 원본 ${column.data_type} ↔ DB ${target.type}`});
    };
    const free = usable.filter(target => !taken.has(target.name));
    const exact = free.find(target => target.name.toUpperCase() === column.name.toUpperCase() || target.name.toUpperCase() === column.target_name.toUpperCase());
    if (exact) { pick(exact, "exact"); continue; }
    const aliases = (koreanAliases[column.name] || []).map(loose);
    const alias = free.find(target => aliases.includes(loose(target.name)));
    if (alias) { pick(alias, "alias"); continue; }
    const key = loose(column.name);
    const scored = free.map(target => { const other = loose(target.name); const longest = Math.max(key.length, other.length) || 1;
      return {target, score: 1 - distance(key, other) / longest}; }).filter(item => /[A-Z0-9]/.test(key) && item.score >= 0.8).sort((a, b) => b.score - a.score);
    // 1·2위가 거의 같으면 모호하므로 확정하지 않는다.
    if (scored.length && (scored.length === 1 || scored[0].score - scored[1].score >= 0.1)) { pick(scored[0].target, "similar"); continue; }
    result.push({columnId: column.id, source: column.name, target: null, reason: "none", typeOk: true,
      note: "일치하는 DB 컬럼을 확정하지 못했습니다. 컬럼 매핑 탭에서 직접 지정하세요."});
  }
  return result;
}
