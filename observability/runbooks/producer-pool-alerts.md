# Producer Pool Alert Runbook

Use the dashboard filters from the alert labels before changing pool or Kafka configuration. Preserve the alert time range when pivoting to logs.

## Metrics missing

1. Check the Prometheus target and its last scrape error.
2. Request `/health` and `/metrics` directly from the protected management network.
3. Confirm the application is running and the observability registry is enabled.
4. Treat missing telemetry as unknown health, not as zero traffic.

## Pool unavailable or degraded

1. Check `pool_health`, ready, leased, and total slot panels.
2. Correlate the transition with fencing and recovery attempts.
3. Verify Kafka reachability, authentication, authorization, and transaction coordinator health.
4. If the pool is draining or stopped, confirm whether a deployment or shutdown is in progress.
5. Do not retry ambiguous commits on another producer or cluster without reconciliation.

## Saturation or lease timeouts

1. Compare leased slots, lease-wait p95/p99, transaction duration, and caller traffic.
2. Look for slow brokers, oversized callbacks, or callers holding leases outside `executeInTransaction`.
3. Reduce offered concurrency or transaction duration before increasing pool size.
4. Increase pool size only after confirming broker and JVM capacity.

## High abort ratio

1. Group retries by `error_class` and inspect application logs for the same interval.
2. Separate serialization or callback failures from Kafka availability failures.
3. Check broker request latency, ISR health, and transaction timeouts.
4. Do not count ambiguous outcomes as ordinary aborts.

## Producer fencing

1. Treat any event as urgent.
2. Find all processes using the affected service and instance identity.
3. Confirm every live pool slot has a globally unique `transactional.id`.
4. Stop duplicate or stale instances before allowing replacement producers to recover.
5. Reconcile any commit whose outcome is unknown.

## Recovery storm

1. Compare recovery attempts by outcome with fencing, aborts, and Kafka availability.
2. Confirm the replacement concurrency limit is active.
3. Check whether credentials, broker addresses, or transaction coordinator availability changed.
4. Avoid restart loops; preserve logs from the first failure.

## High transaction latency

1. Compare transaction p95/p99 with lease wait to separate queueing from Kafka time.
2. Inspect JVM CPU, heap, GC, broker request latency, and network conditions.
3. Confirm payload size and records per transaction have not changed.
4. Adjust the production SLO threshold only from measured workload evidence.
