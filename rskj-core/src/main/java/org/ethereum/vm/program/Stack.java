/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
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
import org.ethereum.vm.program.listener.ProgramListener;
import org.ethereum.vm.program.listener.ProgramListenerAware;

import java.util.Arrays;
import java.util.EmptyStackException;

/**
 * EVM operand stack.
 *
 * <p>A Stack belongs to exactly one {@link Program}, which is executed by a single thread
 * (nested calls and parallel sublists each create their own Program and Stack). The hot
 * accessors below therefore read and write Vector's protected storage directly instead of
 * going through its synchronized methods. They mirror java.util.Stack / java.util.Vector
 * semantics: same element order, same modCount bumps on structural changes, and the same
 * exception types (EmptyStackException from pop/peek on an empty stack,
 * ArrayIndexOutOfBoundsException from get/set out of range). Methods not overridden here
 * keep Vector's implementation, which operates on the same fields.
 */
public class Stack extends java.util.Stack<DataWord> implements ProgramListenerAware {

    private static final long serialVersionUID = -227393866144006913L;
    private ProgramListener traceListener;

    @Override
    public void setTraceListener(ProgramListener listener) {
        this.traceListener = listener;
    }

    @Override
    public DataWord pop() {
        if (traceListener != null) {
            traceListener.onStackPop();
        }
        int len = elementCount;
        if (len == 0) {
            throw new EmptyStackException();
        }
        int index = len - 1;
        DataWord obj = (DataWord) elementData[index];
        modCount++;
        elementCount = index;
        elementData[index] = null;
        return obj;
    }

    @Override
    public DataWord push(DataWord item) {
        if (traceListener != null) {
            traceListener.onStackPush(item);
        }
        modCount++;
        int count = elementCount;
        Object[] data = elementData;
        if (count == data.length) {
            data = grow(count + 1);
        }
        data[count] = item;
        elementCount = count + 1;
        return item;
    }

    @Override
    public DataWord peek() {
        int len = elementCount;
        if (len == 0) {
            throw new EmptyStackException();
        }
        return (DataWord) elementData[len - 1];
    }

    @Override
    public boolean empty() {
        return elementCount == 0;
    }

    @Override
    public DataWord get(int index) {
        if (index >= elementCount) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        return (DataWord) elementData[index];
    }

    @Override
    public DataWord set(int index, DataWord element) {
        if (index >= elementCount) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        DataWord oldValue = (DataWord) elementData[index];
        elementData[index] = element;
        return oldValue;
    }

    @Override
    public int size() {
        return elementCount;
    }

    @Override
    public boolean isEmpty() {
        return elementCount == 0;
    }

    public void swap(int from, int to) {
        if (isAccessible(from) && isAccessible(to) && (from != to)) {
            if (traceListener != null) {
                traceListener.onStackSwap(from, to);
            }
            Object[] data = elementData;
            Object tmp = data[from];
            data[from] = data[to];
            data[to] = tmp;
        }
    }

    private boolean isAccessible(int from) {
        return from >= 0 && from < elementCount;
    }

    private Object[] grow(int minCapacity) {
        int oldCapacity = elementData.length;
        int newCapacity = Math.max(minCapacity, oldCapacity == 0 ? 10 : oldCapacity * 2);
        elementData = Arrays.copyOf(elementData, newCapacity);
        return elementData;
    }

}
