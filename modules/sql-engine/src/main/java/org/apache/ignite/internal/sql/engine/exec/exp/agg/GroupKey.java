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

package org.apache.ignite.internal.sql.engine.exec.exp.agg;

import static org.apache.ignite.internal.util.ArrayUtils.OBJECT_EMPTY_ARRAY;

import java.io.Serializable;
import java.util.Arrays;

/**
 * GroupKey.
 * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
 */
public class GroupKey implements Serializable {
    public static final GroupKey EMPTY_GRP_KEY = new GroupKey(OBJECT_EMPTY_ARRAY);

    private final Object[] fields;

    public GroupKey(Object[] fields) {
        this.fields = fields;
    }

    public Object field(int idx) {
        return fields[idx];
    }

    public int fieldsCount() {
        return fields.length;
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

        GroupKey groupKey = (GroupKey) o;

        return Arrays.equals(fields, groupKey.fields);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Arrays.hashCode(fields);
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "GroupKey" + Arrays.toString(fields);
    }

    public static Builder builder(int rowLen) {
        return new Builder(rowLen);
    }

    /**
     * Builder.
     * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
     */
    public static class Builder {
        private final Object[] fields;

        private int idx;

        private Builder(int rowLen) {
            fields = new Object[rowLen];
        }

        /**
         * Add.
         * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
         */
        public Builder add(Object val) {
            if (idx == fields.length) {
                throw new IndexOutOfBoundsException();
            }

            fields[idx++] = val;

            return this;
        }

        /**
         * Build.
         * TODO Documentation https://issues.apache.org/jira/browse/IGNITE-15859
         */
        public GroupKey build() {
            assert idx == fields.length;

            return new GroupKey(fields);
        }
    }
}
