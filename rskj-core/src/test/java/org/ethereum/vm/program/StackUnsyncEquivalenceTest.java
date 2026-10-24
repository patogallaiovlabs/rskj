/*
 * This file is part of RskJ
 * Copyright (C) 2026 RSK Labs Ltd.
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

package org.ethereum.vm.program;

import org.ethereum.vm.DataWord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.EmptyStackException;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the unsynchronized operand {@link Stack} to the behavior of {@link java.util.Stack}:
 * same contents, same return values and same exception types.
 */
class StackUnsyncEquivalenceTest {

    private static Object outcome(Callable<Object> op) {
        try {
            return op.call();
        } catch (Exception e) {
            return e.getClass();
        }
    }

    private static void assertSameState(java.util.Stack<DataWord> ref, Stack stack) {
        assertEquals(ref.size(), stack.size());
        assertEquals(ref.isEmpty(), stack.isEmpty());
        assertEquals(ref.empty(), stack.empty());
        assertEquals(new ArrayList<>(ref), new ArrayList<>(stack));
        assertEquals(ref, stack);
    }

    @Test
    void randomOperationsMatchJavaUtilStack() {
        for (int seed = 0; seed < 20; seed++) {
            Random random = new Random(seed);
            java.util.Stack<DataWord> ref = new java.util.Stack<>();
            Stack stack = new Stack();
            if (seed % 2 == 0) {
                stack.ensureCapacity(1024);
                ref.ensureCapacity(1024);
            }
            for (int i = 0; i < 20_000; i++) {
                int op = random.nextInt(8);
                int size = ref.size();
                int idx = random.nextInt(size + 6) - 3;
                int idx2 = random.nextInt(size + 6) - 3;
                switch (op) {
                    case 0:
                    case 1: {
                        if (size > 1100) {
                            break;
                        }
                        DataWord w = DataWord.valueOf(random.nextInt());
                        assertSame(ref.push(w), stack.push(w));
                        break;
                    }
                    case 2:
                        assertEquals(outcome(ref::pop), outcome(stack::pop));
                        break;
                    case 3:
                        assertEquals(outcome(ref::peek), outcome(stack::peek));
                        break;
                    case 4:
                        assertEquals(outcome(() -> ref.get(idx)), outcome(() -> stack.get(idx)));
                        break;
                    case 5: {
                        DataWord w = DataWord.valueOf(random.nextInt());
                        assertEquals(outcome(() -> ref.set(idx, w)), outcome(() -> stack.set(idx, w)));
                        break;
                    }
                    case 6: {
                        // DUP-like: copy an element to the top
                        Object a = outcome(() -> ref.push(ref.get(idx)));
                        Object b = outcome(() -> stack.push(stack.get(idx)));
                        assertEquals(a, b);
                        break;
                    }
                    default: {
                        // reference swap: the original get + set + set implementation
                        if (idx >= 0 && idx < size && idx2 >= 0 && idx2 < size && idx != idx2) {
                            DataWord tmp = ref.get(idx);
                            ref.set(idx, ref.set(idx2, tmp));
                        }
                        stack.swap(idx, idx2);
                        break;
                    }
                }
                assertSameState(ref, stack);
            }
        }
    }

    @Test
    void underflowAndOutOfRangeExceptions() {
        Stack stack = new Stack();
        assertThrows(EmptyStackException.class, stack::pop);
        assertThrows(EmptyStackException.class, stack::peek);
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> stack.get(0));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> stack.get(-1));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> stack.set(0, DataWord.ZERO));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> stack.set(-1, DataWord.ZERO));
        assertTrue(stack.isEmpty());
        assertTrue(stack.empty());

        stack.push(DataWord.ONE);
        // index beyond count but within capacity must still fail
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> stack.get(1));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> stack.set(1, DataWord.ZERO));
        // swap with an inaccessible index is a no-op
        stack.swap(0, 1);
        stack.swap(-1, 0);
        assertEquals(1, stack.size());
        assertEquals(DataWord.ONE, stack.peek());
        assertEquals(DataWord.ONE, stack.pop());
        assertThrows(EmptyStackException.class, stack::pop);
    }

    @Test
    void popReleasesSlotAndGrowthKeepsContents() {
        Stack stack = new Stack();
        stack.trimToSize();
        List<DataWord> expected = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            DataWord w = DataWord.valueOf(i);
            stack.push(w);
            expected.add(w);
        }
        assertEquals(expected, new ArrayList<>(stack));
        stack.pop();
        expected.remove(expected.size() - 1);
        assertEquals(expected, new ArrayList<>(stack));
        stack.clear();
        assertTrue(stack.isEmpty());
        stack.push(DataWord.ONE);
        assertEquals(DataWord.ONE, stack.firstElement());
    }

    @Test
    void structuralChangesInvalidateIterators() {
        Stack stack = new Stack();
        stack.push(DataWord.ONE);
        stack.push(DataWord.ZERO);
        Iterator<DataWord> it = stack.iterator();
        it.next();
        stack.push(DataWord.ONE);
        assertThrows(ConcurrentModificationException.class, it::next);

        Iterator<DataWord> it2 = stack.iterator();
        it2.next();
        stack.pop();
        assertThrows(ConcurrentModificationException.class, it2::next);
    }
}
