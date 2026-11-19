# 이번 변경 검증 결과

2026-11-19, Windows / Java 21.0.13 / Next.js 16.4.6. 실제 실행 결과만 기록합니다.

| 검사 | 결과 |
|---|---|
| `backend: .\gradlew.bat test bootJar` | 24개 중 20 통과, 실제 MySQL 통합 4 건너뜀 |
| `frontend: npm run build` | TypeScript 포함 통과 |
| `frontend: npm run test:e2e` | 12개 중 6 통과, 실제 MySQL 2·로컬 OCR 4 건너뜀 |
| `git diff --check` | 통과 |

추가 단위 테스트: 중복 4정책/첫 행 보존/1,000개 경고 뒤 실제 오류 탐지, PostgreSQL E 문자열·불리언·일시·63자 제한·소수 초 제한, MariaDB HEX, CSV BOM·RFC4180 인용·수식 보호·음수 보존, CSV/XLSX 헤더 행·스타일 끝 행/열 제외·숫자 원문·헤더 오류 힌트. 기존 Oracle/MySQL·OCR API 검토 요구·접근 제어 회귀도 통과했습니다.

브라우저: CSV 실제 업로드→매핑→오류 편집→Oracle/MySQL SQL 및 XLSX 다운로드, 샘플→중복 경고/제외→PostgreSQL SQL/CSV 다운로드, PDF 표 선택과 편집 보존, API/OCR 장애 복구 화면, 모바일 390px 가로 넘침을 확인했습니다. 스크린샷은 `frontend/test-results/workspace-*.png`입니다. 해당 폴더는 Git 제외입니다.

실제 DB 서버가 없어 이번 MySQL 적재/롤백 재검증은 하지 않았고, Python OCR 모델도 이번 실행에서는 검증하지 않았습니다. 모의 API/단위 테스트를 실서버 검증으로 간주하지 않습니다. 원격 CI/공개 배포는 미실시입니다.

## 재현

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd C:\dev\TableFlow\backend
.\gradlew.bat test bootJar
cd ..\frontend
$env:NEXT_PUBLIC_API_URL='http://localhost:18080/api/v1'
npm run build
npx playwright install chromium
npm run test:e2e
Remove-Item Env:NEXT_PUBLIC_API_URL
```

E2E 전용 포트는 API 18080/프런트 3101입니다. 평소 개발 서버 8080/3100과 분리하며 테스트 서버는 종료 시 정리됩니다. 실제 DB/OCR 테스트는 기존 문서의 서비스·환경변수를 먼저 준비해야 합니다. 테스트 후 3100에서 production start하려면 평소 API URL(8080)로 다시 빌드하세요. Next 공개 환경변수는 빌드 시 반영됩니다.

## 주요 변경 파일

- `frontend/src/app/page.tsx`, `globals.css`: 3단계 UI, 샘플·헤더·중복 정책·오류 이동·출력 선택
- `frontend/src/lib/workspace.ts`, `api.ts`, `types.ts`: 안전한 이름 제안·오류 힌트·경고
- `frontend/public/samples/`: 중복·오류 CSV 및 다중 시트 XLSX
- `backend/.../parser/FileParser.java`, `FileParseException.java`, `DocumentParser.java`: 헤더·정밀도·구조 오류
- `backend/.../transform/TableEngine.java`, `model/Table.java`: 중복 정책·severity·solution
- `backend/.../export/TableExporter.java`, `api/TableController.java`, `ApiErrors.java`: 4 dialect·CSV·정책 전달·재검증
- Java/Playwright 테스트, `frontend/playwright.config.ts`, CI 빌드 환경 및 기존 문서

## 알려진 제약

CSV 헤더 행은 빈 줄을 건너뛴 논리 레코드 번호입니다. XLSX 헤더는 실제 행 번호입니다. 잘못된 헤더 선택의 행 너비 오류 위치는 선택한 헤더 기준입니다. 중복 제외 검증은 화면 표에서 중복 행을 제거하므로 원본을 다시 불러와야 복원됩니다. CSV 수식 보호는 원문 앞에 작은따옴표를 붙이며 출력 값을 바꿉니다. XLSX General 숫자는 파일에 저장된 값까지 보존하지만 Excel 저장 단계에서 이미 손실된 자릿수는 복원하지 못합니다. DB 소스 행 추출/DB 간 이관/작업 이력은 미구현입니다.

## JDBC 단계 추가 검증

이번 JDBC 변경 뒤 Java **31개 중 27 통과·실제 MySQL 4 건너뜀**, E2E **13개 중 7 통과·실제 MySQL/OCR 6 건너뜀**, Next.js 프로덕션 빌드 통과입니다.

추가 Java 7개: 네 실제 드라이버 로딩/URL/TLS·타임아웃 속성/record 마스킹, 비활성화·호스트 거부·원격 평문 차단, 인증·타임아웃·TLS 오류 마스킹, 메타데이터 패턴 이스케이프/읽기 전용/연결·ResultSet 종료, MySQL catalog/PK/컬럼 조회, API 기본 비활성화, 원격·교차 Origin·URL 주입 거부. JDBC 연결/메타데이터 성공 경로는 Mockito 계약 테스트이며 DB 서버에 접속한 결과가 아닙니다.

새 브라우저 테스트는 실제 API의 비활성화 표시, 연결 버튼 차단, 비밀번호 지우기/화면 전환 폐기, localStorage/sessionStorage 비저장을 확인합니다. 새 파일: `connection/JdbcExplorer.java`, `api/ConnectionController.java`, `frontend/src/components/DatabaseExplorer.tsx`, `JdbcExplorerTests.java`, `ConnectionApiTests.java`, `frontend/tests/connections.spec.ts`. 기존 설정/오류 처리/UI/문서도 갱신했습니다.

## 멀티 DB 적재 변경

공통 JDBC 적재·영수증·트랜잭션을 추가했습니다. 기본 Java 실행은 **28 통과, 실제 MySQL 기존 4 + 새 멀티 DB 통합 테스트 클래스 1 건너뜀**입니다. 새 API 테스트는 적재 opt-in/동의/OCR 검토를 확인합니다. 실제 DB 테스트는 Docker read-only/재시작 실패(디스크 부족)로 실행하지 못했습니다. [실제 검증 상태와 재현 명령](jdbc-loading.md). 신규 핵심 파일은 `load/JdbcLoader.java`, `connection/JdbcLoadIntegrationTests.java`, `infra/jdbc/*`이며 기존 JdbcExplorer/ConnectionController/DatabaseExplorer/page.tsx도 수정했습니다.
