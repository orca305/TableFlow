"""Create synthetic, non-sensitive OCR fixtures (never derived from user documents)."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

def create_samples():
    output = Path(__file__).resolve().parents[1] / "frontend/public/samples"
    output.mkdir(parents=True, exist_ok=True)
    fonts = [Path("C:/Windows/Fonts/malgun.ttf"), Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")]
    font_path = next((path for path in fonts if path.exists()), None)
    font = ImageFont.truetype(str(font_path), 32) if font_path else ImageFont.load_default(size=32)
    for name, records in [
        ("table-en", [["Name", "Amount", "Date"], ["Alice", "15000", "2026-11-18"], ["Bob", "24000", "2026-11-19"]]),
        ("table-ko", [["이름", "금액", "일자"], ["김하늘", "15000", "2026-11-18"], ["이도윤", "24000", "2026-11-19"]]),
    ]:
        image = Image.new("RGB", (1120, 340), "white"); draw = ImageDraw.Draw(image)
        for row, values in enumerate(records):
            for column, text in enumerate(values): draw.text(([60, 400, 730][column], 40 + row * 90), text, fill="black", font=font)
        image.save(output / f"{name}.png")
        if name == "table-en":
            image.save(output / "table-scan.pdf", format="PDF", resolution=144)
            image.save(output / "table-en.jpg", quality=95)
        image.close()
    # Minimal text PDF fixture with two aligned tables, no external PDF generator dependency.
    streams = []
    for person, amount in [("Alice", "15000"), ("Bob", "24000")]:
        text = "BT /F1 16 Tf "
        for y, values in [(700, ["Name", "Amount", "Date"]), (650, [person, amount, "2026-11-18"])]:
            for x, value in zip([50, 220, 390], values): text += f"1 0 0 1 {x} {y} Tm ({value}) Tj "
        streams.append((text + "ET").encode("ascii"))
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [4 0 R 6 0 R] /Count 2 >>", b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    for i, stream in enumerate(streams):
        objects.extend([f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents {5+i*2} 0 R >>".encode(), b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream"])
    data = bytearray(b"%PDF-1.4\n"); offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(data)); data.extend(f"{i} 0 obj\n".encode() + obj + b"\nendobj\n")
    start = len(data); data.extend(f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode())
    for offset in offsets[1:]: data.extend(f"{offset:010d} 00000 n \n".encode())
    data.extend(f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n".encode())
    (output / "table-text.pdf").write_bytes(data)

if __name__ == "__main__": create_samples()
