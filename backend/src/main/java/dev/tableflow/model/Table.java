package dev.tableflow.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Table(
    @NotBlank @Pattern(regexp="1\\.0") String schemaVersion,
    @NotBlank @Size(max=100) String tableId,
    @NotBlank @Size(max=200) String name,
    @NotNull @Valid Source source,
    @NotEmpty @Size(max=100) List<@NotNull @Valid Column> columns,
    @NotNull @Size(max=10000) List<@NotNull @Valid Row> rows
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Source(@NotBlank @Size(max=255) String filename, @NotBlank String type,
                         @NotBlank String extractionMethod, @Min(1) @Max(20) Integer page,
                         @Size(max=30) String provider) {
        public Source(String filename, String type, String extractionMethod) { this(filename, type, extractionMethod, null, null); }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Column(@NotBlank @Size(max=100) String id, @NotBlank @Size(max=200) String name,
                         @NotNull @Size(max=128) String targetName, @NotNull DataType dataType,
                         boolean nullable, boolean included,
                         @Min(1) @Max(32767) Integer maxLength,
                         @Min(1) @Max(38) Integer precision, @Min(0) @Max(38) Integer scale) {}
    public enum DataType { string, integer, decimal, date, datetime, bool }
    public record Row(@NotBlank @Size(max=100) String id,
                      @NotNull @Size(max=100) Map<@NotBlank String, @NotNull @Valid Cell> cells) {}
    public record Cell(@Size(max=32767) String raw, @Size(max=32767) String value,
                       @DecimalMin("0") @DecimalMax("1") Double confidence, boolean edited) {}
    public record ParseResult(List<Table> tables, List<String> warnings, List<String> sheets) {}
    public record Issue(String rowId, String columnId, String code, String message) {}
    public record ValidationResult(boolean valid, List<Issue> issues) {}
}
