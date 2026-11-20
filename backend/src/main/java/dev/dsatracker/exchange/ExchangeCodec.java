package dev.dsatracker.exchange;

import dev.dsatracker.api.ApiException;
import dev.dsatracker.catalog.CatalogDtos.ProblemView;
import dev.dsatracker.exchange.ExchangeDtos.Format;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class ExchangeCodec {
    private static final int MAX_ROWS = 10_000;
    private static final List<String> REQUIRED = List.of("leetcodeId", "title", "url", "difficulty", "topics");
    private static final String[] COLUMNS = {
            "leetcodeId", "title", "url", "difficulty", "topics", "status", "attemptedDate", "solvedDate", "comments"
    };
    private static final CSVFormat CSV_INPUT = CSVFormat.RFC4180.builder().setHeader()
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).setSkipHeaderRecord(true).setIgnoreEmptyLines(true).get();
    private final ObjectMapper json;

    ExchangeCodec(ObjectMapper json) {
        this.json = json;
    }

    record Raw(int line, Map<String, String> fields, List<String> topics, Map<String, String> errors) {}
    record Export(int version, List<ExportRow> problems) {}
    record ExportRow(long leetcodeId, String title, String url, String difficulty, List<String> topics,
                     String status, String attemptedDate, String solvedDate, String comments) {
        static ExportRow from(ProblemView problem) {
            var progress = problem.progress();
            return new ExportRow(problem.leetcodeId(), problem.title(), problem.url(), problem.difficulty().name(),
                    problem.topics().stream().map(topic -> topic.name()).toList(), progress.status().name(),
                    progress.attemptedDate() == null ? null : progress.attemptedDate().toString(),
                    progress.solvedDate() == null ? null : progress.solvedDate().toString(), progress.comments());
        }
    }

    List<Raw> read(byte[] bytes, Format format) {
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw ApiException.invalid("file", "Use a UTF-8 CSV or JSON file.");
        }
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        try {
            return switch (format) {
                case CSV -> readCsv(content);
                case JSON -> readJson(content);
            };
        } catch (IOException | IllegalArgumentException | JacksonException failure) {
            throw ApiException.invalid("file", "The " + format + " file is malformed. Check its syntax and headers.");
        }
    }

    byte[] write(List<ProblemView> problems, Format format) {
        var records = problems.stream().map(ExportRow::from).toList();
        if (format == Format.JSON) return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(new Export(1, records));
        StringBuilder text = new StringBuilder();
        try (var csv = new CSVPrinter(text, CSVFormat.RFC4180.builder().setHeader(COLUMNS).get())) {
            for (var row : records) {
                String topics = String.join(";", row.topics().stream()
                        .map(topic -> topic.replace("\\", "\\\\").replace(";", "\\;")).toList());
                csv.printRecord(row.leetcodeId(), row.title(), row.url(), row.difficulty(), topics, row.status(),
                        row.attemptedDate(), row.solvedDate(), row.comments());
            }
            return text.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not encode CSV", failure);
        }
    }

    private List<Raw> readCsv(String content) throws IOException {
        try (var csv = CSV_INPUT.parse(new StringReader(content))) {
            if (!csv.getHeaderNames().containsAll(REQUIRED)) {
                throw ApiException.invalid("file", "Required CSV columns: " + String.join(", ", REQUIRED) + ".");
            }
            List<Raw> rows = new ArrayList<>();
            for (var row : csv) {
                checkSize(rows);
                int line = Math.toIntExact(row.getRecordNumber() + 1);
                if (!row.isConsistent()) {
                    rows.add(new Raw(line, Map.of(), List.of(), Map.of("row", "Column count does not match the header.")));
                    continue;
                }
                rows.add(new Raw(line, row.toMap(), splitTopics(row.get("topics")), Map.of()));
            }
            return rows;
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private List<Raw> readJson(String content) {
        JsonNode root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content);
        JsonNode rows = root == null ? null : root.get("problems");
        if (rows == null || !rows.isArray() || !root.path("version").isIntegralNumber() || root.path("version").asInt() != 1) {
            throw ApiException.invalid("file", "Use a JSON object with version 1 and a problems array.");
        }
        List<Raw> result = new ArrayList<>();
        for (JsonNode row : rows) {
            checkSize(result);
            result.add(jsonRow(result.size() + 1, row));
        }
        return result;
    }

    private static Raw jsonRow(int line, JsonNode row) {
        if (!row.isObject()) return new Raw(line, Map.of(), List.of(), Map.of("row", "Expected a problem object."));
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        List<String> topics = new ArrayList<>();
        for (String column : COLUMNS) {
            var value = row.get(column);
            if (value == null || value.isNull()) continue;
            if ("topics".equals(column)) {
                if (value.isArray() && value.valueStream().allMatch(JsonNode::isString)) {
                    value.forEach(topic -> topics.add(topic.asString()));
                } else {
                    errors.put(column, "Use an array of topic names.");
                }
            } else if ("leetcodeId".equals(column) && value.isIntegralNumber()) {
                values.put(column, value.toString());
            } else if (value.isString() && !"leetcodeId".equals(column)) {
                values.put(column, value.asString());
            } else {
                errors.put(column, "Invalid value type.");
            }
        }
        return new Raw(line, values, topics, errors);
    }

    private static void checkSize(List<?> rows) {
        if (rows.size() >= MAX_ROWS) throw ApiException.invalid("file", "Import at most 10,000 problems per file.");
    }

    private static List<String> splitTopics(String value) {
        List<String> names = new ArrayList<>();
        StringBuilder name = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' && index + 1 < value.length()
                    && (value.charAt(index + 1) == ';' || value.charAt(index + 1) == '\\')) {
                name.append(value.charAt(++index));
            } else if (character == ';') {
                names.add(name.toString());
                name.setLength(0);
            } else {
                name.append(character);
            }
        }
        names.add(name.toString());
        return names;
    }
}
