/**
 * SPDX-FileCopyrightText: Copyright 2022 ThingsBoard, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.mvel2.execution;

import org.mvel2.ExecutionContext;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class ExecutionCollections {

    public static <T> Collection<T> unmodifiableExecutionCollection(Collection<? extends T> c, ExecutionContext executionContext) {
        if (c.getClass() == UnmodifiableExecutionCollection.class) {
            return (Collection<T>) c;
        }
        return new UnmodifiableExecutionCollection<>(c, executionContext);
    }

    static class UnmodifiableExecutionCollection<E> implements Collection<E>, ExecutionObject, Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 7492637697370124347L;

        /** @serial */
        @SuppressWarnings("serial") // Conditionally serializable
        final Collection<? extends E> c;

        protected final ExecutionContext executionContext;

        UnmodifiableExecutionCollection(Collection<? extends E> c, ExecutionContext executionContext) {
            if (c==null)
                throw new NullPointerException();
            if (!(c instanceof ExecutionObject)) {
                throw new IllegalArgumentException("Collection is not ExecutionObject");
            }
            this.executionContext = executionContext;
            this.c = c;
        }

        protected String collectionTitle() {
            return "Collection";
        }

        protected UnsupportedOperationException unmodifiableException() {
            return new UnsupportedOperationException(collectionTitle() + " is unmodifiable");
        }

        public int size()                          {return c.size();}
        public boolean isEmpty()                   {return c.isEmpty();}
        public boolean contains(Object o)          {return c.contains(o);}
        public Object[] toArray()                  {return c.toArray();}
        public <T> T[] toArray(T[] a)              {return c.toArray(a);}
        public <T> T[] toArray(IntFunction<T[]> f) {return c.toArray(f);}
        public String toString()                   {return c.toString();}

        public Iterator<E> iterator() {
            return new Iterator<>() {
                private final Iterator<? extends E> i = c.iterator();

                public boolean hasNext() {return i.hasNext();}
                public E next()          {return i.next();}
                public void remove() {
                    throw unmodifiableException();
                }
                @Override
                public void forEachRemaining(Consumer<? super E> action) {
                    // Use backing collection version
                    i.forEachRemaining(action);
                }
            };
        }

        public boolean add(E e) {
            throw unmodifiableException();
        }
        public boolean remove(Object o) {
            throw unmodifiableException();
        }

        public boolean containsAll(Collection<?> coll) {
            return c.containsAll(coll);
        }
        public boolean addAll(Collection<? extends E> coll) {
            throw unmodifiableException();
        }
        public boolean removeAll(Collection<?> coll) {
            throw unmodifiableException();
        }
        public boolean retainAll(Collection<?> coll) {
            throw unmodifiableException();
        }
        public void clear() {
            throw unmodifiableException();
        }

        // Override default methods in Collection
        @Override
        public void forEach(Consumer<? super E> action) {
            c.forEach(action);
        }
        @Override
        public boolean removeIf(Predicate<? super E> filter) {
            throw unmodifiableException();
        }
        @SuppressWarnings("unchecked")
        @Override
        public Spliterator<E> spliterator() {
            return (Spliterator<E>)c.spliterator();
        }
        @SuppressWarnings("unchecked")
        @Override
        public Stream<E> stream() {
            return (Stream<E>)c.stream();
        }
        @SuppressWarnings("unchecked")
        @Override
        public Stream<E> parallelStream() {
            return (Stream<E>)c.parallelStream();
        }

        @Override
        public long memorySize() {
            return ((ExecutionObject)c).memorySize();
        }
    }

    public static <T> Set<T> unmodifiableExecutionSet(Set<? extends T> s, ExecutionContext executionContext) {
        // Not checking for subclasses because of heap pollution and information leakage.
        if (s.getClass() == UnmodifiableExecutionSet.class) {
            return (Set<T>) s;
        }
        return new UnmodifiableExecutionSet<>(s, executionContext);
    }

    /**
     * @serial include
     */
    static class UnmodifiableExecutionSet<E> extends UnmodifiableExecutionCollection<E>
            implements Set<E>, Serializable {
        @java.io.Serial
        private static final long serialVersionUID = -9215047833775013803L;

        private static <T> Set<? extends T> checkExecutionSet(Set<? extends T> s, ExecutionContext executionContext) {
            if (!(s instanceof ExecutionContext)) {
                s = new ExecutionLinkedHashSet<>(s, executionContext);
            }
            return s;
        }

        UnmodifiableExecutionSet(Set<? extends E> s, ExecutionContext executionContext)     {
            super(UnmodifiableExecutionSet.checkExecutionSet(s, executionContext), executionContext);
        }

        @Override
        protected String collectionTitle() {
            return "Set";
        }

        public boolean equals(Object o) {return o == this || c.equals(o);}
        public int hashCode()           {return c.hashCode();}
    }

    public static <T> List<T> unmodifiableExecutionList(List<? extends T> list, ExecutionContext executionContext) {
        if (list.getClass() == UnmodifiableExecutionList.class) {
            return (List<T>) list;
        }
        return new UnmodifiableExecutionList<>(list, executionContext);
    }

    static class UnmodifiableExecutionList<E> extends UnmodifiableExecutionCollection<E>
            implements List<E> {
        @java.io.Serial
        private static final long serialVersionUID = -1845512461523656905L;

        /** @serial */
        @SuppressWarnings("serial") // Conditionally serializable
        final List<? extends E> list;

        UnmodifiableExecutionList(List<? extends E> list, ExecutionContext executionContext) {
            super(list, executionContext);
            this.list = list;
        }

        @Override
        protected String collectionTitle() {
            return "List";
        }

        public boolean equals(Object o) {return o == this || list.equals(o);}
        public int hashCode()           {return list.hashCode();}

        public E get(int index) {return list.get(index);}
        public E set(int index, E element) {
            throw unmodifiableException();
        }
        public void add(int index, E element) {
            throw unmodifiableException();
        }
        public E remove(int index) {
            throw unmodifiableException();
        }
        public int indexOf(Object o)            {return list.indexOf(o);}
        public int lastIndexOf(Object o)        {return list.lastIndexOf(o);}
        public boolean addAll(int index, Collection<? extends E> c) {
            throw unmodifiableException();
        }

        @Override
        public void replaceAll(UnaryOperator<E> operator) {
            throw unmodifiableException();
        }
        @Override
        public void sort(Comparator<? super E> c) {
            throw unmodifiableException();
        }

        public ListIterator<E> listIterator()   {return listIterator(0);}

        public ListIterator<E> listIterator(final int index) {
            return new ListIterator<>() {
                private final ListIterator<? extends E> i
                        = list.listIterator(index);

                public boolean hasNext()     {return i.hasNext();}
                public E next()              {return i.next();}
                public boolean hasPrevious() {return i.hasPrevious();}
                public E previous()          {return i.previous();}
                public int nextIndex()       {return i.nextIndex();}
                public int previousIndex()   {return i.previousIndex();}

                public void remove() {
                    throw unmodifiableException();
                }
                public void set(E e) {
                    throw unmodifiableException();
                }
                public void add(E e) {
                    throw unmodifiableException();
                }

                @Override
                public void forEachRemaining(Consumer<? super E> action) {
                    i.forEachRemaining(action);
                }
            };
        }

        public List<E> subList(int fromIndex, int toIndex) {
            return new UnmodifiableExecutionList<>(list.subList(fromIndex, toIndex), this.executionContext);
        }

    }

    public static <K,V> Map<K,V> unmodifiableExecutionMap(Map<? extends K, ? extends V> m, ExecutionContext executionContext) {
        // Not checking for subclasses because of heap pollution and information leakage.
        if (m.getClass() == UnmodifiableExecutionMap.class) {
            return (Map<K,V>) m;
        }
        return new UnmodifiableExecutionMap<>(m, executionContext);
    }

    /**
     * @serial include
     */
    private static class UnmodifiableExecutionMap<K,V> implements Map<K,V>, ExecutionObject, Serializable {
        @java.io.Serial
        private static final long serialVersionUID = -1034234728574286014L;

        /** @serial */
        @SuppressWarnings("serial") // Conditionally serializable
        final Map<? extends K, ? extends V> m;

        protected final ExecutionContext executionContext;

        UnmodifiableExecutionMap(Map<? extends K, ? extends V> m, ExecutionContext executionContext) {
            if (m==null)
                throw new NullPointerException();
            if (!(m instanceof ExecutionObject)) {
                throw new IllegalArgumentException("Map is not an ExecutionObject");
            }
            this.executionContext = executionContext;
            this.m = m;
        }

        protected String collectionTitle() {
            return "Map";
        }

        protected UnsupportedOperationException unmodifiableException() {
            return new UnsupportedOperationException(collectionTitle() + " is unmodifiable");
        }

        public int size()                        {return m.size();}
        public boolean isEmpty()                 {return m.isEmpty();}
        public boolean containsKey(Object key)   {return m.containsKey(key);}
        public boolean containsValue(Object val) {return m.containsValue(val);}
        public V get(Object key)                 {return m.get(key);}

        public V put(K key, V value) {
            throw unmodifiableException();
        }
        public V remove(Object key) {
            throw unmodifiableException();
        }
        public void putAll(Map<? extends K, ? extends V> m) {
            throw unmodifiableException();
        }
        public void clear() {
            throw unmodifiableException();
        }

        private transient Set<K> keySet;
        private transient Set<Map.Entry<K,V>> entrySet;
        private transient Collection<V> values;

        public Set<K> keySet() {
            if (keySet==null)
                keySet = Collections.unmodifiableSet(m.keySet());
            return keySet;
        }

        public Set<Map.Entry<K,V>> entrySet() {
            if (entrySet==null)
                entrySet = new UnmodifiableExecutionEntrySet(m.entrySet(), this.executionContext);
            return entrySet;
        }

        public Collection<V> values() {
            if (values==null)
                values = unmodifiableExecutionCollection(m.values(), this.executionContext);
            return values;
        }

        public boolean equals(Object o) {return o == this || m.equals(o);}
        public int hashCode()           {return m.hashCode();}
        public String toString()        {return m.toString();}

        // Override default methods in Map
        @Override
        @SuppressWarnings("unchecked")
        public V getOrDefault(Object k, V defaultValue) {
            // Safe cast as we don't change the value
            return ((Map<K, V>)m).getOrDefault(k, defaultValue);
        }

        @Override
        public void forEach(BiConsumer<? super K, ? super V> action) {
            m.forEach(action);
        }

        @Override
        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            throw unmodifiableException();
        }

        @Override
        public V putIfAbsent(K key, V value) {
            throw unmodifiableException();
        }

        @Override
        public boolean remove(Object key, Object value) {
            throw unmodifiableException();
        }

        @Override
        public boolean replace(K key, V oldValue, V newValue) {
            throw unmodifiableException();
        }

        @Override
        public V replace(K key, V value) {
            throw unmodifiableException();
        }

        @Override
        public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
            throw unmodifiableException();
        }

        @Override
        public V computeIfPresent(K key,
                                  BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            throw unmodifiableException();
        }

        @Override
        public V compute(K key,
                         BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
            throw unmodifiableException();
        }

        @Override
        public V merge(K key, V value,
                       BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            throw unmodifiableException();
        }

        @Override
        public long memorySize() {
            return ((ExecutionObject)m).memorySize();
        }

        /**
         * We need this class in addition to UnmodifiableSet as
         * Map.Entries themselves permit modification of the backing Map
         * via their setValue operation.  This class is subtle: there are
         * many possible attacks that must be thwarted.
         *
         * @serial include
         */
        static class UnmodifiableExecutionEntrySet<K,V>
                extends UnmodifiableExecutionSet<Map.Entry<K,V>> {
            @java.io.Serial
            private static final long serialVersionUID = 7854390611657943733L;

            @SuppressWarnings({"unchecked"})
            UnmodifiableExecutionEntrySet(Set<? extends Map.Entry<? extends K, ? extends V>> s, ExecutionContext executionContext) {
                super((Set<Map.Entry<K, V>>)s, executionContext);
            }

            @Override
            protected String collectionTitle() {
                return "EntrySet";
            }

            static <K, V> Consumer<Map.Entry<? extends K, ? extends V>> entryConsumer(
                    Consumer<? super Entry<K, V>> action) {
                return e -> action.accept(new UnmodifiableEntry<>(e));
            }

            public void forEach(Consumer<? super Entry<K, V>> action) {
                Objects.requireNonNull(action);
                c.forEach(entryConsumer(action));
            }

            static final class UnmodifiableEntrySetSpliterator<K, V>
                    implements Spliterator<Entry<K,V>> {
                final Spliterator<Map.Entry<K, V>> s;

                UnmodifiableEntrySetSpliterator(Spliterator<Entry<K, V>> s) {
                    this.s = s;
                }

                @Override
                public boolean tryAdvance(Consumer<? super Entry<K, V>> action) {
                    Objects.requireNonNull(action);
                    return s.tryAdvance(entryConsumer(action));
                }

                @Override
                public void forEachRemaining(Consumer<? super Entry<K, V>> action) {
                    Objects.requireNonNull(action);
                    s.forEachRemaining(entryConsumer(action));
                }

                @Override
                public Spliterator<Entry<K, V>> trySplit() {
                    Spliterator<Entry<K, V>> split = s.trySplit();
                    return split == null
                            ? null
                            : new UnmodifiableEntrySetSpliterator<>(split);
                }

                @Override
                public long estimateSize() {
                    return s.estimateSize();
                }

                @Override
                public long getExactSizeIfKnown() {
                    return s.getExactSizeIfKnown();
                }

                @Override
                public int characteristics() {
                    return s.characteristics();
                }

                @Override
                public boolean hasCharacteristics(int characteristics) {
                    return s.hasCharacteristics(characteristics);
                }

                @Override
                public Comparator<? super Entry<K, V>> getComparator() {
                    return s.getComparator();
                }
            }

            @SuppressWarnings("unchecked")
            public Spliterator<Entry<K,V>> spliterator() {
                return new UnmodifiableEntrySetSpliterator<>(
                        (Spliterator<Map.Entry<K, V>>) c.spliterator());
            }

            @Override
            public Stream<Entry<K,V>> stream() {
                return StreamSupport.stream(spliterator(), false);
            }

            @Override
            public Stream<Entry<K,V>> parallelStream() {
                return StreamSupport.stream(spliterator(), true);
            }

            public Iterator<Map.Entry<K,V>> iterator() {
                return new Iterator<>() {
                    private final Iterator<? extends Map.Entry<? extends K, ? extends V>> i = c.iterator();

                    public boolean hasNext() {
                        return i.hasNext();
                    }
                    public Map.Entry<K,V> next() {
                        return new UnmodifiableEntry<>(i.next());
                    }
                    public void remove() {
                        throw unmodifiableException();
                    }
                    public void forEachRemaining(Consumer<? super Map.Entry<K, V>> action) {
                        Objects.requireNonNull(action);
                        i.forEachRemaining(entryConsumer(action));
                    }
                };
            }

            @SuppressWarnings("unchecked")
            public Object[] toArray() {
                Object[] a = c.toArray();
                for (int i=0; i<a.length; i++)
                    a[i] = new UnmodifiableEntry<>((Map.Entry<? extends K, ? extends V>)a[i]);
                return a;
            }

            @SuppressWarnings("unchecked")
            public <T> T[] toArray(T[] a) {
                // We don't pass a to c.toArray, to avoid window of
                // vulnerability wherein an unscrupulous multithreaded client
                // could get his hands on raw (unwrapped) Entries from c.
                Object[] arr = c.toArray(a.length==0 ? a : Arrays.copyOf(a, 0));

                for (int i=0; i<arr.length; i++)
                    arr[i] = new UnmodifiableEntry<>((Map.Entry<? extends K, ? extends V>)arr[i]);

                if (arr.length > a.length)
                    return (T[])arr;

                System.arraycopy(arr, 0, a, 0, arr.length);
                if (a.length > arr.length)
                    a[arr.length] = null;
                return a;
            }

            /**
             * This method is overridden to protect the backing set against
             * an object with a nefarious equals function that senses
             * that the equality-candidate is Map.Entry and calls its
             * setValue method.
             */
            public boolean contains(Object o) {
                if (!(o instanceof Map.Entry))
                    return false;
                return c.contains(
                        new UnmodifiableEntry<>((Map.Entry<?,?>) o));
            }

            /**
             * The next two methods are overridden to protect against
             * an unscrupulous List whose contains(Object o) method senses
             * when o is a Map.Entry, and calls o.setValue.
             */
            public boolean containsAll(Collection<?> coll) {
                for (Object e : coll) {
                    if (!contains(e)) // Invokes safe contains() above
                        return false;
                }
                return true;
            }
            public boolean equals(Object o) {
                if (o == this)
                    return true;

                return o instanceof Set<?> s
                        && s.size() == c.size()
                        && containsAll(s); // Invokes safe containsAll() above
            }

            /**
             * This "wrapper class" serves two purposes: it prevents
             * the client from modifying the backing Map, by short-circuiting
             * the setValue method, and it protects the backing Map against
             * an ill-behaved Map.Entry that attempts to modify another
             * Map Entry when asked to perform an equality check.
             */
            private static class UnmodifiableEntry<K,V> implements Map.Entry<K,V>, ExecutionObject {
                private Map.Entry<? extends K, ? extends V> e;

                UnmodifiableEntry(Map.Entry<? extends K, ? extends V> e)
                {this.e = Objects.requireNonNull(e);}

                public K getKey()        {return e.getKey();}
                public V getValue()      {return e.getValue();}
                public V setValue(V value) {
                    throw new UnsupportedOperationException("Entry is not modifiable");
                }
                public int hashCode()    {return e.hashCode();}
                public boolean equals(Object o) {
                    if (this == o)
                        return true;
                    return o instanceof Map.Entry<?, ?> t
                            && eq(e.getKey(),   t.getKey())
                            && eq(e.getValue(), t.getValue());
                }
                public String toString() {return e.toString();}

                @Override
                public long memorySize() {
                    return 4;
                }
            }
        }
    }

    static boolean eq(Object o1, Object o2) {
        return o1==null ? o2==null : o1.equals(o2);
    }
}

