package org.mvel2.execution;

import org.mvel2.ExecutionContext;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

public class ExecutionHashMap<K, V> extends LinkedHashMap<K, V> implements ExecutionObject {

    private static final Comparator COMP_BY_VALUE_STRING_ASC = (o1, o2) -> {
        String first = String.valueOf(((Entry) o1).getValue());
        String second = String.valueOf(((Entry) o2).getValue());
        return first.compareTo(second);
    };
    private static final Comparator COMP_BY_VALUE_STRING_DESC = (o1, o2) -> {
        String first = String.valueOf(((Entry) o1).getValue());
        String second = String.valueOf(((Entry) o2).getValue());
        return second.compareTo(first);
    };

    private static final Comparator COMP_BY_VALUE_DOUBLE_ASC = (o1, o2) -> {
        Double first = Double.parseDouble(String.valueOf(((Entry) o1).getValue()));
        Double second = Double.parseDouble(String.valueOf(((Entry) o2).getValue()));
        return first.compareTo(second);
    };

    private static final Comparator COMP_BY_VALUE_DOUBLE_DESC = (o1, o2) -> {
        Double first = Double.parseDouble(String.valueOf(((Entry) o1).getValue()));
        Double second = Double.parseDouble(String.valueOf(((Entry) o2).getValue()));
        return second.compareTo(first);
    };

    private final ExecutionContext executionContext;

    private final int id;

    private long memorySize = 0;
    private boolean unmodifiable = false;
    private final String errorUnmodifiableMap = "This Map is unmodifiable";

    public ExecutionHashMap(int size, ExecutionContext executionContext) {
        super(size);
        this.executionContext = executionContext;
        this.id = executionContext.nextId();
    }

    @Override
    public V put(K key, V value) {
        checkModifiable();
        if (containsKey(key)) {
            V prevValue = this.get(key);
            this.memorySize -= this.executionContext.onValRemove(this, key, prevValue);
        }
        V res;
        if (value != null) {
            res = super.put(key, value);
            this.memorySize += this.executionContext.onValAdd(this, key, value);
        } else {
            res = super.remove(key);
        }
        return res;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        checkModifiable();
        Set<Entry<K, V>> executionEntries = new LinkedHashSet<>();
        for (Entry<K, V> entry : super.entrySet()) {
            executionEntries.add(new ExecutionEntry<>(entry.getKey(), entry.getValue()));
        }
        return executionEntries;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        checkModifiable();
        super.putAll(m);
        for (Map.Entry<? extends K, ? extends V> val : m.entrySet()) {
            this.memorySize += this.executionContext.onValAdd(this, val.getKey(), val.getValue());
        }
    }

    @Override
    public V putIfAbsent(K key, V value) {
        checkModifiable();
        if (!super.containsKey(key)) {
            this.memorySize += this.executionContext.onValAdd(this, key, value);
        }
        return super.putIfAbsent(key, value);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        checkModifiable();
        boolean result = super.replace(key, oldValue, newValue);
        if (result) {
            this.memorySize -= this.executionContext.onValRemove(this, key, oldValue);
            this.memorySize += this.executionContext.onValAdd(this, key, newValue);
        }
        return result;
    }

    @Override
    public V replace(K key, V value) {
        checkModifiable();
        this.memorySize += this.executionContext.onValAdd(this, key, value);
        return super.replace(key, value);
    }

    @Override
    public V remove(Object key) {
        checkModifiable();
        if (containsKey(key)) {
            V value = this.get(key);
            this.memorySize -= this.executionContext.onValRemove(this, key, value);
        }
        return super.remove(key);
    }

    @Override
    public int getExecutionObjectId() {
        return id;
    }

    @Override
    public long memorySize() {
        return memorySize;
    }

    public Object toUnmodifiable() {
        ExecutionHashMap newMap = this.slice();
        newMap.unmodifiable = true;
        return newMap;
    }

    public void unmodifiable() {
        this.unmodifiable = true;
    }

    @Override
    public ExecutionArrayList<V> values() {
        return new ExecutionArrayList<>(super.values(), this.executionContext);
    }

    public ExecutionArrayList<K> keys() {
        return new ExecutionArrayList<>(super.keySet(), this.executionContext);
    }

    public void sortByValue() {
        sortByValue(true);
    }

    public void sortByValue(boolean asc) {
        checkModifiable();
        Map valueSort = sortMapByValue((HashMap) super.clone(), asc);
        valueSort.keySet().forEach(this::remove);
        this.putAll(valueSort);
    }

    public void sortByKey() {
        this.sortByKey(true);
    }

    public void sortByKey(boolean asc) {
        checkModifiable();
        ExecutionArrayList keys = this.keys();
        keys.sort(asc);
        HashMap keysMapSort = new LinkedHashMap();
        keys.forEach(k -> keysMapSort.put(k, this.get(k)));
        keysMapSort.keySet().forEach(this::remove);
        this.putAll(keysMapSort);
    }

    public ExecutionHashMap slice() {
        return new ExecutionHashMap<>(this.size(), this.executionContext);
    }

    private <K, V extends Comparable<? super V>> Map<K, V> sortMapByValue(Map<K, V> map, boolean asc) {
        checkModifiable();
        boolean isString = this.values().validateClazzInArrayIsOnlyString();
        Comparator<? super Map.Entry> cmp =
                isString ?
                        asc ? COMP_BY_VALUE_STRING_ASC : COMP_BY_VALUE_STRING_DESC :
                        asc ? COMP_BY_VALUE_DOUBLE_ASC : COMP_BY_VALUE_DOUBLE_DESC;
        return map.entrySet()
                .stream()
                .sorted(cmp)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));
    }

    private void checkModifiable() {
        if (unmodifiable) throw new UnsupportedOperationException("This ExecutionHashMap is unmodifiable");
    }
}

