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

package org.apache.ignite.internal.cluster.management;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toUnmodifiableSet;
import static org.apache.ignite.internal.cluster.management.ClusterTag.clusterTag;
import static org.apache.ignite.internal.cluster.management.CmgGroupId.INSTANCE;
import static org.apache.ignite.network.util.ClusterServiceUtils.resolveNodes;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.apache.ignite.internal.cluster.management.LocalStateStorage.LocalState;
import org.apache.ignite.internal.cluster.management.network.CmgMessageHandlerFactory;
import org.apache.ignite.internal.cluster.management.network.messages.CancelInitMessage;
import org.apache.ignite.internal.cluster.management.network.messages.ClusterStateMessage;
import org.apache.ignite.internal.cluster.management.network.messages.CmgInitMessage;
import org.apache.ignite.internal.cluster.management.network.messages.CmgMessageGroup;
import org.apache.ignite.internal.cluster.management.network.messages.CmgMessagesFactory;
import org.apache.ignite.internal.cluster.management.raft.ClusterStateStorage;
import org.apache.ignite.internal.cluster.management.raft.CmgRaftGroupListener;
import org.apache.ignite.internal.cluster.management.raft.CmgRaftService;
import org.apache.ignite.internal.cluster.management.raft.IllegalInitArgumentException;
import org.apache.ignite.internal.cluster.management.raft.JoinDeniedException;
import org.apache.ignite.internal.cluster.management.raft.commands.JoinReadyCommand;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.manager.IgniteComponent;
import org.apache.ignite.internal.properties.IgniteProductVersion;
import org.apache.ignite.internal.raft.Loza;
import org.apache.ignite.internal.raft.server.RaftGroupEventsListener;
import org.apache.ignite.internal.raft.server.RaftGroupOptions;
import org.apache.ignite.internal.thread.NamedThreadFactory;
import org.apache.ignite.internal.util.IgniteSpinBusyLock;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.internal.vault.VaultManager;
import org.apache.ignite.lang.IgniteInternalException;
import org.apache.ignite.lang.NodeStoppingException;
import org.apache.ignite.network.ClusterNode;
import org.apache.ignite.network.ClusterService;
import org.apache.ignite.network.NetworkAddress;
import org.apache.ignite.network.NetworkMessage;
import org.apache.ignite.network.TopologyEventHandler;
import org.apache.ignite.network.TopologyService;
import org.apache.ignite.raft.jraft.Status;
import org.apache.ignite.raft.jraft.entity.PeerId;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * Ignite component responsible for cluster initialization and managing the Cluster Management Raft Group.
 *
 * <p>Refer to
 * <a href="https://cwiki.apache.org/confluence/display/IGNITE/IEP-77%3A+Node+Join+Protocol+and+Initialization+for+Ignite+3">IEP-77</a>
 * for the description of the Cluster Management Group and its responsibilities.
 */
public class ClusterManagementGroupManager implements IgniteComponent {
    // TODO: timeout should be configurable, see https://issues.apache.org/jira/browse/IGNITE-16785
    private static final int NETWORK_INVOKE_TIMEOUT = 500;

    private static final IgniteLogger LOG = Loggers.forClass(ClusterManagementGroupManager.class);

    /** Busy lock to stop synchronously. */
    private final IgniteSpinBusyLock busyLock = new IgniteSpinBusyLock();

    /** Prevents double stopping of the component. */
    private final AtomicBoolean stopGuard = new AtomicBoolean();

    /** Future that resolves into a CMG Raft service. Can be {@code null} if the Raft service has not been started. */
    @Nullable
    private CompletableFuture<CmgRaftService> raftService;

    /** Lock for the {@code raftService} field. */
    private final Object raftServiceLock = new Object();

    /**
     * Future that resolves after the node has been validated on the CMG leader.
     */
    private final CompletableFuture<Void> joinFuture = new CompletableFuture<>();

    /** Message factory. */
    private final CmgMessagesFactory msgFactory = new CmgMessagesFactory();

    /** Delayed executor. */
    private final ScheduledExecutorService scheduledExecutor =
            Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("cmg-manager", LOG));

    private final ClusterService clusterService;

    private final Loza raftManager;

    private final ClusterStateStorage clusterStateStorage;

    /** Local state. */
    private final LocalStateStorage localStateStorage;

    /** Handles cluster initialization flow. */
    private final ClusterInitializer clusterInitializer;

    /** Constructor. */
    public ClusterManagementGroupManager(
            VaultManager vault,
            ClusterService clusterService,
            Loza raftManager,
            ClusterStateStorage clusterStateStorage
    ) {
        this.clusterService = clusterService;
        this.raftManager = raftManager;
        this.clusterStateStorage = clusterStateStorage;
        this.localStateStorage = new LocalStateStorage(vault);
        this.clusterInitializer = new ClusterInitializer(clusterService);
    }

    /**
     * Initializes the cluster that this node is present in.
     *
     * @param metaStorageNodeNames Names of nodes that will host the Meta Storage.
     * @param cmgNodeNames Names of nodes that will host the Cluster Management Group.
     * @param clusterName Human-readable name of the cluster.
     */
    public void initCluster(
            Collection<String> metaStorageNodeNames,
            Collection<String> cmgNodeNames,
            String clusterName
    ) throws NodeStoppingException {
        if (!busyLock.enterBusy()) {
            throw new NodeStoppingException();
        }

        try {
            clusterInitializer.initCluster(metaStorageNodeNames, cmgNodeNames, clusterName).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new InitException("Interrupted while initializing the cluster", e);
        } catch (ExecutionException e) {
            throw new InitException("Unable to initialize the cluster: " + e.getCause().getMessage(), e.getCause());
        } finally {
            busyLock.leaveBusy();
        }
    }

    @Override
    public void start() {
        synchronized (raftServiceLock) {
            raftService = recoverLocalState();
        }

        var messageHandlerFactory = new CmgMessageHandlerFactory(busyLock, msgFactory, clusterService);

        clusterService.messagingService().addMessageHandler(
                CmgMessageGroup.class,
                messageHandlerFactory.wrapHandler((message, senderAddr, correlationId) -> {
                    if (message instanceof ClusterStateMessage) {
                        assert correlationId != null;

                        handleClusterState((ClusterStateMessage) message, senderAddr, correlationId);
                    } else if (message instanceof CancelInitMessage) {
                        handleCancelInit((CancelInitMessage) message);
                    } else if (message instanceof CmgInitMessage) {
                        assert correlationId != null;

                        handleInit((CmgInitMessage) message, senderAddr, correlationId);
                    }
                })
        );
    }

    /**
     * Returns the cluster state future or the future that will be resolved to null if the cluster is not initialized yet.
     */
    public CompletableFuture<ClusterState> clusterState() {
        synchronized (raftServiceLock) {
            return raftService == null ? completedFuture(null) : raftService.thenCompose(CmgRaftService::readClusterState);
        }
    }

    /**
     * Extracts the local state (if any) and starts the CMG.
     *
     * @return Future, that resolves into the CMG Raft service, or {@code null} if the local state is empty.
     */
    @Nullable
    private CompletableFuture<CmgRaftService> recoverLocalState() {
        LocalState localState;

        try {
            localState = localStateStorage.getLocalState().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IgniteInternalException("Interrupted while retrieving local CMG state", e);
        } catch (ExecutionException e) {
            throw new IgniteInternalException("Error while retrieving local CMG state", e);
        }

        if (localState == null) {
            return null;
        }

        LOG.info("Local CMG state recovered, starting the CMG");

        return startCmgRaftService(localState.cmgNodeNames())
                .thenCompose(service -> joinCluster(service, localState.clusterTag()));
    }

    /**
     * Handles the Init command.
     *
     * <p>This method needs to take the following possibilities into account, depending on the local state and the Raft state:
     * <ol>
     *     <li>No local state found (the Raft service future is {@code null}) - this means that the current node has never been initialized
     *     before.</li>
     *     <li>Local state found (the Raft service future has been started and therefore is not {@code null}),
     *     but no CMG state present in the Raft storage - this means that the node has failed somewhere during
     *     the init process. In this case we need to check the consistency of the local state and the received message and complete
     *     the init process.</li>
     *     <li>Local state found and CMG state is present in the Raft storage - this means that the node has been initialized successfully
     *     and a user may be retrying the init in case the successful response was lost. To make the init message idempotent
     *     we simply check that the Raft state and the received message are the same.</li>
     * </ol>
     */
    private void handleInit(CmgInitMessage msg, NetworkAddress addr, long correlationId) {
        synchronized (raftServiceLock) {
            if (raftService == null) {
                // Raft service has not been started
                LOG.info("Init command received, starting the CMG [nodes={}]", msg.cmgNodes());

                raftService = startCmgRaftService(msg.cmgNodes());
            } else {
                // Raft service has been started, which means that this node has already received an init command at least once.
                LOG.info("Init command received, but the CMG has already been started");
            }

            // Every node, that receives the init command, tries to initialize the CMG state. Raft listener will correctly
            // handle this case by applying only the first attempt and returning the actual cluster state for all other
            // attempts.
            raftService = raftService
                    .thenCompose(service -> doInit(service, msg)
                            .handle((v, e) -> {
                                NetworkMessage response;

                                if (e == null) {
                                    LOG.info("CMG initialized successfully");

                                    response = msgFactory.initCompleteMessage().build();
                                } else {
                                    if (e instanceof CompletionException) {
                                        e = e.getCause();
                                    }

                                    LOG.debug("Error when initializing the CMG", e);

                                    response = msgFactory.initErrorMessage()
                                            .cause(e.getMessage())
                                            .shouldCancel(!(e instanceof IllegalInitArgumentException))
                                            .build();
                                }

                                clusterService.messagingService().respond(addr, response, correlationId);

                                return service;
                            }));
        }
    }

    private CompletableFuture<CmgRaftService> doInit(CmgRaftService service, CmgInitMessage msg) {
        return service.initClusterState(createClusterState(msg))
                .thenCompose(state -> {
                    var localState = new LocalState(state.cmgNodes(), state.clusterTag());

                    return localStateStorage.saveLocalState(localState)
                            .thenCompose(v -> joinCluster(service, state.clusterTag()));
                });
    }

    private ClusterState createClusterState(CmgInitMessage msg) {
        return ClusterState.clusterState(
                msgFactory,
                msg.cmgNodes(),
                msg.metaStorageNodes(),
                IgniteProductVersion.CURRENT_VERSION,
                clusterTag(msgFactory, msg.clusterName())
        );
    }

    /**
     * Executes the following actions when a CMG leader is elected.
     * <ol>
     *     <li>Updates the logical topology in case some nodes have gone offline during leader election.</li>
     *     <li>Broadcasts the current CMG state to all nodes in the physical topology.</li>
     * </ol>
     */
    private void onLeaderElected() {
        LOG.info("CMG leader has been elected, executing onLeaderElected callback");

        raftServiceAfterJoin()
                .thenCompose(this::updateLogicalTopology)
                .thenAccept(service -> {
                    // Register a listener to send ClusterState messages to new nodes.
                    TopologyService topologyService = clusterService.topologyService();

                    // TODO: remove listeners if leadership is lost, see https://issues.apache.org/jira/browse/IGNITE-16842
                    topologyService.addEventHandler(cmgLeaderTopologyEventHandler(service));

                    // Send the ClusterStateMessage to all members of the physical topology. We do not wait for the send operation
                    // because being unable to send ClusterState messages should not fail the CMG service startup.
                    sendClusterState(service, topologyService.allMembers());

                    LOG.info("onLeaderElected callback executed successfully");
                })
                .whenComplete((v, e) -> {
                    if (e != null) {
                        LOG.warn("Error when executing onLeaderElected callback", e);
                    }
                });
    }

    /**
     * This method must be executed upon CMG leader election in order to regain logical topology consistency in case some nodes left the
     * physical topology during the election. Newly appeared nodes will be added automatically after the new leader broadcasts the current
     * cluster state.
     */
    private CompletableFuture<CmgRaftService> updateLogicalTopology(CmgRaftService service) {
        return service.logicalTopology()
                .thenCompose(logicalTopology -> {
                    Set<String> physicalTopologyIds = clusterService.topologyService().allMembers()
                            .stream()
                            .map(ClusterNode::id)
                            .collect(toSet());

                    Set<ClusterNode> nodesToRemove = logicalTopology.stream()
                            .filter(node -> !physicalTopologyIds.contains(node.id()))
                            .collect(toUnmodifiableSet());

                    return nodesToRemove.isEmpty() ? completedFuture(null) : service.removeFromCluster(nodesToRemove);
                })
                .thenApply(v -> service);
    }

    private void handleCancelInit(CancelInitMessage msg) {
        LOG.info("CMG initialization cancelled [reason={}]", msg.reason());

        destroyCmg();
    }

    /**
     * Completely destroys the local CMG Raft service.
     */
    private void destroyCmg() {
        synchronized (raftServiceLock) {
            try {
                if (raftService != null) {
                    raftService.cancel(true);

                    raftService = null;
                }

                raftManager.stopRaftGroup(INSTANCE);

                if (clusterStateStorage.isStarted()) {
                    clusterStateStorage.destroy();
                }

                localStateStorage.clear().get();
            } catch (Exception e) {
                throw new IgniteInternalException("Error when cleaning the CMG state", e);
            }
        }
    }

    /**
     * Handler for the {@link ClusterStateMessage}.
     */
    private void handleClusterState(ClusterStateMessage msg, NetworkAddress addr, long correlationId) {
        clusterService.messagingService().respond(addr, msgFactory.successResponseMessage().build(), correlationId);

        ClusterState state = msg.clusterState();

        synchronized (raftServiceLock) {
            if (raftService == null) {
                LOG.info("ClusterStateMessage received, starting the CMG [nodes={}]", state.cmgNodes());

                raftService = initCmgRaftService(state);
            } else {
                // Raft service might have been started on wrong CMG nodes, because CMG state can change while a node is offline. In this
                // case we need to re-create the service.
                raftService = raftService
                        .handle((service, e) -> {
                            if (service != null && service.nodeNames().equals(state.cmgNodes())) {
                                LOG.info("ClusterStateMessage received, but the CMG service is already started");

                                return completedFuture(service);
                            }

                            if (service == null) {
                                // Service could not be started for some reason, which might be due to starting on incorrect CMG nodes
                                assert e != null;

                                if (e instanceof CompletionException) {
                                    e = e.getCause();
                                }

                                // Nothing can be done if the node has not passed validation.
                                if (e instanceof JoinDeniedException) {
                                    return CompletableFuture.<CmgRaftService>failedFuture(e);
                                }

                                LOG.warn("CMG service could not be started on previous attempts. "
                                        + "Re-creating the CMG Raft service [reason={}]", e, e.getMessage());
                            } else {
                                LOG.warn("CMG service started, but the cluster state is different. "
                                                + "Re-creating the CMG Raft service [localState={}, clusterState={}]",
                                        service.nodeNames(), state.cmgNodes());

                                destroyCmg();
                            }

                            return initCmgRaftService(state);
                        })
                        .thenCompose(Function.identity());
            }
        }
    }

    private CompletableFuture<CmgRaftService> joinCluster(CmgRaftService service, ClusterTag clusterTag) {
        return service.startJoinCluster(clusterTag)
                .thenApply(v -> service)
                .whenComplete((v, e) -> {
                    if (e == null) {
                        LOG.info("Successfully joined the cluster [name={}]", clusterTag.clusterName());

                        joinFuture.complete(null);
                    } else {
                        joinFuture.completeExceptionally(e);
                    }
                });
    }

    /**
     * Starts the CMG Raft service using the provided node names as its peers.
     */
    private CompletableFuture<CmgRaftService> startCmgRaftService(Collection<String> nodeNames) {
        try {
            return raftManager
                    .prepareRaftGroup(
                            INSTANCE,
                            resolveNodes(clusterService, nodeNames),
                            List.of(),
                            () -> {
                                clusterStateStorage.start();

                                return new CmgRaftGroupListener(clusterStateStorage);
                            },
                            this::createCmgRaftGroupEventsListener,
                            RaftGroupOptions.defaults()
                    )
                    .thenApply(service -> new CmgRaftService(service, clusterService));
        } catch (Exception e) {
            return failedFuture(e);
        }
    }

    private RaftGroupEventsListener createCmgRaftGroupEventsListener() {
        return new RaftGroupEventsListener() {
            @Override
            public void onLeaderElected(long term) {
                ClusterManagementGroupManager.this.onLeaderElected();
            }

            @Override
            public void onNewPeersConfigurationApplied(List<PeerId> peers) {
                // No-op.
            }

            @Override
            public void onReconfigurationError(Status status, List<PeerId> peers, long term) {
                // No-op.
            }
        };
    }

    /**
     * Starts the CMG Raft service using the given {@code state} and persists it to the local storage.
     */
    private CompletableFuture<CmgRaftService> initCmgRaftService(ClusterState state) {
        return startCmgRaftService(state.cmgNodes())
                .thenCompose(service -> {
                    var localState = new LocalState(state.cmgNodes(), state.clusterTag());

                    return localStateStorage.saveLocalState(localState)
                            .thenCompose(v -> joinCluster(service, state.clusterTag()));
                });
    }

    private TopologyEventHandler cmgLeaderTopologyEventHandler(CmgRaftService raftService) {
        return new TopologyEventHandler() {
            @Override
            public void onAppeared(ClusterNode member) {
                raftService.isCurrentNodeLeader()
                        .thenAccept(isLeader -> {
                            if (isLeader) {
                                sendClusterState(raftService, member);
                            }
                        });
            }

            @Override
            public void onDisappeared(ClusterNode member) {
                scheduleRemoveFromLogicalTopology(raftService, member);
            }
        };
    }

    private void scheduleRemoveFromLogicalTopology(CmgRaftService raftService, ClusterNode node) {
        // TODO: delay should be configurable, see https://issues.apache.org/jira/browse/IGNITE-16785
        scheduledExecutor.schedule(() -> {
            ClusterNode physicalTopologyNode = clusterService.topologyService().getByConsistentId(node.name());

            if (physicalTopologyNode == null || !physicalTopologyNode.id().equals(node.id())) {
                raftService.removeFromCluster(Set.of(node));
            }
        }, 0, TimeUnit.MILLISECONDS);
    }

    private void sendClusterState(CmgRaftService raftService, ClusterNode node) {
        sendClusterState(raftService, List.of(node));
    }

    private void sendClusterState(CmgRaftService raftService, Collection<ClusterNode> nodes) {
        raftService.readClusterState()
                .thenAccept(state -> {
                    // Raft state might not have been initialized in case of leader failure during cluster init
                    // TODO: properly handle this case, see https://issues.apache.org/jira/browse/IGNITE-16819
                    if (state == null) {
                        throw new IllegalStateException("Cluster state is empty");
                    }

                    NetworkMessage msg = msgFactory.clusterStateMessage()
                            .clusterState(state)
                            .build();

                    for (ClusterNode node : nodes) {
                        sendWithRetry(node, msg);
                    }
                })
                .whenComplete((v, e) -> {
                    if (e != null) {
                        LOG.error("Unable to send cluster state", e);
                    }
                });
    }

    private CompletableFuture<Void> sendWithRetry(ClusterNode node, NetworkMessage msg) {
        var result = new CompletableFuture<Void>();

        sendWithRetry(node, msg, result, 5);

        return result.whenComplete((v, e) -> {
            if (e != null) {
                LOG.warn("Unable to send message [msg={}, target={}]", e, msg.getClass(), node);
            }
        });
    }

    private void sendWithRetry(ClusterNode node, NetworkMessage msg, CompletableFuture<Void> result, int attempts) {
        clusterService.messagingService().invoke(node, msg, NETWORK_INVOKE_TIMEOUT)
                .whenComplete((response, e) -> {
                    if (e == null) {
                        result.complete(null);
                    } else if (attempts == 1) {
                        result.completeExceptionally(e);
                    } else {
                        LOG.debug("Unable to send message, going to retry [targetNode={}]", e, node.name());

                        scheduledExecutor.schedule(() -> sendWithRetry(node, msg, result, attempts - 1), 500, TimeUnit.MILLISECONDS);
                    }
                });
    }

    @Override
    public void stop() throws Exception {
        if (!stopGuard.compareAndSet(false, true)) {
            return;
        }

        busyLock.block();

        IgniteUtils.shutdownAndAwaitTermination(scheduledExecutor, 10, TimeUnit.SECONDS);

        IgniteUtils.closeAll(
                () -> raftManager.stopRaftGroup(INSTANCE),
                clusterStateStorage
        );

        // Fail the future to unblock dependent operations
        joinFuture.completeExceptionally(new NodeStoppingException());
    }

    /**
     * Returns a future that resolves after the join request finishes either successfully or with an error.
     *
     * @return Future that represents the state of the join.
     */
    public CompletableFuture<Void> joinFuture() {
        if (!busyLock.enterBusy()) {
            return failedFuture(new NodeStoppingException());
        }

        try {
            return joinFuture;
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Returns a future that, when complete, resolves into a list of node names that host the Meta Storage.
     *
     * @return Future that, when complete, resolves into a list of node names that host the Meta Storage.
     */
    public CompletableFuture<Collection<String>> metaStorageNodes() {
        if (!busyLock.enterBusy()) {
            return failedFuture(new NodeStoppingException());
        }

        try {
            return raftServiceAfterJoin()
                    .thenCompose(CmgRaftService::readClusterState)
                    .thenApply(ClusterState::metaStorageNodes);
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Returns a future that, when complete, resolves into a list of nodes that comprise the logical topology.
     *
     * @return Future that, when complete, resolves into a list of nodes that comprise the logical topology.
     */
    public CompletableFuture<Collection<ClusterNode>> logicalTopology() {
        if (!busyLock.enterBusy()) {
            return failedFuture(new NodeStoppingException());
        }

        try {
            return raftServiceAfterJoin().thenCompose(CmgRaftService::logicalTopology);
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Issues the {@link JoinReadyCommand} thus completing the join procedure. If this method succeeds, the node will be added to the
     * logical topology.
     *
     * @return Future that represents the state of the operation.
     */
    public CompletableFuture<Void> onJoinReady() {
        if (!busyLock.enterBusy()) {
            return failedFuture(new NodeStoppingException());
        }

        try {
            return raftServiceAfterJoin().thenCompose(CmgRaftService::completeJoinCluster);
        } finally {
            busyLock.leaveBusy();
        }
    }

    /**
     * Returns a future that resolves to {@code true} if the current node is the CMG leader.
     */
    @TestOnly
    CompletableFuture<Boolean> isCmgLeader() {
        if (!busyLock.enterBusy()) {
            return failedFuture(new NodeStoppingException());
        }

        try {
            return raftServiceAfterJoin().thenCompose(CmgRaftService::isCurrentNodeLeader);
        } finally {
            busyLock.leaveBusy();
        }
    }

    private CompletableFuture<CmgRaftService> raftServiceAfterJoin() {
        // There exists a guarantee, that if "joinFuture" completes successfully, then
        // the CMG Raft service must have already been started (reference to "raftService" is not null).
        return joinFuture
                .thenCompose(v -> {
                    synchronized (raftServiceLock) {
                        assert raftService != null;

                        return raftService;
                    }
                });
    }
}
