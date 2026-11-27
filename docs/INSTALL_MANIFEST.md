# 설치 목록

[EAI 설계 §4.3](EAI_ARCHITECTURE.md)에 따라 오프라인 설치물에 넣을 구성요소를 모은다. **설치 패키지는 아직 없다.** 각 단계는 새로 추가한 의존성을 여기에 올려야 끝난 것으로 본다.

SHA-256은 실제로 묶는 파일이 정해질 때(2-설치) 기록한다. **재배포 조건은 모두 확인 전이다.**

| 구성요소 | 버전 | 쓰는 곳 | 추가 단계 | 묶는 방식 | 재배포 조건 |
|---|---|---|---|---|---|
| Java 런타임 | 21 | 앱 서버 | 기존 | OS별 런타임 | 확인 필요(배포판 미정) |
| 앱 jar의 Java 라이브러리 | `backend/build.gradle` | 앱 서버 | 기존 | jar에 포함 | 확인 필요(특히 DB 드라이버) |
| HikariCP | 6.3.3 | 상태 DB 연결 | 1-a | jar에 포함 | 확인 필요 |
| Flyway (core, PostgreSQL) | 11.7.2 | 상태 DB 표 버전 | 1-a | jar에 포함 | 확인 필요 |
| PostgreSQL 서버 | 17 (개발·시험은 `postgres:17-alpine` 컨테이너) | 상태 DB | 1-a | OS별 설치 파일(미정) | 확인 필요 |
| 화면(Next.js 빌드 결과) | `frontend/package.json` | 브라우저 화면 | 기존 | 정적 파일(제안, 미확인) | 확인 필요 |
| Python·OCR 패키지·모델 | `ai-service/requirements.txt` | OCR(선택) | 기존 | 선택 구성 | 확인 필요 |

## 설치할 때 필요한 설정 (설치기가 채울 값)

| 설정 | 환경 변수 | 비고 |
|---|---|---|
| 상태 DB 주소 | `TABLEFLOW_STATE_DB_URL` | 필수. 내장 PostgreSQL의 루프백 주소 |
| 상태 DB 계정 | `TABLEFLOW_STATE_DB_USER`, `TABLEFLOW_STATE_DB_PASSWORD` | 필수. 설치할 때 생성 |
| 상태 DB 스키마 | `TABLEFLOW_STATE_DB_SCHEMA` | 기본 `tableflow` |
| 연결 암호 키 | `TABLEFLOW_SECRET_KEY` | 없으면 `data/connections.key` 생성. DB와 따로 백업 |
