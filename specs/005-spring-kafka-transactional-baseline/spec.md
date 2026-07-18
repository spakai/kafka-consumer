# Specification: Spring Kafka `KafkaTemplate` Transactional Baseline

## 1. Purpose

Define a fair, reproducible benchmark that compares the transactional producer pool from spec 001 with Spring Kafka's transactional `KafkaTemplate`.

The comparison must measure throughput, latency, resource usage, contention, and failure recovery while keeping Kafka, payloads, transaction boundaries, and client settings equivalent.

## 2. Goals

- Quantify the performance cost and benefit of the pooled producer design relative to `KafkaTemplate`.
- Compare one-record and batched transactional publishing.
- Compare behaviour under concurrency and producer contention.
- Compare recovery after a producer or broker fault.
- Verify that both implementations provide equivalent transactional correctness.
- Produce results suitable for design and deployment decisions rather than framework marketing claims.

## 3. Scope

### In Scope

- Spring Kafka `KafkaTemplate` with transactions enabled.
- The `TransactionalProducerPool` implementation from spec 001.
- Identical single-broker and three-broker Kafka environments.
- Single-record and multi-record transaction workloads.
- Pool/thread scaling, saturation, sustained load, and broker recovery.
- JVM, network, broker, and application metrics.
- `read_committed` correctness verification.
- CSV results and a Markdown comparison report.

### Out of Scope

- Non-transactional `KafkaTemplate` benchmarks.
- Consumer throughput or listener-container comparisons.
- Spring Boot startup-time comparisons.
- Cross-cluster failover from spec 003.
- Chaos scenarios already defined by spec 004, except where a matching recovery case is required.
- Tuning either implementation until a neutral baseline has been recorded.

## 4. Definitions

- **Pool implementation:** `TransactionalProducerPool` from this repository.
- **Spring implementation:** Spring Kafka `KafkaTemplate` configured with a transactional producer factory.
- **Transaction:** One `beginTransaction` to `commitTransaction` unit containing the configured number of records.
- **Equivalent workload:** A workload with the same broker, topic, partitioning, record payload, transaction size, concurrency, and duration.
- **Application overhead:** Time spent in framework and coordination code excluding broker I/O, measured separately where possible.
- **Throughput parity:** Comparison of records per second under the same workload and correctness requirements.

## 5. Fairness Rules

- Both implementations shall use the same Kafka client version where Spring Kafka permits it.
- Both shall run in separate JVM processes to prevent classpath, thread, and heap interference.
- Both shall use the same Java version, heap settings, CPU allocation, container limits, and host.
- Both shall use the same Kafka cluster and topic configuration for each paired run.
- Both shall use byte-array keys and values generated from the same deterministic seed.
- Both shall use the same partition key distribution and records-per-transaction.
- Both shall use `acks=all`, idempotence, bounded retries, request timeout, delivery timeout, compression, and batching settings.
- Both shall use equivalent transaction timeout and producer buffer settings.
- Warm-up results shall be discarded.
- Test order shall alternate between implementations to reduce time-of-day and broker-cache bias.
- Each paired scenario shall run at least three times; report median and standard deviation.
- A result shall be marked invalid if broker health, ISR count, or host CPU utilization differs materially between paired runs.

## 6. Implementations Under Test

### 6.1 Pooled Producer

- Use `TransactionalProducerPool.executeInTransaction` as the preferred API.
- Use one producer slot per active transaction up to the configured pool size.
- Record pool lease wait, producer recovery, transaction, and retry metrics.
- Use the transactional ID format and retry policy defined by spec 001.

### 6.2 Spring Kafka

- Use `DefaultKafkaProducerFactory` with `transactionIdPrefix` configured.
- Enable transactions on the producer factory.
- Use `KafkaTemplate.executeInTransaction` for transaction boundaries.
- Configure the transaction ID prefix so concurrent producer instances have unique IDs.
- Use a producer factory cache or equivalent configuration that permits the requested concurrency without silently serializing all transactions.
- Record transaction, send, retry, and producer-factory metrics through Micrometer.

The benchmark report shall include the exact Spring Kafka and Spring Boot versions and the producer-factory cache or producer-count settings. A `KafkaTemplate` configuration that allows only one active producer shall not be presented as a pool comparison without being labelled as such.

## 7. Kafka Environment

### Single-Broker Environment

- One Kafka broker matching the client version.
- Topic: `baseline-compare-single`.
- Partitions: 12.
- Replication factor: 1.
- `min.insync.replicas=1`.

### Multi-Broker Environment

- Three Kafka brokers in KRaft mode.
- Topic: `baseline-compare-replicated`.
- Partitions: 12.
- Replication factor: 3.
- `min.insync.replicas=2`.
- Unclean leader election disabled.

The benchmark shall record broker version, client version, topic configuration, leader distribution, ISR state, CPU, memory, and storage type.

## 8. Scenarios

### B-01: One Record per Transaction

**Purpose:** measure the transaction overhead for the smallest useful unit.

- Pool sizes / Spring producer counts: 1, 4, 8.
- Threads: equal to configured producer count.
- Records per transaction: 1.
- Record sizes: 256 bytes, 1 KB, and 10 KB.
- Duration: 60 seconds per size and implementation.

**Capture:** records/s, transactions/s, p50/p95/p99 end-to-end latency, transaction commit latency, CPU, and heap.

### B-02: Batched Transaction Throughput

**Purpose:** compare batching efficiency.

- Producer count: 4.
- Threads: 4.
- Records per transaction: 10, 50, and 100.
- Record size: 1 KB.
- Duration: 60 seconds per batch size.

**Capture:** records/s, transactions/s, records per commit, p95 commit latency, bytes/s, and CPU per million records.

### B-03: Scaling with Concurrency

**Purpose:** compare throughput scaling as concurrent transactional work increases.

- Producer/thread counts: 1, 2, 4, 8, and 16.
- Records per transaction: 10.
- Record size: 1 KB.
- Duration: 60 seconds per count.

**Capture:** scaling efficiency, lease wait for the pool, framework queue wait for Spring, throughput, p95 latency, and active producer count.

### B-04: Oversubscribed Callers

**Purpose:** compare backpressure and fairness when callers exceed available producers.

- Producer count: 4.
- Caller threads: 8, 16, and 32.
- Records per transaction: 10.
- Lease / template acquisition timeout: 5 seconds.
- Duration: 60 seconds per caller count.

**Capture:** successful records/s, timeout rate, queue depth, p95/p99 wait time, active threads, and memory growth.

The report shall distinguish a bounded pool timeout from a Kafka send or commit timeout. Spring queueing behaviour shall be documented rather than treated as equivalent to a pool lease.

### B-05: Ten-Minute Sustained Load

**Purpose:** detect leaks, throughput degradation, and resource divergence.

- Producer count: 8.
- Threads: 8.
- Records per transaction: 10.
- Record size: 1 KB.
- Duration: 10 minutes.

**Pass criteria:** neither implementation degrades more than 5% from the first to final steady-state window, and neither shows unbounded heap, thread, producer, or queue growth.

### B-06: Transactional Broker Recovery

**Purpose:** compare recovery after a broker restart in the replicated environment.

- Three-broker topic from section 7.
- Producer/thread count: 4.
- Records per transaction: 10.
- Stop a partition-leading broker after a 2-minute baseline.
- Restart it after 60 seconds.
- Continue load for 6 minutes after recovery.

**Capture:** failed, aborted, retried, and ambiguous transactions; recovery time; throughput dip; p99 latency; producer replacement/recreation; and correctness-verifier results.

No implementation may automatically replay a transaction with an unknown commit outcome.

### B-07: JVM Coordination Overhead

**Purpose:** isolate application coordination cost from broker I/O.

- Use an in-memory producer adapter for the pool.
- Use a test producer or mocked send path for the Spring comparison where supported.
- One record per transaction.
- JMH throughput and average-time modes.
- Three forks, five warm-up iterations, and ten measurement iterations.

This scenario shall be labelled a coordination micro-benchmark and shall not be used as a Kafka throughput claim.

## 9. Configuration Matrix

The report shall include a machine-readable configuration row for every run:

| Field | Required value |
|---|---|
| Implementation | `pool` or `spring-kafka` |
| Framework version | Exact dependency version |
| Kafka client version | Exact dependency version |
| Broker topology | Single broker or three brokers |
| Topic configuration | Partitions, replication, ISR minimum |
| Producer count | Configured concurrent producers |
| Caller threads | Concurrent workload threads |
| Records per transaction | Scenario value |
| Record size | Scenario value |
| Compression | Exact setting |
| Retry and timeout envelope | Exact settings |
| JVM and heap | Version and flags |
| Run seed | Deterministic input seed |

## 10. Metrics

Both implementations shall expose or derive:

- `records_per_second`
- `transactions_per_second`
- `bytes_per_second`
- `transaction_duration_ms`
- `commit_latency_ms`
- `send_latency_ms`
- `p50`, `p95`, and `p99` end-to-end latency
- acquisition or queue wait latency
- timeout and rejected-call count
- retry count by error class
- abort count
- ambiguous outcome count
- active producer count
- producer creation and replacement count
- CPU utilization
- heap used and allocation rate
- live thread count
- GC pause time
- broker request latency and under-replicated partitions

Pool-specific metrics shall be tagged with `implementation=pool`; Spring metrics shall be normalized into the same report schema with `implementation=spring-kafka`.

## 11. Correctness Verification

Every transaction shall include:

- Run ID.
- Implementation.
- Publish ID.
- Transaction attempt ID.
- Deterministic key.
- Per-key sequence number.
- Record index within the transaction.

After each run, a verifier shall consume with `isolation.level=read_committed` and assert:

- Every acknowledged transaction is fully visible.
- No partial transaction is visible.
- No conclusively aborted transaction is visible.
- No duplicate publish ID is observed.
- Per-key sequence order is preserved.
- Ambiguous outcomes are reported separately and are not silently counted as success or loss.

A performance result shall be excluded from comparison if either implementation fails correctness verification.

## 12. Results and Analysis

Write:

- `baseline-results/<scenario>-<implementation>-<timestamp>.csv` for raw samples.
- `baseline-results/comparison.csv` for normalized paired results.
- `baseline-results/summary.md` for conclusions and pass/fail status.

The summary shall report:

- Absolute throughput and latency for each implementation.
- Pool-versus-Spring percentage difference.
- Scaling efficiency and saturation point.
- CPU and heap cost per million records.
- Recovery-time comparison.
- Correctness outcomes and excluded runs.
- Confidence limitations and environment details.

Percentage differences shall be calculated as:

```text
(pool_value - spring_value) / spring_value * 100
```

For latency and resource usage, lower is better. For throughput and recovery availability, higher is better.

## 13. Acceptance Criteria

- All paired scenarios use documented equivalent configuration.
- Both implementations pass transactional correctness verification in normal and recovery scenarios.
- The benchmark produces at least three valid paired runs per scenario.
- Every timeout, retry, abort, and ambiguous outcome is classified.
- B-05 detects any throughput degradation above 5% or unbounded resource growth.
- B-06 records recovery time and confirms no unsafe ambiguous-commit replay.
- The report identifies the concurrency and batch-size ranges where each implementation is preferable.
- No conclusion is based solely on a micro-benchmark or a single run.

The benchmark does not impose a universal winner threshold. A result is useful when it makes the throughput, latency, operational, and correctness trade-offs explicit.

## 14. Implementation Layout

```text
src/baseline/java/com/kafka/producer/baseline/
    BaselineRunner.java
    BaselineConfig.java
    ImplementationAdapter.java
    PoolImplementationAdapter.java
    SpringKafkaImplementationAdapter.java
    LoadWorker.java
    CorrectnessVerifier.java
    ResultWriter.java
    scenarios/
        SingleRecordScenario.java
        BatchScenario.java
        ScalingScenario.java
        OversubscriptionScenario.java
        SustainedLoadScenario.java
        BrokerRecoveryScenario.java
```

Spring Kafka dependencies shall be isolated in the benchmark source set or Maven profile so the core producer library does not acquire a Spring runtime dependency.

## 15. Maven Profile and Execution

Add a `spring-baseline` profile that supplies Spring Kafka and the benchmark source set. Example execution:

```bash
mvn test -Pspring-baseline \
  -Dscenario=B-02 \
  -Dimplementation=pool,spring-kafka \
  -Dtopology=three-broker \
  -DproducerCount=4 \
  -DrecordsPerTransaction=50
```

The profile shall not change the default build or runtime dependencies of the producer pool.

## 16. Risks and Mitigations

- **Risk:** Spring's producer cache is configured differently from pool size.
  - **Mitigation:** Record producer-factory cache settings and verify active producer counts.
- **Risk:** Framework startup or serialization overhead dominates the result.
  - **Mitigation:** Exclude startup and use identical byte-array serialization.
- **Risk:** Spring retries a callback differently from the pool.
  - **Mitigation:** Keep callback side effects deterministic and report callback-attempt counts.
- **Risk:** A single broker produces noisy or misleading results.
  - **Mitigation:** Run both single-broker and replicated three-broker topologies.
- **Risk:** One implementation's metrics are more detailed.
  - **Mitigation:** Normalize to a shared result schema and label unavailable metrics.
- **Risk:** Ambiguous commits are counted as ordinary failures.
  - **Mitigation:** Verify commit-outcome classification and exclude invalid correctness runs.
- **Risk:** Spring dependency leakage into the core artifact.
  - **Mitigation:** Isolate the baseline profile and inspect the default dependency tree.

## 17. Implementation Sequence

1. Define the shared adapter and result schema.
2. Add the Spring Kafka benchmark profile and dependency isolation.
3. Implement the pool and Spring adapters with equivalent transaction callbacks.
4. Implement B-01 and B-02 against the single-broker environment.
5. Add scaling, oversubscription, and sustained-load scenarios.
6. Add the replicated three-broker recovery scenario.
7. Add correctness verification and paired-run analysis.
8. Record an initial baseline before tuning either implementation.
9. Add CI or scheduled performance reporting.

## 18. Future Extensions

- Compare Spring Kafka listener-container transactions.
- Compare Kafka Streams exactly-once processing.
- Compare non-transactional producer baselines.
- Add broker and client TLS/SASL overhead comparisons.
- Add cloud-managed Kafka latency profiles.
