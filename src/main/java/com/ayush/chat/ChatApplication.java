package com.ayush.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.cassandra.CassandraAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Infrastructure clients (Mongo, Cassandra, Kafka, Redis) are created by connectors at runtime,
 * so Spring Boot's auto-configuration for them is disabled.
 */
@SpringBootApplication(exclude = {
        MongoAutoConfiguration.class,
        CassandraAutoConfiguration.class,
        KafkaAutoConfiguration.class,
        RedisAutoConfiguration.class
})
@EnableScheduling
@EnableJpaRepositories(basePackages = "com.ayush.chat", considerNestedRepositories = true)
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
