package com.ayush.chat.bus;

import com.ayush.chat.bus.memory.InMemoryEventBus;

import java.time.Duration;

class InMemoryEventBusTest extends EventBusConformanceTest {

    @Override
    protected EventBus createBus() {
        return new InMemoryEventBus(4);
    }

    @Override
    protected Duration quietPeriod() {
        return Duration.ofMillis(200);
    }
}
