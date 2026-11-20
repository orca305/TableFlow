# 개발 현황

기준일: 2026-11-20 (KST), 작업 브랜치: `main` (시작 커밋 `c66af51`, 이후 변경은 미커밋)

## 2026-11-20 요약

- Docker Desktop 크래시(깨진 유닉스 소켓 파일) 복구 → 실제 DB 컨테이너 4종 기동.
- 실제 서버 통합 테스트 통과: 4종 DBMS 연결·메타데이터·적재·재시도·충돌·전체 롤백, DB→DB 8개 경로(Oracle↔PostgreSQL 포함).
- 신규: 읽기 전용 DB 행 추출(`JdbcReader`, `POST /connections/read`) + 화면, 대상 DB 컬럼 기준 자동 매핑 추천, DB→DB 이전(`MigrationService`, `/connections/migrate[/check]`) + 화면.
- 기본 회귀: Java 38개(실패 0, 실제 DB 6 건너뜀), Playwright 17개(10 통과 / 7 건너뜀 / 실패 0), 실제 브라우저 Oracle→PostgreSQL E2E 통과.
- 추가 완료(같은 날 후속): DB→File 실제 브라우저 E2E, **작업 이력**(서버 JSON Lines, 비밀 미저장), **대상 테이블·영수증 자동 생성**(CREATE만, Dry-run DDL 미리보기, 실서버 7경로), **FK·자동 증가 경고/거부**(실서버 4경로). 실서버 테스트가 찾은 `JdbcLoader`의 Oracle IDENTITY 검사 버그(ORA-17027)를 수정.
- 기본 회귀: Java 48개(실패 0, 실제 DB 8 건너뜀) / 실서버 4종 포함 67개(실패 0) / Playwright 10 통과·11 건너뜀·실패 0.
- 추가 완료(마지막 라운드): **10,000행 초과 대용량 이전**(청크별 트랜잭션·영수증, 백그라운드 작업·진행률·취소, 같은 요청으로 이어서 실행; 실서버 4경로 + 실제 브라우저 12,500행), **원본 키 보존 + 시퀀스 재동기화**(실서버 6경로, Oracle ALWAYS는 거부). 시연 스크린샷 4장.
- 미완: FK·인덱스·기본값 복제와 이전 순서 자동 계산, 새 테이블의 자동 증가 속성 복제, OFFSET 방식의 대형 테이블 성능 최적화(키 기반 페이지)와 수십만 행 이상 성능 측정, 서버 재시작 후 작업 목록 복원, 임의 JAR 드라이버, SQL Server 등 추가 DBMS, 공개 배포·원격 CI.

## 이번 변경 — Phase 1 우선

- 인디고/슬레이트 UI, 소스 → 검토·변환 → 대상 3단계, 동작하는 샘플 시작 및 기본/오류/중복/다중 시트 샘플
- 헤더 행 선택, XLSX 스타일만 있는 끝 행/열 제외, General 숫자 원문 정밀도, 해결 방법이 있는 파서 오류
- 중복 경고/유지/제외/엄격 검증, 오류 셀 이동, 안전한 컬럼·테이블명 제안
- 4종 DBMS SQL, XLSX 및 CSV 출력. CSV 수식 보호와 음수 보존
- 이번 검증: Java 27 통과·실제 DB 4 건너뜀, Next 빌드 통과, E2E 7 통과·실제 DB/OCR 6 건너뜀
- 범용 JDBC 연결·탐색·적재·DB 행 추출·DB→DB 이전을 구현하고 **실제 서버 4종(PostgreSQL/MySQL/MariaDB/Oracle)에서 검증**했습니다(2026-11-20, 아래 참고). 기존 MySQL 적재는 유지했습니다.

상세: [지원 범위](DATABASE_SUPPORT.md), [이번 테스트 결과와 변경 파일](TEST_RESULTS.md). 아래 검증 표는 데스크탑에서 기록한 **기존 검증 이력**으로 이번 환경에서 재검증한 결과와 구분합니다.

## 기존 단계

**파일/OCR ETL + MySQL 실제 적재 구현 및 로컬 검증 완료.** 외부 게이트웨이는 모의 HTTP 계약만 검증했습니다. 공개 배포·GitHub push·원격 CI 실행은 아직 하지 않았습니다.

## 완료한 기능

- Java 21 / Spring Boot / Gradle wrapper, 공통 Table 1.0, 원본/가공 값·수정 여부·정밀도 보존
- CSV/XLSX 파싱, 인코딩·구분자·시트 선택, 기본 타입 추정
- 미리보기·셀 편집·컬럼 매핑, 정규화·검증, Oracle/MySQL INSERT 및 XLSX 출력
- PDFBox 텍스트 표, PDF 페이지 지정, 여러 표 선택과 편집 내용 보존
- FastAPI/Pydantic, OCRProvider, mock/local/external 어댑터
- RapidOCR/ONNX Runtime 실제 로컬 인식: 한글·영문 PNG/JPEG 및 스캔 PDF
- OCR 출처·페이지·신뢰도·원본 확인, 낮은 점수 강조, 검토 확인 후 출력
- 외부 전송 서버 opt-in + 파일별 동의, 로컬 장애 시 자동 전환 금지
- 별도 프로세스·타임아웃 종료, Java 응답 재검증, 파일·픽셀·페이지·요청 제한
- Windows 실행 스크립트, 비민감 샘플, 측정 결과, 문서, 확장된 CI

## 검증 결과

| 확인 | 결과 | 근거 |
| --- | --- | --- |
| Java tests | 20/20 통과 | `gradlew.bat test bootJar`: ETL 10 + PDF/OCR 4 + DB 통합 4 + 접근/API 2 |
| Python tests | 11/11 통과 | `TABLEFLOW_TEST_LOCAL_OCR=true`, `python -m pytest -q` |
| 실제 모델 샘플 | 4/4 전체 표 일치 | PNG/JPEG/한글/스캔 PDF, 헤더 포함 36/36 셀 |
| Next.js production build | 통과 | `npm run build` |
| 전체 브라우저 E2E | 11/11 통과 | CSV ETL, PDF 페이지/편집, 실제 OCR 4종 검토·SQL, 장애 UI, 모바일 |
| Mock | 실제 OCR와 구분 확인 | 출처/고정 데이터 경고/신뢰도 0 |
| 외부 OCR | 모의 HTTP만 통과 | 차단·동의·응답 계약. 실제 외부 호출 미실시 |
| 실제 DB 적재 | MySQL 8.4.13 통과 | 고정밀 타입/재시도/동시 요청/300행 중 마지막 실패 시 전체 0행·0영수증/브라우저 실제 적재·롤백 |
| 원격 CI / 공개 배포 | 미실시 | CI 정의만 작성, 호스팅 미선정 |

직접 OCR 시간은 샘플당 약 0.64~1.20초였습니다. [측정 JSON](ocr-benchmark.json)은 작은 합성 표의 측정이며 일반 문서 정확도나 HTTP 포함 응답 시간을 의미하지 않습니다. 상세: [OCR 운영·검증](ocr.md).

## 환경과 주요 결정

- 화면은 **3100** 포트입니다. 기존 3000 포트의 다른 프로젝트를 변경하지 않고 충돌을 피했습니다.
- 기본 Java 18 대신 설치된 JDK 21을 사용했습니다. 전역 설정은 변경하지 않았습니다.
- Python 가상환경 3.13, OCR 기본 공급자는 mock입니다. `scripts/ocr.ps1 -Provider local`로 실제 모델을 선택합니다.
- 한글 글자를 잘못 회전시키는 현상을 확인해 방향 분류기를 비활성화했습니다. 정방향 표가 대상입니다.
- Source에 선택적 page/provider를 추가했습니다. 호환 추가라 schema_version 1.0을 유지합니다.
- Python은 Starlette/httpx 호환 경고 1건이 있으나 모든 테스트를 통과했습니다.

## 제약

- 페이지당 한 개의 정방향 표·첫 행 헤더를 전제로 합니다. 제목/병합/회전/다중 표/불규칙 촬영의 구조·품질은 보장하지 않습니다.
- 신뢰도는 모델 점수입니다. OCR 값은 반드시 원본과 대조해야 합니다.
- XLSX는 문자열 셀, 빈 값/공백만 있는 값은 NULL로 출력합니다.
- 식별자는 ASCII 영문/숫자/_ 최대 64자이며 임의 DDL/Oracle 직접 적재는 없습니다.
- Oracle 문자열 UTF-8 4,000바이트, 숫자 정밀도 Oracle 38/MySQL 65, MySQL 날짜 1000년 이상·소수 초 6자리로 제한합니다.
- 최대 크기 조합의 부하 테스트는 미실시입니다. 공개 배포 전 서버 자원에 맞춘 측정이 필요합니다.
- 단일 프로세스 요청 제한이므로 reverse proxy/다중 인스턴스에는 배포 계층 제한이 필요합니다.

## 다음 작업

1. Java 호스팅 선정 후 Vercel 프런트엔드와 실제 API/Python OCR을 공개 배포합니다.
2. HTTPS·접근 경계·외부 전체 흐름을 확인한 뒤 포트폴리오 Live Demo를 연결합니다.
3. 필요시 비민감 실제 문서로 복잡한 레이아웃과 최대 크기 부하를 별도 측정합니다.

## MySQL 적재 추가 사항

사용자의 추가 요청으로 DB 직접 적재를 범위에 포함했습니다. 기본 비활성화·로컬 전용이며 서버 allowlist 대상에만 INSERT합니다. DB 접속 비밀은 Git/브라우저에 저장하지 않습니다. 실제 적재 성공과 영수증은 대상 DB에 유지하며, 작업 중 원본 파일과 표는 서버에 영구 저장하지 않습니다. 실행은 [DB 적재 문서](database-loading.md)를 확인하세요.

멀티 DB 적재: Oracle/PostgreSQL/MySQL/MariaDB INSERT를 구현하고 2026-11-20 실제 서버에서 검증했습니다. [사용법·제약·실제 테스트](jdbc-loading.md)
