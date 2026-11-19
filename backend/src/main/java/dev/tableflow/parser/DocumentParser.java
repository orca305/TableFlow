package dev.tableflow.parser;

import dev.tableflow.model.Table;
import dev.tableflow.model.Table.*;
import dev.tableflow.ocr.OcrClient;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.*;

@Service
public class DocumentParser {
    private final FileParser parser;
    private final OcrClient ocr;
    public DocumentParser(FileParser parser, OcrClient ocr) { this.parser = parser; this.ocr = ocr; }
    public ParseResult parse(MultipartFile file, String sheet, String encoding, String delimiter, int page) throws IOException {
        return parse(file, sheet, encoding, delimiter, page, false);
    }
    public ParseResult parse(MultipartFile file, String sheet, String encoding, String delimiter, int page, boolean externalConsent) throws IOException {
        return parse(file, sheet, encoding, delimiter, page, externalConsent, 1);
    }
    public ParseResult parse(MultipartFile file, String sheet, String encoding, String delimiter, int page, boolean externalConsent, int headerRow) throws IOException {
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("upload").replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024 || filename.length() > 255)
            throw new IllegalArgumentException("파일 크기(최대 10MB)와 파일명을 확인해주세요.");
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv") || name.endsWith(".xlsx")) return parser.parse(file, sheet, encoding, delimiter, headerRow);
        byte[] bytes = file.getBytes();
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            boolean png = bytes.length >= 8 && java.util.HexFormat.of().formatHex(bytes, 0, 8).equals("89504e470d0a1a0a");
            boolean jpeg = bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
            if (!(name.endsWith(".png") ? png : jpeg)) throw new IllegalArgumentException("이미지 파일 시그니처가 올바르지 않습니다.");
            return ocr.extract(bytes, filename, "", externalConsent);
        }
        if (!name.endsWith(".pdf")) throw new IllegalArgumentException("CSV, XLSX, PDF, PNG, JPEG 파일을 지원합니다.");
        if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
            throw new IllegalArgumentException("PDF 파일 시그니처가 올바르지 않습니다.");
        try (var document = Loader.loadPDF(bytes)) {
            int count = document.getNumberOfPages();
            if (document.isEncrypted()) throw new IllegalArgumentException("암호화된 PDF는 지원하지 않습니다.");
            if (count < 1 || count > 20 || page < 0 || page > count) throw new IllegalArgumentException("PDF는 최대 20페이지이며 페이지 범위가 유효해야 합니다.");
            List<Table> tables = new ArrayList<>(); List<String> scans = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            for (int p = page == 0 ? 1 : page; p <= (page == 0 ? count : page); p++) {
                var stripper = new PositionedStripper(); stripper.setStartPage(p); stripper.setEndPage(p); stripper.getText(document);
                List<List<String>> records = stripper.records();
                if (records.size() < 2 || records.getFirst().size() < 2) { scans.add(Integer.toString(p)); continue; }
                Table parsed = parser.table(records, filename, "pdf", "페이지 " + p);
                tables.add(new Table("1.0", parsed.tableId(), parsed.name(), new Source(filename, "pdf", "pdf_text", p, null), parsed.columns(), parsed.rows()));
            }
            if (!scans.isEmpty()) {
                ParseResult result = ocr.extract(bytes, filename, String.join(",", scans), externalConsent);
                tables.addAll(result.tables()); warnings.addAll(result.warnings());
            }
            tables.sort(Comparator.comparing(table -> Optional.ofNullable(table.source().page()).orElse(1)));
            warnings.add("PDF는 페이지별 첫 행을 헤더로 처리합니다. 제목·병합 셀·복잡한 표는 검토 후 수정해주세요.");
            return new ParseResult(tables, warnings, List.of());
        }
    }

    // ponytail: aligned single tables per page; use a dedicated layout model for merged/multi-table documents.
    static class PositionedStripper extends PDFTextStripper {
        final List<TextPosition> glyphs = new ArrayList<>();
        PositionedStripper() throws IOException { super(); }
        @Override protected void processTextPosition(TextPosition text) {
            if (glyphs.size() >= 1_000_000) throw new IllegalArgumentException("PDF 텍스트가 너무 많습니다.");
            if (!text.getUnicode().isBlank()) glyphs.add(text);
        }
        List<List<String>> records() {
            glyphs.sort(Comparator.comparing(TextPosition::getYDirAdj).thenComparing(TextPosition::getXDirAdj));
            List<List<TextPosition>> lines = new ArrayList<>();
            for (TextPosition glyph : glyphs) {
                if (lines.isEmpty() || Math.abs(lines.getLast().getFirst().getYDirAdj() - glyph.getYDirAdj()) > 3) lines.add(new ArrayList<>());
                lines.getLast().add(glyph);
            }
            if (lines.isEmpty()) return List.of();
            lines.forEach(line -> line.sort(Comparator.comparing(TextPosition::getXDirAdj)));
            List<Float> anchors = new ArrayList<>();
            float end = -100;
            for (TextPosition glyph : lines.getFirst()) {
                if (glyph.getXDirAdj() - end > 12) anchors.add(glyph.getXDirAdj());
                end = glyph.getXDirAdj() + glyph.getWidthDirAdj();
            }
            if (anchors.size() > 100 || lines.size() > 10001) throw new IllegalArgumentException("최대 10,000행, 100열까지 지원합니다.");
            List<List<String>> records = new ArrayList<>();
            for (var line : lines) {
                List<StringBuilder> cells = anchors.stream().map(x -> new StringBuilder()).toList();
                float previousEnd = -100; int previousColumn = -1;
                for (TextPosition glyph : line) {
                    int column = 0;
                    while (column + 1 < anchors.size() && glyph.getXDirAdj() >= anchors.get(column + 1) - 3) column++;
                    if (column == previousColumn && glyph.getXDirAdj() - previousEnd > 1.5) cells.get(column).append(' ');
                    cells.get(column).append(glyph.getUnicode());
                    previousEnd = glyph.getXDirAdj() + glyph.getWidthDirAdj(); previousColumn = column;
                }
                records.add(cells.stream().map(StringBuilder::toString).toList());
            }
            return records;
        }
    }
}
