# Specification: Performance Testing — Pooled Kafka Transactional Producer

## 1. Purpose

Define the performance test suite for the `TransactionalProducerPool` implemented in spec 001. The tests must produce quantified, reproducible results that validate throughput, latency, and resource behaviour under realistic and stress load profiles.

## 2. Goals

- Establish baseline throughput and latency at nominal pool size.
- Quantify how throughput scales with pool size.
- Measure the cost of transactional overhead vs. non-transactional sends.
- Characterise latency under pool saturation and lease contention.
- Measure recovery time after a fatal producer fault.
- Produce a results report that can gate merge or deployment decisions.

## 3. Scope

### In Scope

- JVM micro-benchmark of core transaction execute path (JMH).
- End-to-end load tests against a real single-node Kafka broker.
- Pool scaling tests (pool size 1 → N).
- Saturation and backpressure behaviour.
- Recovery latency after `ProducerFencedException` injection.
- Result reporting in structured form (CSV + summary markdown).

### Out of Scope

- Multi-broker cluster performance (separate spec).
- Consumer-side throughput.
- Network bandwidth profiling.
- OS/JVM tuning guides.

## 4. Test Environment

### Broker

- Single Kafka broker, same host or localhost Docker.
- Kafka version matching the `kafka-clients` version in `pom.xml`.
- Default broker config unless stated otherwise.
- Topic: `perf-test` with 12 partitions, replication factor 1.

### Client JVM

- Java 21, `-Xmx512m -Xms512m`.
- No other applications competing for CPU during runs.
- Warm-up runs excluded from results.

### Repeatability

- Each scenario run 3 times; report median ± standard deviation.
- Results stamped with date, Kafka version, JVM version, and hardware profile.

## 5. Scenarios

### S-01: Baseline Throughput — Single Producer, No Transactions

**Purpose:** establish non-transactional ceiling for comparison.

- Pool size: 1
- Transactions: disabled
- Message size: 256 bytes, 1 KB, 10 KB
- Duration: 60 seconds per message size
- Concurrency: 1 thread

**Capture:**
- Messages per second (msg/s)
- MB/s
- p50 / p95 / p99 send latency

---

### S-02: Baseline Throughput — Single Transactional Producer

**Purpose:** measure transactional overhead cost relative to S-01.

- Pool size: 1
- Transactions: enabled, 1 record per transaction
- Message size: 256 bytes, 1 KB, 10 KB
- Duration: 60 seconds
- Concurrency: 1 thread

**Capture:** same as S-01.
**Expected:** throughput reduction vs S-01 quantified as percentage overhead.

---

### S-03: Batch Transaction Throughput

**Purpose:** measure effect of batching multiple records per transaction.

- Pool size: 4
- Records per transaction: 1, 10, 50, 100
- Message size: 1 KB fixed
- Concurrency: 4 threads (one per producer)
- Duration: 60 seconds per batch size

**Capture:**
- Transactions per second (tx/s)
- Records per second (msg/s)
- p95 transaction commit latency
- Optimal batch size that maximises msg/s

---

### S-04: Pool Scaling — Throughput vs Pool Size

**Purpose:** show linear throughput scaling as pool grows.

- Pool sizes: 1, 2, 4, 8, 16
- Concurrency: threads = pool size (all slots kept busy)
- Records per transaction: 10
- Message size: 1 KB
- Duration: 60 seconds per pool size

**Capture:**
- msg/s per pool size
- Scaling efficiency (actual / ideal linear)
- p95 lease acquisition latency per pool size

**Expected:** near-linear scaling up to broker/network saturation point.

---

### S-05: Lease Contention Under Oversubscription

**Purpose:** measure pool behaviour when callers exceed pool capacity.

- Pool size: 4
- Concurrent caller threads: 8, 16, 32
- Records per transaction: 10
- Lease timeout: 5 000 ms
- Duration: 60 seconds per thread count

**Capture:**
- Successful tx/s
- `LeaseTimeoutException` rate (%)
- `PoolSaturationException` rate (%)
- p95 / p99 lease wait time
- Active lease queue depth over time

---

### S-06: Sustained Load — 10-Minute Soak

**Purpose:** detect throughput degradation, memory growth, or lease leaks over time.

- Pool size: 8
- Concurrent threads: 8
- Records per transaction: 10
- Message size: 1 KB
- Duration: 10 minutes

**Capture:**
- msg/s sampled every 30 seconds (time-series)
- JVM heap used sampled every 30 seconds
- Total `LeaseTimeoutException` count
- Total `PoolSaturationException` count
- Producer eviction count

**Pass criterion:** throughput must not degrade more than 5% from first 30-second window to last.

---

### S-07: Fatal Fault Recovery Latency

**Purpose:** measure time from `ProducerFencedException` injection to pool returning to full capacity.

- Pool size: 4
- Inject fencing on 1 producer mid-run using a competing producer with the same `transactional.id`.
- Load: 4 threads sending continuously.

**Capture:**
- Time from fence event to pool state returning to HEALTHY (ms).
- Transactions lost during recovery window.
- Any double-eviction or cascading failure observed.

**Expected:** pool returns to full capacity within 5 seconds under normal broker conditions.

---

### S-08: JMH Micro-Benchmark — `executeInTransaction` Hot Path

**Purpose:** isolate JVM-level overhead of the pool's coordination code, excluding broker I/O.

- Use a mock `KafkaProducerFactory` returning an in-memory no-op producer.
- Benchmark `executeInTransaction` with 1 record, no real I/O.
- Modes: throughput and average time.
- Forks: 3, warmup iterations: 5, measurement iterations: 10.

**Capture:**
- ops/s
- Average time per operation (ns)
- Identify any lock contention hotspot via JMH profiler (`-prof stack`).

## 6. Metrics to Capture Per Scenario

| Metric | Unit | Collection Method |
|---|---|---|
| Transaction throughput | tx/s | Counter / wall clock |
| Record throughput | msg/s | Counter / wall clock |
| Data throughput | MB/s | Derived |
| Lease wait latency | ms | HDR Histogram |
| Transaction commit latency | ms | HDR Histogram |
| p50 / p95 / p99 latency | ms | HDR Histogram percentiles |
| Lease timeout rate | % | Counter ratio |
| Saturation exception rate | % | Counter ratio |
| Producer eviction count | count | Counter |
| JVM heap used | MB | JMX / MemoryMXBean |
| GC pause time | ms | GC log |
| Pool healthy count | count | PoolHealth gauge |

## 7. Tooling

- **JMH** (`org.openjdk.jmh`) for micro-benchmarks (S-08).
- **Custom load harness** using `java.util.concurrent.ExecutorService` and `CountDownLatch` for S-01 to S-07.
- **HdrHistogram** for latency recording.
- **Micrometer `SimpleMeterRegistry`** to capture pool-emitted metrics.
- **Kafka broker**: Docker image `confluentinc/cp-kafka` or `apache/kafka`.
- **Results**: written to `perf-results/` as CSV per scenario run, plus `perf-results/summary.md`.

## 8. Test Harness Design

### Entry Point

```
com.kafka.producer.perf.PerfRunner
```

Accepts CLI args: `--scenario S-01..S-08 --pool-size N --threads N --duration-sec N --record-size N --records-per-tx N`

### Structure

```
src/perf/java/com/kafka/producer/perf/
    PerfRunner.java          # CLI entry point and scenario dispatch
    ScenarioConfig.java      # Parsed scenario parameters
    LoadWorker.java          # Runnable: acquire lease, execute tx, record latency
    LatencyRecorder.java     # HdrHistogram wrapper
    ResultWriter.java        # CSV + summary markdown output
    scenarios/
        BaselineThroughputScenario.java
        PoolScalingScenario.java
        SaturationScenario.java
        SoakScenario.java
        RecoveryLatencyScenario.java
```

### Maven Profile

```xml
<profile>
  <id>perf</id>
  <build>
    <sourceDirectory>src/perf/java</sourceDirectory>
  </build>
</profile>
```

Run with: `mvn test -Pperf -Dscenario=S-04 -DpoolSize=8`

## 9. Acceptance Criteria

| Criterion | Target |
|---|---|
| S-02 transactional overhead vs S-01 | < 30% throughput reduction for 1 KB messages |
| S-03 optimal batch size identified | Documented batch size at peak msg/s |
| S-04 scaling efficiency at pool=8 | ≥ 70% linear efficiency |
| S-05 lease timeout rate at 2× oversubscription | < 5% |
| S-06 soak throughput degradation | < 5% over 10 minutes |
| S-06 no heap growth trend | Heap stable ± 20 MB after warm-up |
| S-07 recovery latency | Pool HEALTHY within 5 seconds |
| S-08 executeInTransaction overhead (no I/O) | < 50 µs per operation |

## 10. Results Reporting

Each run produces:

- `perf-results/<scenario>-<timestamp>.csv` — raw per-second samples.
- `perf-results/summary.md` — scenario name, config, all captured metrics, pass/fail against acceptance criteria.

The summary must be committed to the repository alongside the test harness code so results are reviewable in PRs.

## 11. Risks

- Localhost broker introduces JVM/OS scheduling noise — mitigate with 3 runs and median reporting.
- GC pauses can spike p99 latency — capture GC log and annotate spikes in results.
- Docker networking overhead on Linux differs from bare metal — document environment.

## 12. Future Extensions

- Multi-broker cluster scenarios.
- Chaos scenarios: broker restart mid-soak, network partition simulation.
- Compare against Spring Kafka `KafkaTemplate` transactional baseline.
- Continuous performance regression gate in CI.
