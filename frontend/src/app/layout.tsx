import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = { title: "TableFlow — 데이터 이관·변환", description: "DB 간 데이터 이관과 파일(CSV·Excel·PDF·이미지) 변환을 지원하는 ETL 워크스페이스" };

// 첫 화면이 그려지기 전에 저장된 화면 모드(없으면 기기 설정)를 적용해 깜빡임을 막는다.
const themeScript = `try{var t=localStorage.getItem("tf-theme");if(t!=="light"&&t!=="dark")t=matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light";document.documentElement.setAttribute("data-theme",t)}catch(e){document.documentElement.setAttribute("data-theme","light")}`;

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="ko" suppressHydrationWarning><head><script dangerouslySetInnerHTML={{ __html: themeScript }} /></head><body>{children}</body></html>;
}
