package dev.tableflow.parser;

public class FileParseException extends IllegalArgumentException {
    public final String code;
    public final String hint;
    public final Integer row;
    public FileParseException(String code, String message, String hint, Integer row) {
        super(message); this.code = code; this.hint = hint; this.row = row;
    }
}
