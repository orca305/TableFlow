import base64
import io
import os
from typing import Protocol
import httpx
from PIL import Image
from .schemas import RecognizedCell, RecognizedGrid

class OCRProvider(Protocol):
    def extract(self, image: Image.Image) -> RecognizedGrid: ...

class MockProvider:
    def extract(self, image: Image.Image) -> RecognizedGrid:
        return RecognizedGrid(rows=[[RecognizedCell(text=text, confidence=0.0) for text in row] for row in [["Name", "Amount"], ["MOCK DATA", "100"]]])

class LocalProvider:
    def __init__(self):
        from rapidocr import RapidOCR, LangRec, OCRVersion, ModelType
        params = {"Global.log_level": "error", "Global.use_cls": False, "Global.text_score": 0.0, "EngineConfig.onnxruntime.intra_op_num_threads": 2,
                  "EngineConfig.onnxruntime.inter_op_num_threads": 2}
        language = os.getenv("TABLEFLOW_OCR_LANGUAGE", "korean")
        if language == "korean":
            params.update({"Rec.lang_type": LangRec.KOREAN, "Rec.ocr_version": OCRVersion.PPOCRV5, "Rec.model_type": ModelType.MOBILE})
        elif language != "multilingual":
            raise ValueError("TABLEFLOW_OCR_LANGUAGE must be korean or multilingual")
        self.engine = RapidOCR(params=params)

    def extract(self, image: Image.Image) -> RecognizedGrid:
        import numpy as np
        output = self.engine(np.array(image.convert("RGB")))
        if output.boxes is None or output.txts is None or len(output.txts) > 20000:
            raise ValueError("No readable table found")
        tokens = []
        for box, text, score in zip(output.boxes, output.txts, output.scores):
            xs, ys = box[:, 0], box[:, 1]
            tokens.append((float(xs.min()), float(ys.mean()), float(ys.max()-ys.min()), str(text), float(score)))
        return arrange(tokens)

# ponytail: a single aligned table with a header row; use a table-layout model for merged or rotated tables.
def arrange(tokens: list[tuple[float, float, float, str, float]]) -> RecognizedGrid:
    lines: list[list[tuple]] = []
    for token in sorted(tokens, key=lambda t: (t[1], t[0])):
        if not lines or abs(token[1] - lines[-1][0][1]) > max(5, min(token[2], lines[-1][0][2]) * 0.6):
            lines.append([])
        lines[-1].append(token)
    if len(lines) < 2:
        raise ValueError("A header row and data rows are required")
    for line in lines:
        line.sort(key=lambda t: t[0])
    anchors = [token[0] for token in lines[0]]
    if not 2 <= len(anchors) <= 100:
        raise ValueError("At least two header columns are required")
    rows = []
    for line in lines:
        groups: list[list[tuple]] = [[] for _ in anchors]
        for token in line:
            column = min(range(len(anchors)), key=lambda i: abs(anchors[i] - token[0]))
            groups[column].append(token)
        rows.append([RecognizedCell(text=" ".join(token[3] for token in group), confidence=min((token[4] for token in group), default=0.0)) for group in groups])
    return RecognizedGrid(rows=rows)

class ExternalProvider:
    """Explicitly configured table OCR endpoint; never a fallback for local failures."""
    def __init__(self):
        if os.getenv("TABLEFLOW_OCR_EXTERNAL_UPLOAD_ENABLED", "false").lower() != "true":
            raise PermissionError("External upload is disabled")
        self.url = os.getenv("TABLEFLOW_OCR_EXTERNAL_URL", "")
        if not self.url.startswith("https://"):
            raise ValueError("An HTTPS OCR endpoint is required")

    def extract(self, image: Image.Image) -> RecognizedGrid:
        buffer = io.BytesIO(); image.save(buffer, format="PNG")
        headers = {}
        if token := os.getenv("TABLEFLOW_OCR_EXTERNAL_API_KEY"):
            headers["Authorization"] = f"Bearer {token}"
        with httpx.Client(timeout=30, follow_redirects=False, trust_env=False) as client:
            with client.stream("POST", self.url, headers=headers, json={"image_base64": base64.b64encode(buffer.getvalue()).decode("ascii"), "mime_type": "image/png"}) as response:
                response.raise_for_status()
                data = bytearray()
                for chunk in response.iter_bytes():
                    data.extend(chunk)
                    if len(data) > 16 * 1024 * 1024:
                        raise ValueError("External response too large")
        return RecognizedGrid.model_validate_json(data)

def provider(name: str) -> OCRProvider:
    if name == "mock": return MockProvider()
    if name == "local": return LocalProvider()
    if name == "external": return ExternalProvider()
    raise ValueError("Unknown OCR provider")
