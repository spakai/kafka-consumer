# Specification: Robust Kafka Pooled Transactional Producers

## 1. Purpose

Build a production-grade pooled transactional producer subsystem for Kafka that:

- Supports exactly-once transactional publishing for message batches.
- Scales producer throughput via a managed producer pool.
- Is resilient to broker instability, fencing, and transient network failures.
- Preserves ordering guarantees per key and topic partition.
- Provides strong observability and deterministic failure behavior.

This component is intended for services that need reliable publish-side semantics with controlled latency and high availability.

## 2. Scope

### In Scope

- Transactional Kafka producer pool lifecycle and resource management.
- Borrow/return leasing model for producer instances.
- Transaction begin/commit/abort orchestration.
- Fencing and fatal error recovery.
- Retry behavior for retriable publish failures.
- Metrics, logs, tracing, and health checks.
- Graceful shutdown and drain strategy.
- Integration contract for upstream application services.

### Out of Scope

- Kafka cluster provisioning and broker configuration automation.
- Consumer-side transaction handling.
- Business domain payload schemas.
- Cross-cluster replication design.

## 3. Definitions

- Producer Pool: Managed collection of transactional Kafka producer instances.
- Lease: Temporary exclusive access to a producer instance.
- Transaction Session: Lifecycle from beginTransaction to commitTransaction or abortTransaction.
- Fenced Producer: Producer invalidated by Kafka due to transactional.id ownership changes.
- Fatal Error: Error that permanently invalidates current producer instance.
- Retriable Error: Error category for safe retry under bounded policy.

## 4. Functional Requirements

### FR-1 Pool Initialization

- The system shall initialize a configurable pool of transactional Kafka producers at startup.
- Each producer shall be configured with a unique transactional.id derived from:
	- Service identity.
	- Instance identifier.
	- Pool slot index.
- Each producer shall call initTransactions before becoming available.
- Pool startup shall fail fast if a minimum healthy producer threshold is not met.

### FR-2 Leasing Model

- Clients shall acquire producers through a bounded blocking lease API.
- A lease request shall support timeout with explicit error on expiration.
- Producers shall not be shared concurrently across active leases.
- Leases shall auto-expire if hard lease timeout is exceeded and transaction must be aborted.

### FR-3 Transaction Lifecycle

- On lease acquisition, client can begin a transaction.
- Within a transaction, client may send one or more records.
- On success path, transaction shall commit atomically.
- On any non-recoverable send/flush error, transaction shall abort.
- Component shall guarantee release of producer back to pool on all exit paths.

### FR-4 Error Classification and Recovery

- Errors shall be classified into retriable, abort-required, and fatal.
- Retriable send errors shall be retried with bounded exponential backoff and jitter.
- Abort-required errors shall force immediate transaction abort.
- Fatal errors, including producer fencing, shall evict producer from pool and trigger replacement.

### FR-5 Ordering and Idempotence

- Idempotence shall be enabled on all producers.
- max.in.flight.requests.per.connection shall be configured to preserve ordering guarantees.
- Partition key strategy shall be deterministic for logically ordered message streams.

### FR-6 Backpressure

- If no producers are available, the pool shall apply bounded wait behavior.
- When queue and lease pressure exceed configured thresholds, component shall:
	- Emit saturation metrics.
	- Return explicit backpressure errors to caller.

### FR-7 Shutdown and Draining

- Shutdown shall stop accepting new lease requests.
- In-flight transactions shall be allowed to complete within graceful timeout.
- Remaining active transactions after timeout shall be aborted.
- All producers shall be closed cleanly.

## 5. Non-Functional Requirements

### NFR-1 Reliability

- Publish operation success must represent committed Kafka transaction.
- No partial commit behavior for records inside one transaction session.
- Recovery from single producer fatal failure must be automatic without process restart.

### NFR-2 Performance

- Target p95 lease acquisition latency under normal load: configurable SLO.
- Target p95 transaction commit latency: configurable SLO.
- Pool should sustain target throughput with linear scaling by pool size until broker limits.

### NFR-3 Availability

- Component shall remain operational with degraded capacity when subset of pool instances fail.
- Pool health endpoint shall reflect healthy, degraded, or unavailable status.

### NFR-4 Operability

- Full metrics, structured logs, and distributed tracing hooks are required.
- Runtime configuration reload support is optional; if unsupported, require restart and document it.

## 6. Kafka Producer Configuration Baseline

Required producer settings:

- enable.idempotence=true
- acks=all
- retries=high bounded value
- delivery.timeout.ms tuned above retry envelope
- request.timeout.ms tuned for broker/network behavior
- linger.ms and batch.size based on throughput profile
- max.in.flight.requests.per.connection <= 5 and validated for ordering needs
- transactional.id unique per producer pool slot

Optional tuning:

- compression.type
- buffer.memory
- client.id format including service and instance identity

## 7. Architecture

### Components

- Pool Manager
	- Creates, validates, recycles producers.
	- Tracks pool state and health.
- Lease Manager
	- Handles producer checkout/checkin with timeout and fairness policy.
	- Enforces hard lease timeout and leak detection.
- Transaction Executor
	- Encapsulates begin/send/flush/commit/abort flow.
	- Centralizes retry and error classification.
- Recovery Supervisor
	- Recreates invalid producers after fatal errors.
	- Applies bounded recovery rate to avoid churn storms.
- Telemetry Module
	- Emits metrics/logs/traces for each transaction and pool event.

### State Model

- Producer states: Initializing, Ready, Leased, Recovering, Closed.
- Pool states: Starting, Healthy, Degraded, Draining, Stopped.

Valid transitions must be enforced by state guards and synchronized access.

## 8. Public Interface Contract

The component shall provide an API conceptually equivalent to:

- acquireLease(timeout)
- beginTransaction(lease)
- send(lease, record)
- commit(lease)
- abort(lease)
- release(lease)
- executeInTransaction(callback, options)

The preferred integration API is executeInTransaction, which wraps lease management and enforces safe cleanup in a single call.

## 9. Transaction Execution Rules

1. Acquire producer lease.
2. Begin transaction.
3. Execute client callback to publish records.
4. Flush pending sends.
5. Commit transaction.
6. On any exception:
	 - Attempt abort if transaction has started.
	 - Classify error and decide retry/evict/fail.
7. Release lease in finally path.

Hard constraints:

- Never return producer to ready pool while transaction is in unknown state.
- If commit result is ambiguous due to timeout/disconnect, producer must be quarantined and recreated.

## 10. Failure Scenarios and Expected Behavior

### Scenario A: Retriable Send Failure

- Retry send with bounded backoff.
- If retry budget exhausted, abort transaction and return classified failure.

### Scenario B: ProducerFencedException

- Abort current flow.
- Mark producer fatal and evict from pool.
- Trigger asynchronous replacement producer creation.

### Scenario C: Commit Timeout or Unknown Commit Outcome

- Treat producer as unsafe.
- Quarantine and evict producer.
- Surface failure to caller with remediation hint.

### Scenario D: Pool Saturation

- Lease requests wait up to configured timeout.
- On timeout, return explicit capacity exception.
- Emit saturation and wait-time metrics.

### Scenario E: Service Shutdown During In-Flight Transaction

- Stop new leases.
- Await completion until grace deadline.
- Abort remaining transactions and close producers.

## 11. Observability Requirements

### Metrics

- pool_size_total
- pool_size_ready
- pool_size_leased
- lease_wait_ms histogram
- lease_timeout_total
- transaction_begin_total
- transaction_commit_total
- transaction_abort_total
- transaction_duration_ms histogram
- producer_fenced_total
- producer_recovery_total
- publish_retry_total by error class

### Logs

- Structured logs with correlation id, transactional.id, lease id, topic, partition, and outcome.
- Error logs must include error class and retry decision.

### Tracing

- Transaction-level span with child spans for send, flush, commit, abort.
- Trace attributes for pool wait duration and retry count.

## 12. Configuration

Required configuration keys:

- pool.size
- pool.minHealthy
- lease.timeout.ms
- lease.hardTimeout.ms
- shutdown.gracePeriod.ms
- retry.maxAttempts
- retry.baseDelay.ms
- retry.maxDelay.ms
- recovery.maxConcurrentRebuilds

Configuration validation must reject invalid combinations at startup.

## 13. Security and Compliance

- Support TLS and SASL producer settings through secure configuration.
- Avoid logging message payloads by default.
- Redact sensitive headers and credentials in logs.

## 14. Testing Strategy

### Unit Tests

- Lease acquire/release under contention.
- Error classification logic.
- Transaction success and failure cleanup paths.
- Recovery supervisor replacement behavior.

### Integration Tests

- End-to-end transactional publish with Kafka test cluster.
- Fencing simulation using duplicate transactional.id.
- Broker restart and transient network interruption.
- Saturation behavior under limited pool size.

### Fault Injection Tests

- Inject send failures and commit unknown outcomes.
- Validate producer quarantine and recreation.
- Validate no leaked leases or stuck producer states.

## 15. Acceptance Criteria

- Exactly-once transactional publish verified in integration test suite.
- No producer leaks during stress tests.
- Recovery from fenced or fatal producer occurs automatically.
- Metrics and logs provide full incident triage coverage.
- Graceful shutdown guarantees no unbounded hang and deterministic cleanup.

## 16. Implementation Notes

- Prefer a small wrapper around KafkaProducer to centralize transaction safety rules.
- Keep pool internals encapsulated; expose only transaction-focused API.
- Use monotonic clock for lease and timeout calculations.
- Document known Kafka client edge cases and selected handling policy.

## 17. Risks and Mitigations

- Risk: Transactional.id collisions across deployments.
	- Mitigation: enforce deterministic unique id format and startup collision checks.
- Risk: Commit ambiguity under network partitions.
	- Mitigation: quarantine producer and fail closed.
- Risk: Throughput collapse from undersized pool.
	- Mitigation: capacity tests and adaptive operational tuning.

## 18. Future Enhancements

- Dynamic pool auto-scaling based on queue pressure.
- Priority-based lease allocation.
- Multi-cluster failover publish strategy.
- Runtime config reload for selected non-structural settings.
