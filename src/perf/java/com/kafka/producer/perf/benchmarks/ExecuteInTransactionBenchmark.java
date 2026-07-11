package com.kafka.producer.perf.benchmarks;

import com.kafka.producer.pool.ExecutionOptions;
import com.kafka.producer.pool.KafkaProducerFactory;
import com.kafka.producer.pool.PoolConfig;
import com.kafka.producer.pool.TransactionalProducerPool;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(3)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
public class ExecuteInTransactionBenchmark {

    private TransactionalProducerPool pool;
    private ProducerRecord<byte[], byte[]> record;

    @Setup(Level.Trial)
    public void setUp() {
        KafkaProducer<byte[], byte[]> mockProducer = Mockito.mock(KafkaProducer.class);
        RecordMetadata metadata = Mockito.mock(RecordMetadata.class);

        Mockito.doNothing().when(mockProducer).initTransactions();
        Mockito.doNothing().when(mockProducer).beginTransaction();
        Mockito.doNothing().when(mockProducer).commitTransaction();
        Mockito.doNothing().when(mockProducer).abortTransaction();
        Mockito.doNothing().when(mockProducer).flush();
        Mockito.doNothing().when(mockProducer).close(Mockito.any(Duration.class));
        Mockito.when(mockProducer.send(Mockito.any())).thenReturn(CompletableFuture.completedFuture(metadata));

        KafkaProducerFactory factory = props -> mockProducer;
        pool = new TransactionalProducerPool(poolConfig(), factory, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        pool.initialize();
        record = new ProducerRecord<>("perf-test", null, new byte[128]);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.shutdown();
    }

    @Benchmark
    public Object executeInTransactionHotPath() {
        return pool.executeInTransaction(lease -> {
            pool.send(lease, record);
            return Boolean.TRUE;
        }, ExecutionOptions.defaults());
    }

    private static PoolConfig poolConfig() {
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", "mock:9092");
        return PoolConfig.builder()
                .poolSize(1)
                .minHealthyProducers(1)
                .leaseTimeoutMs(1000)
                .leaseHardTimeoutMs(10_000)
                .shutdownGracePeriodMs(1000)
                .retryMaxAttempts(0)
                .retryBaseDelayMs(10)
                .retryMaxDelayMs(10)
                .recoveryMaxConcurrentRebuilds(1)
                .serviceIdentity("bench")
                .instanceIdentifier("local")
                .kafkaProperties(props)
                .build();
    }
}
