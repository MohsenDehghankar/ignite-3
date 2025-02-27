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

package org.apache.ignite.internal.metastorage.client;

import static org.apache.ignite.internal.util.ExceptionUtils.withCauseAndCode;
import static org.apache.ignite.lang.ErrorGroups.MetaStorage.CURSOR_CLOSING_ERR;
import static org.apache.ignite.lang.ErrorGroups.MetaStorage.CURSOR_EXECUTION_ERR;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.metastorage.common.MetaStorageException;
import org.apache.ignite.internal.metastorage.common.command.MetaStorageCommandsFactory;
import org.apache.ignite.internal.metastorage.common.command.cursor.CursorCloseCommand;
import org.apache.ignite.internal.metastorage.common.command.cursor.CursorNextCommand;
import org.apache.ignite.internal.util.Cursor;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.lang.NodeStoppingException;
import org.apache.ignite.raft.client.service.RaftGroupService;
import org.jetbrains.annotations.Nullable;

/**
 * Meta storage service side implementation of cursor.
 *
 * @param <T> Cursor parameter.
 */
public class CursorImpl<T> implements Cursor<T> {
    /** The logger. */
    private static final IgniteLogger LOG = Loggers.forClass(CursorImpl.class);

    /** Commands factory. */
    private final MetaStorageCommandsFactory commandsFactory;

    /** Future that runs meta storage service operation that provides cursor. */
    private final CompletableFuture<IgniteUuid> initOp;

    /** Meta storage raft group service. */
    private final RaftGroupService metaStorageRaftGrpSvc;

    private final Iterator<T> it;

    /**
     * Constructor.
     *
     * @param commandsFactory Commands factory.
     * @param metaStorageRaftGrpSvc Meta storage raft group service.
     * @param initOp                Future that runs meta storage service operation that provides cursor.
     * @param fn                    Function transforming the result of {@link CursorNextCommand} to the type of {@link T},
     *                              or to the {@link Iterable} of type {@link T} if needed.
     */
    CursorImpl(
            MetaStorageCommandsFactory commandsFactory,
            RaftGroupService metaStorageRaftGrpSvc,
            CompletableFuture<IgniteUuid> initOp,
            Function<Object, Object> fn
    ) {
        this.commandsFactory = commandsFactory;
        this.metaStorageRaftGrpSvc = metaStorageRaftGrpSvc;
        this.initOp = initOp;
        this.it = new InnerIterator(fn);
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        try {
            initOp.thenCompose(cursorId -> {
                CursorCloseCommand cursorCloseCommand = commandsFactory.cursorCloseCommand().cursorId(cursorId).build();

                return metaStorageRaftGrpSvc.run(cursorCloseCommand);
            }).get();

            ((InnerIterator) it).close();
        } catch (InterruptedException | ExecutionException e) {
            if (e.getCause() instanceof NodeStoppingException) {
                return;
            }

            LOG.debug("Unable to evaluate cursor close command", e);

            throw withCauseAndCode(MetaStorageException::new, CURSOR_CLOSING_ERR, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasNext() {
        return it.hasNext();
    }

    /** {@inheritDoc} */
    @Override
    public T next() {
        return it.next();
    }

    /**
     * Extension of {@link Iterator}.
     */
    private class InnerIterator implements Iterator<T> {
        private final Function<Object, Object> fn;

        @Nullable
        private Iterator<T> internalCacheIterator;

        public InnerIterator(Function<Object, Object> fn) {
            this.fn = fn;
        }

        /** {@inheritDoc} */
        @Override
        public boolean hasNext() {
            try {
                if (internalCacheIterator != null && internalCacheIterator.hasNext()) {
                    return true;
                } else {
                    return initOp
                            .thenCompose(cursorId ->
                                    metaStorageRaftGrpSvc.<Boolean>run(commandsFactory.cursorHasNextCommand().cursorId(cursorId).build()))
                            .get();
                }
            } catch (InterruptedException | ExecutionException e) {
                if (e.getCause() instanceof NodeStoppingException) {
                    return false;
                }

                LOG.debug("Unable to evaluate cursor hasNext command", e);

                throw withCauseAndCode(MetaStorageException::new, CURSOR_EXECUTION_ERR, e);
            }
        }

        /** {@inheritDoc} */
        @Override
        public T next() {
            try {
                if (internalCacheIterator != null && internalCacheIterator.hasNext()) {
                    return internalCacheIterator.next();
                } else {
                    Object res = initOp
                            .thenCompose(cursorId ->
                                    metaStorageRaftGrpSvc.run(commandsFactory.cursorNextCommand().cursorId(cursorId).build()))
                            .get();

                    Object transformed = fn.apply(res);

                    if (transformed instanceof Iterable) {
                        internalCacheIterator = ((Iterable<T>) transformed).iterator();

                        return internalCacheIterator.next();
                    } else {
                        return (T) transformed;
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                Throwable cause = e.getCause();

                if (cause instanceof NodeStoppingException) {
                    throw new NoSuchElementException();
                }

                if (cause instanceof NoSuchElementException) {
                    throw (NoSuchElementException) cause;
                }

                LOG.debug("Unable to evaluate cursor hasNext command", e);

                throw withCauseAndCode(MetaStorageException::new, CURSOR_EXECUTION_ERR, e);
            }
        }

        public void close() {
            internalCacheIterator = null;
        }
    }
}
