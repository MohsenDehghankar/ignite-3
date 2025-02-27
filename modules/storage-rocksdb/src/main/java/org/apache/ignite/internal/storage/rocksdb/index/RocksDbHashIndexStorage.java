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

package org.apache.ignite.internal.storage.rocksdb.index;

import static org.apache.ignite.internal.rocksdb.RocksUtils.incrementArray;
import static org.apache.ignite.internal.util.ArrayUtils.BYTE_EMPTY_ARRAY;
import static org.apache.ignite.internal.util.ByteUtils.bytesToLong;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import org.apache.ignite.internal.rocksdb.ColumnFamily;
import org.apache.ignite.internal.rocksdb.RocksIteratorAdapter;
import org.apache.ignite.internal.schema.BinaryTuple;
import org.apache.ignite.internal.storage.RowId;
import org.apache.ignite.internal.storage.StorageException;
import org.apache.ignite.internal.storage.index.HashIndexDescriptor;
import org.apache.ignite.internal.storage.index.HashIndexStorage;
import org.apache.ignite.internal.storage.index.IndexRow;
import org.apache.ignite.internal.storage.rocksdb.RocksDbMvPartitionStorage;
import org.apache.ignite.internal.util.Cursor;
import org.apache.ignite.internal.util.HashUtils;
import org.apache.ignite.internal.util.IgniteUtils;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Slice;
import org.rocksdb.WriteBatchWithIndex;
import org.rocksdb.WriteOptions;

/**
 * {@link HashIndexStorage} implementation based on RocksDB.
 *
 * <p>This storage uses the following format for keys:
 * <pre>
 * Index ID (UUID) - 16 bytes
 * Partition ID - 2 bytes
 * Tuple hash - 4 bytes
 * Tuple value - variable length
 * Row ID (UUID) - 16 bytes
 * </pre>
 *
 * <p>We use an empty array as values, because all required information can be extracted from the key.
 */
public class RocksDbHashIndexStorage implements HashIndexStorage {
    /**
     * Length of the fixed part of the key: Index ID + Partition ID + Hash.
     */
    public static final int FIXED_PREFIX_LENGTH = 2 * Long.BYTES + Short.BYTES + Integer.BYTES;

    private final HashIndexDescriptor descriptor;

    private final ColumnFamily indexCf;

    private final RocksDbMvPartitionStorage partitionStorage;

    /**
     * Constant prefix of every index key.
     */
    private final byte[] constantPrefix;

    /**
     * Creates a new Hash Index storage.
     *
     * @param descriptor Index descriptor.
     * @param indexCf Column family that stores the index data.
     * @param partitionStorage Partition storage of the partition that is being indexed (needed for consistency guarantees).
     */
    public RocksDbHashIndexStorage(
            HashIndexDescriptor descriptor,
            ColumnFamily indexCf,
            RocksDbMvPartitionStorage partitionStorage
    ) {
        this.descriptor = descriptor;
        this.indexCf = indexCf;
        this.partitionStorage = partitionStorage;

        UUID indexId = descriptor.id();

        this.constantPrefix = ByteBuffer.allocate(2 * Long.BYTES + Short.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(indexId.getMostSignificantBits())
                .putLong(indexId.getLeastSignificantBits())
                .putShort((short) partitionStorage.partitionId())
                .array();
    }

    @Override
    public HashIndexDescriptor indexDescriptor() {
        return descriptor;
    }

    @Override
    public Cursor<RowId> get(BinaryTuple key) {
        byte[] rangeStart = rocksPrefix(key);

        byte[] rangeEnd = incrementArray(rangeStart);

        Slice upperBound = rangeEnd == null ? null : new Slice(rangeEnd);

        ReadOptions options = new ReadOptions().setIterateUpperBound(upperBound);

        RocksIterator it = indexCf.newIterator(options);

        it.seek(rangeStart);

        return new RocksIteratorAdapter<>(it) {
            @Override
            protected RowId decodeEntry(byte[] key, byte[] value) {
                // RowId UUID is located at the last 16 bytes of the key
                long mostSignificantBits = bytesToLong(key, key.length - Long.BYTES * 2);
                long leastSignificantBits = bytesToLong(key, key.length - Long.BYTES);

                return new RowId(partitionStorage.partitionId(), mostSignificantBits, leastSignificantBits);
            }

            @Override
            public void close() throws Exception {
                super.close();

                IgniteUtils.closeAll(options, upperBound);
            }
        };
    }

    @Override
    public void put(IndexRow row) {
        WriteBatchWithIndex writeBatch = partitionStorage.currentWriteBatch();

        try {
            writeBatch.put(indexCf.handle(), rocksKey(row), BYTE_EMPTY_ARRAY);
        } catch (RocksDBException e) {
            throw new StorageException("Unable to insert data into hash index. Index ID: " + descriptor.id(), e);
        }
    }

    @Override
    public void remove(IndexRow row) {
        WriteBatchWithIndex writeBatch = partitionStorage.currentWriteBatch();

        try {
            writeBatch.delete(indexCf.handle(), rocksKey(row));
        } catch (RocksDBException e) {
            throw new StorageException("Unable to remove data from hash index. Index ID: " + descriptor.id(), e);
        }
    }

    @Override
    public void destroy() {
        byte[] rangeEnd = incrementArray(constantPrefix);

        assert rangeEnd != null;

        try (WriteOptions writeOptions = new WriteOptions().setDisableWAL(true)) {
            indexCf.db().deleteRange(indexCf.handle(), writeOptions, constantPrefix, rangeEnd);
        } catch (RocksDBException e) {
            throw new StorageException("Unable to remove data from hash index. Index ID: " + descriptor.id(), e);
        }
    }

    private byte[] rocksPrefix(BinaryTuple prefix) {
        return rocksPrefix(prefix, 0).array();
    }

    private ByteBuffer rocksPrefix(BinaryTuple prefix, int extraLength) {
        ByteBuffer keyBytes = prefix.byteBuffer();

        return ByteBuffer.allocate(FIXED_PREFIX_LENGTH + keyBytes.remaining() + extraLength)
                .order(ByteOrder.BIG_ENDIAN)
                .put(constantPrefix)
                .putInt(HashUtils.hash32(keyBytes))
                .put(keyBytes);
    }

    private byte[] rocksKey(IndexRow row) {
        RowId rowId = row.rowId();

        // We don't store the Partition ID as it is already a part of the key.
        return rocksPrefix(row.indexColumns(), 2 * Long.BYTES)
                .putLong(rowId.mostSignificantBits())
                .putLong(rowId.leastSignificantBits())
                .array();
    }
}
