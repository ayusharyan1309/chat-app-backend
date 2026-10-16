package com.ayush.chat.cache;

import com.ayush.chat.cache.memory.InMemoryCacheStore;

class InMemoryCacheStoreTest extends CacheStoreConformanceTest {

    @Override
    protected CacheStore createCache() {
        return new InMemoryCacheStore(10_000);
    }
}
