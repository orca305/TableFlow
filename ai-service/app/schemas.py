from typing import Annotated, Literal
from uuid import uuid4
from pydantic import BaseModel, ConfigDict, Field, model_validator

Text = Annotated[str, Field(max_length=32767)]
Confidence = Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)]

class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

class Cell(Contract):
    raw: Text | None
    value: Text | None
    confidence: Confidence | None = None
    edited: bool = False

class Column(Contract):
    id: Annotated[str, Field(min_length=1, max_length=100)]
    name: Annotated[str, Field(min_length=1, max_length=200)]
    target_name: Annotated[str, Field(min_length=1, max_length=64)]
    data_type: Literal["string", "integer", "decimal", "date", "datetime", "bool"] = "string"
    nullable: bool = True
    included: bool = True
    max_length: Annotated[int, Field(ge=1, le=32767)] | None = None
    precision: Annotated[int, Field(ge=1, le=38)] | None = None
    scale: Annotated[int, Field(ge=0, le=38)] | None = None

class Source(Contract):
    filename: Annotated[str, Field(min_length=1, max_length=255)]
    type: Literal["image", "pdf"]
    extraction_method: Literal["mock_ocr", "local_ocr", "external_ocr"]
    page: Annotated[int, Field(ge=1, le=20)] | None = None
    provider: Literal["mock", "local", "external"]

class Row(Contract):
    id: Annotated[str, Field(min_length=1, max_length=100)]
    cells: Annotated[dict[str, Cell], Field(max_length=100)]

class Table(Contract):
    schema_version: Literal["1.0"] = "1.0"
    table_id: str = Field(default_factory=lambda: str(uuid4()))
    name: Annotated[str, Field(min_length=1, max_length=200)]
    source: Source
    columns: Annotated[list[Column], Field(min_length=2, max_length=100)]
    rows: Annotated[list[Row], Field(min_length=1, max_length=10000)]

    @model_validator(mode="after")
    def consistent_ids(self):
        ids = {column.id for column in self.columns}
        if len(ids) != len(self.columns) or len({row.id for row in self.rows}) != len(self.rows):
            raise ValueError("Duplicate IDs")
        if any(set(row.cells) != ids for row in self.rows):
            raise ValueError("Cell IDs do not match columns")
        return self

class ParseResult(Contract):
    tables: Annotated[list[Table], Field(min_length=1, max_length=20)]
    warnings: list[str] = Field(default_factory=list)
    sheets: list[str] = Field(default_factory=list)

class RecognizedCell(Contract):
    text: Text
    confidence: Confidence | None = None

class RecognizedGrid(Contract):
    rows: Annotated[list[Annotated[list[RecognizedCell], Field(min_length=1, max_length=100)]], Field(min_length=2, max_length=10001)]

def canonical(grid: RecognizedGrid, filename: str, page: int | None, provider: str) -> Table:
    header = grid.rows[0]
    width = len(header)
    if width < 2 or any(len(row) != width for row in grid.rows):
        raise ValueError("A rectangular table with at least two columns is required")
    columns = [Column(id=f"col_{index+1}", name=cell.text.strip() or f"Column {index+1}", target_name=f"COL_{index+1}") for index, cell in enumerate(header)]
    rows = [Row(id=f"row_{index+1}", cells={column.id: Cell(raw=cell.text, value=cell.text, confidence=cell.confidence) for column, cell in zip(columns, values)}) for index, values in enumerate(grid.rows[1:])]
    return Table(name=f"페이지 {page}" if page else "이미지 표", source=Source(filename=filename, type="pdf" if page else "image", extraction_method=f"{provider}_ocr", page=page, provider=provider), columns=columns, rows=rows)
