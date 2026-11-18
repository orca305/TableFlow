import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = { title: "TableFlow — 데이터를 다음 단계로", description: "Excel과 CSV를 검토하고 Oracle/MySQL SQL 또는 Excel로 변환하는 ETL 워크스페이스" };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="ko"><body>{children}</body></html>;
}
