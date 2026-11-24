"use client";

import { useCallback, useEffect, useState } from "react";
import { remove, request } from "./api";

export type Dbms = "oracle" | "postgresql" | "mysql" | "mariadb" | "sqlserver";
/** 요청에 쓰는 접속 정보. 저장된 연결의 비밀번호를 쓰려면 password를 비우고 savedId를 보낸다(서버가 복호화). */
export type Connection = { dbms: Dbms; host: string; port: number; database: string; username: string; password: string; tls: boolean; savedId?: string; driverId?: string };
export type SavedConnection = { id: string; name: string; dbms: Dbms; host: string; port: number; database: string; username: string; tls: boolean; passwordSaved: boolean; savedAt: string; driverId?: string };
export type InstalledDriver = { id: string; dbms: Dbms; version: string; sha256: string; bytes: number; registeredAt: string };
export type DbColumn = { name: string; type: string; jdbcType?: number; size: number; scale: number; nullable: boolean; primaryKey: boolean; autoIncrement: boolean; position?: number; foreignKey?: boolean };
export type ServerInfo = {
  enabled: boolean; loadEnabled: boolean; savedEnabled: boolean; savePasswordEnabled: boolean; defaultChunkSize: number;
  installedDrivers?: InstalledDriver[];
  drivers: { dbms: Dbms; defaultPort: number; available: boolean; version: string; capabilities?: { browse: boolean; load: boolean; createTable: boolean; preserveKeys: boolean; experimental: boolean } }[];
};

export const PORTS: Record<Dbms, number> = { oracle: 1521, postgresql: 5432, mysql: 3306, mariadb: 3306, sqlserver: 1433 };
export const DBMS_LABEL: Record<Dbms, string> = { oracle: "Oracle", postgresql: "PostgreSQL", mysql: "MySQL", mariadb: "MariaDB", sqlserver: "SQL Server" };
export const blankConnection = (dbms: Dbms = "postgresql"): Connection => ({ dbms, host: "localhost", port: PORTS[dbms], database: "", username: "", password: "", tls: false });

/** 서버 기능 상태(DB 조회·적재 활성화, 비밀번호 저장 허용, 기본 분할 단위). */
export function useServerInfo() {
  const [info, setInfo] = useState<ServerInfo | null>(null);
  const [error, setError] = useState("");
  const reload = useCallback(async () => {
    try { setInfo(await (await request("/connections/drivers")).json()); setError(""); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "서버 설정을 읽지 못했습니다."); }
  }, []);
  useEffect(() => {
    void reload();
    window.addEventListener("tableflow-drivers-changed", reload);
    return () => window.removeEventListener("tableflow-drivers-changed", reload);
  }, [reload]);
  return { info, error, reload };
}

/** 저장된 연결 목록. 비밀번호는 포함되지 않는다(저장 여부만 passwordSaved로 알린다). */
export function useSavedConnections() {
  const [items, setItems] = useState<SavedConnection[]>([]);
  const [enabled, setEnabled] = useState(true);
  const [error, setError] = useState("");
  const reload = useCallback(async () => {
    try { const result = await (await request("/connections/saved")).json(); setItems(result.items); setEnabled(result.enabled); setError(""); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "저장된 연결을 읽지 못했습니다."); }
  }, []);
  useEffect(() => { void reload(); }, [reload]);
  return { items, enabled, error, reload };
}

export async function saveConnection(name: string, connection: Connection, savePassword: boolean): Promise<SavedConnection> {
  return await (await request("/connections/saved", { name, connection, savePassword })).json();
}
export async function deleteSavedConnection(id: string): Promise<void> { await remove(`/connections/saved/${id}`); }

export function connectionName(c: Pick<Connection, "dbms" | "host" | "port" | "database">): string {
  return `${DBMS_LABEL[c.dbms]} · ${c.host}:${c.port}/${c.database}`;
}
