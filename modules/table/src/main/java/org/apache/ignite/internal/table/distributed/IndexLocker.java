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

package org.apache.ignite.internal.table.distributed;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.storage.RowId;

/**
 * A decorator interface to hide all tx-protocol-related things.
 *
 * <p>Different indexes requires different approaches for locking. Thus every index type has its own implementation of this interface.
 */
public interface IndexLocker {
    /** Returns an identifier of the index this locker created for. */
    UUID id();

    /**
     * Acquires the lock for lookup operation.
     *
     * @param txId An identifier of the transaction in which the row is read.
     * @param tableRow A table row to lookup.
     * @return A future representing a result.
     */
    CompletableFuture<?> locksForLookup(UUID txId, BinaryRow tableRow);

    /**
     * Acquires the lock for insert operation.
     *
     * @param txId An identifier of the transaction in which the row is inserted.
     * @param tableRow A table row to insert.
     * @param rowId An identifier of the row in the main storage.
     * @return A future representing a result.
     */
    CompletableFuture<?> locksForInsert(UUID txId, BinaryRow tableRow, RowId rowId);

    /**
     * Acquires the lock for remove operation.
     *
     * @param txId An identifier of the transaction in which the row is removed.
     * @param tableRow A table row to remove.
     * @param rowId An identifier of the row to remove.
     * @return A future representing a result.
     */
    CompletableFuture<?> locksForRemove(UUID txId, BinaryRow tableRow, RowId rowId);
}
