/*
 * Copyright (C) 2016, 2017, 2018, 2019 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.util.stream;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import com.landawn.abacus.util.AsyncExecutor;
import com.landawn.abacus.util.ByteIterator;
import com.landawn.abacus.util.CharIterator;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.DoubleIterator;
import com.landawn.abacus.util.FloatIterator;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.Holder;
import com.landawn.abacus.util.IntIterator;
import com.landawn.abacus.util.LongIterator;
import com.landawn.abacus.util.Multimap;
import com.landawn.abacus.util.Multiset;
import com.landawn.abacus.util.MutableBoolean;
import com.landawn.abacus.util.MutableInt;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Nth;
import com.landawn.abacus.util.Optional;
import com.landawn.abacus.util.Pair;
import com.landawn.abacus.util.ShortIterator;
import com.landawn.abacus.util.Try;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.BinaryOperator;
import com.landawn.abacus.util.function.Consumer;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.Predicate;
import com.landawn.abacus.util.function.Supplier;
import com.landawn.abacus.util.function.ToByteFunction;
import com.landawn.abacus.util.function.ToCharFunction;
import com.landawn.abacus.util.function.ToDoubleFunction;
import com.landawn.abacus.util.function.ToFloatFunction;
import com.landawn.abacus.util.function.ToIntFunction;
import com.landawn.abacus.util.function.ToLongFunction;
import com.landawn.abacus.util.function.ToShortFunction;
import com.landawn.abacus.util.function.TriFunction;

/**
 * 
 */
final class ParallelArrayStream<T> extends ArrayStream<T> {
    private final int maxThreadNum;
    private final Splitor splitor;
    private final AsyncExecutor asyncExecutor;
    private volatile ArrayStream<T> sequential;

    ParallelArrayStream(final T[] values, final int fromIndex, final int toIndex, final boolean sorted, Comparator<? super T> comparator,
            final int maxThreadNum, final Splitor splitor, final AsyncExecutor asyncExector, final Collection<Runnable> closeHandlers) {
        super(values, fromIndex, toIndex, sorted, comparator, closeHandlers);

        this.maxThreadNum = checkMaxThreadNum(maxThreadNum);
        this.splitor = splitor == null ? DEFAULT_SPLITOR : splitor;
        this.asyncExecutor = asyncExector == null ? DEFAULT_ASYNC_EXECUTOR : asyncExector;
    }

    @Override
    public Stream<T> filter(final Predicate<? super T> predicate) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.filter(predicate);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<Iterator<T>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<T>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                    private T next = null;
                    private boolean hasNext = false;

                    @Override
                    public boolean hasNext() {
                        if (hasNext == false) {
                            while (cursor < to) {
                                next = elements[cursor++];

                                if (predicate.test(next)) {
                                    hasNext = true;
                                    break;
                                }
                            }
                        }

                        return hasNext;
                    }

                    @Override
                    public T next() {
                        if (hasNext == false && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        hasNext = false;
                        return next;
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<T>() {

                    private T next = null;
                    private boolean hasNext = false;

                    @Override
                    public boolean hasNext() {
                        if (hasNext == false) {
                            while (true) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(next)) {
                                    hasNext = true;
                                    break;
                                }
                            }
                        }

                        return hasNext;
                    }

                    @Override
                    public T next() {
                        if (hasNext == false && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        hasNext = false;
                        return next;
                    }

                });
            }
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public Stream<T> takeWhile(final Predicate<? super T> predicate) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.takeWhile(predicate);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<Iterator<T>> iters = new ArrayList<>(threadNum);
        final MutableBoolean hasMore = MutableBoolean.of(true);
        final MutableInt cursor = MutableInt.of(fromIndex);

        for (int i = 0; i < threadNum; i++) {
            iters.add(new ObjIteratorEx<T>() {
                private T next = null;
                private boolean hasNext = false;

                @Override
                public boolean hasNext() {
                    if (hasNext == false && hasMore.isTrue()) {
                        synchronized (elements) {
                            if (cursor.intValue() < toIndex) {
                                next = elements[cursor.getAndIncrement()];
                                hasNext = true;
                            } else {
                                hasMore.setFalse();
                            }
                        }

                        if (hasNext && predicate.test(next) == false) {
                            hasNext = false;
                            hasMore.setFalse();
                        }
                    }

                    return hasNext;
                }

                @Override
                public T next() {
                    if (hasNext == false && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    hasNext = false;
                    return next;
                }
            });
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public Stream<T> dropWhile(final Predicate<? super T> predicate) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.dropWhile(predicate);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<Iterator<T>> iters = new ArrayList<>(threadNum);
        final MutableBoolean dropped = MutableBoolean.of(false);
        final MutableInt cursor = MutableInt.of(fromIndex);

        for (int i = 0; i < threadNum; i++) {
            iters.add(new ObjIteratorEx<T>() {
                private T next = null;
                private boolean hasNext = false;

                @Override
                public boolean hasNext() {
                    if (hasNext == false) {
                        // Only one thread is kept for running after it's dropped.
                        if (dropped.isTrue()) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                    hasNext = true;
                                }
                            }
                        } else {
                            while (dropped.isFalse()) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(next) == false) {
                                    hasNext = true;
                                    dropped.setTrue();
                                    break;
                                }
                            }

                            if (hasNext == false && dropped.isTrue()) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                        hasNext = true;
                                    }
                                }
                            }
                        }
                    }

                    return hasNext;
                }

                @Override
                public T next() {
                    if (hasNext == false && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    hasNext = false;
                    return next;
                }
            });
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public <R> Stream<R> map(final Function<? super T, ? extends R> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.map(mapper);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<Iterator<R>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<R>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                    @Override
                    public boolean hasNext() {
                        return cursor < to;
                    }

                    @Override
                    public R next() {
                        if (cursor >= to) {
                            throw new NoSuchElementException();
                        }

                        return mapper.apply(elements[cursor++]);
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<R>() {

                    private Object next = NONE;

                    @Override
                    public boolean hasNext() {
                        if (next == NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                }
                            }
                        }

                        return next != NONE;
                    }

                    @Override
                    public R next() {
                        if (next == NONE && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        R result = mapper.apply((T) next);
                        next = NONE;
                        return result;
                    }

                });
            }
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    //    @Override
    //    public <R> Stream<R> biMap(final BiFunction<? super T, ? super T, ? extends R> mapper, final boolean ignoreNotPaired) {
    //        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
    //            return new ParallelIteratorStream<>(sequential().biMap(mapper, ignoreNotPaired).iterator(), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    //        }
    //
    //        final int atLeast = ignoreNotPaired ? 2 : 1;
    //        final int count = (toIndex - fromIndex) / 2 + (ignoreNotPaired || (toIndex - fromIndex) % 2 == 0 ? 0 : 1);
    //        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
    //        final List<Iterator<R>> iters = new ArrayList<>(threadNum);
    //
    //        if (splitor == Splitor.ARRAY) {
    //            final int sliceSize = count / maxThreadNum + (count % maxThreadNum == 0 ? 0 : 1);
    //
    //            for (int i = 0; i < threadNum; i++) {
    //                final int sliceIndex = i;
    //                iters.add(new ObjIteratorEx<R>() {
    //                    private int cursor = fromIndex + sliceIndex * sliceSize * 2;
    //                    private final int to = toIndex - cursor > sliceSize * 2 ? cursor + sliceSize * 2 : toIndex;
    //
    //                    @Override
    //                    public boolean hasNext() {
    //                        return to - cursor >= atLeast;
    //                    }
    //
    //                    @Override
    //                    public R next() {
    //                        if (to - cursor < atLeast) {
    //                            throw new NoSuchElementException();
    //                        }
    //
    //                        return mapper.apply(elements[cursor++], cursor == toIndex ? null : elements[cursor++]);
    //                    }
    //                });
    //            }
    //        } else {
    //            final MutableInt cursor = MutableInt.of(fromIndex);
    //
    //            for (int i = 0; i < threadNum; i++) {
    //                iters.add(new ObjIteratorEx<R>() {
    //
    //                    private Object pre = NONE;
    //                    private Object next = NONE;
    //
    //                    @Override
    //                    public boolean hasNext() {
    //                        if (pre == NONE) {
    //                            synchronized (elements) {
    //                                if (toIndex - cursor.intValue() >= atLeast) {
    //                                    pre = elements[cursor.getAndIncrement()];
    //                                    next = cursor.intValue() == toIndex ? null : elements[cursor.getAndIncrement()];
    //                                }
    //                            }
    //                        }
    //
    //                        return pre != NONE;
    //                    }
    //
    //                    @Override
    //                    public R next() {
    //                        if (pre == NONE && hasNext() == false) {
    //                            throw new NoSuchElementException();
    //                        }
    //
    //                        final R result = mapper.apply((T) pre, (T) next);
    //                        pre = NONE;
    //                        next = NONE;
    //                        return result;
    //                    }
    //
    //                });
    //            }
    //        }
    //
    //        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    //    }
    //
    //    @Override
    //    public <R> Stream<R> triMap(final TriFunction<? super T, ? super T, ? super T, ? extends R> mapper, final boolean ignoreNotPaired) {
    //        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
    //            return new ParallelIteratorStream<>(sequential().triMap(mapper, ignoreNotPaired).iterator(), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    //        }
    //
    //        final int atLeast = ignoreNotPaired ? 3 : 1;
    //        final int count = (toIndex - fromIndex) / 3 + (ignoreNotPaired || (toIndex - fromIndex) % 3 == 0 ? 0 : 1);
    //        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
    //        final List<Iterator<R>> iters = new ArrayList<>(threadNum);
    //
    //        if (splitor == Splitor.ARRAY) {
    //            final int sliceSize = count / maxThreadNum + (count % maxThreadNum == 0 ? 0 : 1);
    //
    //            for (int i = 0; i < threadNum; i++) {
    //                final int sliceIndex = i;
    //                iters.add(new ObjIteratorEx<R>() {
    //                    private int cursor = fromIndex + sliceIndex * sliceSize * 3;
    //                    private final int to = toIndex - cursor > sliceSize * 3 ? cursor + sliceSize * 3 : toIndex;
    //
    //                    @Override
    //                    public boolean hasNext() {
    //                        return to - cursor >= atLeast;
    //                    }
    //
    //                    @Override
    //                    public R next() {
    //                        if (to - cursor < atLeast) {
    //                            throw new NoSuchElementException();
    //                        }
    //
    //                        return mapper.apply(elements[cursor++], cursor == toIndex ? null : elements[cursor++], cursor == toIndex ? null : elements[cursor++]);
    //                    }
    //                });
    //            }
    //        } else {
    //            final MutableInt cursor = MutableInt.of(fromIndex);
    //
    //            for (int i = 0; i < threadNum; i++) {
    //                iters.add(new ObjIteratorEx<R>() {
    //
    //                    private Object prepre = NONE;
    //                    private Object pre = NONE;
    //                    private Object next = NONE;
    //
    //                    @Override
    //                    public boolean hasNext() {
    //                        if (prepre == NONE) {
    //                            synchronized (elements) {
    //                                if (toIndex - cursor.intValue() >= atLeast) {
    //                                    prepre = elements[cursor.getAndIncrement()];
    //                                    pre = cursor.intValue() == toIndex ? null : elements[cursor.getAndIncrement()];
    //                                    next = cursor.intValue() == toIndex ? null : elements[cursor.getAndIncrement()];
    //                                }
    //                            }
    //                        }
    //
    //                        return prepre != NONE;
    //                    }
    //
    //                    @Override
    //                    public R next() {
    //                        if (prepre == NONE && hasNext() == false) {
    //                            throw new NoSuchElementException();
    //                        }
    //
    //                        final R result = mapper.apply((T) prepre, (T) pre, (T) next);
    //                        prepre = NONE;
    //                        pre = NONE;
    //                        next = NONE;
    //                        return result;
    //                    }
    //
    //                });
    //            }
    //        }
    //
    //        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    //    }

    @Override
    public <R> Stream<R> slidingMap(final BiFunction<? super T, ? super T, R> mapper, final int increment, final boolean ignoreNotPaired) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorStream<>(sequential().slidingMap(mapper, increment).iterator(), false, null, maxThreadNum, splitor, asyncExecutor,
                    closeHandlers);
        }

        final int windowSize = 2;

        N.checkArgument(increment > 0, "'increment'=%s must not be less than 1", increment);

        final List<Iterator<R>> iters = new ArrayList<>(maxThreadNum);
        final MutableInt curIndex = MutableInt.of(fromIndex);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<R>() {
                private int cursor = -1;

                @Override
                public boolean hasNext() {
                    if (cursor == -1) {
                        synchronized (elements) {
                            if (ignoreNotPaired ? toIndex - curIndex.intValue() >= windowSize : curIndex.intValue() < toIndex) {
                                cursor = curIndex.value();
                                curIndex.setValue(increment < toIndex - cursor && windowSize < toIndex - cursor ? cursor + increment : toIndex);
                            }
                        }
                    }

                    return cursor != -1;
                }

                @Override
                public R next() {
                    if (cursor == -1 && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    final R result = mapper.apply(elements[cursor], cursor < toIndex - 1 ? elements[cursor + 1] : null);
                    cursor = -1;
                    return result;
                }
            });
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public <R> Stream<R> slidingMap(final TriFunction<? super T, ? super T, ? super T, R> mapper, final int increment, final boolean ignoreNotPaired) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorStream<>(sequential().slidingMap(mapper, increment).iterator(), false, null, maxThreadNum, splitor, asyncExecutor,
                    closeHandlers);
        }

        final int windowSize = 3;

        N.checkArgument(increment > 0, "'increment'=%s must not be less than 1", increment);

        final List<Iterator<R>> iters = new ArrayList<>(maxThreadNum);
        final MutableInt curIndex = MutableInt.of(fromIndex);

        for (int i = 0; i < maxThreadNum; i++) {
            iters.add(new ObjIteratorEx<R>() {
                private int cursor = -1;

                @Override
                public boolean hasNext() {
                    if (cursor == -1) {
                        synchronized (elements) {
                            if (ignoreNotPaired ? toIndex - curIndex.intValue() >= windowSize : curIndex.intValue() < toIndex) {
                                cursor = curIndex.value();
                                curIndex.setValue(increment < toIndex - cursor && windowSize < toIndex - cursor ? cursor + increment : toIndex);
                            }
                        }
                    }

                    return cursor != -1;
                }

                @Override
                public R next() {
                    if (cursor == -1 && hasNext() == false) {
                        throw new NoSuchElementException();
                    }

                    final R result = mapper.apply(elements[cursor], cursor < toIndex - 1 ? elements[cursor + 1] : null,
                            cursor < toIndex - 2 ? elements[cursor + 2] : null);
                    cursor = -1;
                    return result;
                }
            });
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public <R> Stream<R> mapFirstOrElse(final Function<? super T, ? extends R> mapperForFirst, final Function<? super T, ? extends R> mapperForElse) {
        N.checkArgNotNull(mapperForFirst);
        N.checkArgNotNull(mapperForElse);

        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.mapFirstOrElse(mapperForFirst, mapperForElse);
        }

        if (fromIndex == toIndex) {
            return (Stream<R>) this;
        } else if (toIndex - fromIndex == 1) {
            return map(mapperForFirst);
        } else {
            final Function<T, R> mapperForFirst2 = (Function<T, R>) mapperForFirst;
            final Function<T, R> mapperForElse2 = (Function<T, R>) mapperForElse;

            return skip(1).map(mapperForElse2).prepend(Stream.of(elements[fromIndex]).map(mapperForFirst2));
        }
    }

    @Override
    public <R> Stream<R> mapLastOrElse(final Function<? super T, ? extends R> mapperForLast, final Function<? super T, ? extends R> mapperForElse) {
        N.checkArgNotNull(mapperForLast);
        N.checkArgNotNull(mapperForElse);

        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorStream<>(sequential().mapLastOrElse(mapperForLast, mapperForElse).iterator(), false, null, maxThreadNum, splitor,
                    asyncExecutor, closeHandlers);
        }

        if (fromIndex == toIndex) {
            return (Stream<R>) this;
        } else if (toIndex - fromIndex == 1) {
            return map(mapperForLast);
        } else {
            final Function<T, R> mapperForLast2 = (Function<T, R>) mapperForLast;
            final Function<T, R> mapperForElse2 = (Function<T, R>) mapperForElse;

            return limit(toIndex - fromIndex - 1).map(mapperForElse2).append(Stream.of(elements[toIndex - 1]).map(mapperForLast2));
        }
    }

    @Override
    public CharStream mapToChar(final ToCharFunction<? super T> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.mapToChar(mapper);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Character>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Character>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                    @Override
                    public boolean hasNext() {
                        return cursor < to;
                    }

                    @Override
                    public Character next() {
                        if (cursor >= to) {
                            throw new NoSuchElementException();
                        }

                        return mapper.applyAsChar(elements[cursor++]);
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Character>() {

                    private Object next = NONE;

                    @Override
                    public boolean hasNext() {
                        if (next == NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                }
                            }
                        }

                        return next != NONE;
                    }

                    @Override
                    public Character next() {
                        if (next == NONE && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        Character result = mapper.applyAsChar((T) next);
                        next = NONE;
                        return result;
                    }

                });
            }
        }

        return new ParallelIteratorCharStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public ByteStream mapToByte(final ToByteFunction<? super T> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.mapToByte(mapper);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Byte>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Byte>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                    @Override
                    public boolean hasNext() {
                        return cursor < to;
                    }

                    @Override
                    public Byte next() {
                        if (cursor >= to) {
                            throw new NoSuchElementException();
                        }

                        return mapper.applyAsByte(elements[cursor++]);
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Byte>() {

                    private Object next = NONE;

                    @Override
                    public boolean hasNext() {
                        if (next == NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                }
                            }
                        }

                        return next != NONE;
                    }

                    @Override
                    public Byte next() {
                        if (next == NONE && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        Byte result = mapper.applyAsByte((T) next);
                        next = NONE;
                        return result;
                    }

                });
            }
        }

        return new ParallelIteratorByteStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public ShortStream mapToShort(final ToShortFunction<? super T> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.mapToShort(mapper);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Short>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Short>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                    @Override
                    public boolean hasNext() {
                        return cursor < to;
                    }

                    @Override
                    public Short next() {
                        if (cursor >= to) {
                            throw new NoSuchElementException();
                        }

                        return mapper.applyAsShort(elements[cursor++]);
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Short>() {

                    private Object next = NONE;

                    @Override
                    public boolean hasNext() {
                        if (next == NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                }
                            }
                        }

                        return next != NONE;
                    }

                    @Override
                    public Short next() {
                        if (next == NONE && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        Short result = mapper.applyAsShort((T) next);
                        next = NONE;
                        return result;
                    }

                });
            }
        }

        return new ParallelIteratorShortStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public IntStream mapToInt(final ToIntFunction<? super T> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.mapToInt(mapper);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Integer>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Integer>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                    @Override
                    public boolean hasNext() {
                        return cursor < to;
                    }

                    @Override
                    public Integer next() {
                        if (cursor >= to) {
                            throw new NoSuchElementException();
                        }

                        return mapper.applyAsInt(elements[cursor++]);
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Integer>() {

                    private Object next = NONE;

                    @Override
                    public boolean hasNext() {
                        if (next == NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                }
                            }
                        }

                        return next != NONE;
                    }

                    @Override
                    public Integer next() {
                        if (next == NONE && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        Integer result = mapper.applyAsInt((T) next);
                        next = NONE;
                        return result;
                    }

                });
            }
        }

        return new ParallelIteratorIntStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public LongStream mapToLong(final ToLongFunction<? super T> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.mapToLong(mapper);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Long>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Long>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                    @Override
                    public boolean hasNext() {
                        return cursor < to;
                    }

                    @Override
                    public Long next() {
                        if (cursor >= to) {
                            throw new NoSuchElementException();
                        }

                        return mapper.applyAsLong(elements[cursor++]);
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Long>() {

                    private Object next = NONE;

                    @Override
                    public boolean hasNext() {
                        if (next == NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                }
                            }
                        }

                        return next != NONE;
                    }

                    @Override
                    public Long next() {
                        if (next == NONE && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        Long result = mapper.applyAsLong((T) next);
                        next = NONE;
                        return result;
                    }

                });
            }
        }

        return new ParallelIteratorLongStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public FloatStream mapToFloat(final ToFloatFunction<? super T> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.mapToFloat(mapper);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Float>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Float>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                    @Override
                    public boolean hasNext() {
                        return cursor < to;
                    }

                    @Override
                    public Float next() {
                        if (cursor >= to) {
                            throw new NoSuchElementException();
                        }

                        return mapper.applyAsFloat(elements[cursor++]);
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Float>() {

                    private Object next = NONE;

                    @Override
                    public boolean hasNext() {
                        if (next == NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                }
                            }
                        }

                        return next != NONE;
                    }

                    @Override
                    public Float next() {
                        if (next == NONE && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        Float result = mapper.applyAsFloat((T) next);
                        next = NONE;
                        return result;
                    }

                });
            }
        }

        return new ParallelIteratorFloatStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public DoubleStream mapToDouble(final ToDoubleFunction<? super T> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.mapToDouble(mapper);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Double>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Double>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                    @Override
                    public boolean hasNext() {
                        return cursor < to;
                    }

                    @Override
                    public Double next() {
                        if (cursor >= to) {
                            throw new NoSuchElementException();
                        }

                        return mapper.applyAsDouble(elements[cursor++]);
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Double>() {

                    private Object next = NONE;

                    @Override
                    public boolean hasNext() {
                        if (next == NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                }
                            }
                        }

                        return next != NONE;
                    }

                    @Override
                    public Double next() {
                        if (next == NONE && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        Double result = mapper.applyAsDouble((T) next);
                        next = NONE;
                        return result;
                    }

                });
            }
        }

        return new ParallelIteratorDoubleStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public <R> Stream<R> flatMap(final Function<? super T, ? extends Stream<? extends R>> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorStream<>(sequential().flatMap(mapper), false, null, maxThreadNum, splitor, asyncExecutor, null);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<R>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<R>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                    private Iterator<? extends R> cur = null;
                    private Stream<? extends R> s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && cursor < to) {
                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(elements[cursor++]);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public R next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.next();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<R>() {

                    private T next = null;
                    private Iterator<? extends R> cur = null;
                    private Stream<? extends R> s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && next != NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                } else {
                                    next = (T) NONE;
                                    break;
                                }
                            }

                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(next);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };
                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public R next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.next();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        }

        final Deque<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalArrayDeque<Runnable>(1)
                : new LocalArrayDeque<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, asyncExecutor, newCloseHandlers);
    }

    @Override
    public CharStream flatMapToChar(final Function<? super T, ? extends CharStream> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorCharStream(sequential().flatMapToChar(mapper), false, maxThreadNum, splitor, asyncExecutor, null);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Character>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Character>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                    private CharIterator cur = null;
                    private CharStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && cursor < to) {
                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(elements[cursor++]);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Character next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextChar();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Character>() {

                    private T next = null;
                    private CharIterator cur = null;
                    private CharStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && next != NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                } else {
                                    next = (T) NONE;
                                    break;
                                }
                            }

                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(next);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Character next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextChar();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        }

        final Deque<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalArrayDeque<Runnable>(1)
                : new LocalArrayDeque<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorCharStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, newCloseHandlers);
    }

    @Override
    public ByteStream flatMapToByte(final Function<? super T, ? extends ByteStream> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorByteStream(sequential().flatMapToByte(mapper), false, maxThreadNum, splitor, asyncExecutor, null);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Byte>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Byte>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                    private ByteIterator cur = null;
                    private ByteStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && cursor < to) {
                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(elements[cursor++]);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Byte next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextByte();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Byte>() {

                    private T next = null;
                    private ByteIterator cur = null;
                    private ByteStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && next != NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                } else {
                                    next = (T) NONE;
                                    break;
                                }
                            }

                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(next);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Byte next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextByte();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        }

        final Deque<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalArrayDeque<Runnable>(1)
                : new LocalArrayDeque<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorByteStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, newCloseHandlers);
    }

    @Override
    public ShortStream flatMapToShort(final Function<? super T, ? extends ShortStream> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorShortStream(sequential().flatMapToShort(mapper), false, maxThreadNum, splitor, asyncExecutor, null);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Short>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Short>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                    private ShortIterator cur = null;
                    private ShortStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && cursor < to) {
                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(elements[cursor++]);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Short next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextShort();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Short>() {

                    private T next = null;
                    private ShortIterator cur = null;
                    private ShortStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && next != NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                } else {
                                    next = (T) NONE;
                                    break;
                                }
                            }

                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(next);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Short next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextShort();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        }

        final Deque<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalArrayDeque<Runnable>(1)
                : new LocalArrayDeque<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorShortStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, newCloseHandlers);
    }

    @Override
    public IntStream flatMapToInt(final Function<? super T, ? extends IntStream> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorIntStream(sequential().flatMapToInt(mapper), false, maxThreadNum, splitor, asyncExecutor, null);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Integer>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Integer>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                    private IntIterator cur = null;
                    private IntStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && cursor < to) {
                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(elements[cursor++]);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Integer next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextInt();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Integer>() {

                    private T next = null;
                    private IntIterator cur = null;
                    private IntStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && next != NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                } else {
                                    next = (T) NONE;
                                    break;
                                }
                            }

                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(next);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Integer next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextInt();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        }

        final Deque<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalArrayDeque<Runnable>(1)
                : new LocalArrayDeque<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorIntStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, newCloseHandlers);
    }

    @Override
    public LongStream flatMapToLong(final Function<? super T, ? extends LongStream> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorLongStream(sequential().flatMapToLong(mapper), false, maxThreadNum, splitor, asyncExecutor, null);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Long>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Long>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                    private LongIterator cur = null;
                    private LongStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && cursor < to) {
                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(elements[cursor++]);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Long next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextLong();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Long>() {

                    private T next = null;
                    private LongIterator cur = null;
                    private LongStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && next != NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                } else {
                                    next = (T) NONE;
                                    break;
                                }
                            }

                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(next);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Long next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextLong();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        }

        final Deque<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalArrayDeque<Runnable>(1)
                : new LocalArrayDeque<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorLongStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, newCloseHandlers);
    }

    @Override
    public FloatStream flatMapToFloat(final Function<? super T, ? extends FloatStream> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorFloatStream(sequential().flatMapToFloat(mapper), false, maxThreadNum, splitor, asyncExecutor, null);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Float>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Float>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                    private FloatIterator cur = null;
                    private FloatStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && cursor < to) {
                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(elements[cursor++]);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Float next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextFloat();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Float>() {

                    private T next = null;
                    private FloatIterator cur = null;
                    private FloatStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && next != NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                } else {
                                    next = (T) NONE;
                                    break;
                                }
                            }

                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(next);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Float next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextFloat();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        }

        final Deque<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalArrayDeque<Runnable>(1)
                : new LocalArrayDeque<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorFloatStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, newCloseHandlers);
    }

    @Override
    public DoubleStream flatMapToDouble(final Function<? super T, ? extends DoubleStream> mapper) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorDoubleStream(sequential().flatMapToDouble(mapper), false, maxThreadNum, splitor, asyncExecutor, null);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ObjIteratorEx<Double>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<Double>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                    private DoubleIterator cur = null;
                    private DoubleStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && cursor < to) {
                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(elements[cursor++]);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Double next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextDouble();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<Double>() {

                    private T next = null;
                    private DoubleIterator cur = null;
                    private DoubleStream s = null;
                    private Runnable closeHandle = null;

                    @Override
                    public boolean hasNext() {
                        while ((cur == null || cur.hasNext() == false) && next != NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                } else {
                                    next = (T) NONE;
                                    break;
                                }
                            }

                            if (closeHandle != null) {
                                final Runnable tmp = closeHandle;
                                closeHandle = null;
                                tmp.run();
                            }

                            s = mapper.apply(next);

                            if (N.notNullOrEmpty(s.closeHandlers)) {
                                final Deque<Runnable> tmp = s.closeHandlers;

                                closeHandle = new Runnable() {
                                    @Override
                                    public void run() {
                                        Stream.close(tmp);
                                    }
                                };

                            }

                            cur = s.iterator();
                        }

                        return cur != null && cur.hasNext();
                    }

                    @Override
                    public Double next() {
                        if ((cur == null || cur.hasNext() == false) && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        return cur.nextDouble();
                    }

                    @Override
                    public void close() {
                        if (closeHandle != null) {
                            final Runnable tmp = closeHandle;
                            closeHandle = null;
                            tmp.run();
                        }
                    }
                });
            }
        }

        final Deque<Runnable> newCloseHandlers = N.isNullOrEmpty(closeHandlers) ? new LocalArrayDeque<Runnable>(1)
                : new LocalArrayDeque<Runnable>(closeHandlers);

        newCloseHandlers.add(new Runnable() {
            @Override
            public void run() {
                Stream.close(iters);
            }
        });

        return new ParallelIteratorDoubleStream(Stream.parallelConcatt(iters, iters.size()), false, maxThreadNum, splitor, asyncExecutor, newCloseHandlers);
    }

    @Override
    public Stream<T> peek(final Consumer<? super T> action) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.peek(action);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<Iterator<T>> iters = new ArrayList<>(threadNum);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;
                iters.add(new ObjIteratorEx<T>() {
                    private int cursor = fromIndex + sliceIndex * sliceSize;
                    private final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                    @Override
                    public boolean hasNext() {
                        return cursor < to;
                    }

                    @Override
                    public T next() {
                        if (cursor >= to) {
                            throw new NoSuchElementException();
                        }

                        action.accept(elements[cursor]);

                        return elements[cursor++];
                    }
                });
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                iters.add(new ObjIteratorEx<T>() {

                    private Object next = NONE;

                    @Override
                    public boolean hasNext() {
                        if (next == NONE) {
                            synchronized (elements) {
                                if (cursor.intValue() < toIndex) {
                                    next = elements[cursor.getAndIncrement()];
                                }
                            }
                        }

                        return next != NONE;
                    }

                    @Override
                    public T next() {
                        if (next == NONE && hasNext() == false) {
                            throw new NoSuchElementException();
                        }

                        T result = (T) next;
                        action.accept(result);
                        next = NONE;
                        return result;
                    }

                });
            }
        }

        return new ParallelIteratorStream<>(Stream.parallelConcatt(iters, iters.size()), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public <E extends Exception> void forEach(final Try.Consumer<? super T, E> action) throws E {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            super.forEach(action);
            return;
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {
                    @Override
                    public void run() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        try {
                            while (cursor < to && eHolder.value() == null) {
                                action.accept(elements[cursor++]);
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {

                    @Override
                    public void run() {
                        T next = null;

                        try {
                            while (eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                action.accept(next);
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }

                }));
            }
        }

        try {
            complette(futureList, eHolder, (E) null);
        } finally {
            close();
        }
    }

    @Override
    public <E extends Exception> void forEachPair(final Try.BiConsumer<? super T, ? super T, E> action, final int increment) throws E {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            super.forEachPair(action, increment);
            return;
        }

        final int windowSize = 2;

        N.checkArgument(increment > 0, "'increment'=%s must not be less than 1", increment);

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableInt curIndex = MutableInt.of(fromIndex);

        for (int i = 0; i < threadNum; i++) {
            futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {
                private int cursor = -1;

                @Override
                public void run() {
                    try {
                        while (curIndex.intValue() < toIndex && eHolder.value() == null) {
                            synchronized (elements) {
                                if (curIndex.intValue() < toIndex) {
                                    cursor = curIndex.value();
                                    curIndex.setValue(increment < toIndex - cursor && windowSize < toIndex - cursor ? cursor + increment : toIndex);
                                } else {
                                    break;
                                }
                            }

                            action.accept(elements[cursor], cursor < toIndex - 1 ? elements[cursor + 1] : null);
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        try {
            complette(futureList, eHolder, (E) null);
        } finally {
            close();
        }
    }

    @Override
    public <E extends Exception> void forEachTriple(final Try.TriConsumer<? super T, ? super T, ? super T, E> action, final int increment) throws E {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            super.forEachTriple(action, increment);
            return;
        }

        final int windowSize = 3;

        N.checkArgument(increment > 0, "'increment'=%s must not be less than 1", increment);

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableInt curIndex = MutableInt.of(fromIndex);

        for (int i = 0; i < threadNum; i++) {
            futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {
                private int cursor = -1;

                @Override
                public void run() {
                    try {
                        while (curIndex.intValue() < toIndex && eHolder.value() == null) {
                            synchronized (elements) {
                                if (curIndex.intValue() < toIndex) {
                                    cursor = curIndex.value();
                                    curIndex.setValue(increment < toIndex - cursor && windowSize < toIndex - cursor ? cursor + increment : toIndex);
                                } else {
                                    break;
                                }
                            }

                            action.accept(elements[cursor], cursor < toIndex - 1 ? elements[cursor + 1] : null,
                                    cursor < toIndex - 2 ? elements[cursor + 2] : null);
                        }
                    } catch (Exception e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        try {
            complette(futureList, eHolder, (E) null);
        } finally {
            close();
        }
    }

    @Override
    <A> A[] toArray(A[] a) {
        assertNotClosed();

        try {
            if (a.length < (toIndex - fromIndex)) {
                a = N.newArray(a.getClass().getComponentType(), toIndex - fromIndex);
            }

            N.copy(elements, fromIndex, a, 0, toIndex - fromIndex);

            return a;
        } finally {
            close();
        }
    }

    @Override
    public <K, V, M extends Map<K, V>> M toMap(final Function<? super T, ? extends K> keyExtractor, final Function<? super T, ? extends V> valueMapper,
            final BinaryOperator<V> mergeFunction, final Supplier<M> mapFactory) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.toMap(keyExtractor, valueMapper, mapFactory);
        }

        // return collect(Collectors.toMap(keyExtractor, valueMapper, mapFactory));

        //    final M res = mapFactory.get();
        //    res.putAll(collect(Collectors.toConcurrentMap(keyExtractor, valueMapper, mergeFunction)));
        //    return res;

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<M>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Callable<M>() {
                    @Override
                    public M call() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        M map = mapFactory.get();

                        try {
                            while (cursor < to && eHolder.value() == null) {
                                Collectors.merge(map, keyExtractor.apply(elements[cursor]), valueMapper.apply(elements[cursor]), mergeFunction);
                                cursor++;
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return map;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Callable<M>() {

                    @Override
                    public M call() {
                        M map = mapFactory.get();
                        T next = null;

                        try {
                            while (eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                Collectors.merge(map, keyExtractor.apply(next), valueMapper.apply(next), mergeFunction);
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return map;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            close();
            throw N.toRuntimeException(eHolder.value());
        }

        M res = null;

        try {
            for (ContinuableFuture<M> future : futureList) {
                if (res == null) {
                    res = future.get();
                } else {
                    final M m = future.get();

                    for (Map.Entry<K, V> entry : m.entrySet()) {
                        Collectors.merge(res, entry.getKey(), entry.getValue(), mergeFunction);
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        } finally {
            close();
        }

        return res;
    }

    @Override
    public <K, A, D, M extends Map<K, D>> M toMap(final Function<? super T, ? extends K> classifier, final Collector<? super T, A, D> downstream,
            final Supplier<M> mapFactory) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.toMap(classifier, downstream, mapFactory);
        }

        // return collect(Collectors.groupingBy(classifier, downstream, mapFactory));

        //    final M res = mapFactory.get();
        //    res.putAll(collect(Collectors.groupingByConcurrent(classifier, downstream)));
        //    return res;

        final Supplier<A> downstreamSupplier = downstream.supplier();
        final BiConsumer<A, ? super T> downstreamAccumulator = downstream.accumulator();
        final BinaryOperator<A> downstreamCombiner = downstream.combiner();
        final Function<A, D> downstreamFinisher = downstream.finisher();

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<Map<K, A>>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Callable<Map<K, A>>() {
                    @Override
                    public Map<K, A> call() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        @SuppressWarnings("rawtypes")
                        Map<K, A> map = (Map) mapFactory.get();
                        K key = null;
                        A value = null;

                        try {
                            while (cursor < to && eHolder.value() == null) {
                                key = N.checkArgNotNull(classifier.apply(elements[cursor]), "element cannot be mapped to a null key");

                                value = map.get(key);

                                if (value == null) {
                                    value = downstreamSupplier.get();
                                    map.put(key, value);
                                }

                                downstreamAccumulator.accept(value, elements[cursor]);

                                cursor++;
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return map;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Callable<Map<K, A>>() {

                    @Override
                    public Map<K, A> call() {
                        @SuppressWarnings("rawtypes")
                        Map<K, A> map = (Map) mapFactory.get();
                        K key = null;
                        A value = null;
                        T next = null;

                        try {
                            while (eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                key = N.checkArgNotNull(classifier.apply(next), "element cannot be mapped to a null key");
                                value = map.get(key);

                                if (value == null) {
                                    value = downstreamSupplier.get();
                                    map.put(key, value);
                                }

                                downstreamAccumulator.accept(value, next);
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return map;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            close();
            throw N.toRuntimeException(eHolder.value());
        }

        Map<K, A> intermediate = null;

        try {
            for (ContinuableFuture<Map<K, A>> future : futureList) {
                if (intermediate == null) {
                    intermediate = future.get();
                } else {
                    final Map<K, A> m = future.get();
                    K key = null;

                    for (Map.Entry<K, A> entry : m.entrySet()) {
                        key = entry.getKey();

                        if (intermediate.containsKey(key)) {
                            intermediate.put(key, downstreamCombiner.apply(intermediate.get(key), m.get(key)));
                        } else {
                            intermediate.put(key, m.get(key));
                        }
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        } finally {
            close();
        }

        final BiFunction<? super K, ? super A, ? extends A> function = new BiFunction<K, A, A>() {
            @Override
            public A apply(K k, A v) {
                return (A) downstreamFinisher.apply(v);
            }
        };

        Collectors.replaceAll(intermediate, function);

        return (M) intermediate;
    }

    @Override
    public <K, U, V extends Collection<U>, M extends Multimap<K, U, V>> M toMultimap(final Function<? super T, ? extends K> keyExtractor,
            final Function<? super T, ? extends U> valueMapper, final Supplier<M> mapFactory) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.toMultimap(keyExtractor, valueMapper, mapFactory);
        }

        // return collect(Collectors.toMultimap(keyExtractor, valueMapper, mapFactory));

        //    final M res = mapFactory.get();
        //    final ConcurrentMap<K, List<U>> tmp = collect(Collectors.groupingByConcurrent(keyExtractor, Collectors.mapping(valueMapper, Collectors.<U> toList())));
        //
        //    for (Map.Entry<K, List<U>> entry : tmp.entrySet()) {
        //        res.putAll(entry.getKey(), entry.getValue());
        //    }
        //
        //    return res;

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<M>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Callable<M>() {
                    @Override
                    public M call() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        M map = mapFactory.get();

                        try {
                            while (cursor < to && eHolder.value() == null) {
                                map.put(keyExtractor.apply(elements[cursor]), valueMapper.apply(elements[cursor]));
                                cursor++;
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return map;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Callable<M>() {

                    @Override
                    public M call() {
                        M map = mapFactory.get();
                        T next = null;

                        try {
                            while (eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                map.put(keyExtractor.apply(next), valueMapper.apply(next));
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return map;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            close();
            throw N.toRuntimeException(eHolder.value());
        }

        M res = null;

        try {
            for (ContinuableFuture<M> future : futureList) {
                if (res == null) {
                    res = future.get();
                } else {
                    final M m = future.get();

                    for (Map.Entry<K, V> entry : m.entrySet()) {
                        res.putAll(entry.getKey(), entry.getValue());
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        } finally {
            close();
        }

        return res;
    }

    @Override
    public <A, D> Map<Boolean, D> partitionTo(final Predicate<? super T> predicate, Collector<? super T, A, D> downstream) {
        final Function<T, Boolean> keyExtractor = new Function<T, Boolean>() {
            @Override
            public Boolean apply(T t) {
                return predicate.test(t);
            }
        };

        final Supplier<Map<Boolean, D>> mapFactory = Fn.Suppliers.ofMap();
        final Map<Boolean, D> map = toMap(keyExtractor, downstream, mapFactory);

        if (map.containsKey(Boolean.TRUE) == false) {
            map.put(Boolean.TRUE, downstream.finisher().apply(downstream.supplier().get()));
        } else if (map.containsKey(Boolean.FALSE) == false) {
            map.put(Boolean.FALSE, downstream.finisher().apply(downstream.supplier().get()));
        }

        return map;
    }

    @Override
    public T reduce(final T identity, final BinaryOperator<T> accumulator) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.reduce(identity, accumulator);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<T>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Callable<T>() {
                    @Override
                    public T call() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        T result = identity;

                        try {
                            while (cursor < to && eHolder.value() == null) {
                                result = accumulator.apply(result, elements[cursor++]);
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return result;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Callable<T>() {

                    @Override
                    public T call() {
                        T result = identity;
                        T next = null;

                        try {
                            while (eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                result = accumulator.apply(result, next);
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return result;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            close();
            throw N.toRuntimeException(eHolder.value());
        }

        T result = (T) NONE;

        try {
            for (ContinuableFuture<T> future : futureList) {
                if (result == NONE) {
                    result = future.get();
                } else {
                    result = accumulator.apply(result, future.get());
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        } finally {
            close();
        }

        return result == NONE ? identity : result;
    }

    @Override
    public Optional<T> reduce(final BinaryOperator<T> accumulator) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.reduce(accumulator);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<T>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Callable<T>() {
                    @Override
                    public T call() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        if (cursor >= to) {
                            return (T) NONE;
                        }

                        T result = elements[cursor++];

                        try {
                            while (cursor < to && eHolder.value() == null) {
                                result = accumulator.apply(result, elements[cursor++]);
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return result;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Callable<T>() {

                    @Override
                    public T call() {
                        T result = null;

                        synchronized (elements) {
                            if (cursor.intValue() < toIndex) {
                                result = elements[cursor.getAndIncrement()];
                            } else {
                                return (T) NONE;
                            }
                        }

                        T next = null;

                        try {
                            while (eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                result = accumulator.apply(result, next);
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return result;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            close();
            throw N.toRuntimeException(eHolder.value());
        }

        T result = (T) NONE;

        try {
            for (ContinuableFuture<T> future : futureList) {
                final T tmp = future.get();

                if (tmp == NONE) {
                    continue;
                } else if (result == NONE) {
                    result = tmp;
                } else {
                    result = accumulator.apply(result, tmp);
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        } finally {
            close();
        }

        return result == NONE ? (Optional<T>) Optional.empty() : Optional.of(result);
    }

    @Override
    public <R> R collect(final Supplier<R> supplier, final BiConsumer<R, ? super T> accumulator, final BiConsumer<R, R> combiner) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.collect(supplier, accumulator, combiner);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<R>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Callable<R>() {
                    @Override
                    public R call() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        final R container = supplier.get();

                        try {
                            while (cursor < to && eHolder.value() == null) {
                                accumulator.accept(container, elements[cursor++]);
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return container;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Callable<R>() {

                    @Override
                    public R call() {
                        final R container = supplier.get();
                        T next = null;

                        try {
                            while (eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                accumulator.accept(container, next);
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return container;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            close();
            throw N.toRuntimeException(eHolder.value());
        }

        R container = (R) NONE;

        try {
            for (ContinuableFuture<R> future : futureList) {
                if (container == NONE) {
                    container = future.get();
                } else {
                    combiner.accept(container, future.get());
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        } finally {
            close();
        }

        return container == NONE ? supplier.get() : container;
    }

    @Override
    public <R, A> R collect(Collector<? super T, A, R> collector) {
        if (maxThreadNum <= 1 || collector.characteristics().contains(Collector.Characteristics.CONCURRENT) == false
                || collector.characteristics().contains(Collector.Characteristics.UNORDERED) == false) {
            return sequential().collect(collector);
        }

        final Supplier<A> supplier = collector.supplier();
        final BiConsumer<A, ? super T> accumulator = collector.accumulator();
        final BinaryOperator<A> combiner = collector.combiner();
        final Function<A, R> finisher = collector.finisher();

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<A>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Callable<A>() {
                    @Override
                    public A call() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        A container = supplier.get();

                        try {
                            while (cursor < to && eHolder.value() == null) {
                                accumulator.accept(container, elements[cursor++]);
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return container;
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Callable<A>() {

                    @Override
                    public A call() {
                        A container = supplier.get();
                        T next = null;

                        try {
                            while (eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                accumulator.accept(container, next);
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }

                        return container;
                    }
                }));
            }
        }

        if (eHolder.value() != null) {
            close();
            throw N.toRuntimeException(eHolder.value());
        }

        A container = (A) NONE;

        try {
            for (ContinuableFuture<A> future : futureList) {
                if (container == NONE) {
                    container = future.get();
                } else {
                    container = combiner.apply(container, future.get());
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        } finally {
            close();
        }

        return finisher.apply(container == NONE ? supplier.get() : container);
    }

    @Override
    public Optional<T> min(Comparator<? super T> comparator) {
        boolean isDone = true;

        try {
            if (fromIndex == toIndex) {
                return Optional.empty();
            } else if (sorted && isSameComparator(cmp, comparator)) {
                return Optional.of(elements[fromIndex]);
            } else {
                isDone = false;
            }
        } finally {
            if (isDone) {
                close();
            }
        }

        comparator = comparator == null ? NATURAL_COMPARATOR : comparator;

        return collect(Collectors.minBy(comparator));
    }

    @Override
    public Optional<T> max(Comparator<? super T> comparator) {
        boolean isDone = true;

        try {
            if (fromIndex == toIndex) {
                return Optional.empty();
            } else if (sorted && isSameComparator(cmp, comparator)) {
                return Optional.of(elements[toIndex - 1]);
            } else {
                isDone = false;
            }
        } finally {
            if (isDone) {
                close();
            }
        }

        comparator = comparator == null ? NATURAL_COMPARATOR : comparator;

        return collect(Collectors.maxBy(comparator));
    }

    @Override
    public <E extends Exception> boolean anyMatch(final Try.Predicate<? super T, E> predicate) throws E {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.anyMatch(predicate);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableBoolean result = MutableBoolean.of(false);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {
                    @Override
                    public void run() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        try {
                            while (cursor < to && result.isFalse() && eHolder.value() == null) {
                                if (predicate.test(elements[cursor++])) {
                                    result.setTrue();
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {

                    @Override
                    public void run() {
                        T next = null;

                        try {
                            while (result.isFalse() && eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(next)) {
                                    result.setTrue();
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }

                }));
            }
        }

        try {
            complette(futureList, eHolder, (E) null);
        } finally {
            close();
        }

        return result.value();
    }

    @Override
    public <E extends Exception> boolean allMatch(final Try.Predicate<? super T, E> predicate) throws E {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.allMatch(predicate);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableBoolean result = MutableBoolean.of(true);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {
                    @Override
                    public void run() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        try {
                            while (cursor < to && result.isTrue() && eHolder.value() == null) {
                                if (predicate.test(elements[cursor++]) == false) {
                                    result.setFalse();
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {

                    @Override
                    public void run() {
                        T next = null;

                        try {
                            while (result.isTrue() && eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(next) == false) {
                                    result.setFalse();
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }

                }));
            }
        }

        try {
            complette(futureList, eHolder, (E) null);
        } finally {
            close();
        }

        return result.value();
    }

    @Override
    public <E extends Exception> boolean noneMatch(final Try.Predicate<? super T, E> predicate) throws E {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.noneMatch(predicate);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableBoolean result = MutableBoolean.of(true);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {
                    @Override
                    public void run() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;

                        try {
                            while (cursor < to && result.isTrue() && eHolder.value() == null) {
                                if (predicate.test(elements[cursor++])) {
                                    result.setFalse();
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {

                    @Override
                    public void run() {
                        T next = null;

                        try {
                            while (result.isTrue() && eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(next)) {
                                    result.setFalse();
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }

                }));
            }
        }

        try {
            complette(futureList, eHolder, (E) null);
        } finally {
            close();
        }

        return result.value();
    }

    @Override
    public <E extends Exception> Optional<T> findFirst(final Try.Predicate<? super T, E> predicate) throws E {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.findFirst(predicate);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<Pair<Integer, T>> resultHolder = new Holder<>();

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {
                    @Override
                    public void run() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                        final Pair<Integer, T> pair = new Pair<>();

                        try {
                            while (cursor < to && (resultHolder.value() == null || cursor < resultHolder.value().left) && eHolder.value() == null) {
                                pair.left = cursor;
                                pair.right = elements[cursor++];

                                if (predicate.test(pair.right)) {
                                    synchronized (resultHolder) {
                                        if (resultHolder.value() == null || pair.left < resultHolder.value().left) {
                                            resultHolder.setValue(pair.copy());
                                        }
                                    }

                                    break;
                                }
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {

                    @Override
                    public void run() {
                        final Pair<Integer, T> pair = new Pair<>();

                        try {
                            while (resultHolder.value() == null && eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        pair.left = cursor.intValue();
                                        pair.right = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(pair.right)) {
                                    synchronized (resultHolder) {
                                        if (resultHolder.value() == null || pair.left < resultHolder.value().left) {
                                            resultHolder.setValue(pair.copy());
                                        }
                                    }

                                    break;
                                }
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }

                }));
            }
        }

        try {
            complette(futureList, eHolder, (E) null);
        } finally {
            close();
        }

        return resultHolder.value() == null ? (Optional<T>) Optional.empty() : Optional.of(resultHolder.value().right);
    }

    @Override
    public <E extends Exception> Optional<T> findLast(final Try.Predicate<? super T, E> predicate) throws E {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.findLast(predicate);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<Pair<Integer, T>> resultHolder = new Holder<>();

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {
                    @Override
                    public void run() {
                        final int from = fromIndex + sliceIndex * sliceSize;
                        int cursor = toIndex - from > sliceSize ? from + sliceSize : toIndex;
                        final Pair<Integer, T> pair = new Pair<>();

                        try {
                            while (cursor > from && (resultHolder.value() == null || cursor > resultHolder.value().left) && eHolder.value() == null) {
                                pair.left = cursor;
                                pair.right = elements[--cursor];

                                if (predicate.test(pair.right)) {
                                    synchronized (resultHolder) {
                                        if (resultHolder.value() == null || pair.left > resultHolder.value().left) {
                                            resultHolder.setValue(pair.copy());
                                        }
                                    }

                                    break;
                                }
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(toIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {

                    @Override
                    public void run() {
                        final Pair<Integer, T> pair = new Pair<>();

                        try {
                            while (resultHolder.value() == null && eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() > fromIndex) {
                                        pair.left = cursor.intValue();
                                        pair.right = elements[cursor.decrementAndGet()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(pair.right)) {
                                    synchronized (resultHolder) {
                                        if (resultHolder.value() == null || pair.left > resultHolder.value().left) {
                                            resultHolder.setValue(pair.copy());
                                        }
                                    }

                                    break;
                                }
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }

                }));
            }
        }

        try {
            complette(futureList, eHolder, (E) null);
        } finally {
            close();
        }

        return resultHolder.value() == null ? (Optional<T>) Optional.empty() : Optional.of(resultHolder.value().right);
    }

    @Override
    public <E extends Exception> Optional<T> findAny(final Try.Predicate<? super T, E> predicate) throws E {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return super.findAny(predicate);
        }

        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(threadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<T> resultHolder = Holder.of((T) NONE);

        if (splitor == Splitor.ARRAY) {
            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);

            for (int i = 0; i < threadNum; i++) {
                final int sliceIndex = i;

                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {
                    @Override
                    public void run() {
                        int cursor = fromIndex + sliceIndex * sliceSize;
                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
                        T next = null;

                        try {
                            while (cursor < to && resultHolder.value() == NONE && eHolder.value() == null) {
                                next = elements[cursor++];

                                if (predicate.test(next)) {
                                    synchronized (resultHolder) {
                                        if (resultHolder.value() == NONE) {
                                            resultHolder.setValue(next);
                                        }
                                    }

                                    break;
                                }
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }
                }));
            }
        } else {
            final MutableInt cursor = MutableInt.of(fromIndex);

            for (int i = 0; i < threadNum; i++) {
                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {

                    @Override
                    public void run() {
                        T next = null;

                        try {
                            while (resultHolder.value() == NONE && eHolder.value() == null) {
                                synchronized (elements) {
                                    if (cursor.intValue() < toIndex) {
                                        next = elements[cursor.getAndIncrement()];
                                    } else {
                                        break;
                                    }
                                }

                                if (predicate.test(next)) {
                                    synchronized (resultHolder) {
                                        if (resultHolder.value() == NONE) {
                                            resultHolder.setValue(next);
                                        }
                                    }

                                    break;
                                }
                            }
                        } catch (Exception e) {
                            setError(eHolder, e);
                        }
                    }

                }));
            }
        }

        try {
            complette(futureList, eHolder, (E) null);
        } finally {
            close();
        }

        return resultHolder.value() == NONE ? (Optional<T>) Optional.empty() : Optional.of(resultHolder.value());
    }

    @Override
    public Stream<T> intersection(final Function<? super T, ?> mapper, final Collection<?> c) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorStream<>(sequential().intersection(mapper, c).iterator(), sorted, cmp, maxThreadNum, splitor, asyncExecutor,
                    closeHandlers);
        }

        final Multiset<?> multiset = Multiset.from(c);

        return filter(new Predicate<T>() {
            @Override
            public boolean test(T value) {
                final Object key = mapper.apply(value);

                synchronized (multiset) {
                    return multiset.getAndRemove(key) > 0;
                }
            }
        });
    }

    @Override
    public Stream<T> difference(final Function<? super T, ?> mapper, final Collection<?> c) {
        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
            return new ParallelIteratorStream<>(sequential().intersection(mapper, c).iterator(), sorted, cmp, maxThreadNum, splitor, asyncExecutor,
                    closeHandlers);
        }

        final Multiset<?> multiset = Multiset.from(c);

        return filter(new Predicate<T>() {
            @Override
            public boolean test(T value) {
                final Object key = mapper.apply(value);

                synchronized (multiset) {
                    return multiset.getAndRemove(key) < 1;
                }
            }
        });
    }

    @Override
    public Stream<T> append(Stream<T> stream) {
        return new ParallelIteratorStream<>(Stream.concat(this, stream), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public Stream<T> prepend(Stream<T> stream) {
        return new ParallelIteratorStream<>(Stream.concat(stream, this), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public Stream<T> merge(final Stream<? extends T> b, final BiFunction<? super T, ? super T, Nth> nextSelector) {
        return new ParallelIteratorStream<>(Stream.merge(this, b, nextSelector), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public <T2, R> Stream<R> zipWith(Stream<T2> b, BiFunction<? super T, ? super T2, R> zipFunction) {
        return new ParallelIteratorStream<>(Stream.zip(this, b, zipFunction), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public <T2, T3, R> Stream<R> zipWith(Stream<T2> b, Stream<T3> c, TriFunction<? super T, ? super T2, ? super T3, R> zipFunction) {
        return new ParallelIteratorStream<>(Stream.zip(this, b, c, zipFunction), false, null, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    public <T2, R> Stream<R> zipWith(Stream<T2> b, T valueForNoneA, T2 valueForNoneB, BiFunction<? super T, ? super T2, R> zipFunction) {
        return new ParallelIteratorStream<>(Stream.zip(this, b, valueForNoneA, valueForNoneB, zipFunction), false, null, maxThreadNum, splitor, asyncExecutor,
                closeHandlers);
    }

    @Override
    public <T2, T3, R> Stream<R> zipWith(Stream<T2> b, Stream<T3> c, T valueForNoneA, T2 valueForNoneB, T3 valueForNoneC,
            TriFunction<? super T, ? super T2, ? super T3, R> zipFunction) {
        return new ParallelIteratorStream<>(Stream.zip(this, b, c, valueForNoneA, valueForNoneB, valueForNoneC, zipFunction), false, null, maxThreadNum,
                splitor, asyncExecutor, closeHandlers);
    }

    //    @Override
    //    public long persist(final PreparedStatement stmt, final int batchSize, final int batchInterval,
    //            final Try.BiConsumer<? super PreparedStatement, ? super T, SQLException> stmtSetter) {
    //        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
    //                batchInterval);
    //
    //        if (maxThreadNum <= 1 || toIndex - fromIndex <= 1) {
    //            return sequential().persist(stmt, batchSize, batchInterval, stmtSetter);
    //        }
    //
    //        final int threadNum = N.min(maxThreadNum, (toIndex - fromIndex));
    //        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(threadNum);
    //        final Holder<Throwable> eHolder = new Holder<>();
    //        final AtomicLong result = new AtomicLong();
    //
    //        if (splitor == Splitor.ARRAY) {
    //            final int sliceSize = (toIndex - fromIndex) / threadNum + ((toIndex - fromIndex) % threadNum == 0 ? 0 : 1);
    //
    //            for (int i = 0; i < threadNum; i++) {
    //                final int sliceIndex = i;
    //
    //                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {
    //                    @Override
    //                    public void run() {
    //                        int cursor = fromIndex + sliceIndex * sliceSize;
    //                        final int to = toIndex - cursor > sliceSize ? cursor + sliceSize : toIndex;
    //                        long cnt = 0;
    //
    //                        try {
    //                            while (cursor < to && eHolder.value() == null) {
    //                                stmtSetter.accept(stmt, elements[cursor++]);
    //                                stmt.addBatch();
    //
    //                                if ((++cnt % batchSize) == 0) {
    //                                    stmt.executeBatch();
    //                                    stmt.clearBatch();
    //
    //                                    if (batchInterval > 0) {
    //                                        N.sleep(batchInterval);
    //                                    }
    //                                }
    //                            }
    //
    //                            if ((cnt % batchSize) > 0) {
    //                                stmt.executeBatch();
    //                                stmt.clearBatch();
    //                            }
    //
    //                            result.addAndGet(cnt);
    //                        } catch (Exception e) {
    //                            setError(eHolder, e);
    //                        }
    //                    }
    //                }));
    //            }
    //        } else {
    //            final MutableInt cursor = MutableInt.of(fromIndex);
    //
    //            for (int i = 0; i < threadNum; i++) {
    //                futureList.add(asyncExecutor.execute(new Try.Runnable<RuntimeException>() {
    //
    //                    @Override
    //                    public void run() {
    //                        long cnt = 0;
    //                        T next = null;
    //
    //                        try {
    //                            while (eHolder.value() == null) {
    //                                synchronized (elements) {
    //                                    if (cursor.intValue() < toIndex) {
    //                                        next = elements[cursor.getAndIncrement()];
    //                                    } else {
    //                                        break;
    //                                    }
    //                                }
    //
    //                                stmtSetter.accept(stmt, next);
    //                                stmt.addBatch();
    //
    //                                if ((++cnt % batchSize) == 0) {
    //                                    stmt.executeBatch();
    //                                    stmt.clearBatch();
    //
    //                                    if (batchInterval > 0) {
    //                                        N.sleep(batchInterval);
    //                                    }
    //                                }
    //                            }
    //
    //                            if ((cnt % batchSize) > 0) {
    //                                stmt.executeBatch();
    //                                stmt.clearBatch();
    //                            }
    //
    //                            result.addAndGet(cnt);
    //                        } catch (Exception e) {
    //                            setError(eHolder, e);
    //                        }
    //                    }
    //
    //                }));
    //            }
    //        }
    //
    //        complete(futureList, eHolder);
    //
    //        return result.longValue();
    //    }

    @Override
    public boolean isParallel() {
        return true;
    }

    @Override
    public Stream<T> sequential() {
        ArrayStream<T> tmp = sequential;

        if (tmp == null) {
            tmp = new ArrayStream<>(elements, fromIndex, toIndex, sorted, cmp, closeHandlers);
            sequential = tmp;
        }

        return tmp;
    }

    @Override
    public Stream<T> parallel(int maxThreadNum, Splitor splitor) {
        if (this.maxThreadNum == checkMaxThreadNum(maxThreadNum) && this.splitor == checkSplitor(splitor)) {
            return this;
        }

        return new ParallelArrayStream<>(elements, fromIndex, toIndex, sorted, cmp, maxThreadNum, splitor, asyncExecutor, closeHandlers);
    }

    @Override
    protected int maxThreadNum() {
        return maxThreadNum;
    }

    @Override
    protected BaseStream.Splitor splitor() {
        return splitor;
    }

    @Override
    protected AsyncExecutor asyncExecutor() {
        return asyncExecutor;
    }

    @Override
    public Stream<T> onClose(Runnable closeHandler) {
        final Deque<Runnable> newCloseHandlers = new LocalArrayDeque<>(N.isNullOrEmpty(this.closeHandlers) ? 1 : this.closeHandlers.size() + 1);

        newCloseHandlers.add(wrapCloseHandlers(closeHandler));

        if (N.notNullOrEmpty(this.closeHandlers)) {
            newCloseHandlers.addAll(this.closeHandlers);
        }

        return new ParallelArrayStream<>(elements, fromIndex, toIndex, sorted, cmp, maxThreadNum, splitor, asyncExecutor, newCloseHandlers);
    }
}
