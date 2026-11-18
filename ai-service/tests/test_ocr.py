import os
from pathlib import Path
import pytest
from fastapi.testclient import TestClient
from PIL import Image
from pydantic import ValidationError
from app.main import app
from app.providers import ExternalProvider, arrange
from app.schemas import RecognizedCell, RecognizedGrid, canonical
from app.worker import extract

SAMPLES = Path(__file__).resolve().parents[2] / "frontend/public/samples"
client = TestClient(app)

def test_mock_is_explicit_and_canonical(monkeypatch):
    monkeypatch.setenv("TABLEFLOW_OCR_PROVIDER", "mock")
    response = client.post("/internal/v1/ocr/extract", files={"file": ("table.png", (SAMPLES / "table-en.png").read_bytes(), "image/png")})
    assert response.status_code == 200
    body = response.json(); table = body["tables"][0]
    assert table["schema_version"] == "1.0"
    assert table["source"]["extraction_method"] == "mock_ocr"
    assert "MOCK" in body["warnings"][0]
    assert table["rows"][0]["cells"]["col_1"]["confidence"] == 0

def test_signature_page_and_external_gate(monkeypatch):
    assert client.post("/internal/v1/ocr/extract", files={"file": ("fake.png", b"not an image")}).status_code == 400
    sample = (SAMPLES / "table-en.png").read_bytes()
    assert client.post("/internal/v1/ocr/extract", files={"file": ("table.png", sample)}, data={"pages": "21"}).status_code == 400
    monkeypatch.setenv("TABLEFLOW_OCR_PROVIDER", "external")
    monkeypatch.setenv("TABLEFLOW_OCR_EXTERNAL_UPLOAD_ENABLED", "false")
    assert client.post("/internal/v1/ocr/extract", files={"file": ("table.png", sample)}).status_code == 503
    with pytest.raises(PermissionError): ExternalProvider()
    monkeypatch.setenv("TABLEFLOW_OCR_EXTERNAL_UPLOAD_ENABLED", "true")
    assert client.post("/internal/v1/ocr/extract", files={"file": ("table.png", sample)}).status_code == 403

def test_grid_alignment_confidence_and_contract():
    grid = arrange([(10, 10, 10, "Name", .99), (200, 10, 10, "Amount", .98), (10, 50, 10, "Alice", .8), (200, 50, 10, "15000", .9)])
    table = canonical(grid, "image.png", None, "local")
    assert table.rows[0].cells["col_1"].raw == "Alice"
    assert table.rows[0].cells["col_1"].confidence == .8
    with pytest.raises(ValidationError): RecognizedCell(text="bad", confidence=1.1)
    with pytest.raises(ValidationError): RecognizedCell(text="bad", confidence=float("nan"))
    with pytest.raises(ValueError): canonical(RecognizedGrid(rows=[[RecognizedCell(text="a"), RecognizedCell(text="b")], [RecognizedCell(text="x")]]), "x.png", None, "local")

def test_pdf_page_bounds():
    with pytest.raises(ValueError): extract((SAMPLES / "table-scan.pdf").read_bytes(), "scan.pdf", [2], "mock")

def stalled_worker(*args):
    import time
    time.sleep(10)

def test_deadline_terminates_worker(monkeypatch):
    import time
    import app.main as main
    monkeypatch.setattr(main, "run_worker", stalled_worker)
    monkeypatch.setenv("TABLEFLOW_OCR_PROVIDER", "mock")
    monkeypatch.setenv("TABLEFLOW_OCR_TIMEOUT_SECONDS", "1")
    start = time.monotonic()
    response = client.post("/internal/v1/ocr/extract", files={"file": ("table.png", (SAMPLES / "table-en.png").read_bytes())})
    assert response.status_code == 504
    assert time.monotonic() - start < 5

def test_external_adapter_validates_explicit_gateway(monkeypatch):
    import httpx
    import app.providers as providers
    monkeypatch.setenv("TABLEFLOW_OCR_EXTERNAL_UPLOAD_ENABLED", "true")
    monkeypatch.setenv("TABLEFLOW_OCR_EXTERNAL_URL", "https://ocr.example/extract")
    def respond(request):
        assert request.url.host == "ocr.example"
        return httpx.Response(200, json={"rows": [[{"text": "Name"}, {"text": "Amount"}], [{"text": "Alice", "confidence": .9}, {"text": "15000", "confidence": .8}]]})
    original_client = httpx.Client
    monkeypatch.setattr(providers.httpx, "Client", lambda **kwargs: original_client(transport=httpx.MockTransport(respond), **kwargs))
    with Image.new("RGB", (10, 10), "white") as image:
        assert ExternalProvider().extract(image).rows[1][0].text == "Alice"

def test_local_failure_never_falls_back_to_external(monkeypatch):
    import queue
    import app.worker as worker
    calls = []
    def fail(name):
        calls.append(name)
        raise RuntimeError("unavailable")
    monkeypatch.setattr(worker, "provider", fail)
    results = queue.Queue()
    worker.run_worker(results, b"image", "x.png", [], "local")
    assert results.get() == (503, None)
    assert calls == ["local"]

@pytest.mark.skipif(os.getenv("TABLEFLOW_TEST_LOCAL_OCR") != "true", reason="Run opt-in real local model integration")
@pytest.mark.parametrize("filename,expected", [("table-en.png", "Alice"), ("table-en.jpg", "Alice"), ("table-ko.png", "김하늘"), ("table-scan.pdf", "Alice")])
def test_real_local_ocr(filename, expected):
    result = extract((SAMPLES / filename).read_bytes(), filename, [], "local")
    table = result.tables[0]
    values = [cell.value for row in table.rows for cell in row.cells.values()]
    assert expected in values
    assert "15000" in values
    assert table.source.provider == "local"
    assert all(cell.confidence is not None and 0 <= cell.confidence <= 1 for row in table.rows for cell in row.cells.values())
