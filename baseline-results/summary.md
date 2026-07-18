# Spring Kafka transactional baseline

Generated rows are observations from the recorded environment; invalid correctness runs must not be compared.

## B-02 / pool / run 1

- Environment: `single-broker`, JVM `21.0.11`, topic `baseline-compare-single`
- Configuration: producers 4, callers 4, records/transaction 50, record bytes 1024, seed 7193
- Throughput: 9556.311 records/s; p95 transaction latency: 39.777 ms
- Failures: 0; timeouts: 0; ambiguous: 0
- Correctness: PASS

## B-02 aggregate comparison

Three paired 60-second measurements were run with alternating implementation
order after a 10-second warm-up. All six runs passed `read_committed`
verification and reported zero failures, timeouts, aborts, or ambiguous
outcomes.

| Metric | Pool | Spring Kafka | Pool difference |
|---|---:|---:|---:|
| Median records/s | 9272.507 | 8114.348 | +14.273% |
| Mean records/s | 9218.928 | 8451.174 | +9.085% |
| Records/s population standard deviation | 299.749 | 897.347 | |
| Median p95 transaction latency | 41.533 ms | 45.954 ms | -9.620% |
| Mean p95 transaction latency | 41.584 ms | 45.432 ms | -8.471% |
| p95 population standard deviation | 1.497 ms | 4.617 ms | |

For this B-02 configuration, the pool had higher median throughput and lower
median p95 latency. This is a single-host, single-broker result and must not be
generalized to other batch sizes, concurrency levels, sustained load, or broker
recovery.

## Recorded environment

- Host: WSL2, 12 logical CPUs, 12th Gen Intel Core i5-12400, 7.6 GiB memory
- JVM: OpenJDK 21.0.11
- Broker: Apache Kafka 3.7.0 in a Docker container
- Client: Apache Kafka 3.6.1
- Spring Kafka: 3.1.1
- Topic: 12 partitions, replication factor 1, `min.insync.replicas=1`
- Payload: deterministic 1024-byte values using seed 7193
- Transaction configuration: 4 producers, 4 callers, 50 records/transaction

CPU, allocation-rate, GC, broker-request-latency, and storage telemetry were not
captured by this run, so no resource-efficiency conclusion is made.

## B-02 / spring-kafka / run 1

- Environment: `single-broker`, JVM `21.0.11`, topic `baseline-compare-single`
- Configuration: producers 4, callers 4, records/transaction 50, record bytes 1024, seed 7193
- Throughput: 7559.984 records/s; p95 transaction latency: 50.807 ms
- Failures: 0; timeouts: 0; ambiguous: 0
- Correctness: PASS

## B-02 / spring-kafka / run 2

- Environment: `single-broker`, JVM `21.0.11`, topic `baseline-compare-single`
- Configuration: producers 4, callers 4, records/transaction 50, record bytes 1024, seed 7193
- Throughput: 8114.348 records/s; p95 transaction latency: 45.954 ms
- Failures: 0; timeouts: 0; ambiguous: 0
- Correctness: PASS

## B-02 / pool / run 2

- Environment: `single-broker`, JVM `21.0.11`, topic `baseline-compare-single`
- Configuration: producers 4, callers 4, records/transaction 50, record bytes 1024, seed 7193
- Throughput: 8827.967 records/s; p95 transaction latency: 43.442 ms
- Failures: 0; timeouts: 0; ambiguous: 0
- Correctness: PASS

## B-02 / pool / run 3

- Environment: `single-broker`, JVM `21.0.11`, topic `baseline-compare-single`
- Configuration: producers 4, callers 4, records/transaction 50, record bytes 1024, seed 7193
- Throughput: 9272.507 records/s; p95 transaction latency: 41.533 ms
- Failures: 0; timeouts: 0; ambiguous: 0
- Correctness: PASS

## B-02 / spring-kafka / run 3

- Environment: `single-broker`, JVM `21.0.11`, topic `baseline-compare-single`
- Configuration: producers 4, callers 4, records/transaction 50, record bytes 1024, seed 7193
- Throughput: 9679.190 records/s; p95 transaction latency: 39.534 ms
- Failures: 0; timeouts: 0; ambiguous: 0
- Correctness: PASS
