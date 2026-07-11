package com.kafka.producer.pool;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidProducerEpochException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnsupportedVersionException;

/**
 * Classifies exceptions thrown by KafkaProducer into {@link ErrorClass} categories.
 *
 * <p>Classification rules (highest priority first):
 * <ol>
 *   <li>FATAL: ProducerFencedException, InvalidProducerEpochException,
 *       OutOfOrderSequenceException, AuthorizationException, UnsupportedVersionException —
 *       the producer instance is permanently broken and must be evicted.</li>
 *   <li>ABORT_REQUIRED: CommitFailedException — the transaction cannot commit;
 *       abort and return the producer to the pool.</li>
 *   <li>RETRIABLE: any Kafka RetriableException —
 *       the operation may succeed on retry after aborting the current transaction.</li>
 *   <li>ABORT_REQUIRED (default): any other exception — abort the transaction but
 *       do not evict the producer.</li>
 * </ol>
 *
 * <p>Note on commit-timeout ambiguity: a {@link TimeoutException} raised during
 * {@code commitTransaction()} is treated as FATAL because the commit outcome is
 * unknown; the producer is quarantined per the spec (Scenario C).
 */
public final class ErrorClassifier {

    private ErrorClassifier() {}

    /**
     * Classify {@code e} into the appropriate {@link ErrorClass}.
     *
     * @param e         the exception to classify
     * @param duringCommit {@code true} if the exception was raised during
     *                  {@code commitTransaction()} or {@code flush()} immediately
     *                  before commit; triggers FATAL treatment for ambiguous outcomes.
     * @return the error class
     */
    public static ErrorClass classify(Exception e, boolean duringCommit) {
        // --- FATAL errors: producer is permanently invalidated ---
        if (e instanceof ProducerFencedException
                || e instanceof InvalidProducerEpochException
                || e instanceof OutOfOrderSequenceException
                || e instanceof AuthorizationException
                || e instanceof UnsupportedVersionException) {
            return ErrorClass.FATAL;
        }

        // Commit timeout / unknown outcome → quarantine and evict (Scenario C)
        if (duringCommit && e instanceof TimeoutException) {
            return ErrorClass.FATAL;
        }

        // --- ABORT_REQUIRED: transaction must be aborted, producer reusable ---
        if (e instanceof CommitFailedException) {
            return ErrorClass.ABORT_REQUIRED;
        }

        // --- RETRIABLE: safe to retry after aborting the transaction ---
        if (e instanceof RetriableException) {
            return ErrorClass.RETRIABLE;
        }
        if (e instanceof InterruptedException) {
            return ErrorClass.RETRIABLE;
        }

        // Default: abort required, producer is still usable
        return ErrorClass.ABORT_REQUIRED;
    }

    /**
     * Convenience overload for non-commit contexts.
     *
     * @param e the exception to classify
     * @return the error class
     */
    public static ErrorClass classify(Exception e) {
        return classify(e, false);
    }
}
