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

package org.apache.ignite.internal.sql.engine.util;

import java.lang.reflect.Method;
import org.apache.calcite.DataContext;
import org.apache.calcite.avatica.util.ByteString;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.parser.SqlParserUtil;
import org.apache.ignite.internal.sql.engine.exec.ExecutionContext;
import org.apache.ignite.internal.sql.engine.exec.RowHandler;
import org.apache.ignite.internal.sql.engine.exec.exp.BiScalar;
import org.apache.ignite.internal.sql.engine.exec.exp.IgniteSqlFunctions;
import org.apache.ignite.internal.sql.engine.exec.exp.SingleScalar;
import org.apache.ignite.internal.sql.engine.metadata.IgniteMetadata.FragmentMappingMetadata;
import org.apache.ignite.internal.sql.engine.prepare.MappingQueryContext;

/**
 * Contains methods used in metadata definitions.
 */
public enum IgniteMethod {
    /** See {@link RowHandler#set(int, Object, Object)}. */
    ROW_HANDLER_SET(RowHandler.class, "set", int.class, Object.class, Object.class),

    /** See {@link RowHandler#get(int, Object)}. */
    ROW_HANDLER_GET(RowHandler.class, "get", int.class, Object.class),

    /** See {@link Commons#getFieldFromBiRows(RowHandler, int, Object, Object)}. */
    ROW_HANDLER_BI_GET(Commons.class, "getFieldFromBiRows", RowHandler.class, int.class,
            Object.class, Object.class),

    /** See {@link ExecutionContext#rowHandler()}. */
    CONTEXT_ROW_HANDLER(ExecutionContext.class, "rowHandler"),

    /** See {@link ExecutionContext#unspecifiedValue()}. */
    CONTEXT_UNSPECIFIED_VALUE(ExecutionContext.class, "unspecifiedValue"),
    /** See {@link ExecutionContext#getCorrelated(int)}. */
    CONTEXT_GET_CORRELATED_VALUE(ExecutionContext.class, "getCorrelated", int.class),

    /** See {@link SingleScalar#execute(ExecutionContext, Object, Object)}. */
    SCALAR_EXECUTE(SingleScalar.class, "execute", ExecutionContext.class, Object.class, Object.class),

    /** See {@link BiScalar#execute(ExecutionContext, Object, Object, Object)}. */
    BI_SCALAR_EXECUTE(BiScalar.class, "execute", ExecutionContext.class, Object.class, Object.class, Object.class),

    SYSTEM_RANGE2(IgniteSqlFunctions.class, "systemRange", Object.class, Object.class),

    SYSTEM_RANGE3(IgniteSqlFunctions.class, "systemRange", Object.class, Object.class, Object.class),

    /** See {@link FragmentMappingMetadata#fragmentMapping(MappingQueryContext)}. */
    FRAGMENT_MAPPING(FragmentMappingMetadata.class, "fragmentMapping", MappingQueryContext.class),

    /** See {@link SqlParserUtil#intervalToMonths(String, SqlIntervalQualifier)}. */
    PARSE_INTERVAL_YEAR_MONTH(SqlParserUtil.class, "intervalToMonths", String.class, SqlIntervalQualifier.class),

    /** See {@link SqlParserUtil#intervalToMillis(String, SqlIntervalQualifier)}. */
    PARSE_INTERVAL_DAY_TIME(SqlParserUtil.class, "intervalToMillis", String.class, SqlIntervalQualifier.class),

    /** See {@link IgniteSqlFunctions#toString(ByteString)}. */
    BYTESTRING_TO_STRING(IgniteSqlFunctions.class, "toString", ByteString.class),

    /** See {@link IgniteSqlFunctions#toByteString(String)}. */
    STRING_TO_BYTESTRING(IgniteSqlFunctions.class, "toByteString", String.class),

    /** See {@link IgniteSqlFunctions#currentTime(DataContext)}. */
    CURRENT_TIME(IgniteSqlFunctions.class, "currentTime", DataContext.class);

    private final Method method;

    /**
     * Constructor.
     *
     * @param clazz         Class where to lookup method.
     * @param methodName    Method name.
     * @param argumentTypes Method parameters types.
     */
    IgniteMethod(Class<?> clazz, String methodName, Class<?>... argumentTypes) {
        method = Types.lookupMethod(clazz, methodName, argumentTypes);
    }

    /**
     * Get method.
     */
    public Method method() {
        return method;
    }
}
