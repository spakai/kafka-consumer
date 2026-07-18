# Specification: Multi-Cluster Failover Publish Strategy

## 1. Purpose

Extend the transactional producer pool from spec 001 with an application-side strategy for publishing to a secondary Kafka cluster when the primary cluster is unavailable.

The strategy must:

- Preserve transactional atomicity within the selected cluster.
- Make cluster selection deterministic and observable.
- Avoid unsafe automatic replay when a commit outcome is unknown.
- Bound failover and failback behaviour to prevent cluster flapping.
- State clearly where cross-cluster exactly-once delivery cannot be guaranteed.

## 2. Goals

- Continue publishing during a sustained primary-cluster outage.
- Fail over without requiring an application restart.
- Route all records in one transaction to exactly one cluster.
- Preserve per-key ordering by preventing concurrent publication of the same routing scope to both clusters.
- Provide explicit outcomes for committed, rejected, and ambiguous publishes.
- Support controlled failback after the primary cluster has recovered.
- Reuse one independent `TransactionalProducerPool` per cluster.

## 3. Scope

### In Scope

- Active-passive cluster selection for transactional publishes.
- Independent producer pools, health state, and transactional IDs per cluster.
- Failure detection, failover eligibility, cooldown, and failback.
- Routing scopes used to protect ordering during cluster transitions.
- Publish identifiers and metadata required for downstream deduplication.
- Behaviour for failures before send, during a transaction, and during commit.
- Metrics, logs, tracing, health reporting, configuration, and testing.
- Operator controls for forcing, freezing, and restoring cluster routing.

### Out of Scope

- Kafka cluster provisioning.
- Topic creation or configuration synchronization.
- Cross-cluster topic replication and replication conflict resolution.
- Consumer failover and consumer offset migration.
- Automatic reconciliation of records committed to only one cluster.
- Active-active load balancing.
- A distributed consensus service for coordinating independent publisher deployments.

## 4. Definitions

- **Cluster:** One independently operated Kafka deployment with its own bootstrap servers.
- **Primary cluster:** Preferred cluster during normal operation.
- **Secondary cluster:** Standby cluster eligible to receive publishes during failover.
- **Cluster pool:** A `TransactionalProducerPool` configured for one cluster.
- **Routing scope:** Stable unit whose records must remain on one cluster to preserve ordering. The default scope is the record key; applications may configure a coarser scope.
- **Routing epoch:** Monotonically increasing identifier for a cluster-routing decision period.
- **Failover:** Transition of eligible routing scopes from primary to secondary.
- **Failback:** Controlled transition from secondary to primary after recovery.
- **Ambiguous commit:** A commit whose success cannot be determined because of a timeout, disconnect, or equivalent failure.
- **Publish ID:** Application-supplied globally unique identifier that remains unchanged across safe retries.

## 5. Delivery Guarantees and Constraints

### 5.1 Guaranteed

- Every transaction is executed by one cluster pool only.
- Records within a successfully committed transaction are atomic in that cluster.
- Safe retries within the same cluster retain spec 001 transactional and idempotence guarantees.
- A transaction that begins on one cluster is never continued on another cluster.
- The strategy does not automatically retry an ambiguous commit on the alternate cluster.

### 5.2 Not Guaranteed

- Kafka transactions do not provide atomic commit across independent clusters.
- Kafka producer idempotence does not deduplicate the same logical record across clusters.
- The strategy cannot guarantee global exactly-once delivery across failover boundaries.
- Per-key ordering across clusters requires consumers or a replication layer to honour routing epoch metadata.
- Availability during failover depends on equivalent topics, authorization, schemas, and capacity existing in the secondary cluster.

Applications requiring an end-to-end global exactly-once outcome must add a durable outbox or ledger and downstream deduplication keyed by `publish.id`. That capability is not part of this specification.

## 6. Strategy Decision

The system shall use an **active-passive, health-gated strategy**:

1. The primary cluster receives all traffic during normal operation.
2. Both cluster pools are initialized at startup and monitored continuously.
3. A circuit breaker opens only after a configured consecutive-failure or time-window threshold.
4. New eligible transactions route to the secondary cluster after the failover transition completes.
5. Transactions already started on the primary complete or fail there; they never migrate.
6. Ambiguous primary commits are returned to the caller for reconciliation and do not trigger cross-cluster replay.
7. Failback is delayed until the primary passes a stabilization period.
8. Failback occurs through a new routing epoch and may be automatic or operator-approved.

Active-active publishing and dual writes are rejected because they create unresolved ordering, duplicate, and split-brain semantics without a cross-cluster coordination mechanism.

## 7. Functional Requirements

### FR-1 Cluster Pool Initialization

- The system shall configure exactly one independent cluster pool for each cluster.
- Each pool shall satisfy all requirements in spec 001.
- Both pools shall initialize at startup unless `startup.allowDegraded=true`.
- If degraded startup is allowed, at least one cluster pool must meet its minimum healthy threshold.
- Kafka client instances and producer slots shall never be shared across clusters.
- A cluster pool failure shall not directly evict or rebuild producers in the other pool.

### FR-2 Transactional ID Namespace

- Transactional IDs shall include the cluster alias in addition to the spec 001 identity fields:

```text
<serviceIdentity>-<instanceIdentifier>-<clusterAlias>-<slotIndex>
```

- `clusterAlias` shall be stable, unique within the deployment, and validated at startup.
- Transactional IDs shall never be reused across primary and secondary pools.

### FR-3 Publish Contract

- The preferred API shall accept:
  - One deterministic routing scope.
  - One globally unique publish ID.
  - A transaction callback.
  - Execution options, including whether failover is permitted.
- All records emitted by the callback shall belong to the declared routing scope.
- The publish ID shall remain stable if the caller retries the same logical publish.
- The callback shall remain deterministic and free of non-Kafka side effects, as required by spec 001.
- A request without a routing scope or publish ID shall be rejected unless compatibility mode is explicitly enabled.

### FR-4 Cluster Selection

- Cluster selection shall occur before a producer lease is acquired.
- One selection shall apply to the entire transaction attempt.
- The selected cluster alias and routing epoch shall be immutable for that attempt.
- Retriable errors may be retried on the same cluster according to spec 001.
- A retry may move to the secondary only when:
  - No transaction began on the primary, or the primary transaction was conclusively aborted.
  - The error is classified as cluster-unavailable and failover-eligible.
  - The primary circuit is open.
  - The routing scope is not blocked by an ambiguous prior result.
- Business errors, serialization errors, authorization errors, invalid topics, and other non-availability failures shall not trigger failover.

### FR-5 Failure Detection

- Pool state alone shall not cause immediate failover.
- The router shall combine:
  - Cluster pool health.
  - Failover-eligible publish failures.
  - A lightweight readiness probe that does not create application records.
- The primary circuit shall open when either:
  - Consecutive failover-eligible failures reach a configured threshold, or
  - The failure rate exceeds a configured threshold within a rolling window.
- A single producer fencing event shall be handled by the pool and shall not be treated as a cluster outage.
- Authentication, authorization, topic, and schema failures shall surface as configuration or application failures and shall not automatically open the availability circuit.

### FR-6 Failover Transition

- Only one transition may execute at a time within a router instance.
- The router shall stop assigning new eligible transactions to the primary.
- It shall wait up to `transition.drainTimeout.ms` for primary transactions already in flight.
- Conclusively failed or aborted routing scopes may move to the secondary.
- Routing scopes with an ambiguous commit shall enter `BLOCKED_AMBIGUOUS` and require reconciliation before another publish with that scope is accepted.
- On completion, the router shall increment the routing epoch and set the secondary as active.
- If the secondary is not healthy, the router shall return an unavailable outcome rather than oscillate between clusters.

### FR-7 Ambiguous Commit Handling

- An ambiguous commit shall never be automatically replayed on either cluster.
- The result shall include:
  - Publish ID.
  - Routing scope.
  - Cluster alias.
  - Routing epoch.
  - Transactional ID when available.
  - Failure class and timestamp.
- Subsequent publishes for the affected routing scope shall be blocked by default.
- The application or operator may resolve the ambiguity as:
  - `COMMITTED`: advance the scope and allow later publishes.
  - `NOT_COMMITTED`: permit an explicit retry with the original publish ID.
  - `ABANDONED`: unblock the scope while recording that delivery is unresolved.
- Resolution shall be auditable.
- A process-local resolver is sufficient for one publisher instance. Deployments with multiple publisher instances must use an external durable routing and ambiguity store.

### FR-8 Ordering

- A routing scope shall have at most one active cluster within a routing epoch.
- The router shall serialize routing transitions with new assignments for the same scope.
- The default routing scope shall be the Kafka record key.
- Unkeyed records shall use an application-supplied ordering scope or explicitly opt out of cross-cluster ordering.
- Every record shall carry the headers:

```text
publish.id
publish.cluster
publish.routing-scope
publish.routing-epoch
```

- Downstream systems that merge records from both clusters shall compare routing epochs and deduplicate by publish ID.
- If a deployment cannot provide durable routing state across publisher instances, its documented ordering guarantee is limited to one router process.

### FR-9 Failback

- The primary circuit shall first move from `OPEN` to `RECOVERING`.
- The primary must remain healthy and pass probes for `failback.stabilizationPeriod.ms`.
- No failback shall occur during `failover.cooldown.ms`.
- `failback.mode` shall support:
  - `MANUAL`: an operator approves the transition.
  - `AUTOMATIC`: the router transitions after all gates pass.
- Failback shall use the same drain, ambiguity, ordering, and epoch rules as failover.
- A failed failback shall leave the secondary active and restart the stabilization period.

### FR-10 Operator Controls

- Operators shall be able to:
  - Force routing to primary or secondary.
  - Freeze routing on the current cluster.
  - Disable automatic failover.
  - Approve manual failback.
  - Inspect and resolve ambiguous routing scopes.
- Force operations shall require a reason and actor identifier.
- Unsafe force operations that bypass health gates shall emit an audit event and a critical alert.

### FR-11 Shutdown

- Shutdown shall stop accepting new publish requests.
- In-flight transactions shall drain through their selected cluster pool.
- The router shall persist outstanding ambiguity and routing state when a durable state store is configured.
- Both cluster pools shall then perform the graceful shutdown process defined in spec 001.

## 8. Architecture

### Components

- **Multi-Cluster Publisher**
  - Exposes the application publish API.
  - Adds required record headers and returns classified outcomes.
- **Cluster Router**
  - Selects the active cluster by health, policy, routing scope, and epoch.
- **Cluster Registry**
  - Owns the primary and secondary `TransactionalProducerPool` instances.
- **Availability Circuit**
  - Evaluates failures, probes, thresholds, cooldown, and recovery.
- **Transition Coordinator**
  - Drains in-flight work and atomically changes the active routing epoch.
- **Routing State Store**
  - Tracks scope assignment and ambiguity state.
  - May be in-memory only for a single-instance deployment.
- **Reconciliation Interface**
  - Exposes ambiguous outcomes for explicit resolution.
- **Telemetry Module**
  - Emits cluster-tagged metrics, logs, traces, and audit events.

### Router States

- `STARTING`
- `PRIMARY_ACTIVE`
- `FAILING_OVER`
- `SECONDARY_ACTIVE`
- `FAILING_BACK`
- `FROZEN`
- `UNAVAILABLE`
- `STOPPING`
- `STOPPED`

### Cluster Circuit States

- `CLOSED`
- `OPEN`
- `RECOVERING`

State transitions shall use synchronized or atomic guards. Concurrent requests must observe either the old or new routing epoch, never a partially applied transition.

## 9. Public Interface Contract

The component shall provide an API conceptually equivalent to:

```text
publish(routingScope, publishId, callback, options) -> PublishResult
getRoutingStatus() -> RoutingStatus
listAmbiguousScopes() -> collection
resolveAmbiguity(routingScope, publishId, resolution, auditContext)
forceCluster(clusterAlias, auditContext)
freezeRouting(auditContext)
resumePolicyRouting(auditContext)
approveFailback(auditContext)
shutdown()
```

`PublishResult` shall distinguish:

- `COMMITTED`
- `REJECTED`
- `UNAVAILABLE`
- `AMBIGUOUS`

A committed result shall include the selected cluster and routing epoch. An ambiguous result shall never be represented as a generic retriable failure.

## 10. Publish Decision Flow

1. Validate publish ID, routing scope, callback, and options.
2. Reject the request if the scope is blocked by an ambiguous outcome.
3. Read a consistent active-cluster and routing-epoch snapshot.
4. Verify the selected cluster is permitted by policy and circuit state.
5. Acquire a lease from that cluster pool.
6. Begin and execute the transaction using spec 001 rules.
7. Add the required failover metadata headers to every record.
8. On success, return `COMMITTED` with cluster and epoch.
9. On failure:
   - Classify the transaction outcome.
   - Retry only when the outcome and spec 001 rules make retry safe.
   - Record failover-eligible availability failures.
   - Return and block the scope on an ambiguous commit.
10. Release or evict the producer according to spec 001.

## 11. Failure Scenarios and Expected Behaviour

### Scenario A: Primary Unreachable Before Transaction Start

- Record a failover-eligible failure.
- Open the circuit only when its configured threshold is met.
- If the circuit opens and the secondary is healthy, retry the request on the secondary.

### Scenario B: Primary Send Fails and Abort Succeeds

- Apply bounded same-cluster retries first.
- If retries are exhausted and the primary circuit opens, the logical publish may retry on the secondary with the same publish ID.

### Scenario C: Primary Commit Is Ambiguous

- Return `AMBIGUOUS`.
- Do not publish to the secondary.
- Block later publishes for the routing scope until reconciliation.

### Scenario D: Secondary Fails During Primary Outage

- Mark the router unavailable if neither cluster is eligible.
- Apply backpressure and return `UNAVAILABLE`.
- Do not alternate clusters on every request.

### Scenario E: Producer Is Fenced

- Let the selected cluster pool evict and replace the producer.
- Do not trigger cluster failover unless independent availability thresholds are also met.

### Scenario F: Primary Recovers Briefly

- Keep the secondary active.
- Reset the stabilization timer when any recovery probe fails.
- Fail back only after stabilization and cooldown gates pass.

### Scenario G: Router Restarts During Secondary Operation

- Restore the active cluster, epoch, and blocked scopes from the durable state store.
- If durable state is required but unavailable, start in `FROZEN` or `UNAVAILABLE`; do not assume the primary is active.

### Scenario H: Topic or ACL Missing on Secondary

- Treat the error as a secondary readiness/configuration failure.
- Keep the primary active when possible.
- If the primary is unavailable, surface `UNAVAILABLE` and alert operators.

## 12. Configuration

Required configuration keys:

- `clusters.primary.alias`
- `clusters.primary.kafkaProperties`
- `clusters.secondary.alias`
- `clusters.secondary.kafkaProperties`
- `routing.defaultCluster`
- `routing.stateStore`
- `failover.consecutiveFailureThreshold`
- `failover.failureRateThreshold`
- `failover.window.ms`
- `failover.cooldown.ms`
- `transition.drainTimeout.ms`
- `failback.mode`
- `failback.stabilizationPeriod.ms`
- `probe.interval.ms`
- `probe.timeout.ms`
- `startup.allowDegraded`

Optional configuration keys:

- `routing.compatibilityMode`
- `routing.unkeyedOrderingMode`
- `routing.ambiguityBlockTimeout.ms`
- `operator.forceOverrideEnabled`

Configuration validation shall reject:

- Equal primary and secondary aliases.
- Equal transactional ID namespaces.
- Missing bootstrap servers for either cluster.
- Automatic failback with a stabilization period shorter than the probe interval.
- A cooldown shorter than the transition drain timeout.
- Multi-instance mode with an in-memory routing state store.

## 13. Observability

### Metrics

- `cluster_publish_total{cluster,outcome}`
- `cluster_publish_duration_ms{cluster}`
- `cluster_pool_health{cluster,state}`
- `cluster_circuit_state{cluster,state}`
- `cluster_failover_total{from,to,reason}`
- `cluster_failover_duration_ms{from,to}`
- `cluster_failback_total{from,to,outcome}`
- `cluster_probe_total{cluster,outcome}`
- `cluster_probe_duration_ms{cluster}`
- `cluster_ambiguous_commit_total{cluster}`
- `cluster_blocked_scope_total`
- `cluster_routing_epoch`
- `cluster_inflight_transactions{cluster}`
- All spec 001 pool metrics tagged with `cluster`.

### Logs

- Structured logs shall include publish ID, correlation ID, routing scope hash, cluster alias, routing epoch, transaction outcome, circuit state, and failure class.
- Raw routing keys and payloads shall not be logged by default.
- Every failover, failback, force, freeze, resume, and ambiguity resolution shall produce an audit log.

### Tracing

- The publish span shall include selected cluster, routing epoch, circuit state, retry count, and final outcome.
- Transition spans shall include drain duration, in-flight count, blocked-scope count, and transition reason.

### Health

Health reporting shall expose:

- Router state and active cluster.
- Current routing epoch.
- Health and circuit state for both clusters.
- Outstanding ambiguous-scope count.
- Whether routing is policy-driven, frozen, or forced.

## 14. Security and Compliance

- Each cluster may use independent TLS, SASL, and authorization settings.
- Credentials shall be loaded through secure configuration and never copied between cluster definitions implicitly.
- Probe credentials shall have only the permissions required for readiness validation.
- Publish payloads, credentials, and sensitive headers shall not appear in logs.
- Operator overrides and ambiguity resolutions shall require authenticated actor context and be retained as audit events.

## 15. Testing Strategy

### Unit Tests

- Deterministic cluster selection for each router state.
- Failure classification and circuit thresholds.
- No failover after a single fenced producer.
- No alternate-cluster retry after an ambiguous commit.
- Routing epoch increment and atomic visibility.
- Cooldown, stabilization, manual failback, and automatic failback.
- Blocking and resolution of ambiguous routing scopes.
- Configuration validation.

### Integration Tests

- Publish to primary during normal operation.
- Stop primary before transaction start and verify secondary takeover.
- Restore primary and verify gated failback.
- Remove secondary topic or ACL and verify readiness failure.
- Verify transactions never contain records sent to both clusters.
- Restart the publisher during secondary operation and restore durable routing state.
- Run multiple publisher instances against the durable routing state store.

### Fault Injection Tests

- Network partition before begin, during send, and during commit.
- Delayed commit response that creates an ambiguous result.
- Simultaneous primary degradation and secondary recovery.
- Probe flapping around threshold boundaries.
- Router crash during transition state update.
- State-store outage during publish and transition.

### Soak and Capacity Tests

- Run sustained traffic through failover and failback cycles.
- Confirm bounded latency during drain and transition.
- Confirm the secondary has sufficient throughput and partition capacity.
- Verify no growth in blocked scopes except injected ambiguity cases.

## 16. Acceptance Criteria

- Normal operation routes 100% of eligible transactions to the primary.
- A confirmed primary outage transitions new eligible traffic to a healthy secondary within the configured failover SLO.
- No transaction sends records to both clusters.
- No ambiguous commit is automatically replayed.
- Producer fencing alone does not trigger cluster failover.
- Failback cannot occur before cooldown and stabilization gates pass.
- Per-scope requests are blocked after ambiguity until explicit resolution.
- Routing state and epoch recover correctly after process restart in multi-instance mode.
- All failover, failback, and override decisions are visible in metrics, logs, traces, and audit events.
- Test evidence documents duplicates and ordering behaviour at every injected transition boundary.

## 17. Operational Runbook Requirements

Before enabling automatic failover, operators shall verify:

- Required topics exist on both clusters with compatible partition counts and configuration.
- Schemas and serializer compatibility are equivalent.
- ACLs, quotas, TLS trust, and credentials are valid.
- Secondary capacity meets the defined failover load.
- Consumers have a documented strategy for reading or merging records from both clusters.
- Alerting exists for open circuits, secondary activation, ambiguity, and unavailable state.

The runbook shall document:

- How to force and freeze routing.
- How to inspect publish ID and routing epoch metadata.
- How to reconcile an ambiguous commit.
- How to approve failback.
- How to respond when both clusters are unavailable.

## 18. Risks and Mitigations

- **Risk:** Duplicate logical records across clusters after a conclusively aborted but delayed primary write.
  - **Mitigation:** Stable publish IDs and downstream deduplication.
- **Risk:** Ordering inversion when consumers merge clusters.
  - **Mitigation:** Routing scopes, transition draining, routing epochs, and epoch-aware consumers.
- **Risk:** Split brain between multiple publisher instances.
  - **Mitigation:** Durable routing state with atomic transitions; reject in-memory state in multi-instance mode.
- **Risk:** Failover caused by a local producer fault.
  - **Mitigation:** Separate pool recovery from cluster availability thresholds.
- **Risk:** Cluster flapping.
  - **Mitigation:** Circuit thresholds, cooldown, stabilization, and optional manual failback.
- **Risk:** Secondary is operational but not equivalent.
  - **Mitigation:** Continuous readiness validation and pre-production failover exercises.
- **Risk:** Ambiguous scopes block business traffic.
  - **Mitigation:** Reconciliation tooling, alerting, and an audited operator resolution path.

## 19. Implementation Sequence

1. Add cluster-tag support to spec 001 metrics and transactional ID construction.
2. Introduce the cluster registry and router with manual selection only.
3. Add publish IDs, routing scopes, epochs, and required record headers.
4. Implement availability circuits and health-gated failover.
5. Implement ambiguity blocking and reconciliation.
6. Add durable routing state for multi-instance deployments.
7. Implement controlled failback and operator controls.
8. Complete fault-injection, soak, and operational readiness testing.

## 20. Future Enhancements

- Durable outbox and cross-cluster reconciliation ledger.
- Automated topic, ACL, and schema parity checks.
- Consumer-side epoch-aware merge library.
- Region-aware routing with more than two clusters.
- Cross-cluster replication integration with documented conflict semantics.
