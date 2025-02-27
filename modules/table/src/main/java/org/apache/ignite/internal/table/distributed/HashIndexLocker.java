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
import java.util.function.Function;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.schema.BinaryTuple;
import org.apache.ignite.internal.storage.RowId;
import org.apache.ignite.internal.tx.LockKey;
import org.apache.ignite.internal.tx.LockManager;
import org.apache.ignite.internal.tx.LockMode;

/**
 * Locker for a hash-based indexes.
 *
 * <p>Simply acquires lock on a given row.
 */
public class HashIndexLocker implements IndexLocker {
    private final UUID indexId;
    private final LockMode modificationMode;
    private final LockManager lockManager;
    private final Function<BinaryRow, BinaryTuple> indexRowResolver;

    /**
     * Constructs the object.
     *
     * @param indexId An identifier of the index this locker is created for.
     * @param lockManager A lock manager to acquire locks in.
     * @param indexRowResolver A convertor which derives an index key from given table row.
     */
    public HashIndexLocker(UUID indexId, boolean unique, LockManager lockManager,
            Function<BinaryRow, BinaryTuple> indexRowResolver) {
        this.indexId = indexId;
        this.modificationMode = unique ? LockMode.X : LockMode.IX;
        this.lockManager = lockManager;
        this.indexRowResolver = indexRowResolver;
    }

    /** {@inheritDoc} */
    @Override
    public UUID id() {
        return indexId;
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<?> locksForLookup(UUID txId, BinaryRow tableRow) {
        BinaryTuple key = indexRowResolver.apply(tableRow);

        return lockManager.acquire(txId, new LockKey(indexId, key.byteBuffer()), LockMode.S);
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<?> locksForInsert(UUID txId, BinaryRow tableRow, RowId rowId) {
        BinaryTuple key = indexRowResolver.apply(tableRow);

        return lockManager.acquire(txId, new LockKey(indexId, key.byteBuffer()), modificationMode);
    }

    /** {@inheritDoc} */
    @Override
    public CompletableFuture<?> locksForRemove(UUID txId, BinaryRow tableRow, RowId rowId) {
        BinaryTuple key = indexRowResolver.apply(tableRow);

        return lockManager.acquire(txId, new LockKey(indexId, key.byteBuffer()), modificationMode);
    }
}
