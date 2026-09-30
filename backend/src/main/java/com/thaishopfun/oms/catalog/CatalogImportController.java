package com.thaishopfun.oms.catalog;

import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class CatalogImportController {

  private final CatalogImportService imports;

  CatalogImportController(CatalogImportService imports) {
    this.imports = imports;
  }

  /** Multipart field {@code file}: UTF-8 CSV with a header row. */
  @PostMapping(path = "/api/v1/catalog/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<ImportResult> importCsv(@RequestParam("file") MultipartFile file)
      throws IOException {
    return ProductController.ok(imports.importCsv(file.getBytes()));
  }
}
