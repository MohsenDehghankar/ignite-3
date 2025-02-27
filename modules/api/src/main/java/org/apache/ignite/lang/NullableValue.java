/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.lang;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * A container object which contains a nullable value. {@code NullableValue} is primarily intended for use as a method return type where
 * it's not clear if the value is absent or value is {@code null}.
 *
 * <p>In contrast to {@link java.util.Optional} this class allows {@code null} values, and allow a method with {@code NullableValue}
 * returning type to return a {@code null} as well as {@code NullableValue} object. Thus, if the method returns {@code null} that means no
 * value present, and {@code NullableValue} object means the value is present, but value itself can be {@code null} though.
 *
 * @param <T> Value type.
 */
public final class NullableValue<T> {
    /** Null value instance. */
    private static final NullableValue<?> NULL = new NullableValue<>(null);

    /**
     * Wraps nullable object.
     *
     * @param obj Value to wrap, or {@code null}.
     * @return Nullable value.
     */
    public static <T> NullableValue<T> of(@Nullable T obj) {
        return obj == null ? (NullableValue<T>) NULL : new NullableValue<>(obj);
    }

    /** Wrapped value. */
    private T value;

    /**
     * Creates a wrapper for nullable value.
     *
     * @param value Value.
     */
    private NullableValue(@Nullable T value) {
        this.value = value;
    }

    /**
     * Returns wrapped value.
     *
     * @return Value.
     */
    public @Nullable T get() {
        return value;
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        NullableValue<?> that = (NullableValue<?>) o;
        return Objects.equals(value, that.value);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "NullableValue[value=" + value + ']';
    }
}
