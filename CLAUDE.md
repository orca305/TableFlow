# TableFlow — Claude Code 작업 지침

파일·관계형 DB(Oracle/PostgreSQL/MySQL/MariaDB)를 Source/Target으로 쓰는 ETL·마이그레이션 도구입니다. Spring Boot(Java 21) + Next.js + (선택) FastAPI OCR.

**작업 시작 전에 [docs/HANDOFF.md](docs/HANDOFF.md)를 먼저 읽으세요.** 현재 상태, 실행·테스트 명령, 알려진 한계, 이미 겪은 함정, 다음 작업 후보가 정리되어 있습니다. 상세 검증 근거는 `docs/TEST_RESULTS.md`, API는 `docs/api.md`.

## 반드시 지킬 것

- 화면 문구는 쉬운 업무 용어로 통일합니다: 이관(이전 아님), 원본/대상, 분할(청크 아님), 사전 점검(Dry-run 아님), 컬럼 매핑, 기본 키, 재개. 새 문구도 이 표기를 따르세요.
- 응답·진행 안내·계획·최종 보고는 **한국어**로 씁니다(코드 식별자·명령어·경로·로그 원문은 그대로).
- 구현과 검증 완료를 구분해서 보고합니다(통과/실패/건너뜀, mock/H2 기반 vs 실서버). 건너뛴 테스트를 검증으로 보고하지 않습니다.
- 기존 동작을 보존하고, 수정 후 회귀 테스트를 돌립니다. 문제는 로그·코드에서 원인을 확인한 뒤 고칩니다.
- 위험한 작업(데이터·Docker 볼륨 삭제, 강제 push/reset, 운영 DB 변경)은 사용자 승인 후에만 합니다. **Docker 이미지·볼륨은 TableFlow 전용이 아닐 수 있으니 임의로 정리하지 않습니다.**
- 실제 DB 테스트는 `infra/jdbc/compose.yml`의 전용 컨테이너(루프백 포트)에서만 실행합니다. DB 비밀번호는 Git·브라우저 저장소·로그·이력에 남기지 않습니다. 예외: 사용자가 "비밀번호 저장"을 선택한 연결만 서버 로컬 `data/connections.json`에 AES-256-GCM으로 암호화해 저장합니다(키는 `TABLEFLOW_SECRET_KEY` 또는 `data/connections.key`, 둘 다 git 제외, 브라우저로 다시 내려보내지 않음).
- 커밋·푸시는 사용자가 요청할 때만 합니다. 작업을 끝내면 `docs/HANDOFF.md`와 관련 문서를 최신 상태로 갱신합니다.

## 자주 쓰는 명령

```powershell
cd backend;  .\gradlew.bat test                                  # 기본(실제 DB 없이)
cd frontend; npm ci; npx tsc --noEmit; npx playwright test       # 사전: backend bootJar + NEXT_PUBLIC_API_URL=http://localhost:18080/api/v1 npm run build
```

실제 DB 통합 테스트와 브라우저 E2E 명령은 `docs/HANDOFF.md` §2를 따릅니다.
