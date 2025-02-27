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

package org.apache.ignite.internal.metastorage.common.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ignite.lang.ByteArray;
import org.apache.ignite.network.NetworkMessage;
import org.apache.ignite.network.annotations.Transferable;
import org.apache.ignite.raft.client.WriteCommand;

/**
 * Get and put all command for MetaStorageCommandListener that inserts or updates entries with given keys and given values and retrieves a
 * previous entries for given keys.
 */
@Transferable(MetastorageCommandsMessageGroup.GET_AND_PUT_ALL)
public interface GetAndPutAllCommand extends WriteCommand, NetworkMessage {
    /**
     * Returns keys.
     */
    List<byte[]> keys();

    /**
     * Returns values.
     */
    List<byte[]> values();

    /**
     * Static constructor.
     *
     * @param commandsFactory Commands factory.
     * @param map Values.
     */
    static GetAndPutAllCommand getAndPutAllCommand(MetaStorageCommandsFactory commandsFactory, Map<ByteArray, byte[]> map) {
        int size = map.size();

        List<byte[]> keys = new ArrayList<>(size);
        List<byte[]> values = new ArrayList<>(size);

        for (Map.Entry<ByteArray, byte[]> e : map.entrySet()) {
            keys.add(e.getKey().bytes());

            values.add(e.getValue());
        }

        return commandsFactory.getAndPutAllCommand().keys(keys).values(values).build();
    }
}
