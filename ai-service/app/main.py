import multiprocessing as mp
import os
import queue
import re
import threading
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from .schemas import ParseResult
from .worker import run_worker

app = FastAPI(title="TableFlow internal OCR", docs_url=None, redoc_url=None)
slots = threading.BoundedSemaphore(2)

class BodyLimit:
    def __init__(self, app): self.app = app
    async def __call__(self, scope, receive, send):
        count = 0
        async def limited_receive():
            nonlocal count
            message = await receive()
            count += len(message.get("body", b""))
            if count > 11 * 1024 * 1024: raise HTTPException(413, "Upload too large")
            return message
        await self.app(scope, limited_receive, send)

app.add_middleware(BodyLimit)

@app.exception_handler(HTTPException)
async def http_error(request, exc):
    return JSONResponse(status_code=exc.status_code, content={"code": "OCR_ERROR", "message": str(exc.detail)})

@app.exception_handler(RequestValidationError)
async def invalid_request(request, exc):
    return JSONResponse(status_code=400, content={"code": "INVALID_REQUEST", "message": "Check upload and pages."})

@app.get("/health")
def health():
    return {"status": "ok", "provider": os.getenv("TABLEFLOW_OCR_PROVIDER", "mock")}

@app.post("/internal/v1/ocr/extract", response_model=ParseResult)
def extract(file: UploadFile = File(...), pages: str = Form(""), external_upload_consent: bool = Form(False)):
    try:
        filename = (file.filename or "").replace("\\", "/").rsplit("/", 1)[-1]
        data = file.file.read(10 * 1024 * 1024 + 1)
    finally: file.file.close()
    if not data or len(data) > 10 * 1024 * 1024 or not 1 <= len(filename) <= 255:
        raise HTTPException(413, "File must be between 1 byte and 10MB.")
    name = filename.lower()
    valid = ((name.endswith(".png") and data.startswith(b"\x89PNG\r\n\x1a\n")) or
             (name.endswith((".jpg", ".jpeg")) and data.startswith(b"\xff\xd8\xff")) or
             (name.endswith(".pdf") and data.startswith(b"%PDF-")))
    if not valid: raise HTTPException(400, "File signature does not match the extension.")
    if len(pages) > 60 or (pages and not re.fullmatch(r"[0-9]+(?:,[0-9]+)*", pages)):
        raise HTTPException(400, "Invalid pages.")
    selected = sorted(set(int(page) for page in pages.split(","))) if pages else []
    if any(page < 1 or page > 20 for page in selected): raise HTTPException(400, "Pages must be 1..20.")
    provider_name = os.getenv("TABLEFLOW_OCR_PROVIDER", "mock")
    if provider_name not in ("mock", "local", "external"): raise HTTPException(503, "Unknown OCR provider.")
    if provider_name == "external" and os.getenv("TABLEFLOW_OCR_EXTERNAL_UPLOAD_ENABLED", "false").lower() != "true":
        raise HTTPException(503, "External uploads are disabled.")
    if provider_name == "external" and not external_upload_consent:
        raise HTTPException(403, "Explicit consent is required for each external upload.")
    if not slots.acquire(blocking=False): raise HTTPException(429, "OCR capacity reached.")
    context = mp.get_context("spawn")
    results = context.Queue(maxsize=1)
    process = context.Process(target=run_worker, args=(results, data, filename, selected, provider_name), daemon=True)
    try:
        process.start()
        timeout = max(1, min(300, int(os.getenv("TABLEFLOW_OCR_TIMEOUT_SECONDS", "40"))))
        try: status, payload = results.get(timeout=timeout)
        except queue.Empty: raise HTTPException(504, "OCR deadline exceeded.") from None
        if status != 200: raise HTTPException(status, "OCR could not extract this table.")
        return ParseResult.model_validate(payload)
    finally:
        if process.is_alive(): process.terminate()
        if process.pid: process.join(timeout=3)
        results.close(); slots.release()
