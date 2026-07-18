package com.kafka.producer.chaos;

import java.util.List;

public record VerificationReport(
        int ledgerEntries,
        int observedPublishIds,
        long observedRecords,
        int missingCommitted,
        int visibleFailed,
        int duplicatePublishIds,
        int partialTransactions,
        int orderingViolations,
        List<String> issues,
        List<VerificationRow> rows) {

    public VerificationReport {
        issues = List.copyOf(issues);
        rows = List.copyOf(rows);
    }

    public boolean passed() {
        return missingCommitted == 0
                && visibleFailed == 0
                && duplicatePublishIds == 0
                && partialTransactions == 0
                && orderingViolations == 0;
    }

    public record VerificationRow(
            String publishId,
            String ledgerOutcome,
            int expectedRecords,
            int observedRecords,
            int callbackAttempts,
            String errorClass) {}
}
