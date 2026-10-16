package com.ayush.chat.store;

import com.ayush.chat.store.memory.InMemoryMessageStore;

class InMemoryMessageStoreTest extends MessageStoreConformanceTest {

    @Override
    protected MessageStore createStore() {
        return new InMemoryMessageStore();
    }
}
