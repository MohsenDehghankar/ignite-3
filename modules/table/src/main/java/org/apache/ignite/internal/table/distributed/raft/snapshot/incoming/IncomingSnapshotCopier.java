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

package org.apache.ignite.internal.table.distributed.raft.snapshot.incoming;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.apache.ignite.internal.table.distributed.TableManager.FULL_RABALANCING_STARTED;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import org.apache.ignite.internal.hlc.HybridTimestamp;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.schema.ByteBufferRow;
import org.apache.ignite.internal.storage.MvPartitionStorage;
import org.apache.ignite.internal.storage.ReadResult;
import org.apache.ignite.internal.storage.RowId;
import org.apache.ignite.internal.table.distributed.TableManager;
import org.apache.ignite.internal.table.distributed.TableMessagesFactory;
import org.apache.ignite.internal.table.distributed.raft.snapshot.PartitionSnapshotStorage;
import org.apache.ignite.internal.table.distributed.raft.snapshot.SnapshotUri;
import org.apache.ignite.internal.table.distributed.raft.snapshot.message.SnapshotMetaRequest;
import org.apache.ignite.internal.table.distributed.raft.snapshot.message.SnapshotMetaResponse;
import org.apache.ignite.internal.table.distributed.raft.snapshot.message.SnapshotMvDataResponse;
import org.apache.ignite.internal.table.distributed.raft.snapshot.message.SnapshotMvDataResponse.ResponseEntry;
import org.apache.ignite.internal.table.distributed.raft.snapshot.message.SnapshotTxDataResponse;
import org.apache.ignite.internal.tx.storage.state.TxStateStorage;
import org.apache.ignite.network.ClusterNode;
import org.apache.ignite.raft.jraft.entity.RaftOutter.SnapshotMeta;
import org.apache.ignite.raft.jraft.error.RaftError;
import org.apache.ignite.raft.jraft.storage.snapshot.SnapshotCopier;
import org.apache.ignite.raft.jraft.storage.snapshot.SnapshotReader;
import org.jetbrains.annotations.Nullable;

/**
 * Snapshot copier implementation for partitions. Used to stream partition data from the leader to the local node.
 */
public class IncomingSnapshotCopier extends SnapshotCopier {
    private static final IgniteLogger LOG = Loggers.forClass(IncomingSnapshotCopier.class);

    private static final TableMessagesFactory MSG_FACTORY = new TableMessagesFactory();

    private static final long NETWORK_TIMEOUT = Long.MAX_VALUE;

    private final PartitionSnapshotStorage partitionSnapshotStorage;

    private final SnapshotUri snapshotUri;

    /**
     * Snapshot meta read from the leader.
     *
     * @see SnapshotMetaRequest
     */
    @Nullable
    private volatile SnapshotMeta snapshotMeta;

    private volatile boolean canceled;

    @Nullable
    private volatile CompletableFuture<?> future;

    /**
     * Constructor.
     *
     * @param partitionSnapshotStorage Snapshot storage.
     * @param snapshotUri Snapshot URI.
     */
    public IncomingSnapshotCopier(PartitionSnapshotStorage partitionSnapshotStorage, SnapshotUri snapshotUri) {
        this.partitionSnapshotStorage = partitionSnapshotStorage;
        this.snapshotUri = snapshotUri;
    }

    @Override
    public void start() {
        Executor executor = partitionSnapshotStorage.getIncomingSnapshotsExecutor();

        LOG.info("Copier is started for the partition [partId={}, tableId={}]", partId(), tableId());

        future = prepareMvPartitionStorageForRebalance(executor)
                .thenCompose(unused -> prepareTxStatePartitionStorageForRebalance(executor))
                .thenCompose(unused -> {
                    ClusterNode snapshotSender = getSnapshotSender(snapshotUri.nodeName);

                    if (snapshotSender == null) {
                        LOG.error(
                                "Snapshot sender not found [partId={}, tableId={}, nodeName={}]",
                                partId(),
                                tableId(),
                                snapshotUri.nodeName
                        );

                        if (!isOk()) {
                            setError(RaftError.UNKNOWN, "Sender node was not found or it is offline");
                        }

                        return completedFuture(null);
                    }

                    return loadSnapshotMeta(snapshotSender)
                            .thenCompose(unused1 -> loadSnapshotMvData(snapshotSender, executor))
                            .thenCompose(unused1 -> loadSnapshotTxData(snapshotSender, executor))
                            .thenAcceptAsync(unused1 -> updateLastAppliedIndexFromSnapshotMetaForStorages(), executor);
                });
    }

    @Override
    public void join() throws InterruptedException {
        CompletableFuture<?> fut = future;

        if (fut != null) {
            try {
                fut.get();

                if (canceled && !isOk()) {
                    setError(RaftError.ECANCELED, "Copier is cancelled");
                }
            } catch (CancellationException e) {
                // Ignored.
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();

                LOG.error("Error when completing the copier", cause);

                if (!isOk()) {
                    setError(RaftError.UNKNOWN, "Unknown error on completion the copier");
                }

                // By analogy with LocalSnapshotCopier#join.
                throw new IllegalStateException(cause);
            }
        }
    }

    @Override
    public void cancel() {
        canceled = true;

        LOG.info("Copier is canceled for partition [partId={}, tableId={}]", partId(), tableId());

        CompletableFuture<?> fut = future;

        if (fut != null) {
            try {
                // Because after the cancellation, no one waits for #join.
                join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        // No-op.
    }

    @Override
    public SnapshotReader getReader() {
        // This one's called when "join" is complete.
        return new IncomingSnapshotReader(snapshotMeta);
    }

    /**
     * Prepares the {@link MvPartitionStorage} for a full rebalance.
     *
     * <p>Recreates {@link MvPartitionStorage} and sets the last applied index to {@link TableManager#FULL_RABALANCING_STARTED} so that
     * when the node is restarted, we can understand that the full rebalance has not completed, and we need to clean up the storage from
     * garbage.
     */
    private CompletableFuture<?> prepareMvPartitionStorageForRebalance(Executor executor) {
        if (canceled) {
            return completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> partitionSnapshotStorage.partition().reCreateMvPartitionStorage(), executor)
                .thenCompose(mvPartitionStorage -> {
                    if (canceled) {
                        return completedFuture(null);
                    }

                    mvPartitionStorage.runConsistently(() -> {
                        mvPartitionStorage.lastAppliedIndex(FULL_RABALANCING_STARTED);

                        return null;
                    });

                    LOG.info("Copier prepared multi-versioned storage for the partition [partId={}, tableId={}]", partId());

                    return completedFuture(null);
                });
    }

    /**
     * Prepares the {@link TxStateStorage} for a full rebalance.
     *
     * <p>Recreates {@link TxStateStorage} and sets the last applied index to {@link TableManager#FULL_RABALANCING_STARTED} so that when
     * the node is restarted, we can understand that the full rebalance has not completed, and we need to clean up the storage from
     * garbage.
     */
    private CompletableFuture<?> prepareTxStatePartitionStorageForRebalance(Executor executor) {
        if (canceled) {
            return completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> partitionSnapshotStorage.partition().reCreateTxStatePartitionStorage(), executor)
                .thenCompose(txStatePartitionStorage -> {
                    if (canceled) {
                        return completedFuture(null);
                    }

                    txStatePartitionStorage.lastAppliedIndex(FULL_RABALANCING_STARTED);

                    LOG.info("Copier prepared transaction state storage for the partition [partId={}, tableId={}]", partId());

                    return completedFuture(null);
                });
    }

    private @Nullable ClusterNode getSnapshotSender(String nodeName) {
        return partitionSnapshotStorage.topologyService().getByConsistentId(nodeName);
    }

    /**
     * Requests and saves the snapshot meta in {@link #snapshotMeta}.
     */
    private CompletableFuture<?> loadSnapshotMeta(ClusterNode snapshotSender) {
        if (canceled) {
            return completedFuture(null);
        }

        return partitionSnapshotStorage.outgoingSnapshotsManager().messagingService().invoke(
                snapshotSender,
                MSG_FACTORY.snapshotMetaRequest().id(snapshotUri.snapshotId).build(),
                NETWORK_TIMEOUT
        ).thenAccept(response -> {
            snapshotMeta = ((SnapshotMetaResponse) response).meta();

            LOG.info("Copier has loaded the snapshot meta for the partition [partId={}, tableId={}]", partId(), tableId());
        });
    }

    /**
     * Requests and stores data into {@link MvPartitionStorage}.
     */
    private CompletableFuture<?> loadSnapshotMvData(ClusterNode snapshotSender, Executor executor) {
        if (canceled) {
            return completedFuture(null);
        }

        return partitionSnapshotStorage.outgoingSnapshotsManager().messagingService().invoke(
                snapshotSender,
                MSG_FACTORY.snapshotMvDataRequest().id(snapshotUri.snapshotId).build(),
                NETWORK_TIMEOUT
        ).thenComposeAsync(response -> {
            SnapshotMvDataResponse snapshotMvDataResponse = ((SnapshotMvDataResponse) response);

            MvPartitionStorage mvPartition = partitionSnapshotStorage.partition().mvPartitionStorage();

            for (ResponseEntry entry : snapshotMvDataResponse.rows()) {
                if (canceled) {
                    return completedFuture(null);
                }

                // Let's write all versions for the row ID.
                mvPartition.runConsistently(() -> {
                    RowId rowId = new RowId(partId(), entry.rowId());

                    for (int i = 0; i < entry.rowVersions().size(); i++) {
                        HybridTimestamp timestamp = i < entry.timestamps().size() ? entry.timestamps().get(i) : null;

                        BinaryRow binaryRow = new ByteBufferRow(entry.rowVersions().get(i).rewind());

                        if (timestamp == null) {
                            // Writes an intent to write (uncommitted version).
                            assert entry.txId() != null;
                            assert entry.commitTableId() != null;
                            assert entry.commitPartitionId() != ReadResult.UNDEFINED_COMMIT_PARTITION_ID;

                            mvPartition.addWrite(rowId, binaryRow, entry.txId(), entry.commitTableId(), entry.commitPartitionId());
                        } else {
                            // Writes committed version.
                            mvPartition.addWriteCommitted(rowId, binaryRow, timestamp);
                        }
                    }

                    return null;
                });
            }

            if (snapshotMvDataResponse.finish()) {
                LOG.info(
                        "Copier has finished loading multi-versioned data [partId={}, rows={}]",
                        partId(),
                        snapshotMvDataResponse.rows().size()
                );

                return completedFuture(null);
            } else {
                LOG.info(
                        "Copier has loaded a portion of multi-versioned data [partId={}, rows={}]",
                        partId(),
                        snapshotMvDataResponse.rows().size()
                );

                // Let's upload the rest.
                return loadSnapshotMvData(snapshotSender, executor);
            }
        }, executor);
    }

    /**
     * Requests and stores data into {@link TxStateStorage}.
     */
    private CompletableFuture<?> loadSnapshotTxData(ClusterNode snapshotSender, Executor executor) {
        if (canceled) {
            return completedFuture(null);
        }

        return partitionSnapshotStorage.outgoingSnapshotsManager().messagingService().invoke(
                snapshotSender,
                MSG_FACTORY.snapshotTxDataRequest().id(snapshotUri.snapshotId).build(),
                NETWORK_TIMEOUT
        ).thenComposeAsync(response -> {
            SnapshotTxDataResponse snapshotTxDataResponse = (SnapshotTxDataResponse) response;

            TxStateStorage txStatePartitionStorage = partitionSnapshotStorage.partition().txStatePartitionStorage();

            assert snapshotTxDataResponse.txMeta().size() == snapshotTxDataResponse.txIds().size();

            for (int i = 0; i < snapshotTxDataResponse.txMeta().size(); i++) {
                if (canceled) {
                    return completedFuture(null);
                }

                txStatePartitionStorage.put(snapshotTxDataResponse.txIds().get(i), snapshotTxDataResponse.txMeta().get(i));
            }

            if (snapshotTxDataResponse.finish()) {
                LOG.info(
                        "Copier has finished loading transaction meta [partId={}, metas={}]",
                        partId(),
                        snapshotTxDataResponse.txMeta().size()
                );

                return completedFuture(null);
            } else {
                LOG.info(
                        "Copier has loaded a portion of transaction meta [partId={}, metas={}]",
                        partId(),
                        snapshotTxDataResponse.txMeta().size()
                );

                // Let's upload the rest.
                return loadSnapshotTxData(snapshotSender, executor);
            }
        }, executor);
    }

    /**
     * Updates the last applied index for {@link MvPartitionStorage} and {@link TxStateStorage} from the {@link #snapshotMeta}.
     */
    private void updateLastAppliedIndexFromSnapshotMetaForStorages() {
        if (canceled) {
            return;
        }

        TxStateStorage txStatePartitionStorage = partitionSnapshotStorage.partition().txStatePartitionStorage();
        MvPartitionStorage mvPartitionStorage = partitionSnapshotStorage.partition().mvPartitionStorage();

        SnapshotMeta meta = snapshotMeta;

        assert meta != null;

        mvPartitionStorage.runConsistently(() -> {
            txStatePartitionStorage.lastAppliedIndex(meta.lastIncludedIndex());
            mvPartitionStorage.lastAppliedIndex(meta.lastIncludedIndex());

            return null;
        });

        LOG.info(
                "Copier has finished updating last applied index for the partition [partId={}, lastAppliedIndex={}]",
                partId(),
                meta.lastIncludedIndex()
        );
    }

    private int partId() {
        return partitionSnapshotStorage.partition().partitionKey().partitionId();
    }

    private UUID tableId() {
        return partitionSnapshotStorage.partition().partitionKey().tableId();
    }
}
