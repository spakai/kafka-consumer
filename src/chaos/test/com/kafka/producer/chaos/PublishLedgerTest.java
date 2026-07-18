package com.kafka.producer.chaos;

import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublishLedgerTest {

    @Test
    void tracksAttemptsOutcomeAndRootError() {
        PublishLedger ledger = new PublishLedger();
        PublishLedger.Entry entry = ledger.attempted("publish-1", "key-1", 4, 10);
        entry.recordCallbackAttempt();
        entry.recordCallbackAttempt();
        entry.complete(PublishLedger.Outcome.AMBIGUOUS,
                new RuntimeException(new TimeoutException("unknown commit")));

        assertEquals(2, entry.callbackAttempts());
        assertEquals(PublishLedger.Outcome.AMBIGUOUS, entry.outcome());
        assertEquals(TimeoutException.class.getName(), entry.errorClass());
        assertEquals(1, ledger.entries().size());
    }

    @Test
    void rejectsDuplicatePublishIds() {
        PublishLedger ledger = new PublishLedger();
        ledger.attempted("same", "key", 1, 1);

        assertThrows(IllegalStateException.class,
                () -> ledger.attempted("same", "key", 2, 1));
    }
}
