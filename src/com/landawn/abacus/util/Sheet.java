/*
 * Copyright (c) 2015, Haiyang Li.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.landawn.abacus.util;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.stream.Stream;

/**
 *
 * @since 0.8
 *
 * @author Haiyang Li
 */
public interface Sheet<R, C, E> {

    Set<R> rowKeySet();

    Set<C> columnKeySet();

    E get(Object rowKey, Object columnKey);

    E put(R rowKey, C columnKey, E value);

    void putAll(Sheet<? extends R, ? extends C, ? extends E> source);

    E remove(Object rowKey, Object columnKey);

    boolean contains(Object rowKey, Object columnKey);

    boolean containsValue(Object value);

    List<E> getRow(Object rowKey);

    void setRow(R rowKey, Collection<? extends E> row);

    void addRow(R rowKey, Collection<? extends E> row);

    void addRow(int rowIndex, R rowKey, Collection<? extends E> row);

    void updateRow(R rowKey, Function<? super E, E> func);

    void removeRow(Object rowKey);

    /**
     * Move the specified row to the new position.
     * 
     * @param rowKey
     * @param newRowIndex
     */
    void moveRow(Object rowKey, int newRowIndex);

    /**
     * Swap the positions of the two specified rows.
     * 
     * @param rowKeyA
     * @param rowKeyB
     */
    void swapRow(Object rowKeyA, Object rowKeyB);

    void renameRow(R rowKey, R newRowKey);

    boolean containsRow(Object rowKey);

    Map<C, E> row(Object rowKey);

    Map<R, Map<C, E>> rowMap();

    List<E> getColumn(Object columnKey);

    void setColumn(C columnKey, Collection<? extends E> column);

    void addColumn(C columnKey, Collection<? extends E> column);

    void addColumn(int columnIndex, C columnKey, Collection<? extends E> column);

    void updateColumn(C columnKey, Function<? super E, E> func);

    void removeColumn(Object columnKey);

    /**
     * Move the specified column to the new position.
     * 
     * @param columnKey
     * @param newColumnIndex
     */
    void moveColumn(Object columnKey, int newColumnIndex);

    /**
     * Swap the positions of the two specified columns.
     * 
     * @param columnKeyA
     * @param columnKeyB
     */
    void swapColumn(Object columnKeyA, Object columnKeyB);

    void renameColumn(C columnKey, C newColumnKey);

    boolean containsColumn(Object columnKey);

    Map<R, E> column(Object columnKey);

    Map<C, Map<R, E>> columnMap();

    /**
     * Returns the size of row key set.
     *
     * @return
     */
    int rowLength();

    /**
     * Returns the size of column key set.
     *
     * @return
     */
    int columnLength();

    void updateAll(Function<? super E, E> func);

    Sheet<R, C, E> copy();

    Sheet<R, C, E> copy(Collection<R> rowKeySet, Collection<C> columnKeySet);

    /**
     * Deeply copy each element in this <code>Sheet</code> by Serialization/Deserialization.
     * 
     * @return
     */
    Sheet<R, C, E> clone();

    /**
     * Deeply copy each element in this <code>Sheet</code> by Serialization/Deserialization.
     * 
     * @return
     */
    Sheet<R, C, E> clone(boolean freeze);

    Sheet<R, C, E> merge(Sheet<? extends R, ? extends C, ? extends E> source, BiFunction<? super E, ? super E, E> mergeFunction);

    Sheet<C, R, E> transpose();

    /**
     * Method freeze
     */
    void freeze();

    /**
     * Method frozen
     *
     * @return
     */
    boolean frozen();

    void clear();

    void trimToSize();

    /**
     * 
     * @return a stream of Cells based on the order of row.
     */
    Stream<Cell<R, C, E>> cells();

    /**
     * 
     * @return a stream of Cells based on the order of row.
     */
    Stream<Cell<R, C, E>> cells(int fromRowIndex, int toRowIndex);

    /**
     * 
     * @return a stream of Cells based on the order of column.
     */
    @Beta
    Stream<Cell<R, C, E>> cells0();

    /**
     * 
     * @return a stream of Cells based on the order of column.
     */
    @Beta
    Stream<Cell<R, C, E>> cells0(int fromColumnIndex, int toColumnIndex);

    /**
     * 
     * @return a stream based on the order of row.
     */
    Stream<E> stream();

    /**
     * 
     * @param fromRowIndex
     * @param toRowIndex
     * @return a stream based on the order of row.
     */
    Stream<E> stream(int fromRowIndex, int toRowIndex);

    /**
     * 
     * @return a stream based on the order of column.
     */
    @Beta
    Stream<E> stream0();

    /**
     * 
     * @param fromColumnIndex
     * @param toColumnIndex
     * @return a stream based on the order of column.
     */
    @Beta
    Stream<E> stream0(int fromColumnIndex, int toColumnIndex);

    /**
     * 
     * @return a row stream based on the order of row.
     */
    Stream<Stream<E>> stream2();

    /**
     * 
     * @param fromRowIndex
     * @param toRowIndex
     * @return a row stream based on the order of row.
     */
    Stream<Stream<E>> stream2(int fromRowIndex, int toRowIndex);

    /**
     * 
     * @return a column stream based on the order of column.
     */
    @Beta
    Stream<Stream<E>> stream02();

    /**
     * 
     * @param fromColumnIndex
     * @param toColumnIndex
     * @return a column stream based on the order of column.
     */
    @Beta
    Stream<Stream<E>> stream02(int fromColumnIndex, int toColumnIndex);

    /**
     * 
     * @return a DataSet based on row.
     */
    DataSet toDataSet();

    /**
     * 
     * @return a DataSet based on column.
     */
    @Beta
    DataSet toDataSet0();

    /**
     * 
     * @param cls
     * @return a Matrix based on row.
     */
    Matrix<E> toMatrix(Class<E> cls);

    /**
     * 
     * @param cls
     * @return a Matrix based on column.
     */
    @Beta
    Matrix<E> toMatrix0(Class<E> cls);

    /**
     * 
     * @return a 2D array based on row.
     */
    Object[][] toArray();

    /**
     * 
     * @return a 2D array based on row.
     */
    <T> T[][] toArray(Class<T> cls);

    /**
     * 
     * @return a 2D array based on column.
     */
    @Beta
    Object[][] toArray0();

    /**
     * 
     * @return a 2D array based on row.
     */
    @Beta
    <T> T[][] toArray0(Class<T> cls);

    void println();

    public interface Cell<R, C, E> {
        R rowKey();

        C columnKey();

        E value();
    }
}
