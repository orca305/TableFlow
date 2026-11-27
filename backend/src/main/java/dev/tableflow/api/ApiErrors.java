package dev.tableflow.api;

import dev.tableflow.export.TableExporter.InvalidTable;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import java.io.IOException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(dev.tableflow.connection.JdbcExplorer.Failure.class) ResponseEntity<?> jdbc(dev.tableflow.connection.JdbcExplorer.Failure e) {
        return error(e.status, e.code, e.getMessage());
    }
    @ExceptionHandler(dev.tableflow.load.DatabaseLoader.LoadFailure.class) ResponseEntity<?> load(dev.tableflow.load.DatabaseLoader.LoadFailure e) {
        return error(e.status, e.code, e.getMessage());
    }
    @ExceptionHandler(dev.tableflow.ocr.OcrClient.OcrFailure.class) ResponseEntity<?> ocr(dev.tableflow.ocr.OcrClient.OcrFailure e) {
        return error(e.status, e.code, e.getMessage());
    }
    @ExceptionHandler(InvalidTable.class) ResponseEntity<?> invalid(InvalidTable e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("code", "VALIDATION_FAILED", "message", e.getMessage(), "issues", e.result.issues()));
    }
    @ExceptionHandler(dev.tableflow.parser.FileParseException.class) ResponseEntity<?> parse(dev.tableflow.parser.FileParseException e) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("code", e.code); body.put("message", e.getMessage()); body.put("hint", e.hint); body.put("row", e.row);
        return ResponseEntity.badRequest().body(body);
    }
    /** 상태 DB에 연결할 수 없거나 응답이 없을 때. 접속 정보는 돌려주지 않는다. */
    @ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class, org.springframework.dao.TransientDataAccessException.class})
    ResponseEntity<?> stateDb(Exception e) {
        return error(503, "STATE_DB_UNAVAILABLE", "상태 DB를 사용할 수 없습니다. PostgreSQL 실행 상태를 확인한 뒤 다시 시도해주세요.");
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> bad(IllegalArgumentException e) {
        return error(400, "INVALID_INPUT", e.getMessage());
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed(Exception e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause())
            if (cause instanceof JsonLimits.PayloadTooLarge) return error(413, "REQUEST_TOO_LARGE", "요청 크기는 최대 32MB입니다.");
        return error(400, "INVALID_REQUEST", "요청 데이터의 필수 항목과 자료형을 확인해주세요.");
    }
    @ExceptionHandler(JsonLimits.PayloadTooLarge.class) ResponseEntity<?> payload(Exception e) {
        return error(413, "REQUEST_TOO_LARGE", "요청 크기는 최대 32MB입니다.");
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<?> large(Exception e) {
        return error(413, "FILE_TOO_LARGE", "파일 최대 크기는 10MB입니다.");
    }
    @ExceptionHandler(IOException.class) ResponseEntity<?> file(IOException e) {
        return error(400, "FILE_READ_ERROR", "파일을 읽을 수 없습니다. 손상 여부와 파일 형식을 확인해주세요.");
    }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) {
        return error(500, "INTERNAL_ERROR", "처리 중 오류가 발생했습니다. 파일 형식과 설정을 확인해주세요.");
    }
    private ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
