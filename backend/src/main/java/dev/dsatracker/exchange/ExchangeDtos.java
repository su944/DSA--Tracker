package dev.dsatracker.exchange;

import java.util.List;
import java.util.Map;

public final class ExchangeDtos {
    private ExchangeDtos() {}

    public enum Format { CSV, JSON }
    public enum Mode { SKIP_EXISTING, UPDATE_EXISTING }
    public enum Action { CREATE, UPDATE, SKIP, INVALID }

    public record RowPreview(int row, Long leetcodeId, String title, Action action, Map<String, String> errors) {}
    public record Preview(int total, long creates, long updates, long skipped, long invalid, List<RowPreview> rows) {}
    public record ImportResult(int total, int created, int updated, int skipped, int failed, List<RowPreview> errors) {}
}
