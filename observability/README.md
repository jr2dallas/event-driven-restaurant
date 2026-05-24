# Observability stack

Prometheus + Grafana are included in `docker-compose.yml` and start automatically alongside the application services. No manual setup is required.

## Access

| Service | URL | Credentials |
|---|---|---|
| Grafana | http://localhost:3001 | admin / admin |
| Prometheus | http://localhost:9090 | — |

## Architecture

```
restaurant-service :8080/actuator/prometheus  ──┐
                                                 ├──► Prometheus :9090 ──► Grafana :3001
kitchen-service    :8081/actuator/prometheus  ──┘
```

Prometheus scrapes both services every **15 seconds** via the Micrometer `prometheus` actuator endpoint. Grafana auto-provisions the datasource and the dashboard from files mounted at startup — no manual configuration needed.

## Dashboard — Restaurant Overview

The dashboard is defined in `grafana/dashboards/restaurant-dashboard.json` and auto-loaded under **Restaurant → Restaurant Overview**.

![Grafana dashboard](../docs/grafana.jpg)

### Panels

| Row | Panel | Query |
|---|---|---|
| **Live state** | Active sessions | `restaurant_sessions_active` |
| | Clients queued | `restaurant_clients_queued` |
| | Active waiters | `restaurant_waiters_active` |
| | Kitchen orders in progress | `sum(kitchen_orders_active)` |
| | Total orders sent | `restaurant_orders_sent_total` |
| | Total orders processed | `sum(kitchen_orders_processed_total)` |
| **Throughput** | Order rate (sent vs processed) | `rate(...[1m])` |
| | Sessions / waiters / queue over time | gauge time series |
| **JVM** | Heap + non-heap memory | `jvm_memory_used_bytes` |
| | CPU usage | `process_cpu_usage` |
| | Thread count | `jvm_threads_live_threads` |
| **HTTP** | Request rate by endpoint | `rate(http_server_requests_seconds_count[1m])` |
| | Latency p50 / p99 | `histogram_quantile(0.99, ...)` |
| **Kafka** | Consumer lag per partition | `kafka_consumer_fetch_manager_records_lag` |
| | Records consumed per second | `rate(...[1m])` |

The dashboard auto-refreshes every **10 seconds** and defaults to a **30-minute** time window.

## Business metrics

Both services expose custom Micrometer metrics registered at startup.

**restaurant-service**

| Metric | Type | Description |
|---|---|---|
| `restaurant_sessions_active` | Gauge | Live client sessions in the restaurant |
| `restaurant_clients_queued` | Gauge | Clients waiting at the entrance queue |
| `restaurant_waiters_active` | Gauge | Active waiter workers on the floor |
| `restaurant_orders_sent_total` | Counter | Orders successfully published to Kafka |
| `restaurant_orders_failed_total` | Counter | Orders that failed to publish |
| `restaurant_clients_seated_total` | Counter | Total clients seated at a table |

**kitchen-service**

| Metric | Type | Description |
|---|---|---|
| `kitchen_orders_active` | Gauge | Orders currently being cooked (per instance) |
| `kitchen_orders_processed_total` | Counter | Orders fully cooked and dispatched |

## Prometheus scrape config

```yaml
# observability/prometheus/prometheus.yml
scrape_configs:
  - job_name: 'restaurant-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['restaurant-service:8080']

  - job_name: 'kitchen-service'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['kitchen-service:8081']
```

> With `deploy.replicas: 2` for kitchen-service, Docker DNS round-robins between instances. For per-instance metrics, replace the target with explicit container hostnames (`kitchen-service-1`, `kitchen-service-2`).

## Adding a new dashboard

1. Create your dashboard in the Grafana UI at http://localhost:3001
2. Export it: **Dashboard settings → JSON Model → Copy to clipboard**
3. Save it as a `.json` file in `observability/grafana/dashboards/`
4. Restart Grafana (or wait up to 30 seconds for the provisioner to pick it up)

The `dashboard.yml` provisioner scans `/var/lib/grafana/dashboards` every 30 seconds, so new files are loaded automatically without rebuilding the container.
