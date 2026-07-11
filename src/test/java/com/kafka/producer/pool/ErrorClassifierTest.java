package com.kafka.producer.pool;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidProducerEpochException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ErrorClassifier}.
 */
class ErrorClassifierTest {

    // --- FATAL errors ---

    @Test
    void classifiesProducerFencedExceptionAsFatal() {
        assertEquals(ErrorClass.FATAL,
                ErrorClassifier.classify(new ProducerFencedException("fenced")));
    }

    @Test
    void classifiesInvalidProducerEpochAsFatal() {
        assertEquals(ErrorClass.FATAL,
                ErrorClassifier.classify(new InvalidProducerEpochException("epoch")));
    }

    @Test
    void classifiesOutOfOrderSequenceAsFatal() {
        assertEquals(ErrorClass.FATAL,
                ErrorClassifier.classify(new OutOfOrderSequenceException("oos")));
    }

    @Test
    void classifiesAuthorizationExceptionAsFatal() {
        assertEquals(ErrorClass.FATAL,
                ErrorClassifier.classify(new AuthorizationException("auth")));
    }

    @Test
    void classifiesTimeoutDuringCommitAsFatal() {
        // Commit timeout → ambiguous outcome → quarantine producer (Scenario C)
        assertEquals(ErrorClass.FATAL,
                ErrorClassifier.classify(new TimeoutException("timeout"), true));
    }

    // --- ABORT_REQUIRED errors ---

    @Test
    void classifiesCommitFailedExceptionAsAbortRequired() {
        assertEquals(ErrorClass.ABORT_REQUIRED,
                ErrorClassifier.classify(new CommitFailedException("commit failed")));
    }

    @Test
    void classifiesGenericRuntimeExceptionAsAbortRequired() {
        assertEquals(ErrorClass.ABORT_REQUIRED,
                ErrorClassifier.classify(new RuntimeException("generic")));
    }

    // --- RETRIABLE errors ---

    @Test
    void classifiesNetworkExceptionAsRetriable() {
        // NetworkException extends RetriableException
        assertEquals(ErrorClass.RETRIABLE,
                ErrorClassifier.classify(new NetworkException("network")));
    }

    @Test
    void classifiesTimeoutNotDuringCommitAsRetriable() {
        // TimeoutException extends RetriableException — retriable unless during commit
        assertEquals(ErrorClass.RETRIABLE,
                ErrorClassifier.classify(new TimeoutException("timeout"), false));
    }

    @Test
    void classifiesInterruptedExceptionAsRetriable() {
        assertEquals(ErrorClass.RETRIABLE,
                ErrorClassifier.classify(new InterruptedException("interrupted")));
    }
}
