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

package org.apache.ignite.internal.table.distributed.command;

import java.util.UUID;
import org.apache.ignite.internal.hlc.HybridTimestamp;

/**
 * State machine command to cleanup on a transaction commit.
 */
public class TxCleanupCommand extends PartitionCommand {
    /**
     * A commit or a rollback state.
     */
    private final boolean commit;

    /**
     * Transaction commit timestamp.
     */
    private final HybridTimestamp commitTimestamp;

    /**
     * The constructor.
     *
     * @param txId The txId.
     * @param commit Commit or rollback state {@code True} to commit.
     * @param commitTimestamp Transaction commit timestamp.
     */
    public TxCleanupCommand(UUID txId, boolean commit, HybridTimestamp commitTimestamp) {
        super(txId);
        this.commit = commit;
        this.commitTimestamp = commitTimestamp;
    }

    /**
     * Returns a commit or a rollback state.
     *
     * @return A commit or a rollback state.
     */
    public boolean commit() {
        return commit;
    }

    /**
     * Returns a transaction commit timestamp.
     *
     * @return A transaction commit timestamp.
     */
    public HybridTimestamp commitTimestamp() {
        return commitTimestamp;
    }
}
