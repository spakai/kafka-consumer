package com.kafka.producer.pool;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.*;
import static java.util.concurrent.TimeUnit.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RecoverySupervisor}.
 */
@ExtendWith(MockitoExtension.class)
class RecoverySupervisorTest {

    @Mock
    private KafkaProducer<byte[], byte[]> mockProducer;

    private PoolConfig config() {
        return PoolConfig.builder()
                .poolSize(3)
                .minHealthyProducers(1)
                .leaseTimeoutMs(500)
                .leaseHardTimeoutMs(5_000)
                .shutdownGracePeriodMs(1_000)
                .retryMaxAttempts(3)
                .retryBaseDelayMs(10)
                .retryMaxDelayMs(50)
                .recoveryMaxConcurrentRebuilds(2)
                .serviceIdentity("svc")
                .instanceIdentifier("inst-0")
                .kafkaProperties(new Properties())
                .build();
    }

    @Test
    void recoveryCreatesReplacementAndInvokesCallback() {
        KafkaProducerFactory factory = props -> mockProducer;
        RecoverySupervisor supervisor = new RecoverySupervisor(config(), factory);

        PooledProducer faulted = new PooledProducer("svc-inst-0-1", 1, mockProducer);
        faulted.forceState(ProducerState.RECOVERING);

        AtomicReference<PooledProducer> recovered = new AtomicReference<>();
        supervisor.scheduleRecovery(faulted, recovered::set, ex -> {});

        // Wait for async recovery to complete
        await().atMost(3, SECONDS).until(() -> recovered.get() != null);

        PooledProducer replacement = recovered.get();
        assertNotNull(replacement);
        assertEquals(ProducerState.READY, replacement.getState());
        assertEquals(1, replacement.getSlotIndex());
        verify(mockProducer).initTransactions();

        supervisor.shutdown();
    }

    @Test
    void recoveryInvokesFailureCallbackOnInitializationError() {
        // Make initTransactions throw
        doThrow(new RuntimeException("broker down"))
                .when(mockProducer).initTransactions();
        KafkaProducerFactory factory = props -> mockProducer;
        RecoverySupervisor supervisor = new RecoverySupervisor(config(), factory);

        PooledProducer faulted = new PooledProducer("svc-inst-0-0", 0, mockProducer);
        faulted.forceState(ProducerState.RECOVERING);

        AtomicReference<Exception> caught = new AtomicReference<>();
        supervisor.scheduleRecovery(faulted, r -> {}, caught::set);

        await().atMost(3, SECONDS).until(() -> caught.get() != null);
        assertNotNull(caught.get());

        supervisor.shutdown();
    }

    @Test
    void shutdownPreventsNewRecoveries() throws InterruptedException {
        KafkaProducerFactory factory = props -> mockProducer;
        RecoverySupervisor supervisor = new RecoverySupervisor(config(), factory);
        supervisor.shutdown();

        PooledProducer faulted = new PooledProducer("svc-inst-0-0", 0, mockProducer);
        AtomicReference<PooledProducer> recovered = new AtomicReference<>();

        supervisor.scheduleRecovery(faulted, recovered::set, ex -> {});

        // Should not complete since shutdown
        Thread.sleep(200);
        assertNull(recovered.get());
    }
}
