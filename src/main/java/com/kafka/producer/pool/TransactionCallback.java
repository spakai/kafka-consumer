package com.kafka.producer.pool;

/**
 * Callback executed within a managed transaction scope.
 *
 * <p>The callback receives a {@link ProducerLease} that provides the send API.
 * The pool handles begin, flush, commit, abort, and release automatically around
 * the callback invocation.
 *
 * <p>Implementations <strong>must be retriable</strong>: if the pool retries the
 * transaction due to a retriable error, the callback will be invoked again from
 * the beginning with a fresh transaction scope.
 *
 * @param <T> the type of value returned by the callback
 */
@FunctionalInterface
public interface TransactionCallback<T> {

    /**
     * Execute business logic that sends records via {@code lease}.
     *
     * @param lease the active producer lease; use it to send records
     * @return application-defined result (may be {@code null})
     * @throws Exception any exception aborts the transaction; the pool classifies
     *                   it and decides whether to retry or propagate
     */
    T execute(ProducerLease lease) throws Exception;
}
