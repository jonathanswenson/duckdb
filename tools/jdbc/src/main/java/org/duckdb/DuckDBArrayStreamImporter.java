package org.duckdb;

public interface DuckDBArrayStreamImporter {
  // should return an ArrowReader?
  Object importArrayStream(long arrowArrayStreamPointer);
}
