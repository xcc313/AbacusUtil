/*
 * Copyright (C) 2016 HaiYang Li
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Holder;
import com.landawn.abacus.util.MutableBoolean;
import com.landawn.abacus.util.MutableLong;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Nth;
import com.landawn.abacus.util.OptionalShort;
import com.landawn.abacus.util.Pair;
import com.landawn.abacus.util.ShortIterator;
import com.landawn.abacus.util.Try;
import com.landawn.abacus.util.function.BiConsumer;
import com.landawn.abacus.util.function.BinaryOperator;
import com.landawn.abacus.util.function.Consumer;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.ObjShortConsumer;
import com.landawn.abacus.util.function.Predicate;
import com.landawn.abacus.util.function.ShortBiFunction;
import com.landawn.abacus.util.function.ShortBinaryOperator;
import com.landawn.abacus.util.function.ShortConsumer;
import com.landawn.abacus.util.function.ShortFunction;
import com.landawn.abacus.util.function.ShortPredicate;
import com.landawn.abacus.util.function.ShortToIntFunction;
import com.landawn.abacus.util.function.ShortTriFunction;
import com.landawn.abacus.util.function.ShortUnaryOperator;
import com.landawn.abacus.util.function.Supplier;
import com.landawn.abacus.util.function.ToIntFunction;
import com.landawn.abacus.util.function.ToShortFunction;

/**
 * This class is a sequential, stateful and immutable stream implementation.
 *
 * @since 0.8
 *
 * @author Haiyang Li
 */
final class ParallelIteratorShortStream extends IteratorShortStream {
    private final int maxThreadNum;
    private final Splitor splitor;
    private volatile IteratorShortStream sequential;
    private volatile Stream<Short> boxed;

    ParallelIteratorShortStream(final ShortIterator values, final boolean sorted, final int maxThreadNum, final Splitor splitor,
            final Collection<Runnable> closeHandlers) {
        super(values, sorted, closeHandlers);

        this.maxThreadNum = checkMaxThreadNum(maxThreadNum);
        this.splitor = splitor == null ? DEFAULT_SPLITOR : splitor;
    }

    ParallelIteratorShortStream(final ShortStream stream, final boolean sorted, final int maxThreadNum, final Splitor splitor,
            final Set<Runnable> closeHandlers) {
        this(stream.iteratorEx(), sorted, maxThreadNum, splitor, mergeCloseHandlers(stream, closeHandlers));
    }

    ParallelIteratorShortStream(final Stream<Short> stream, final boolean sorted, final int maxThreadNum, final Splitor splitor,
            final Set<Runnable> closeHandlers) {
        this(shortIterator(stream.iteratorEx()), sorted, maxThreadNum, splitor, mergeCloseHandlers(stream, closeHandlers));
    }

    @Override
    public ShortStream filter(final ShortPredicate predicate) {
        if (maxThreadNum <= 1) {
            return super.filter(predicate);
        }

        final Stream<Short> stream = boxed().filter(new Predicate<Short>() {
            @Override
            public boolean test(Short value) {
                return predicate.test(value);
            }
        });

        return new ParallelIteratorShortStream(stream, false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public ShortStream takeWhile(final ShortPredicate predicate) {
        if (maxThreadNum <= 1) {
            return super.takeWhile(predicate);
        }

        final Stream<Short> stream = boxed().takeWhile(new Predicate<Short>() {
            @Override
            public boolean test(Short value) {
                return predicate.test(value);
            }
        });

        return new ParallelIteratorShortStream(stream, false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public ShortStream dropWhile(final ShortPredicate predicate) {
        if (maxThreadNum <= 1) {
            return super.dropWhile(predicate);
        }

        final Stream<Short> stream = boxed().dropWhile(new Predicate<Short>() {
            @Override
            public boolean test(Short value) {
                return predicate.test(value);
            }
        });

        return new ParallelIteratorShortStream(stream, false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public ShortStream map(final ShortUnaryOperator mapper) {
        if (maxThreadNum <= 1) {
            return super.map(mapper);
        }

        final ShortStream stream = boxed().mapToShort(new ToShortFunction<Short>() {
            @Override
            public short applyAsShort(Short value) {
                return mapper.applyAsShort(value);
            }
        });

        return new ParallelIteratorShortStream(stream, false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public IntStream mapToInt(final ShortToIntFunction mapper) {
        if (maxThreadNum <= 1) {
            return super.mapToInt(mapper);
        }

        final IntStream stream = boxed().mapToInt(new ToIntFunction<Short>() {
            @Override
            public int applyAsInt(Short value) {
                return mapper.applyAsInt(value);
            }
        });

        return new ParallelIteratorIntStream(stream, false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public <U> Stream<U> mapToObj(final ShortFunction<? extends U> mapper) {
        if (maxThreadNum <= 1) {
            return super.mapToObj(mapper);
        }

        return boxed().map(new Function<Short, U>() {
            @Override
            public U apply(Short value) {
                return mapper.apply(value);
            }
        });
    }

    @Override
    public ShortStream flatMap(final ShortFunction<? extends ShortStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorShortStream(sequential().flatMap(mapper), false, maxThreadNum, splitor, null);
        }

        final ShortStream stream = boxed().flatMapToShort(new Function<Short, ShortStream>() {
            @Override
            public ShortStream apply(Short value) {
                return mapper.apply(value);
            }
        });

        return new ParallelIteratorShortStream(stream, false, maxThreadNum, splitor, null);
    }

    @Override
    public IntStream flatMapToInt(final ShortFunction<? extends IntStream> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorIntStream(sequential().flatMapToInt(mapper), false, maxThreadNum, splitor, null);
        }

        final IntStream stream = boxed().flatMapToInt(new Function<Short, IntStream>() {
            @Override
            public IntStream apply(Short value) {
                return mapper.apply(value);
            }
        });

        return new ParallelIteratorIntStream(stream, false, maxThreadNum, splitor, null);
    }

    @Override
    public <T> Stream<T> flatMapToObj(final ShortFunction<? extends Stream<T>> mapper) {
        if (maxThreadNum <= 1) {
            return new ParallelIteratorStream<>(sequential().flatMapToObj(mapper), false, null, maxThreadNum, splitor, null);
        }

        return boxed().flatMap(new Function<Short, Stream<T>>() {
            @Override
            public Stream<T> apply(Short value) {
                return mapper.apply(value);
            }
        });
    }

    @Override
    public ShortStream peek(final ShortConsumer action) {
        if (maxThreadNum <= 1) {
            return super.peek(action);
        }

        final ShortStream stream = boxed().peek(new Consumer<Short>() {
            @Override
            public void accept(Short t) {
                action.accept(t);
            }
        }).sequential().mapToShort(ToShortFunction.UNBOX);

        return new ParallelIteratorShortStream(stream, false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public <E extends Exception> void forEach(final Try.ShortConsumer<E> action) throws E {
        if (maxThreadNum <= 1) {
            super.forEach(action);
            return;
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    short next = 0;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.nextShort();
                                } else {
                                    break;
                                }
                            }

                            action.accept(next);
                        }
                    } catch (Throwable e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);
    }

    @Override
    public <K, V, M extends Map<K, V>> M toMap(final ShortFunction<? extends K> keyExtractor, final ShortFunction<? extends V> valueMapper,
            final BinaryOperator<V> mergeFunction, final Supplier<M> mapFactory) {
        if (maxThreadNum <= 1) {
            return super.toMap(keyExtractor, valueMapper, mergeFunction, mapFactory);
        }

        final Function<? super Short, ? extends K> keyExtractor2 = new Function<Short, K>() {
            @Override
            public K apply(Short value) {
                return keyExtractor.apply(value);
            }
        };

        final Function<? super Short, ? extends V> valueMapper2 = new Function<Short, V>() {
            @Override
            public V apply(Short value) {
                return valueMapper.apply(value);
            }
        };

        return boxed().toMap(keyExtractor2, valueMapper2, mergeFunction, mapFactory);
    }

    @Override
    public <K, A, D, M extends Map<K, D>> M toMap(final ShortFunction<? extends K> classifier, final Collector<Short, A, D> downstream,
            final Supplier<M> mapFactory) {
        if (maxThreadNum <= 1) {
            return super.toMap(classifier, downstream, mapFactory);
        }

        final Function<? super Short, ? extends K> classifier2 = new Function<Short, K>() {
            @Override
            public K apply(Short value) {
                return classifier.apply(value);
            }
        };

        return boxed().toMap(classifier2, downstream, mapFactory);
    }

    @Override
    public short reduce(final short identity, final ShortBinaryOperator op) {
        if (maxThreadNum <= 1) {
            return super.reduce(identity, op);
        }

        final List<ContinuableFuture<Short>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Callable<Short>() {
                @Override
                public Short call() {
                    short result = identity;
                    short next = 0;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.nextShort();
                                } else {
                                    break;
                                }
                            }

                            result = op.applyAsShort(result, next);
                        }
                    } catch (Throwable e) {
                        setError(eHolder, e);
                    }

                    return result;
                }
            }));
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        Short result = null;

        try {
            for (ContinuableFuture<Short> future : futureList) {
                if (result == null) {
                    result = future.get();
                } else {
                    result = op.applyAsShort(result, future.get());
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        }

        return result == null ? identity : result;
    }

    @Override
    public OptionalShort reduce(final ShortBinaryOperator accumulator) {
        if (maxThreadNum <= 1) {
            return super.reduce(accumulator);
        }

        final List<ContinuableFuture<Short>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Callable<Short>() {
                @Override
                public Short call() {
                    short result = 0;

                    synchronized (elements) {
                        if (elements.hasNext()) {
                            result = elements.nextShort();
                        } else {
                            return null;
                        }
                    }

                    short next = 0;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.nextShort();
                                } else {
                                    break;
                                }
                            }

                            result = accumulator.applyAsShort(result, next);
                        }
                    } catch (Throwable e) {
                        setError(eHolder, e);
                    }

                    return result;
                }
            }));
        }

        if (eHolder.value() != null) {
            throw N.toRuntimeException(eHolder.value());
        }

        Short result = null;

        try {
            for (ContinuableFuture<Short> future : futureList) {
                final Short tmp = future.get();

                if (tmp == null) {
                    continue;
                } else if (result == null) {
                    result = tmp;
                } else {
                    result = accumulator.applyAsShort(result, tmp);
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            throw N.toRuntimeException(e);
        }

        return result == null ? OptionalShort.empty() : OptionalShort.of(result);
    }

    @Override
    public <R> R collect(final Supplier<R> supplier, final ObjShortConsumer<R> accumulator, final BiConsumer<R, R> combiner) {
        if (maxThreadNum <= 1) {
            return super.collect(supplier, accumulator, combiner);
        }

        final List<ContinuableFuture<R>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Callable<R>() {
                @Override
                public R call() {
                    final R container = supplier.get();
                    short next = 0;

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.nextShort();
                                } else {
                                    break;
                                }
                            }

                            accumulator.accept(container, next);
                        }
                    } catch (Throwable e) {
                        setError(eHolder, e);
                    }

                    return container;
                }
            }));
        }

        if (eHolder.value() != null) {
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
        }

        return container == NONE ? supplier.get() : container;
    }

    @Override
    public <E extends Exception> boolean anyMatch(final Try.ShortPredicate<E> predicate) throws E {
        if (maxThreadNum <= 1) {
            return super.anyMatch(predicate);
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableBoolean result = MutableBoolean.of(false);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    short next = 0;

                    try {
                        while (result.isFalse() && eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.nextShort();
                                } else {
                                    break;
                                }
                            }

                            if (predicate.test(next)) {
                                result.setTrue();
                                break;
                            }
                        }
                    } catch (Throwable e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);

        return result.value();
    }

    @Override
    public <E extends Exception> boolean allMatch(final Try.ShortPredicate<E> predicate) throws E {
        if (maxThreadNum <= 1) {
            return super.allMatch(predicate);
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableBoolean result = MutableBoolean.of(true);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    short next = 0;

                    try {
                        while (result.isTrue() && eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.nextShort();
                                } else {
                                    break;
                                }
                            }

                            if (predicate.test(next) == false) {
                                result.setFalse();
                                break;
                            }
                        }
                    } catch (Throwable e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);

        return result.value();
    }

    @Override
    public <E extends Exception> boolean noneMatch(final Try.ShortPredicate<E> predicate) throws E {
        if (maxThreadNum <= 1) {
            return super.noneMatch(predicate);
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final MutableBoolean result = MutableBoolean.of(true);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    short next = 0;

                    try {
                        while (result.isTrue() && eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.nextShort();
                                } else {
                                    break;
                                }
                            }

                            if (predicate.test(next)) {
                                result.setFalse();
                                break;
                            }
                        }
                    } catch (Throwable e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);

        return result.value();
    }

    @Override
    public <E extends Exception> OptionalShort findFirst(final Try.ShortPredicate<E> predicate) throws E {
        if (maxThreadNum <= 1) {
            return super.findFirst(predicate);
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<Pair<Long, Short>> resultHolder = new Holder<>();
        final MutableLong index = MutableLong.of(0);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    final Pair<Long, Short> pair = new Pair<>();

                    try {
                        while (resultHolder.value() == null && eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    pair.left = index.getAndIncrement();
                                    pair.right = elements.nextShort();
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
                    } catch (Throwable e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);

        return resultHolder.value() == null ? OptionalShort.empty() : OptionalShort.of(resultHolder.value().right);
    }

    @Override
    public <E extends Exception> OptionalShort findLast(final Try.ShortPredicate<E> predicate) throws E {
        if (maxThreadNum <= 1) {
            return super.findLast(predicate);
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<Pair<Long, Short>> resultHolder = new Holder<>();
        final MutableLong index = MutableLong.of(0);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    final Pair<Long, Short> pair = new Pair<>();

                    try {
                        while (eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    pair.left = index.getAndIncrement();
                                    pair.right = elements.nextShort();
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
                            }
                        }
                    } catch (Throwable e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);

        return resultHolder.value() == null ? OptionalShort.empty() : OptionalShort.of(resultHolder.value().right);
    }

    @Override
    public <E extends Exception> OptionalShort findAny(final Try.ShortPredicate<E> predicate) throws E {
        if (maxThreadNum <= 1) {
            return super.findAny(predicate);
        }

        final List<ContinuableFuture<Void>> futureList = new ArrayList<>(maxThreadNum);
        final Holder<Throwable> eHolder = new Holder<>();
        final Holder<Object> resultHolder = Holder.of(NONE);

        for (int i = 0; i < maxThreadNum; i++) {
            futureList.add(asyncExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    short next = 0;

                    try {
                        while (resultHolder.value() == NONE && eHolder.value() == null) {
                            synchronized (elements) {
                                if (elements.hasNext()) {
                                    next = elements.nextShort();
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
                    } catch (Throwable e) {
                        setError(eHolder, e);
                    }
                }
            }));
        }

        complette(futureList, eHolder, (E) null);

        return resultHolder.value() == NONE ? OptionalShort.empty() : OptionalShort.of((Short) resultHolder.value());
    }

    @Override
    public Stream<Short> boxed() {
        Stream<Short> tmp = boxed;

        if (tmp == null) {
            tmp = new ParallelIteratorStream<>(iterator(), sorted, sorted ? SHORT_COMPARATOR : null, maxThreadNum, splitor, closeHandlers);
            boxed = tmp;
        }

        return tmp;
    }

    @Override
    public ShortStream append(ShortStream stream) {
        return new ParallelIteratorShortStream(ShortStream.concat(this, stream), false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public ShortStream prepend(ShortStream stream) {
        return new ParallelIteratorShortStream(ShortStream.concat(stream, this), false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public ShortStream merge(final ShortStream b, final ShortBiFunction<Nth> nextSelector) {
        return new ParallelIteratorShortStream(ShortStream.merge(this, b, nextSelector), false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public ShortStream zipWith(ShortStream b, ShortBiFunction<Short> zipFunction) {
        return new ParallelIteratorShortStream(ShortStream.zip(this, b, zipFunction), false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public ShortStream zipWith(ShortStream b, ShortStream c, ShortTriFunction<Short> zipFunction) {
        return new ParallelIteratorShortStream(ShortStream.zip(this, b, c, zipFunction), false, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public ShortStream zipWith(ShortStream b, short valueForNoneA, short valueForNoneB, ShortBiFunction<Short> zipFunction) {
        return new ParallelIteratorShortStream(ShortStream.zip(this, b, valueForNoneA, valueForNoneB, zipFunction), false, maxThreadNum, splitor,
                closeHandlers);
    }

    @Override
    public ShortStream zipWith(ShortStream b, ShortStream c, short valueForNoneA, short valueForNoneB, short valueForNoneC,
            ShortTriFunction<Short> zipFunction) {
        return new ParallelIteratorShortStream(ShortStream.zip(this, b, c, valueForNoneA, valueForNoneB, valueForNoneC, zipFunction), false, maxThreadNum,
                splitor, closeHandlers);
    }

    @Override
    public boolean isParallel() {
        return true;
    }

    @Override
    public ShortStream sequential() {
        IteratorShortStream tmp = sequential;

        if (tmp == null) {
            tmp = new IteratorShortStream(elements, sorted, closeHandlers);
            sequential = tmp;
        }

        return tmp;
    }

    @Override
    public ShortStream parallel(int maxThreadNum, Splitor splitor) {
        if (this.maxThreadNum == checkMaxThreadNum(maxThreadNum) && this.splitor == splitor) {
            return this;
        }

        return new ParallelIteratorShortStream(elements, sorted, maxThreadNum, splitor, closeHandlers);
    }

    @Override
    public int maxThreadNum() {
        return maxThreadNum;
    }

    //    @Override
    //    public ShortStream maxThreadNum(int maxThreadNum) {
    //        if (this.maxThreadNum == checkMaxThreadNum(maxThreadNum)) {
    //            return this;
    //        }
    //
    //        return new ParallelIteratorShortStream(elements, sorted, maxThreadNum, splitor, closeHandlers);
    //    }

    @Override
    public BaseStream.Splitor splitor() {
        return splitor;
    }

    //    @Override
    //    public ShortStream splitor(BaseStream.Splitor splitor) {
    //        if (this.splitor == splitor) {
    //            return this;
    //        }
    //
    //        return new ParallelIteratorShortStream(elements, sorted, maxThreadNum, splitor, closeHandlers);
    //    }

    @Override
    public ShortStream onClose(Runnable closeHandler) {
        final Set<Runnable> newCloseHandlers = new AbstractStream.LocalLinkedHashSet<>(N.isNullOrEmpty(this.closeHandlers) ? 1 : this.closeHandlers.size() + 1);

        if (N.notNullOrEmpty(this.closeHandlers)) {
            newCloseHandlers.addAll(this.closeHandlers);
        }

        newCloseHandlers.add(closeHandler);

        return new ParallelIteratorShortStream(elements, sorted, maxThreadNum, splitor, newCloseHandlers);
    }
}
