# PDF·OCR 운영과 검증

기술 참고: [RapidOCR 공식 저장소](https://github.com/RapidAI/RapidOCR), [PDFBox 3 마이그레이션](https://pdfbox.apache.org/3.0/migration.html).

브라우저 → Spring Boot → 텍스트 PDF는 PDFBox, 이미지/스캔 페이지는 FastAPI → 공통 Table 1.0 → 사용자 검토·수정 → 기존 검증/SQL·Excel 출력으로 연결됩니다. 브라우저는 Python을 직접 호출하지 않으며 Python은 loopback에 바인딩합니다.

## 실행과 모델

README의 가상환경 설치 후 `python prepare_models.py`, `scripts/ocr.ps1 -Provider local`로 실행합니다. 최초 한글 모델 다운로드가 필요할 수 있으며 사용자 문서는 전송하지 않습니다. 가중치는 가상환경 모델 캐시에 저장되며 이후 추론은 로컬 CPU에서 수행합니다.

기본 provider는 **mock**입니다. 실제 로컬은 RapidOCR 3.10.0 / ONNX Runtime, PP-OCRv6 small detector + PP-OCRv5 Korean mobile recognizer입니다. `TABLEFLOW_OCR_LANGUAGE=multilingual`은 번들 다국어 모델을 선택합니다. 한글 글자를 잘못 회전시키는 현상을 실제 테스트에서 확인하여 글자 방향 분류기는 비활성화했습니다. 정방향 표를 준비해야 합니다.

`.env.example`은 참고용이며 자동 로드하지 않습니다. `$env:...` 또는 배포 환경변수로 설정하세요. Python timeout 기본 40초, Java 호출 제한 기본 45초입니다. 변경 시 Python 제한을 더 짧게 설정하세요. 모델 다운로드는 업로드 요청 전에 준비하는 것이 좋습니다.

## 공급자와 외부 전송

- mock: 고정 표, confidence 0.0, `mock_ocr` 출처와 MOCK 경고
- local: 실제 로컬 추론, `local_ocr` 출처, 셀 점수와 페이지
- external: 명시적으로 구성한 HTTPS 표 OCR 게이트웨이. 특정 상용 API에 바로 연결하는 어댑터는 아닙니다.

외부 전송에는 provider=external, `TABLEFLOW_OCR_EXTERNAL_UPLOAD_ENABLED=true`, 요청의 파일별 동의가 모두 필요합니다. UI의 `OCR 전송 설정`에서 동의하며 요청 한 번 후 체크가 초기화됩니다. 로컬 장애 시 외부로 전환하지 않습니다.

게이트웨이 설정: `TABLEFLOW_OCR_EXTERNAL_URL=https://...`, 선택 `TABLEFLOW_OCR_EXTERNAL_API_KEY`(Bearer). POST JSON은 PNG 정규화 이미지의 `{image_base64, mime_type:"image/png"}`이고 다음 응답을 요구합니다:

```json
{"rows":[[{"text":"Name","confidence":0.99},{"text":"Amount","confidence":0.98}],[{"text":"Alice","confidence":0.95},{"text":"15000","confidence":0.93}]]}
```

첫 행은 헤더, 직사각형 2~100열, 최대 10,000 데이터 행입니다. Pydantic에서 문자열/신뢰도(0~1, NaN 불가)/ID를 확인하고 Java에서도 재검증합니다. Redirect를 따르지 않고 응답 본문/키를 로그로 기록하지 않습니다. 실제 외부 호출은 하지 않았고 모의 HTTP 계약만 검증했습니다.

## 제한과 검토

- 10MB 파일, 20페이지 PDF, 2,000만 픽셀 이미지/렌더링 페이지, 10,000행·100열
- Python 동시 2건, 작업별 프로세스, deadline 초과 시 프로세스 종료
- PDF 페이지당 정렬된 단일 표/첫 행 헤더. 텍스트 표가 추출되지 않는 페이지를 OCR로 전달합니다. 혼합 PDF에서 일부 OCR이 실패하면 페이지를 지정해 재시도할 수 있습니다.
- 회전·제목·병합·다중 표·불규칙 촬영 문서의 구조/품질은 보장하지 않습니다.
- 신뢰도는 모델 점수이며 정확도 보증이 아닙니다. 85% 미만 셀을 강조합니다. 편집해도 raw/원래 점수는 유지합니다.
- 검증 후 원본 대조 확인이 있어야 출력 가능합니다. 서버는 OCR 출력에 `X-TableFlow-Reviewed: true`를 요구합니다. 수정·검증·표 전환 시 확인이 초기화됩니다.

## 실제 검증

2026-11-19 Windows/Python 3.13/CPU에서 영문 PNG/JPEG, 한글 PNG, 스캔 PDF 4개를 실제 OCR로 실행했습니다. 헤더 포함 36/36 셀이 기준값과 일치했고 직접 호출 시간은 샘플당 약 0.64~1.20초였습니다. 프로세스 시작/HTTP/브라우저 비용은 이 수치에 포함하지 않습니다.

결과: [ocr-benchmark.json](ocr-benchmark.json). 재현: 가상환경 Python으로 `benchmark_ocr.py` 실행. 비민감 합성 표의 작은 테스트이므로 일반 문서 정확도로 해석하지 않습니다.

Python 테스트는 실제 OCR 4종, mock 식별, 계약, 외부 서버 차단/파일별 동의, 게이트웨이 모의 응답, 외부 자동 전환 없음, timeout 종료를 포함합니다. Java는 PDF 페이지, 손상 시그니처, 수신 계약·타임아웃·연결 실패·검토 헤더를 검사합니다. 브라우저는 실제 업로드 → OCR → 검증 → 검토 → SQL 다운로드까지 확인합니다.
