import io
import warnings
from PIL import Image, ImageOps
from .providers import provider
from .schemas import ParseResult, canonical

MAX_PIXELS = 20_000_000

def extract(data: bytes, filename: str, pages: list[int], provider_name: str) -> ParseResult:
    engine = provider(provider_name)
    tables = []
    if filename.lower().endswith(".pdf"):
        import pypdfium2 as pdfium
        with pdfium.PdfDocument(data) as document:
            if not 1 <= len(document) <= 20 or any(page < 1 or page > len(document) for page in pages):
                raise ValueError("Invalid PDF page range")
            for index in pages or range(1, len(document) + 1):
                page = document[index-1]
                try:
                    width, height = page.get_size()
                    if width <= 0 or height <= 0 or width * height * 4 > MAX_PIXELS:
                        raise ValueError("PDF page dimensions exceed limit")
                    bitmap = page.render(scale=2)
                    try: image = bitmap.to_pil().convert("RGB")
                    finally: bitmap.close()
                    try: tables.append(canonical(engine.extract(image), filename, index, provider_name))
                    finally: image.close()
                finally: page.close()
    else:
        Image.MAX_IMAGE_PIXELS = MAX_PIXELS
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as original:
                if original.width * original.height > MAX_PIXELS or original.format not in ("PNG", "JPEG"):
                    raise ValueError("Unsupported image or dimensions")
                with ImageOps.exif_transpose(original).convert("RGB") as image:
                    tables.append(canonical(engine.extract(image), filename, None, provider_name))
    notes = ["OCR 결과는 원본과 대조하여 검토해주세요. 빈 셀·병합 셀은 잘못 인식될 수 있습니다."]
    if provider_name == "mock": notes.insert(0, "MOCK: 실제 파일을 인식한 결과가 아닌 고정 테스트 데이터입니다.")
    return ParseResult(tables=tables, warnings=notes)

def run_worker(queue, data: bytes, filename: str, pages: list[int], provider_name: str):
    try:
        result = extract(data, filename, pages, provider_name)
        queue.put((200, result.model_dump()))
    except PermissionError:
        queue.put((503, None))
    except (ValueError, OSError):
        queue.put((422, None))
    except Exception:
        # File contents, credentials and OCR provider error bodies never enter logs/responses.
        queue.put((503, None))
