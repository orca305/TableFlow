# TableFlow

Excel/CSV/PDF/이미지의 표를 검토·수정하고 Oracle/MySQL INSERT SQL 또는 XLSX로 출력하는 ETL 워크스페이스입니다. **Spring Boot ETL 엔진 + FastAPI 로컬 OCR**에서 실제 파싱, 인식, 정규화, 검증, 출력을 처리합니다.

현재 상태: **파일/OCR 추출·변환부터 MySQL 실제 적재까지 구현 및 로컬 검증 완료**. 실제 적재는 로컬 MySQL, Oracle은 SQL 파일 생성을 지원합니다. 외부 OCR은 모의 HTTP 검증이며 공개 배포는 미실시입니다.

## 시작하기

필수 환경: **JDK 21**, **Node.js 20.9 이상** (검증: Node 24), **Python 3.13** (이번 검증 환경). Gradle은 저장소의 wrapper를 사용합니다.

첫 번째 PowerShell 터미널:

```powershell
cd C:\dev\TableFlow
# 이 PC에서는 설치된 JDK 21을 자동 탐색합니다. 다른 PC에서는 JAVA_HOME을 설정하세요.
.\scripts\backend.ps1
```

직접 실행할 때:

```powershell
$env:JAVA_HOME='C:\Users\JBWOO\.jdks\openjdk-21.0.2'
cd C:\dev\TableFlow\backend
.\gradlew.bat bootRun
```

두 번째 터미널:

```powershell
cd C:\dev\TableFlow\frontend
npm ci
npm run dev
```

- 화면: <http://localhost:3100> (다른 프로젝트와의 포트 충돌 방지)
- API 상태: <http://localhost:8080/api/v1/health>
- 종료: 각 터미널에서 `Ctrl+C`

Linux/macOS: `JAVA_HOME`과 `PATH`를 JDK 21로 설정한 뒤 `cd backend && bash gradlew bootRun`을 실행합니다.

이미지/스캔 PDF에는 세 번째 터미널에서 OCR 서비스도 실행하세요:

```powershell
cd C:\dev\TableFlow\ai-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe prepare_models.py
cd ..
.\scripts\ocr.ps1 -Provider local
```

최초 준비 시 한글 모델을 다운로드할 수 있습니다. 사용자 파일은 업로드하지 않으며 이후 추론은 로컬에서 실행됩니다. Provider를 생략하면 고정 테스트 데이터를 반환하는 **mock**입니다. Linux/macOS: `cd ai-service; TABLEFLOW_OCR_PROVIDER=local .venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000`.

## 구현 기능

- CSV: UTF-8/BOM, MS949, EUC-KR, 쉼표·세미콜론·탭·파이프 구분자, 인용부호와 줄바꿈
- XLSX: 시트 선택, 헤더 행 선택, 날짜 셀, 수식 원문 보존
- PDF: 텍스트 좌표 기반 표 추출, 최대 20페이지, 페이지별 표 선택·편집 내용 보존
- PNG/JPEG/스캔 PDF: RapidOCR + ONNX Runtime 실제 로컬 추론, 한글/영문·숫자 샘플 검증
- OCR 출처·페이지·셀별 신뢰도·원본 확인, 검토 확인 후 다운로드, 타임아웃 시 프로세스 종료
- 기본 타입 추정, 셀 편집, 원본 값 보존, 대상 컬럼 이름·포함 여부·자료형·필수값·길이·정밀도 설정
- 정수/소수/날짜/일시/불리언 정규화, 셀 오류·중복 행·식별자 검증
- Oracle/PostgreSQL/MySQL/MariaDB INSERT SQL 다운로드, XLSX·CSV 다운로드
- 실제 MySQL 배치 INSERT, 대상 스키마 검사, 전체 트랜잭션·롤백, 영수증 기반 중복 요청 방지
- 요청·업로드 제한, XLSX 압축 해제 크기 제한, 문자열 안전 처리, Excel 수식 실행 방지

## 3분 시연

1. 화면에서 **샘플 데이터로 시작**을 누릅니다. 실제 CSV 파서로 데이터를 불러옵니다.
2. **컬럼 매핑**에서 사원번호→`EMP_NO`(문자열), 이름→`EMP_NM`(문자열), 입사일→`HIRE_DT`(날짜), 급여→`SALARY`(소수)로 설정합니다.
3. 이름을 필수값으로 설정하고 미리보기에서 첫 번째 이름을 지운 뒤 검증 오류를 확인합니다.
4. 이름을 복구하고 **데이터 검증**을 눌러 급여의 쉼표가 제거되는 것을 확인합니다.
5. 원하는 DBMS로 SQL을 내려받고, 대상 선택을 Excel 또는 CSV로 바꿔 파일도 내려받습니다.

OCR 시연: 화면의 **이미지 샘플** 다운로드 → 업로드 → 로컬 OCR 출처/신뢰도 확인 → 수정/컬럼 타입 설정 → 검증 → 원본 대조 검토 확인 → SQL/Excel 다운로드. PDF 샘플은 두 페이지의 표를 선택하며 편집할 수 있습니다. 스캔 샘플: `frontend/public/samples/table-scan.pdf`.

## MySQL 실제 적재 시작

Docker Desktop 실행 후 `.env.example`을 `.env`로 복사하고 비밀번호를 각각 임의 값으로 변경하세요. DB는 기존 프로젝트와 분리하여 `127.0.0.1:3307`에서 실행합니다.

```powershell
cd C:\dev\TableFlow
docker compose up -d --wait
. .\scripts\enable-local-db.ps1
.\scripts\backend.ps1
```

화면에서 샘플 컬럼 매핑·검증 → **데이터베이스 적재**를 선택하고 대상 확인 → 스키마 검사 → 실제 적재 동의 → 실행합니다. 기본은 비활성화이며 DB 비밀번호는 서버에만 유지합니다. 실제 적재는 로컬 접근만 허용하고 공개 배포에서는 노출하지 않습니다. [설정·권한·롤백·재시도 안내](docs/database-loading.md)

## 테스트 실행

```powershell
cd C:\dev\TableFlow\backend
.\gradlew.bat test bootJar
cd ..\frontend
npm ci
$env:NEXT_PUBLIC_API_URL="http://localhost:18080/api/v1"
npm run build
npx playwright install chromium
npm run test:e2e
```

실제 OCR을 포함한 전체 검증:

```powershell
cd C:\dev\TableFlow\ai-service
$env:TABLEFLOW_TEST_LOCAL_OCR='true'
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe benchmark_ocr.py
cd ..\frontend
npm run test:e2e
```

플래그를 생략하면 E2E의 실제 OCR 4건을 건너뜁니다. 전체 E2E는 JAR·Next.js·로컬 OCR 서비스를 자동 실행하거나 기존 서버를 사용합니다. `java`가 JDK 21을 가리키도록 `PATH`를 설정하세요. GitHub Actions에도 같은 흐름을 정의했으며 원격 CI 결과는 아직 없습니다.

실제 MySQL 테스트는 위 DB를 준비하고 `TABLEFLOW_TEST_MYSQL=true`, `TABLEFLOW_MYSQL_PASSWORD`를 설정한 뒤 실행합니다. DB를 사용하지 않는 기본 테스트에서는 4건의 DB 통합 테스트와 브라우저 DB 적재 2건을 명시적으로 건너뜁니다. 전체 실행 명령은 [DB 적재 문서](docs/database-loading.md)에 있습니다.

## 구조

```text
frontend/    Next.js 16 · React 19 · TypeScript · Tailwind 4
backend/     Java 21 · Spring Boot 3.5 · Gradle 8.8 · POI · Commons CSV · PDFBox · MySQL JDBC
infra/       MySQL 샘플 대상/영수증 스키마 (Docker Compose)
ai-service/  FastAPI · Pydantic · RapidOCR · ONNX Runtime · PDFium
docs/        아키텍처 · API · 요구사항 · 개발 현황
scripts/     Windows 실행 보조 스크립트
```

## 설정과 제한

- 프런트엔드: `frontend/.env.example`을 `.env.local`로 복사하여 `NEXT_PUBLIC_API_URL` 설정
- 백엔드: `PORT`(8080), `TABLEFLOW_FRONTEND_ORIGIN`(`http://localhost:3100`), `TABLEFLOW_OCR_URL`(`http://127.0.0.1:8000`), `TABLEFLOW_OCR_TIMEOUT_SECONDS`(45)
- OCR: `TABLEFLOW_OCR_PROVIDER`(mock), `TABLEFLOW_OCR_LANGUAGE`(korean, 대안 multilingual), `TABLEFLOW_OCR_TIMEOUT_SECONDS`(40). 괄호는 기본값입니다.
- 외부 OCR은 서버 허용과 파일별 전송 동의가 모두 필요하며 로컬 실패 시 외부로 자동 전환하지 않습니다. [OCR 운영·검증](docs/ocr.md)
- 최대 파일 10MB, 10,000 데이터 행, 100열, 셀 32,767자. XLSX 압축 해제 합계 32MB / ZIP 항목 2,000개
- PDF 최대 20페이지, OCR 이미지 최대 2,000만 픽셀입니다. 페이지당 한 개의 정방향 표와 첫 행 헤더를 전제로 하며 제목/병합/회전/다중 표/불규칙한 레이아웃은 정확한 구조를 보장하지 않습니다.
- JSON 요청 최대 32MB, IP당 POST 120회/분, 동시 처리 4건. 프록시 앞에서는 실제 IP/요청 제한을 배포 계층에서도 설정해야 합니다.
- 원본 파일과 편집 중 표는 서버에 영구 저장하지 않습니다. 새로고침 시 화면 작업이 사라지며, 성공적으로 적재한 DB 행과 영수증은 대상 DB에 남습니다.
- 빈 값과 공백만 있는 값은 NULL입니다. 모든 비어 있지 않은 값이 같은 형식일 때만 타입을 추정하고, 선행 0이 있는 값은 문자열로 유지합니다. 추정 결과는 사용자가 수정할 수 있습니다.
- XLSX 출력은 값들을 문자열 셀로 기록해 수식 실행과 숫자 반올림을 방지합니다.
- 실제 DB 적재는 MySQL에서 검증했습니다. 대상은 미리 준비한 트리거 없는 InnoDB 테이블이어야 합니다. 원격 인증/권한, Oracle 직접 적재, UPDATE/DELETE/DDL/스케줄링은 지원하지 않습니다.

이번 변경은 Phase 1을 우선했습니다. 범용 JDBC 연결 테스트·메타데이터 탐색을 구현했습니다(실서버 미검증). DB 추출/이관은 미구현입니다. [DBMS별 지원 범위](docs/DATABASE_SUPPORT.md) · [이번 검증 결과](docs/TEST_RESULTS.md)

상세: [개발 현황](docs/DEVELOPMENT_STATUS.md) · [API](docs/api.md) · [아키텍처](docs/architecture.md) · [요구사항](docs/requirements.md)

멀티 DB 적재 추가: 대상 연결을 사용한 Oracle/PostgreSQL/MySQL/MariaDB INSERT를 구현했습니다. 실제 DB 검증은 디스크 부족/Docker 장애로 대기 중입니다. [사용법·제약·실제 테스트](docs/jdbc-loading.md)
