export type DataType = "string" | "integer" | "decimal" | "date" | "datetime" | "bool";
export interface Column {
  id: string; name: string; target_name: string; data_type: DataType; nullable: boolean; included: boolean;
  max_length: number | null; precision: number | null; scale: number | null;
}
export interface Cell { raw: string | null; value: string | null; confidence: number | null; edited: boolean }
export interface Table {
  schema_version: "1.0"; table_id: string; name: string;
  source: { filename: string; type: string; extraction_method: string; page?: number | null; provider?: string | null };
  columns: Column[]; rows: { id: string; cells: Record<string, Cell> }[];
}
export interface Issue { rowId: string | null; columnId: string | null; code: string; message: string }
export interface ValidationResult { valid: boolean; issues: Issue[] }
export interface ParseResult { tables: Table[]; warnings: string[]; sheets: string[] }
