# kafka-consumer
Decouple Consumption and Processing model Kafka consumer

# Performance harness

Performance scenarios for the pooled transactional producer are under:

- `src/perf/java/com/kafka/producer/perf`

Run a scenario with:

```bash
mvn test -Pperf -Dscenario=S-04
```

Raw CSV and summary output are written to `perf-results/`.

# Setup 
docker run -d --name zookeeper --network kafka-net zookeeper:latest

docker run -d --name kafka --network kafka-net --publish 9092:9092 --publish 7203:7203 --env KAFKA_ADVERTISED_HOST_NAME=172.18.0.1 --env ZOOKEEPER_IP=zookeeper ches/kafka

where 172.18.0.1 is my machine IP
