# Specification: Prometheus Metrics and Grafana Dashboard

## 1. Purpose

Provide an operational Grafana dashboard for the transactional producer pool defined in spec 001.

The implementation shall publish the pool's Micrometer metrics through a Prometheus-compatible scrape endpoint, provision a version-controlled Grafana dashboard, and define alerts that make saturation, transaction failures, producer fencing, and recovery visible.

Prometheus shall scrape the application endpoint. The producer pool shall not push time series directly to Grafana or require a Pushgateway for a long-running application.

## 2. Goals

- Show pool capacity, utilization, availability, and transaction health at a glance.
- Expose latency percentiles from histogram data rather than client-side averages.
- Make failures, retries, fencing, and producer recovery easy to correlate.
- Support filtering by environment, service, instance, cluster, and pool.
- Provision the dashboard and data source from version-controlled files.
- Provide actionable alerts with documented meaning and runbook guidance.
- Keep the core producer-pool library independent of Prometheus and Grafana runtime dependencies.
- Validate metric names, PromQL queries, dashboard provisioning, and alert behaviour automatically.

## 3. Scope

### In Scope

- A Micrometer `PrometheusMeterRegistry` supplied by the host application.
- An HTTP `/metrics` endpoint in the example or observability application.
- Prometheus scrape configuration and recording rules.
- A provisioned Grafana data source and dashboard.
- Pool overview, transaction, latency, reliability, and JVM panels.
- Alert rules for unavailability, degradation, saturation, lease timeouts, aborts, fencing, and missing telemetry.
- Docker Compose support for local Kafka, Prometheus, and Grafana.
- Documentation, automated validation, and a repeatable demo workload.

### Out of Scope

- A production Grafana hosting platform.
- Production authentication, TLS termination, secrets management, and ingress.
- Long-term metrics retention and cross-region Prometheus federation.
- Logs and traces beyond links or correlation fields.
- Kafka broker exporter implementation; an existing exporter may be integrated optionally.
- Pushgateway use for the long-running producer application.
- Metrics and dashboards specific to multi-cluster routing from spec 003 until that implementation exists.

## 4. Definitions

- **Pool:** One `TransactionalProducerPool` instance.
- **Host application:** The service that owns the pool and its `MeterRegistry`.
- **Scrape endpoint:** HTTP endpoint returning Prometheus text exposition format.
- **Dashboard UID:** Stable Grafana identifier used when provisioning and linking.
- **Saturation:** Fraction of total producer slots currently leased.
- **Availability:** Whether the pool has enough healthy slots to accept work.
- **Recording rule:** Precomputed PromQL expression used to simplify dashboard and alert queries.
- **Low-cardinality label:** A label whose possible values are bounded and operationally controlled.
- **Missing telemetry:** Absence of expected scrape or pool metric series for a running target.

## 5. Architecture Decision

The observability path shall be:

```text
TransactionalProducerPool
        |
        v
Micrometer MeterRegistry in the host application
        |
        v
Prometheus-compatible /metrics endpoint
        |
        v
Prometheus scrape and recording/alert rules
        |
        v
Grafana provisioned data source and dashboard
```

The core library shall continue accepting Micrometer's `MeterRegistry` abstraction. Prometheus registry and HTTP-server dependencies shall be isolated in an example, integration, or observability Maven profile and shall not become required runtime dependencies of the core artifact.

Grafana shall query Prometheus; the pool shall not connect to Grafana.

## 6. Metric Contract

### 6.1 Naming

The implementation shall retain the logical Micrometer meter names already exposed by the pool:

| Logical meter | Type | Meaning |
|---|---|---|
| `pool_size_total` | Gauge | Configured producer slots |
| `pool_size_ready` | Gauge | Slots available to lease |
| `pool_size_leased` | Gauge | Slots currently leased |
| `lease_wait_ms` | Timer | Time waiting for a slot |
| `lease_timeout_total` | Counter | Lease acquisition timeouts |
| `transaction_begin_total` | Counter | Transactions begun |
| `transaction_commit_total` | Counter | Transactions committed |
| `transaction_abort_total` | Counter | Transactions aborted |
| `transaction_duration_ms` | Timer | End-to-end transaction duration |
| `producer_fenced_total` | Counter | Producer fencing events |
| `producer_recovery_total` | Counter | Producer recovery attempts |
| `publish_retry_total` | Counter | Publish retries by error class |

Prometheus exposition may normalize names and add standard timer suffixes such as `_seconds_count`, `_seconds_sum`, and `_seconds_bucket`. Dashboard queries shall use the actual names emitted by the pinned Micrometer Prometheus registry version; they shall not assume the logical timer name is itself a Prometheus histogram.

### 6.2 Required Enhancements

The pool telemetry shall additionally expose:

| Logical meter | Type | Required labels | Meaning |
|---|---|---|---|
| `pool_health` | Gauge | Standard labels, `state` | One-hot current state: `1` for the active state |
| `transaction_outcome_total` | Counter | Standard labels, `outcome` | Unified committed, aborted, rejected, or ambiguous outcome |
| `producer_recovery_total` | Counter | Standard labels, `outcome` | Recovery attempt result |

If `transaction_outcome_total` cannot be added without changing the public implementation in the first increment, committed and aborted outcomes may be derived from the existing counters. Rejected and ambiguous outcomes shall be shown as unavailable until their source metrics exist; the dashboard must not display fabricated zeroes.

### 6.3 Standard Labels

Every pool meter shall carry:

- `application`: stable service identity.
- `environment`: bounded deployment environment such as `local`, `test`, or `prod`.
- `instance`: runtime instance identity supplied by the deployment.
- `cluster`: Kafka cluster alias; use `default` for the spec 001 single-cluster case.
- `pool`: stable pool name within the application.

Allowed event labels include:

- `error_class`: bounded `ErrorClass` enum values.
- `outcome`: bounded outcome enum values.
- `state`: bounded pool-state enum values.

Labels shall never contain:

- Transactional ID.
- Correlation ID.
- Publish ID.
- Record key, topic payload, exception message, or stack trace.
- Thread name.
- Arbitrary client-, tenant-, or user-supplied text.

High-cardinality diagnostic values belong in logs or traces, not metric labels.

### 6.4 Histograms

- Percentile panels and alerts shall be calculated from published histogram buckets.
- Histograms shall be enabled for lease wait and transaction duration.
- Service-level buckets shall include boundaries appropriate for the configured SLOs.
- A default local profile shall include useful boundaries from 1 ms through 10 s.
- Client-side percentile gauges shall not be the only source for fleet-wide percentiles.

### 6.5 Units and Counter Semantics

- Duration metrics shall use Prometheus base units after exposition, normally seconds.
- Grafana panels may display milliseconds by applying a documented conversion or the appropriate unit.
- Counters shall only increase, except when a process restarts.
- Rates and ratios shall use `rate()` or `increase()` over a dashboard-controlled interval.
- Queries shall remain valid across counter resets.

## 7. Functional Requirements

### FR-1 Metrics Registry Integration

- The host application shall construct a `PrometheusMeterRegistry`.
- The registry shall be passed into `TransactionalProducerPool`.
- Common labels shall be applied once at application bootstrap.
- Meter filters shall reject forbidden high-cardinality labels.
- The default `SimpleMeterRegistry` convenience path shall remain available for tests and non-production use.

### FR-2 Scrape Endpoint

- The example application shall expose `GET /metrics`.
- A successful response shall return HTTP 200 and Prometheus text exposition content.
- The endpoint shall not expose credentials, producer configuration values, or record payloads.
- Production deployments shall be able to protect the endpoint at the network or application layer.
- Scraping shall not block producer leases or transaction execution.
- The local scrape interval shall default to 15 seconds.

### FR-3 Prometheus Configuration

- The repository shall provide a local Prometheus scrape configuration.
- The target shall have stable `job`, `application`, and `environment` labels.
- Prometheus shall load recording and alert rules without errors.
- The local environment shall persist data in a named volume but remain disposable.
- Scrape failure shall be distinguishable from a pool reporting zero traffic.

### FR-4 Grafana Provisioning

- The Prometheus data source shall be provisioned automatically.
- The dashboard shall be stored as JSON in version control.
- The dashboard UID shall be stable across restarts.
- Dashboard creation shall not require manual clicking or JSON import.
- Provisioned files shall contain no secrets.
- Grafana shall open the dashboard with a useful local default time range and refresh interval.

### FR-5 Dashboard Variables

The dashboard shall provide chained variables in this order:

1. `environment`
2. `application`
3. `cluster`
4. `pool`
5. `instance`

Every pool panel shall apply these filters. The `instance` variable shall support `All` so operators can compare one instance with the fleet aggregate.

### FR-6 Overview Row

The first row shall answer “is the pool healthy?” using:

- Pool health state.
- Total, ready, and leased slots.
- Saturation percentage.
- Transaction commit rate.
- Transaction success ratio.
- Abort rate.
- Lease timeout rate.
- p95 transaction duration.

Stat panels shall use explicit no-data behaviour. Missing series shall display `No data`, not healthy green or numeric zero.

### FR-7 Capacity and Saturation Row

The dashboard shall include:

- Total, ready, and leased slots over time.
- Saturation over time.
- Ready-capacity percentage.
- Lease wait p50, p95, and p99.
- Lease timeout rate.
- Per-instance saturation table.

The primary saturation expression shall be conceptually equivalent to:

```promql
sum by (environment, application, cluster, pool) (pool_size_leased{...})
/
clamp_min(
  sum by (environment, application, cluster, pool) (pool_size_total{...}),
  1
)
```

### FR-8 Transaction Row

The dashboard shall include:

- Begin, commit, and abort rates.
- Success ratio over the selected range.
- Transaction duration p50, p95, and p99.
- Transaction duration heatmap from histogram buckets.
- Active gap between begun and terminal transactions where derivable.

The success ratio denominator shall include committed plus aborted terminal outcomes. Rejected or ambiguous outcomes shall be included when their source metric exists and clearly identified in the panel description.

### FR-9 Reliability and Recovery Row

The dashboard shall include:

- Publish retry rate grouped by bounded `error_class`.
- Producer fencing events.
- Producer recovery attempt rate grouped by outcome when available.
- Pool health state transitions.
- A table of instances currently degraded or unavailable.

The row shall make isolated fencing distinguishable from a sustained cluster or pool outage.

### FR-10 Runtime and Kafka Context Row

When the host application exports standard JVM/process binders, the dashboard shall include:

- Heap used and maximum.
- CPU utilization.
- Live threads.
- GC pause rate and duration.
- Process uptime.

Kafka client or broker panels may be added only when their exporter and metric contract are documented. Optional panels shall not break the dashboard when those metrics are absent.

### FR-11 Links and Annotations

- The dashboard shall contain a link to the repository runbook.
- Panel descriptions shall state the query meaning and likely operator action.
- Deployment and chaos-test annotations may be shown when a supported annotation source exists.
- No external link shall embed secrets or unrestricted user-controlled values.

## 8. Recording Rules

Recording rules shall use a `producer_pool:` prefix and preserve the standard aggregation labels. At minimum:

```text
producer_pool:saturation:ratio
producer_pool:ready:ratio
producer_pool:transactions:commit_rate5m
producer_pool:transactions:abort_rate5m
producer_pool:transactions:success_ratio5m
producer_pool:lease_timeout:rate5m
producer_pool:lease_wait:p95
producer_pool:transaction_duration:p95
```

Rules shall aggregate histogram buckets before `histogram_quantile`. For example:

```promql
histogram_quantile(
  0.95,
  sum by (le, environment, application, cluster, pool) (
    rate(<transaction_duration_bucket>{...}[5m])
  )
)
```

The exact bucket metric name shall be set from the validated Prometheus exposition for the selected Micrometer version.

Recording rules shall not remove the ability to filter or alert by environment, application, cluster, and pool.

## 9. Alerting Requirements

Default thresholds are local examples and shall be configurable for production. Every alert shall include `severity`, `summary`, `description`, and `runbook_url` annotations.

| Alert | Default condition | For | Severity |
|---|---|---:|---|
| `ProducerPoolMetricsMissing` | Expected target or core pool series absent | 5m | warning |
| `ProducerPoolUnavailable` | Health state is unavailable or total healthy capacity is zero | 1m | critical |
| `ProducerPoolDegraded` | Health state remains degraded | 5m | warning |
| `ProducerPoolSaturated` | Saturation above 90% | 10m | warning |
| `ProducerPoolLeaseTimeouts` | Lease timeout rate above configured threshold | 5m | warning |
| `ProducerPoolHighAbortRatio` | Abort ratio above 5% with minimum traffic | 10m | warning |
| `ProducerPoolFencingDetected` | Increase in fencing events is greater than zero | 0m | critical |
| `ProducerPoolRecoveryStorm` | Recovery attempts exceed configured threshold | 5m | warning |
| `ProducerPoolHighTransactionLatency` | p95 exceeds configured SLO | 10m | warning |

Alert expressions shall:

- Include a minimum traffic gate for ratios.
- Avoid division by zero.
- Preserve labels needed to identify the affected pool.
- Distinguish missing data from zero-valued data.
- Avoid paging on a single transient scrape failure.

## 10. Security and Privacy

- The metrics endpoint shall be read-only.
- Production access shall be restricted through network policy, authentication, or a protected management listener.
- Grafana anonymous administrator access shall be allowed only in the disposable local profile.
- Default local passwords shall never be presented as production-safe values.
- No credentials shall be committed in Prometheus or Grafana provisioning files.
- Metric labels, descriptions, and annotations shall not expose Kafka credentials or application payload data.
- Dependencies and container images shall be version-pinned and included in routine vulnerability scanning.

## 11. Local Developer Experience

One documented command shall start:

- Kafka and any required topic initialization.
- A producer-pool demo workload.
- Prometheus.
- Grafana with the provisioned data source and dashboard.

The documentation shall state:

- The Grafana URL and dashboard name.
- The Prometheus targets URL.
- How to generate normal load, saturation, aborts, and recovery events safely.
- How to stop the stack and remove its local volumes.
- Which components are development-only.

Container health checks and startup dependencies shall make a fresh start deterministic. The workload shall retry bounded startup races rather than require the user to restart the stack manually.

## 12. Testing and Validation

### 12.1 Unit Tests

- All expected meters register on the supplied registry.
- Standard labels appear on every pool meter.
- Forbidden labels are rejected.
- Gauge values track pool state changes.
- Counters increment once per corresponding event.
- Timer histograms publish count, sum, and buckets.
- No duplicate meter-registration conflict occurs when supported pool identities differ.

### 12.2 Metrics Endpoint Integration Test

- Start the example application with a Prometheus registry.
- Execute at least one committed transaction and one controlled failure path.
- Scrape `/metrics`.
- Parse the response with Prometheus tooling.
- Assert the expected series, labels, counter changes, and histogram buckets.
- Assert forbidden data is absent.

### 12.3 Prometheus Validation

- Run `promtool check config` on the scrape configuration.
- Run `promtool check rules` on recording and alert rules.
- Add rule tests for normal, missing, saturated, degraded, fencing, and counter-reset cases.
- Confirm the local target becomes `UP`.

### 12.4 Grafana Validation

- Validate dashboard JSON syntax and stable UID.
- Start Grafana with provisioning enabled and confirm the dashboard is loaded through its API.
- Verify every panel data source resolves.
- Verify dashboard variables populate from fixture metrics.
- Verify core panels return data under a deterministic workload.
- Verify no-data states are explicit.

### 12.5 Manual Acceptance Exercise

1. Start the local observability stack from a clean checkout.
2. Confirm Prometheus reports the application target as `UP`.
3. Open the dashboard without importing files manually.
4. Run steady traffic and verify commit rate, slot gauges, and latency update.
5. Oversubscribe callers and verify saturation and lease wait rise.
6. Trigger a controlled producer failure and verify fencing or recovery panels update.
7. Stop the demo application and verify missing-telemetry behaviour.
8. Restart it and confirm metrics and panels recover without reconfiguration.

## 13. Acceptance Criteria

Spec 006 is complete when:

- A fresh local stack starts with one documented command.
- Prometheus successfully scrapes the demo application.
- Grafana automatically provisions the Prometheus data source and stable dashboard.
- All mandatory rows and variables from sections 7.5 through 7.10 exist.
- p95 and p99 panels use histogram buckets and valid fleet aggregation.
- Saturation, transaction success, aborts, lease timeouts, fencing, and recovery are observable.
- Missing telemetry is visibly different from a healthy zero value.
- Prometheus configuration, recording rules, and alert rules pass `promtool`.
- Automated tests cover normal, saturation, failure, missing-series, and counter-reset behaviour.
- Core library consumers are not forced to depend on Prometheus, Grafana, or an HTTP server.
- No high-cardinality or sensitive values are emitted as metric labels.
- The README links to the dashboard instructions and alert runbook.

## 14. Proposed Repository Layout

```text
src/observability/java/com/kafka/producer/observability/
    ObservabilityDemo.java
    MetricsHttpServer.java
    DemoWorkload.java

observability/
    compose.yaml
    prometheus/
        prometheus.yml
        rules/
            producer-pool-recording.yml
            producer-pool-alerts.yml
            producer-pool-alerts.test.yml
    grafana/
        provisioning/
            datasources/prometheus.yml
            dashboards/dashboards.yml
        dashboards/
            transactional-producer-pool.json
    runbooks/
        producer-pool-alerts.md
```

An equivalent layout is acceptable if core dependencies remain isolated and all provisioning assets stay version controlled.

## 15. Implementation Sequence

1. Pin the Micrometer Prometheus registry version and capture its actual exposition names.
2. Add standard pool identity labels and required health/outcome metrics.
3. Add histogram configuration for lease wait and transaction duration.
4. Implement the isolated demo application and `/metrics` endpoint.
5. Add Prometheus scrape configuration and validate it with `promtool`.
6. Add recording rules and rule tests.
7. Build and provision the Grafana dashboard row by row.
8. Add alert rules and the operator runbook.
9. Add the local Compose stack and deterministic workload controls.
10. Add endpoint, provisioning, and smoke tests.
11. Link the observability guide from the README.

## 16. Risks and Mitigations

- **Risk:** Micrometer normalizes timer names differently across registry versions.
  - **Mitigation:** Pin the registry version and test its exact exposition contract.
- **Risk:** Percentiles are mathematically invalid across instances.
  - **Mitigation:** Aggregate histogram bucket rates before calling `histogram_quantile`.
- **Risk:** Dashboard panels show zero during scrape failure.
  - **Mitigation:** Define explicit no-data states and a missing-telemetry alert.
- **Risk:** Labels create unbounded Prometheus cardinality.
  - **Mitigation:** Enforce the allow-list in section 6.3 with meter filters and tests.
- **Risk:** Alert ratios flap during low traffic.
  - **Mitigation:** Add minimum-volume gates and sustained `for` windows.
- **Risk:** The observability stack leaks dependencies into the core artifact.
  - **Mitigation:** Isolate it in a Maven profile or example module and inspect the default dependency tree.
- **Risk:** Grafana JSON becomes difficult to review.
  - **Mitigation:** Keep stable panel IDs, deterministic formatting, meaningful titles, and automated provisioning tests.
- **Risk:** Local anonymous Grafana settings are copied into production.
  - **Mitigation:** Label them development-only and document production security requirements.

## 17. Future Extensions

- Multi-cluster routing and epoch panels from spec 003.
- Broker and topic health using a documented Kafka exporter.
- Exemplars linking transaction latency to distributed traces.
- Log links filtered by application, instance, cluster, and pool.
- Grafana dashboard screenshots in release evidence.
- Remote-write and long-term retention guidance.
- SLO burn-rate alerts for transaction success and latency.
