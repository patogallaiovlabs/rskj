/*
 * This file is part of RskJ
 * Copyright (C) 2018 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package co.rsk.util;

import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Objects;

/**
 * Note: This should be replaced with a library like Caffeine/Guava.
 */
public class MaxSizeHashMap<K, V> extends LinkedHashMap<K, V> {
    private static final long serialVersionUID = -4738743439913276608L;

    public enum EvictionPolicy {
        FIFO,
        LRU,
        LFU_THEN_AGE
    }

    private final int maxSize;
    private final EvictionPolicy evictionPolicy;
    private final Map<K, UsageMetadata> usageByKey;
    private long touchSequence;

    public MaxSizeHashMap(int maxSize, boolean accessOrder) {
        this(maxSize, accessOrder ? EvictionPolicy.LRU : EvictionPolicy.FIFO);
    }

    public MaxSizeHashMap(int maxSize, EvictionPolicy evictionPolicy) {
        super(Math.max(1, maxSize), 0.75f, evictionPolicy == EvictionPolicy.LRU);
        this.maxSize = maxSize;
        this.evictionPolicy = Objects.requireNonNull(evictionPolicy);
        this.usageByKey = new LinkedHashMap<>();
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        if (evictionPolicy == EvictionPolicy.LFU_THEN_AGE) {
            return false;
        }

        return size() > maxSize;
    }

    @Override
    public V put(K key, V value) {
        if (maxSize <= 0) {
            return null;
        }

        boolean keyExists = super.containsKey(key);

        if (!keyExists && evictionPolicy == EvictionPolicy.LFU_THEN_AGE && size() >= maxSize) {
            evictLeastUsedKey();
        }

        V previousValue = super.put(key, value);

        if (evictionPolicy == EvictionPolicy.LFU_THEN_AGE) {
            touch(key);
        }

        return previousValue;
    }

    @Override
    public V get(Object key) {
        V value = super.get(key);
        if (value != null && evictionPolicy == EvictionPolicy.LFU_THEN_AGE) {
            @SuppressWarnings("unchecked")
            K typedKey = (K) key;
            touch(typedKey);
        }

        return value;
    }

    @Override
    public V remove(Object key) {
        if (evictionPolicy == EvictionPolicy.LFU_THEN_AGE) {
            usageByKey.remove(key);
        }

        return super.remove(key);
    }

    @Override
    public void clear() {
        super.clear();

        if (evictionPolicy == EvictionPolicy.LFU_THEN_AGE) {
            usageByKey.clear();
        }
    }

    private void evictLeastUsedKey() {
        K keyToRemove = null;
        UsageMetadata metadataToRemove = null;

        for (Entry<K, UsageMetadata> entry : usageByKey.entrySet()) {
            UsageMetadata candidate = entry.getValue();
            if (metadataToRemove == null
                    || candidate.accessCount < metadataToRemove.accessCount
                    || (candidate.accessCount == metadataToRemove.accessCount
                    && candidate.lastTouchSequence < metadataToRemove.lastTouchSequence)) {
                keyToRemove = entry.getKey();
                metadataToRemove = candidate;
            }
        }

        if (keyToRemove != null) {
            super.remove(keyToRemove);
            usageByKey.remove(keyToRemove);
        }
    }

    private void touch(K key) {
        UsageMetadata metadata = usageByKey.computeIfAbsent(key, ignored -> new UsageMetadata());
        metadata.accessCount++;
        metadata.lastTouchSequence = ++touchSequence;
    }

    private static class UsageMetadata {
        private long accessCount;
        private long lastTouchSequence;
    }
}
