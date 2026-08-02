/**
 * SPDX-FileCopyrightText: Copyright 2022 ThingsBoard, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.mvel2.execution;

import org.mvel2.ExecutionContext;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ExecutionLinkedHashSet<E> extends LinkedHashSet<E> implements ExecutionObject {

    private final ExecutionContext executionContext;

    private long memorySize = 0;

    public ExecutionLinkedHashSet(ExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    public ExecutionLinkedHashSet(Set<? extends E> s, ExecutionContext executionContext) {
        super(Math.max(2 * s.size(), 11), 0.75F);
        this.executionContext = executionContext;
        this.addAll(s);
    }

    @Override
    public boolean add(E e) {
        if (super.add(e)) {
            this.memorySize += this.executionContext.onValAdd(this, e);
            return true;
        }
        return false;
    }

    @Override
    public boolean remove(Object o) {
        if (super.remove(o)) {
            this.memorySize -= this.executionContext.onValRemove(this, o);
            return true;
        }
        return false;
    }

    @Override
    public void clear() {
        for (E val : this) {
            this.executionContext.onValRemove(this, val);
        }
        super.clear();
        this.memorySize = 0;
    }

    public void sort() {
        this.sort(true);
    }

    public void sort(boolean asc) {
        ExecutionArrayList<E> list = this.toList();
        list.sort(asc);

        // Очистити і перезаписати
        this.clear();
        this.addAll(list);
    }

    public ExecutionLinkedHashSet<E> toSorted() {
        return this.toSorted(true);
    }

    public ExecutionLinkedHashSet<E> toSorted(boolean asc) {
        ExecutionArrayList<E> list = this.toList();
        list.sort(asc);
        Set<E> newSet = new LinkedHashSet<>(list);
        return new ExecutionLinkedHashSet<>(newSet, this.executionContext);
    }

    @SuppressWarnings("unchecked")
    public ExecutionArrayList<E> toList() {
        return new ExecutionArrayList<>((List<E>)(List<?>) Arrays.asList(this.toArray()), this.executionContext);
    }


    @Override
    public long memorySize() {
        return this.memorySize;
    }

    public Set<E> toUnmodifiable() {
        return ExecutionCollections.unmodifiableExecutionSet(this, this.executionContext);
    }
}
