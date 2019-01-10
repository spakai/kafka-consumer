# kafka-consumer
Decouple Consumption and Processing model Kafka consumer

# Setup 
docker run -d --name zookeeper --network kafka-net zookeeper:latest

docker run -d --name kafka --network kafka-net --publish 9092:9092 --publish 7203:7203 --env KAFKA_ADVERTISED_HOST_NAME=172.18.0.1 --env ZOOKEEPER_IP=zookeeper ches/kafka

where 172.18.0.1 is my machine IP
