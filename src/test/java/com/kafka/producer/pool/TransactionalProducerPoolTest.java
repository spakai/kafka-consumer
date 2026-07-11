package com.kafka.producer.pool;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.mockito.InOrder;

/**
 * Unit tests for {@link TransactionalProducerPool}.
 *
 * <p>The Kafka broker is replaced by a Mockito mock of {@link KafkaProducer}.
 */
@ExtendWith(MockitoExtension.class)
class TransactionalProducerPoolTest {

    @Mock
    private KafkaProducer<byte[], byte[]> mockProducer;

    private KafkaProducerFactory mockFactory;
    private TransactionalProducerPool pool;

    private static PoolConfig smallConfig() {
        return PoolConfig.builder()
                .poolSize(2)
                .minHealthyProducers(1)
                .leaseTimeoutMs(500)
                .leaseHardTimeoutMs(5_000)
                .shutdownGracePeriodMs(1_000)
                .retryMaxAttempts(2)
                .retryBaseDelayMs(10)
                .retryMaxDelayMs(50)
                .recoveryMaxConcurrentRebuilds(1)
                .serviceIdentity("test-svc")
                .instanceIdentifier("inst-0")
                .kafkaProperties(new Properties())
                .build();
    }

    @BeforeEach
    void setUp() {
        mockFactory = props -> mockProducer;
        pool = new TransactionalProducerPool(smallConfig(), mockFactory, new SimpleMeterRegistry());
        // initTransactions is void — Mockito no-ops by default
        pool.initialize();
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    // -----------------------------------------------------------------------
    // Initialization
    // -----------------------------------------------------------------------

    @Test
    void poolInitializesAndBecomesHealthy() {
        assertEquals(PoolState.HEALTHY, pool.getPoolState());
        assertEquals(2, pool.getReadyCount());
        assertEquals(0, pool.getLeasedCount());
    }

    @Test
    void poolFailsFastWhenInitTransactionsAlwaysFails() {
        doThrow(new RuntimeException("broker unavailable"))
                .when(mockProducer).initTransactions();

        // Single-producer pool — minHealthy=1 cannot be satisfied
        PoolConfig failConfig = PoolConfig.builder()
                .poolSize(1)
                .minHealthyProducers(1)
                .leaseTimeoutMs(500)
                .leaseHardTimeoutMs(5_000)
                .shutdownGracePeriodMs(1_000)
                .retryMaxAttempts(0)
                .retryBaseDelayMs(10)
                .retryMaxDelayMs(50)
                .recoveryMaxConcurrentRebuilds(1)
                .serviceIdentity("fail-svc")
                .instanceIdentifier("inst-0")
                .kafkaProperties(new Properties())
                .build();

        TransactionalProducerPool failPool = new TransactionalProducerPool(
                failConfig, mockFactory, new SimpleMeterRegistry());
        assertThrows(ProducerInitializationException.class, failPool::initialize);
    }

    // -----------------------------------------------------------------------
    // Lease acquire / release
    // -----------------------------------------------------------------------

    @Test
    void acquireLeaseAndRelease() {
        ProducerLease lease = pool.acquireLease();
        assertNotNull(lease.getLeaseId());
        assertEquals(1, pool.getLeasedCount());
        assertEquals(1, pool.getReadyCount());

        pool.release(lease);
        assertEquals(0, pool.getLeasedCount());
        assertEquals(2, pool.getReadyCount());
    }

    @Test
    void acquireAllLeasesExhaustsPool() {
        ProducerLease lease1 = pool.acquireLease();
        ProducerLease lease2 = pool.acquireLease();
        assertEquals(2, pool.getLeasedCount());
        assertEquals(0, pool.getReadyCount());

        // Third acquire should time out
        assertThrows(PoolSaturationException.class, pool::acquireLease);

        pool.release(lease1);
        pool.release(lease2);
    }

    @Test
    void leaseRejectedWhenPoolDraining() {
        pool.shutdown();
        assertThrows(PoolShutdownException.class, pool::acquireLease);
    }

    @Test
    void concurrentLeasesAreExclusive() throws InterruptedException {
        int threadCount = 10;
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger current = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Pool size 2 — at most 2 simultaneous leases
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    ProducerLease lease = pool.acquireLease(2_000);
                    int c = current.incrementAndGet();
                    maxConcurrent.updateAndGet(prev -> Math.max(prev, c));
                    Thread.sleep(10);
                    current.decrementAndGet();
                    pool.release(lease);
                } catch (PoolSaturationException | InterruptedException e) {
                    // acceptable if timeout
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(maxConcurrent.get() <= 2, "Max concurrent leases should be ≤ pool size");
    }

    // -----------------------------------------------------------------------
    // Transaction success path
    // -----------------------------------------------------------------------

    @Test
    void executeInTransactionSuccessPath() {
        String result = pool.executeInTransaction(lease -> "success");
        assertEquals("success", result);

        InOrder inOrder = inOrder(mockProducer);
        inOrder.verify(mockProducer).beginTransaction();
        inOrder.verify(mockProducer).flush();
        inOrder.verify(mockProducer).commitTransaction();
    }

    @Test
    void executeInTransactionSendsRecord() {
        ProducerRecord<byte[], byte[]> record =
                new ProducerRecord<>("topic", "key".getBytes(), "value".getBytes());

        pool.executeInTransaction(lease -> {
            lease.send(record);
            return null;
        });

        verify(mockProducer).send(record);
        verify(mockProducer).commitTransaction();
        verify(mockProducer, never()).abortTransaction();
    }

    // -----------------------------------------------------------------------
    // Error handling — retriable
    // -----------------------------------------------------------------------

    @Test
    void retriableErrorIsRetriedThenSucceeds() {
        // First send call throws NetworkException (retriable), second succeeds
        doThrow(new NetworkException("transient"))
                .doNothing()
                .when(mockProducer).flush();

        String result = pool.executeInTransaction(lease -> "ok");
        assertEquals("ok", result);

        // flush called twice (once failing, once succeeding)
        verify(mockProducer, times(2)).flush();
        // abort called once for the failed attempt
        verify(mockProducer, times(1)).abortTransaction();
        // commit called once for the successful attempt
        verify(mockProducer, times(1)).commitTransaction();
    }

    @Test
    void retriableErrorExhaustsRetriesAndThrows() {
        // All flush() calls throw NetworkException
        doThrow(new NetworkException("always fails")).when(mockProducer).flush();

        assertThrows(RuntimeException.class,
                () -> pool.executeInTransaction(lease -> "x"));

        // Aborted for each failed attempt (retryMaxAttempts=2, so 3 total attempts)
        verify(mockProducer, times(3)).abortTransaction();
        verify(mockProducer, never()).commitTransaction();
    }

    // -----------------------------------------------------------------------
    // Error handling — fatal (ProducerFencedException)
    // -----------------------------------------------------------------------

    @Test
    void fatalFencingEvictsProducerAndTriggersRecovery() throws InterruptedException {
        doThrow(new ProducerFencedException("fenced")).when(mockProducer).flush();

        assertThrows(RuntimeException.class,
                () -> pool.executeInTransaction(lease -> "x"));

        // Pool size drops but stays operational as other slot is still healthy
        // Give recovery supervisor a moment
        TimeUnit.MILLISECONDS.sleep(200);
        // The pool should be non-STOPPED
        assertNotEquals(PoolState.STOPPED, pool.getPoolState());
        // Producer.close() should have been called (eviction)
        verify(mockProducer, atLeastOnce()).close(any());
    }

    // -----------------------------------------------------------------------
    // Abort path
    // -----------------------------------------------------------------------

    @Test
    void exceptionInCallbackAbortsTransaction() {
        assertThrows(RuntimeException.class, () ->
                pool.executeInTransaction(lease -> {
                    throw new RuntimeException("callback failed");
                }));

        verify(mockProducer).abortTransaction();
        verify(mockProducer, never()).commitTransaction();
    }

    // -----------------------------------------------------------------------
    // Shutdown
    // -----------------------------------------------------------------------

    @Test
    void shutdownTransitionsPoolToStopped() {
        pool.shutdown();
        assertEquals(PoolState.STOPPED, pool.getPoolState());
    }

    @Test
    void shutdownClosesAllProducers() {
        pool.shutdown();
        // 2 producers in pool
        verify(mockProducer, times(2)).close(any());
    }
}
