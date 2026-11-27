import type { Dbms } from "./connections";

/** 신규 테이블 컬럼 이름 기본값: DBMS 관례(PostgreSQL 소문자, Oracle 대문자)에 맞추고 영문/숫자/_만 남긴다. */
export function newColumnName(dbms: Dbms, name: string): string {
  let clean = name.normalize("NFKD").replace(/[^A-Za-z0-9_]/g, "_").replace(/^_+|_+$/g, "") || "COL";
  if (/^[0-9]/.test(clean)) clean = "C_" + clean;
  return dbms === "postgresql" || dbms === "cubrid" ? clean.toLowerCase() : (dbms === "oracle" || dbms === "tibero") ? clean.toUpperCase() : clean;
}
