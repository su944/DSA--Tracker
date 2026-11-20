package dev.dsatracker.exchange;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.exchange.ExchangeDtos.Format;
import dev.dsatracker.exchange.ExchangeDtos.ImportResult;
import dev.dsatracker.exchange.ExchangeDtos.Mode;
import dev.dsatracker.exchange.ExchangeDtos.Preview;
import java.io.IOException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping({"/api/exchange", "/api/v1/exchange"})
class ExchangeController {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final ExchangeService exchange;

    ExchangeController(ExchangeService exchange) {
        this.exchange = exchange;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Preview preview(@RequestParam MultipartFile file, @RequestParam Format format,
                    @RequestParam(defaultValue = "SKIP_EXISTING") Mode mode) {
        return exchange.preview(read(file), format, mode);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ImportResult apply(@RequestParam MultipartFile file, @RequestParam Format format,
                       @RequestParam(defaultValue = "SKIP_EXISTING") Mode mode) {
        return exchange.apply(read(file), format, mode);
    }

    @GetMapping("/export")
    ResponseEntity<byte[]> export(@RequestParam(defaultValue = "JSON") Format format) {
        boolean csv = format == Format.CSV;
        String filename = csv ? "dsa-tracker-catalog.csv" : "dsa-tracker-catalog.json";
        return ResponseEntity.ok().contentType(csv ? MediaType.parseMediaType("text/csv;charset=UTF-8") : MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(exchange.export(format));
    }

    private static byte[] read(MultipartFile file) {
        if (file.isEmpty()) throw ApiException.invalid("file", "Choose a non-empty CSV or JSON file.");
        if (file.getSize() > MAX_BYTES) throw ApiException.invalid("file", "The file must be 10 MB or less.");
        try {
            return file.getBytes();
        } catch (IOException failure) {
            throw ApiException.invalid("file", "Could not read the uploaded file.");
        }
    }
}
