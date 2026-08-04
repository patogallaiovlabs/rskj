/*
 * This file is part of RskJ
 * Copyright (C) 2019 RSK Labs Ltd.
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

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaxSizeHashMapTest {

    @Test
    void maxSizeMapTest() {
        int maxSize = 10;
        Map<Integer, Integer> maxSizeMap = new MaxSizeHashMap<>(maxSize, true);

        for(int i=0; i < 2*maxSize ; i++) {
            maxSizeMap.put(i, i);
        }
        assertEquals(maxSize, maxSizeMap.size());
    }

    @Test
    void lruEvictionRemovesLeastRecentlyAccessedEntry() {
        Map<Integer, Integer> map = new MaxSizeHashMap<>(2, MaxSizeHashMap.EvictionPolicy.LRU);

        map.put(1, 1);
        map.put(2, 2);
        map.get(1);
        map.put(3, 3);

        assertTrue(map.containsKey(1));
        assertFalse(map.containsKey(2));
        assertTrue(map.containsKey(3));
    }

    @Test
    void lfuThenAgeEvictionRemovesLeastFrequentlyUsedEntry() {
        Map<Integer, Integer> map = new MaxSizeHashMap<>(2, MaxSizeHashMap.EvictionPolicy.LFU_THEN_AGE);

        map.put(1, 1);
        map.put(2, 2);
        map.get(1);
        map.put(3, 3);

        assertTrue(map.containsKey(1));
        assertFalse(map.containsKey(2));
        assertTrue(map.containsKey(3));
    }

    @Test
    void lfuThenAgeEvictionUsesAgeAsTieBreaker() {
        Map<Integer, Integer> map = new MaxSizeHashMap<>(2, MaxSizeHashMap.EvictionPolicy.LFU_THEN_AGE);

        map.put(1, 1);
        map.put(2, 2);
        map.put(3, 3);

        assertFalse(map.containsKey(1));
        assertTrue(map.containsKey(2));
        assertTrue(map.containsKey(3));
    }
}
