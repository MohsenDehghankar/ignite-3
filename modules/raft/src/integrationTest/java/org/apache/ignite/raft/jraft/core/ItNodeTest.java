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
package org.apache.ignite.raft.jraft.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.synchronizedList;
import static java.util.stream.Collectors.toList;
import static org.apache.ignite.raft.jraft.core.TestCluster.ELECTION_TIMEOUT_MILLIS;
import static org.apache.ignite.raft.jraft.test.TestUtils.sender;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.codahale.metrics.ConsoleReporter;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.logger.IgniteLogger;
import org.apache.ignite.internal.logger.Loggers;
import org.apache.ignite.internal.raft.server.RaftGroupEventsListener;
import org.apache.ignite.internal.raft.storage.impl.DefaultLogStorageFactory;
import org.apache.ignite.internal.raft.storage.impl.IgniteJraftServiceFactory;
import org.apache.ignite.internal.testframework.WorkDirectory;
import org.apache.ignite.internal.testframework.WorkDirectoryExtension;
import org.apache.ignite.network.ClusterService;
import org.apache.ignite.network.NetworkAddress;
import org.apache.ignite.network.StaticNodeFinder;
import org.apache.ignite.raft.jraft.Iterator;
import org.apache.ignite.raft.jraft.JRaftUtils;
import org.apache.ignite.raft.jraft.Node;
import org.apache.ignite.raft.jraft.NodeManager;
import org.apache.ignite.raft.jraft.RaftGroupService;
import org.apache.ignite.raft.jraft.StateMachine;
import org.apache.ignite.raft.jraft.Status;
import org.apache.ignite.raft.jraft.closure.JoinableClosure;
import org.apache.ignite.raft.jraft.closure.ReadIndexClosure;
import org.apache.ignite.raft.jraft.closure.SynchronizedClosure;
import org.apache.ignite.raft.jraft.closure.TaskClosure;
import org.apache.ignite.raft.jraft.conf.Configuration;
import org.apache.ignite.raft.jraft.entity.EnumOutter;
import org.apache.ignite.raft.jraft.entity.PeerId;
import org.apache.ignite.raft.jraft.entity.Task;
import org.apache.ignite.raft.jraft.entity.UserLog;
import org.apache.ignite.raft.jraft.error.LogIndexOutOfBoundsException;
import org.apache.ignite.raft.jraft.error.LogNotFoundException;
import org.apache.ignite.raft.jraft.error.RaftError;
import org.apache.ignite.raft.jraft.error.RaftException;
import org.apache.ignite.raft.jraft.option.BootstrapOptions;
import org.apache.ignite.raft.jraft.option.NodeOptions;
import org.apache.ignite.raft.jraft.option.RaftOptions;
import org.apache.ignite.raft.jraft.option.ReadOnlyOption;
import org.apache.ignite.raft.jraft.rpc.AppendEntriesRequestImpl;
import org.apache.ignite.raft.jraft.rpc.AppendEntriesResponseImpl;
import org.apache.ignite.raft.jraft.rpc.RpcClientEx;
import org.apache.ignite.raft.jraft.rpc.RpcRequests;
import org.apache.ignite.raft.jraft.rpc.RpcServer;
import org.apache.ignite.raft.jraft.rpc.TestIgniteRpcServer;
import org.apache.ignite.raft.jraft.rpc.impl.IgniteRpcClient;
import org.apache.ignite.raft.jraft.rpc.impl.IgniteRpcServer;
import org.apache.ignite.raft.jraft.rpc.impl.core.DefaultRaftClientService;
import org.apache.ignite.raft.jraft.storage.SnapshotThrottle;
import org.apache.ignite.raft.jraft.storage.snapshot.SnapshotReader;
import org.apache.ignite.raft.jraft.storage.snapshot.ThroughputSnapshotThrottle;
import org.apache.ignite.raft.jraft.test.TestUtils;
import org.apache.ignite.raft.jraft.util.Bits;
import org.apache.ignite.raft.jraft.util.Endpoint;
import org.apache.ignite.raft.jraft.util.ExecutorServiceHelper;
import org.apache.ignite.raft.jraft.util.ExponentialBackoffTimeoutStrategy;
import org.apache.ignite.raft.jraft.util.Utils;
import org.apache.ignite.raft.jraft.util.concurrent.FixedThreadsExecutorGroup;
import org.apache.ignite.utils.ClusterServiceTestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for raft cluster. TODO asch get rid of sleeps wherether possible IGNITE-14832
 */
@ExtendWith(WorkDirectoryExtension.class)
public class ItNodeTest {
    private static final IgniteLogger LOG = Loggers.forClass(ItNodeTest.class);

    private static DumpThread dumpThread;

    private static class DumpThread extends Thread {
        private static long DUMP_TIMEOUT_MS = 5 * 60 * 1000;
        private volatile boolean stopped = false;

        /** {@inheritDoc} */
        @SuppressWarnings("BusyWait") @Override
        public void run() {
            while (!stopped) {
                try {
                    Thread.sleep(DUMP_TIMEOUT_MS);
                    LOG.info("Test hang too long, dump threads");
                    TestUtils.dumpThreads();
                }
                catch (InterruptedException e) {
                    // reset request, continue
                    continue;
                }
            }
        }
    }

    private String dataPath;

    private final AtomicInteger startedCounter = new AtomicInteger(0);

    private final AtomicInteger stoppedCounter = new AtomicInteger(0);

    private long testStartMs;

    private TestCluster cluster;

    private final List<RaftGroupService> services = new ArrayList<>();

    private final List<ExecutorService> executors = new ArrayList<>();

    private final List<FixedThreadsExecutorGroup> appendEntriesExecutors = new ArrayList<>();

    /** Test info. */
    private TestInfo testInfo;

    @BeforeAll
    public static void setupNodeTest() {
        dumpThread = new DumpThread();
        dumpThread.setName("NodeTest-DumpThread");
        dumpThread.setDaemon(true);
        dumpThread.start();
    }

    @AfterAll
    public static void tearNodeTest() throws Exception {
        dumpThread.stopped = true;
        dumpThread.interrupt();
        dumpThread.join(100);
    }

    @BeforeEach
    public void setup(TestInfo testInfo, @WorkDirectory Path workDir) throws Exception {
        LOG.info(">>>>>>>>>>>>>>> Start test method: " + testInfo.getDisplayName());

        this.testInfo = testInfo;
        dataPath = workDir.toString();

        testStartMs = Utils.monotonicMs();
        dumpThread.interrupt(); // reset dump timeout
    }

    @AfterEach
    public void teardown() throws Exception {
        services.forEach(service -> {
            try {
                service.shutdown();
            }
            catch (Exception e) {
                LOG.error("Error while closing a service", e);
            }
        });

        executors.forEach(ExecutorServiceHelper::shutdownAndAwaitTermination);

        appendEntriesExecutors.forEach(FixedThreadsExecutorGroup::shutdownGracefully);

        if (cluster != null)
            cluster.stopAll();

        startedCounter.set(0);
        stoppedCounter.set(0);

        TestUtils.assertAllJraftThreadsStopped();

        LOG.info(">>>>>>>>>>>>>>> End test method: " + testInfo.getDisplayName() + ", cost:"
            + (Utils.monotonicMs() - testStartMs) + " ms.");
    }

    @Test
    public void testInitShutdown() {
        Endpoint addr = new Endpoint(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        NodeOptions nodeOptions = createNodeOptions(0);

        nodeOptions.setFsm(new MockStateMachine(addr));
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");

        RaftGroupService service = createService("unittest", new PeerId(addr, 0), nodeOptions);

        service.start();
    }

    @Test
    public void testNodeTaskOverload() throws Exception {
        Endpoint addr = new Endpoint(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        PeerId peer = new PeerId(addr, 0);

        NodeOptions nodeOptions = createNodeOptions(0);
        RaftOptions raftOptions = new RaftOptions();
        raftOptions.setDisruptorBufferSize(2);
        nodeOptions.setRaftOptions(raftOptions);
        MockStateMachine fsm = new MockStateMachine(addr);
        nodeOptions.setFsm(fsm);
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOptions.setInitialConf(new Configuration(Collections.singletonList(peer)));

        RaftGroupService service = createService("unittest", new PeerId(addr, 0), nodeOptions);

        Node node = service.start();

        assertEquals(1, node.listPeers().size());
        assertTrue(node.listPeers().contains(peer));

        while (!node.isLeader())
            ;

        List<Task> tasks = new ArrayList<>();
        AtomicInteger c = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            ByteBuffer data = ByteBuffer.wrap(("hello" + i).getBytes(UTF_8));
            int finalI = i;
            Task task = new Task(data, new JoinableClosure(status -> {
                LOG.info("{} i={}", status, finalI);
                if (!status.isOk()) {
                    assertTrue(
                        status.getRaftError() == RaftError.EBUSY || status.getRaftError() == RaftError.EPERM);
                }
                c.incrementAndGet();
            }));
            node.apply(task);
            tasks.add(task);
        }

        Task.joinAll(tasks, TimeUnit.SECONDS.toMillis(30));
        assertEquals(10, c.get());
    }

    /**
     * Test rollback stateMachine with readIndex for issue 317: https://github.com/sofastack/sofa-jraft/issues/317
     */
    @Test
    public void testRollbackStateMachineWithReadIndex_Issue317() throws Exception {
        Endpoint addr = new Endpoint(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        PeerId peer = new PeerId(addr, 0);

        NodeOptions nodeOptions = createNodeOptions(0);
        CountDownLatch applyCompleteLatch = new CountDownLatch(1);
        CountDownLatch applyLatch = new CountDownLatch(1);
        CountDownLatch readIndexLatch = new CountDownLatch(1);
        AtomicInteger currentValue = new AtomicInteger(-1);
        String errorMsg = testInfo.getDisplayName();
        StateMachine fsm = new StateMachineAdapter() {

            @Override
            public void onApply(Iterator iter) {
                // Notify that the #onApply is preparing to go.
                readIndexLatch.countDown();
                // Wait for submitting a read-index request
                try {
                    applyLatch.await();
                }
                catch (InterruptedException e) {
                    fail();
                }
                int i = 0;
                while (iter.hasNext()) {
                    byte[] data = iter.next().array();
                    int v = Bits.getInt(data, 0);
                    assertEquals(i++, v);
                    currentValue.set(v);
                }
                if (i > 0) {
                    // rollback
                    currentValue.set(i - 1);
                    iter.setErrorAndRollback(1, new Status(-1, errorMsg));
                    applyCompleteLatch.countDown();
                }
            }
        };
        nodeOptions.setFsm(fsm);
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOptions.setInitialConf(new Configuration(Collections.singletonList(peer)));

        RaftGroupService service = createService("unittest", peer, nodeOptions);

        Node node = service.start();

        assertEquals(1, node.listPeers().size());
        assertTrue(node.listPeers().contains(peer));

        while (!node.isLeader())
            ;

        int n = 5;
        {
            // apply tasks
            for (int i = 0; i < n; i++) {
                byte[] b = new byte[4];
                Bits.putInt(b, 0, i);
                node.apply(new Task(ByteBuffer.wrap(b), null));
            }
        }

        AtomicInteger readIndexSuccesses = new AtomicInteger(0);
        {
            // Submit a read-index, wait for #onApply
            readIndexLatch.await();
            CountDownLatch latch = new CountDownLatch(1);
            node.readIndex(null, new ReadIndexClosure() {

                @Override
                public void run(Status status, long index, byte[] reqCtx) {
                    try {
                        if (status.isOk())
                            readIndexSuccesses.incrementAndGet();
                        else {
                            assertTrue(
                                status.getErrorMsg().contains(errorMsg) || status.getRaftError() == RaftError.ETIMEDOUT
                                    || status.getErrorMsg().contains("Invalid state for readIndex: STATE_ERROR"),
                                "Unexpected status: " + status);
                        }
                    }
                    finally {
                        latch.countDown();
                    }
                }
            });
            // We have already submit a read-index request,
            // notify #onApply can go right now
            applyLatch.countDown();

            // The state machine is in error state, the node should step down.
            waitForCondition(() -> !node.isLeader(), 5_000);

            latch.await();
            applyCompleteLatch.await();
        }
        // No read-index request succeed.
        assertEquals(0, readIndexSuccesses.get());
        assertTrue(n - 1 >= currentValue.get());
    }

    @Test
    public void testSingleNode() throws Exception {
        Endpoint addr = new Endpoint(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        PeerId peer = new PeerId(addr, 0);

        NodeOptions nodeOptions = createNodeOptions(0);
        MockStateMachine fsm = new MockStateMachine(addr);
        nodeOptions.setFsm(fsm);
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOptions.setInitialConf(new Configuration(Collections.singletonList(peer)));
        RaftGroupService service = createService("unittest", peer, nodeOptions);

        Node node = service.start();

        assertEquals(1, node.listPeers().size());
        assertTrue(node.listPeers().contains(peer));

        while (!node.isLeader())
            ;

        sendTestTaskAndWait(node);
        assertEquals(10, fsm.getLogs().size());
        int i = 0;
        for (ByteBuffer data : fsm.getLogs()) {
            assertEquals("hello" + i++, stringFromBytes(data.array()));
        }
    }

    private String stringFromBytes(byte[] bytes) {
        return new String(bytes, UTF_8);
    }

    @Test
    public void testNoLeader() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);

        assertTrue(cluster.start(peers.get(0).getEndpoint()));

        List<Node> followers = cluster.getFollowers();
        assertEquals(1, followers.size());

        Node follower = followers.get(0);
        sendTestTaskAndWait(follower, 0, RaftError.EPERM);

        // adds a peer3
        PeerId peer3 = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 3);
        CountDownLatch latch = new CountDownLatch(1);
        follower.addPeer(peer3, new ExpectClosure(RaftError.EPERM, latch));
        waitLatch(latch);

        // remove the peer0
        PeerId peer0 = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        latch = new CountDownLatch(1);
        follower.removePeer(peer0, new ExpectClosure(RaftError.EPERM, latch));
        waitLatch(latch);
    }

    @Test
    public void testTripleNodesWithReplicatorStateListener() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);
        //final TestCluster cluster = new TestCluster("unittest", this.dataPath, peers);

        UserReplicatorStateListener listener1 = new UserReplicatorStateListener();
        UserReplicatorStateListener listener2 = new UserReplicatorStateListener();

        cluster = new TestCluster("unitest", dataPath, peers, new LinkedHashSet<>(), ELECTION_TIMEOUT_MILLIS,
            opts -> opts.setReplicationStateListeners(List.of(listener1, listener2)), testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        // elect leader
        cluster.waitLeader();
        cluster.ensureLeader(cluster.getLeader());

        for (Node follower : cluster.getFollowers())
            waitForCondition(() -> follower.getLeaderId() != null, 5_000);

        assertEquals(4, startedCounter.get());
        assertEquals(2, cluster.getLeader().getReplicatorStateListeners().size());
        assertEquals(2, cluster.getFollowers().get(0).getReplicatorStateListeners().size());
        assertEquals(2, cluster.getFollowers().get(1).getReplicatorStateListeners().size());

        for (Node node : cluster.getNodes())
            node.removeReplicatorStateListener(listener1);
        assertEquals(1, cluster.getLeader().getReplicatorStateListeners().size());
        assertEquals(1, cluster.getFollowers().get(0).getReplicatorStateListeners().size());
        assertEquals(1, cluster.getFollowers().get(1).getReplicatorStateListeners().size());
    }

    // TODO asch Broken then using volatile log. A follower with empty log can become a leader IGNITE-14832.
    @Test
    @Disabled
    public void testVoteTimedoutStepDown() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        assertEquals(3, leader.listPeers().size());
        // apply tasks to leader
        sendTestTaskAndWait(leader);

        // Stop all followers
        List<Node> followers = cluster.getFollowers();
        assertFalse(followers.isEmpty());
        for (Node node : followers)
            assertTrue(cluster.stop(node.getNodeId().getPeerId().getEndpoint()));

        // Wait leader to step down.
        while (leader.isLeader())
            Thread.sleep(10);

        // old leader try to elect self, it should fail.
        ((NodeImpl) leader).tryElectSelf();
        Thread.sleep(1500);

        assertNull(cluster.getLeader());

        // Start followers
        for (Node node : followers)
            assertTrue(cluster.start(node.getNodeId().getPeerId().getEndpoint()));

        cluster.ensureSame();
    }

    class UserReplicatorStateListener implements Replicator.ReplicatorStateListener {
        /** {@inheritDoc} */
        @Override
        public void onCreated(PeerId peer) {
            int val = startedCounter.incrementAndGet();

            LOG.info("Replicator has been created {} {}", peer, val);
        }

        /** {@inheritDoc} */
        @Override
        public void onError(PeerId peer, Status status) {
            LOG.info("Replicator has errors {} {}", peer, status);
        }

        /** {@inheritDoc} */
        @Override
        public void onDestroyed(PeerId peer) {
            int val = stoppedCounter.incrementAndGet();

            LOG.info("Replicator has been destroyed {} {}", peer, val);
        }
    }

    @Test
    public void testLeaderTransferWithReplicatorStateListener() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, new LinkedHashSet<>(), ELECTION_TIMEOUT_MILLIS,
            opts -> opts.setReplicationStateListeners(List.of(new UserReplicatorStateListener())), testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();
        Node leader = cluster.getLeader();
        cluster.ensureLeader(leader);

        sendTestTaskAndWait(leader);
        Thread.sleep(100);
        List<Node> followers = cluster.getFollowers();

        assertTrue(waitForCondition(() -> startedCounter.get() == 2, 5_000), startedCounter.get() + "");

        PeerId targetPeer = followers.get(0).getNodeId().getPeerId().copy();
        LOG.info("Transfer leadership from {} to {}", leader, targetPeer);
        assertTrue(leader.transferLeadershipTo(targetPeer).isOk());
        Thread.sleep(1000);
        cluster.waitLeader();

        assertTrue(waitForCondition(() -> startedCounter.get() == 4, 5_000), startedCounter.get() + "");

        for (Node node : cluster.getNodes())
            node.clearReplicatorStateListeners();
        assertEquals(0, cluster.getLeader().getReplicatorStateListeners().size());
        assertEquals(0, cluster.getFollowers().get(0).getReplicatorStateListeners().size());
        assertEquals(0, cluster.getFollowers().get(1).getReplicatorStateListeners().size());
    }

    @Test
    public void testTripleNodes() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        assertEquals(3, leader.listPeers().size());
        // apply tasks to leader
        sendTestTaskAndWait(leader);

        {
            ByteBuffer data = ByteBuffer.wrap("no closure".getBytes(UTF_8));
            Task task = new Task(data, null);
            leader.apply(task);
        }

        {
            // task with TaskClosure
            ByteBuffer data = ByteBuffer.wrap("task closure".getBytes(UTF_8));
            List<String> cbs = synchronizedList(new ArrayList<>());
            CountDownLatch latch = new CountDownLatch(1);
            Task task = new Task(data, new TaskClosure() {

                @Override
                public void run(Status status) {
                    cbs.add("apply");
                    latch.countDown();
                }

                @Override
                public void onCommitted() {
                    cbs.add("commit");

                }
            });
            leader.apply(task);
            latch.await();
            assertEquals(2, cbs.size());
            assertEquals("commit", cbs.get(0));
            assertEquals("apply", cbs.get(1));
        }

        cluster.ensureSame();
        assertEquals(2, cluster.getFollowers().size());
    }

    @Test
    public void testSingleNodeWithLearner() throws Exception {
        Endpoint addr = new Endpoint(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        PeerId peer = new PeerId(addr, 0);

        Endpoint learnerAddr = new Endpoint(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 1);
        PeerId learnerPeer = new PeerId(learnerAddr, 0);

        final int cnt = 10;
        MockStateMachine learnerFsm;
        RaftGroupService learnerServer;
        {
            // Start learner
            NodeOptions nodeOptions = createNodeOptions(0);
            learnerFsm = new MockStateMachine(learnerAddr);
            nodeOptions.setFsm(learnerFsm);
            nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta1");
            nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot1");
            nodeOptions.setInitialConf(new Configuration(Collections.singletonList(peer), Collections
                .singletonList(learnerPeer)));

            learnerServer = createService("unittest", new PeerId(learnerAddr, 0), nodeOptions);
            learnerServer.start();
        }

        {
            // Start leader
            NodeOptions nodeOptions = createNodeOptions(1);
            MockStateMachine fsm = new MockStateMachine(addr);
            nodeOptions.setFsm(fsm);
            nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
            nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
            nodeOptions.setInitialConf(new Configuration(Collections.singletonList(peer), Collections
                .singletonList(learnerPeer)));

            RaftGroupService server = createService("unittest", new PeerId(addr, 0), nodeOptions);
            Node node = server.start();

            assertEquals(1, node.listPeers().size());
            assertTrue(node.listPeers().contains(peer));
            assertTrue(waitForCondition(() -> node.isLeader(), 1_000));

            sendTestTaskAndWait(node, cnt);
            assertEquals(cnt, fsm.getLogs().size());
            int i = 0;
            for (ByteBuffer data : fsm.getLogs())
                assertEquals("hello" + i++, stringFromBytes(data.array()));
            Thread.sleep(1000); //wait for entries to be replicated to learner.
            server.shutdown();
        }
        {
            // assert learner fsm
            assertEquals(cnt, learnerFsm.getLogs().size());
            int i = 0;
            for (ByteBuffer data : learnerFsm.getLogs())
                assertEquals("hello" + i++, stringFromBytes(data.array()));
            learnerServer.shutdown();
        }
    }

    @Test
    public void testResetLearners() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        LinkedHashSet<PeerId> learners = new LinkedHashSet<>();

        for (int i = 0; i < 3; i++)
            learners.add(new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 3 + i));

        cluster = new TestCluster("unittest", dataPath, peers, learners, ELECTION_TIMEOUT_MILLIS, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));
        for (PeerId peer : learners)
            assertTrue(cluster.startLearner(peer));

        // elect leader
        cluster.waitLeader();

        Node leader = cluster.getLeader();
        cluster.ensureLeader(leader);

        waitForCondition(() -> leader.listAlivePeers().size() == 3, 5_000);
        waitForCondition(() -> leader.listAliveLearners().size() == 3, 5_000);

        sendTestTaskAndWait(leader);
        List<MockStateMachine> fsms = cluster.getFsms();
        assertEquals(6, fsms.size());
        cluster.ensureSame();

        {
            // Reset learners to 2 nodes
            PeerId learnerPeer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 3);
            learners.remove(learnerPeer);
            assertEquals(2, learners.size());

            SynchronizedClosure done = new SynchronizedClosure();
            leader.resetLearners(new ArrayList<>(learners), done);
            assertTrue(done.await().isOk());
            assertEquals(2, leader.listAliveLearners().size());
            assertEquals(2, leader.listLearners().size());
            sendTestTaskAndWait(leader);
            Thread.sleep(500);

            assertEquals(6, fsms.size());

            MockStateMachine fsm = fsms.remove(3); // get the removed learner's fsm
            assertEquals(fsm.getAddress(), learnerPeer.getEndpoint());
            // Ensure no more logs replicated to the removed learner.
            assertTrue(cluster.getLeaderFsm().getLogs().size() > fsm.getLogs().size());
            assertEquals(cluster.getLeaderFsm().getLogs().size(), 2 * fsm.getLogs().size());
        }
        {
            // remove another learner
            PeerId learnerPeer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 4);
            SynchronizedClosure done = new SynchronizedClosure();
            leader.removeLearners(Arrays.asList(learnerPeer), done);
            assertTrue(done.await().isOk());

            sendTestTaskAndWait(leader);
            Thread.sleep(500);
            MockStateMachine fsm = fsms.remove(3); // get the removed learner's fsm
            assertEquals(fsm.getAddress(), learnerPeer.getEndpoint());
            // Ensure no more logs replicated to the removed learner.
            assertTrue(cluster.getLeaderFsm().getLogs().size() > fsm.getLogs().size());
            assertEquals(cluster.getLeaderFsm().getLogs().size(), fsm.getLogs().size() / 2 * 3);
        }

        assertEquals(3, leader.listAlivePeers().size());
        assertEquals(1, leader.listAliveLearners().size());
        assertEquals(1, leader.listLearners().size());
    }

    @Test
    public void testTripleNodesWithStaticLearners() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        LinkedHashSet<PeerId> learners = new LinkedHashSet<>();
        PeerId learnerPeer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 3);
        learners.add(learnerPeer);
        cluster.setLearners(learners);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        // elect leader
        cluster.waitLeader();
        Node leader = cluster.getLeader();
        cluster.ensureLeader(leader);

        assertEquals(3, leader.listPeers().size());
        assertEquals(1, leader.listLearners().size());
        assertTrue(leader.listLearners().contains(learnerPeer));
        assertTrue(leader.listAliveLearners().isEmpty());

        // start learner after cluster setup.
        assertTrue(cluster.start(learnerPeer.getEndpoint()));

        Thread.sleep(1000);

        assertEquals(3, leader.listPeers().size());
        assertEquals(1, leader.listLearners().size());
        assertEquals(1, leader.listAliveLearners().size());

        // apply tasks to leader
        sendTestTaskAndWait(leader);

        cluster.ensureSame();
        assertEquals(4, cluster.getFsms().size());
    }

    @Test
    public void testTripleNodesWithLearners() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        assertEquals(3, leader.listPeers().size());
        assertTrue(leader.listLearners().isEmpty());
        assertTrue(leader.listAliveLearners().isEmpty());

        {
            // Adds a learner
            SynchronizedClosure done = new SynchronizedClosure();
            PeerId learnerPeer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 3);
            // Start learner
            assertTrue(cluster.startLearner(learnerPeer));
            leader.addLearners(Arrays.asList(learnerPeer), done);
            assertTrue(done.await().isOk());
            assertEquals(1, leader.listAliveLearners().size());
            assertEquals(1, leader.listLearners().size());
        }

        // apply tasks to leader
        sendTestTaskAndWait(leader);

        {
            ByteBuffer data = ByteBuffer.wrap("no closure".getBytes(UTF_8));
            Task task = new Task(data, null);
            leader.apply(task);
        }

        {
            // task with TaskClosure
            ByteBuffer data = ByteBuffer.wrap("task closure".getBytes(UTF_8));
            List<String> cbs = synchronizedList(new ArrayList<>());
            CountDownLatch latch = new CountDownLatch(1);
            Task task = new Task(data, new TaskClosure() {

                @Override
                public void run(Status status) {
                    cbs.add("apply");
                    latch.countDown();
                }

                @Override
                public void onCommitted() {
                    cbs.add("commit");

                }
            });
            leader.apply(task);
            latch.await();
            assertEquals(2, cbs.size());
            assertEquals("commit", cbs.get(0));
            assertEquals("apply", cbs.get(1));
        }

        assertEquals(4, cluster.getFsms().size());
        assertEquals(2, cluster.getFollowers().size());
        assertEquals(1, cluster.getLearners().size());
        cluster.ensureSame();

        {
            // Adds another learner
            SynchronizedClosure done = new SynchronizedClosure();
            PeerId learnerPeer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 4);
            // Start learner
            assertTrue(cluster.startLearner(learnerPeer));
            leader.addLearners(Arrays.asList(learnerPeer), done);
            assertTrue(done.await().isOk());
            assertEquals(2, leader.listAliveLearners().size());
            assertEquals(2, leader.listLearners().size());
            cluster.ensureSame();
        }
        {
            // stop two followers
            for (Node follower : cluster.getFollowers())
                assertTrue(cluster.stop(follower.getNodeId().getPeerId().getEndpoint()));
            // send a new task
            ByteBuffer data = ByteBuffer.wrap("task closure".getBytes(UTF_8));
            SynchronizedClosure done = new SynchronizedClosure();
            leader.apply(new Task(data, done));
            // should fail
            assertFalse(done.await().isOk());
            assertEquals(RaftError.EPERM, done.getStatus().getRaftError());
            // One peer with two learners.
            assertEquals(3, cluster.getFsms().size());
        }
    }

    @Test
    public void testNodesWithPriorityElection() throws Exception {
        List<Integer> priorities = new ArrayList<>();
        priorities.add(100);
        priorities.add(40);
        priorities.add(40);

        List<PeerId> peers = TestUtils.generatePriorityPeers(3, priorities);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), peer.getPriority()));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        assertEquals(3, leader.listPeers().size());
        assertEquals(100, leader.getNodeTargetPriority());
        assertEquals(100, leader.getLeaderId().getPriority());
        assertEquals(2, cluster.getFollowers().size());
    }

    @Test
    public void testNodesWithPartPriorityElection() throws Exception {
        List<Integer> priorities = new ArrayList<>();
        priorities.add(100);
        priorities.add(40);
        priorities.add(-1);

        List<PeerId> peers = TestUtils.generatePriorityPeers(3, priorities);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), peer.getPriority()));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        assertEquals(3, leader.listPeers().size());
        assertEquals(2, cluster.getFollowers().size());
    }

    @Test
    public void testNodesWithSpecialPriorityElection() throws Exception {

        List<Integer> priorities = new ArrayList<>();
        priorities.add(0);
        priorities.add(0);
        priorities.add(-1);

        List<PeerId> peers = TestUtils.generatePriorityPeers(3, priorities);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), peer.getPriority()));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        assertEquals(3, leader.listPeers().size());
        assertEquals(2, cluster.getFollowers().size());
    }

    @Test
    public void testNodesWithZeroValPriorityElection() throws Exception {

        List<Integer> priorities = new ArrayList<>();
        priorities.add(50);
        priorities.add(0);
        priorities.add(0);

        List<PeerId> peers = TestUtils.generatePriorityPeers(3, priorities);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), peer.getPriority()));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        assertEquals(3, leader.listPeers().size());
        assertEquals(2, cluster.getFollowers().size());
        assertEquals(50, leader.getNodeTargetPriority());
        assertEquals(50, leader.getLeaderId().getPriority());
    }

    @Test
    public void testNoLeaderWithZeroValPriorityElection() throws Exception {
        List<Integer> priorities = new ArrayList<>();
        priorities.add(0);
        priorities.add(0);
        priorities.add(0);

        List<PeerId> peers = TestUtils.generatePriorityPeers(3, priorities);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), peer.getPriority()));

        Thread.sleep(200);

        List<Node> followers = cluster.getFollowers();
        assertEquals(3, followers.size());

        for (Node follower : followers)
            assertEquals(0, follower.getNodeId().getPeerId().getPriority());
    }

    @Test
    public void testLeaderStopAndReElectWithPriority() throws Exception {
        List<Integer> priorities = new ArrayList<>();
        priorities.add(100);
        priorities.add(60);
        priorities.add(10);

        List<PeerId> peers = TestUtils.generatePriorityPeers(3, priorities);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), peer.getPriority()));

        cluster.waitLeader();
        Node leader = cluster.getLeader();
        cluster.ensureLeader(leader);

        assertNotNull(leader);
        assertEquals(100, leader.getNodeId().getPeerId().getPriority());
        assertEquals(100, leader.getNodeTargetPriority());

        // apply tasks to leader
        sendTestTaskAndWait(leader);

        // wait for all update received, before election of new leader
        cluster.ensureSame();

        // stop leader
        assertTrue(cluster.stop(leader.getNodeId().getPeerId().getEndpoint()));

        // elect new leader
        cluster.waitLeader();
        leader = cluster.getLeader();

        assertNotNull(leader);

        // nodes with the same log size will elect leader only by priority
        assertEquals(60, leader.getNodeId().getPeerId().getPriority());
        assertEquals(100, leader.getNodeTargetPriority());
    }

    @Test
    public void testRemoveLeaderWithPriority() throws Exception {
        List<Integer> priorities = new ArrayList<>();
        priorities.add(100);
        priorities.add(60);
        priorities.add(10);

        List<PeerId> peers = TestUtils.generatePriorityPeers(3, priorities);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), peer.getPriority()));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        assertEquals(100, leader.getNodeTargetPriority());
        assertEquals(100, leader.getNodeId().getPeerId().getPriority());

        List<Node> followers = cluster.getFollowers();
        assertEquals(2, followers.size());

        PeerId oldLeader = leader.getNodeId().getPeerId().copy();
        Endpoint oldLeaderAddr = oldLeader.getEndpoint();

        // remove old leader
        LOG.info("Remove old leader {}", oldLeader);
        CountDownLatch latch = new CountDownLatch(1);
        leader.removePeer(oldLeader, new ExpectClosure(latch));
        waitLatch(latch);
        assertEquals(60, leader.getNodeTargetPriority());

        // stop and clean old leader
        LOG.info("Stop and clean old leader {}", oldLeader);
        assertTrue(cluster.stop(oldLeaderAddr));
        cluster.clean(oldLeaderAddr);

        // elect new leader
        cluster.waitLeader();
        leader = cluster.getLeader();
        LOG.info("New leader is {}", leader);
        assertNotNull(leader);
        assertNotSame(leader, oldLeader);
    }

    @Test
    public void testChecksum() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        // start with checksum validation
        {
            TestCluster cluster = new TestCluster("unittest", dataPath, peers, testInfo);
            try {
                RaftOptions raftOptions = new RaftOptions();
                raftOptions.setEnableLogEntryChecksum(true);
                for (PeerId peer : peers)
                    assertTrue(cluster.start(peer.getEndpoint(), false, 300, true, null, raftOptions));

                cluster.waitLeader();
                Node leader = cluster.getLeader();
                assertNotNull(leader);
                assertEquals(3, leader.listPeers().size());
                sendTestTaskAndWait(leader);
                cluster.ensureSame();
            }
            finally {
                cluster.stopAll();
            }
        }

        // restart with peer3 enable checksum validation
        {
            TestCluster cluster = new TestCluster("unittest", dataPath, peers, testInfo);
            try {
                RaftOptions raftOptions = new RaftOptions();
                raftOptions.setEnableLogEntryChecksum(false);
                for (PeerId peer : peers) {
                    if (peer.equals(peers.get(2))) {
                        raftOptions = new RaftOptions();
                        raftOptions.setEnableLogEntryChecksum(true);
                    }
                    assertTrue(cluster.start(peer.getEndpoint(), false, 300, true, null, raftOptions));
                }

                cluster.waitLeader();
                Node leader = cluster.getLeader();
                assertNotNull(leader);
                assertEquals(3, leader.listPeers().size());
                sendTestTaskAndWait(leader);
                cluster.ensureSame();
            }
            finally {
                cluster.stopAll();
            }
        }

        // restart with no checksum validation
        {
            TestCluster cluster = new TestCluster("unittest", dataPath, peers, testInfo);
            try {
                RaftOptions raftOptions = new RaftOptions();
                raftOptions.setEnableLogEntryChecksum(false);
                for (PeerId peer : peers)
                    assertTrue(cluster.start(peer.getEndpoint(), false, 300, true, null, raftOptions));

                cluster.waitLeader();
                Node leader = cluster.getLeader();
                assertNotNull(leader);
                assertEquals(3, leader.listPeers().size());
                sendTestTaskAndWait(leader);
                cluster.ensureSame();
            }
            finally {
                cluster.stopAll();
            }
        }

        // restart with all peers enable checksum validation
        {
            TestCluster cluster = new TestCluster("unittest", dataPath, peers, testInfo);
            try {
                RaftOptions raftOptions = new RaftOptions();
                raftOptions.setEnableLogEntryChecksum(true);
                for (PeerId peer : peers)
                    assertTrue(cluster.start(peer.getEndpoint(), false, 300, true, null, raftOptions));

                cluster.waitLeader();
                Node leader = cluster.getLeader();
                assertNotNull(leader);
                assertEquals(3, leader.listPeers().size());
                sendTestTaskAndWait(leader);
                cluster.ensureSame();
            }
            finally {
                cluster.stopAll();
            }
        }

    }

    @Test
    public void testReadIndex() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), false, 300, true));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);

        assertEquals(3, leader.listPeers().size());
        // apply tasks to leader
        sendTestTaskAndWait(leader);

        // first call will fail-fast when no connection
        if (!assertReadIndex(leader, 11))
            assertTrue(assertReadIndex(leader, 11));

        // read from follower
        for (Node follower : cluster.getFollowers()) {
            assertNotNull(follower);

            assertTrue(waitForCondition(() -> leader.getNodeId().getPeerId().equals(follower.getLeaderId()), 5_000));

            assertReadIndex(follower, 11);
        }

        // read with null request context
        CountDownLatch latch = new CountDownLatch(1);
        leader.readIndex(null, new ReadIndexClosure() {

            @Override
            public void run(Status status, long index, byte[] reqCtx) {
                assertNull(reqCtx);
                assertTrue(status.isOk());
                latch.countDown();
            }
        });
        latch.await();
    }

    @Test // TODO asch do we need read index timeout ? https://issues.apache.org/jira/browse/IGNITE-14832
    public void testReadIndexTimeout() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), false, 300, true));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);

        assertEquals(3, leader.listPeers().size());
        // apply tasks to leader
        sendTestTaskAndWait(leader);

        // first call will fail-fast when no connection
        if (!assertReadIndex(leader, 11))
            assertTrue(assertReadIndex(leader, 11));

        // read from follower
        for (Node follower : cluster.getFollowers()) {
            assertNotNull(follower);

            assertTrue(waitForCondition(() -> leader.getNodeId().getPeerId().equals(follower.getLeaderId()), 5_000));

            assertReadIndex(follower, 11);
        }

        // read with null request context
        CountDownLatch latch = new CountDownLatch(1);
        long start = System.currentTimeMillis();
        leader.readIndex(null, new ReadIndexClosure() {

            @Override
            public void run(Status status, long index, byte[] reqCtx) {
                assertNull(reqCtx);
                if (status.isOk())
                    System.err.println("Read-index so fast: " + (System.currentTimeMillis() - start) + "ms");
                else {
                    assertEquals(new Status(RaftError.ETIMEDOUT, "read-index request timeout"), status);
                    assertEquals(-1, index);
                }
                latch.countDown();
            }
        });
        latch.await();
    }

    @Test
    public void testReadIndexFromLearner() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), false, 300, true));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        assertEquals(3, leader.listPeers().size());
        // apply tasks to leader
        sendTestTaskAndWait(leader);

        {
            // Adds a learner
            SynchronizedClosure done = new SynchronizedClosure();
            PeerId learnerPeer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 3);
            // Start learner
            assertTrue(cluster.startLearner(learnerPeer));
            leader.addLearners(Arrays.asList(learnerPeer), done);
            assertTrue(done.await().isOk());
            assertEquals(1, leader.listAliveLearners().size());
            assertEquals(1, leader.listLearners().size());
        }

        Thread.sleep(100);
        // read from learner
        Node learner = cluster.getNodes().get(3);
        assertNotNull(leader);
        assertReadIndex(learner, 12);
        assertReadIndex(learner, 12);
    }

    @Test
    public void testReadIndexChaos() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), false, 300, true));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        assertEquals(3, leader.listPeers().size());

        CountDownLatch latch = new CountDownLatch(10);

        ExecutorService executor = Executors.newFixedThreadPool(10);

        executors.add(executor);

        for (int i = 0; i < 10; i++) {
            executor.submit(new Runnable() {
                /** {@inheritDoc} */
                @Override public void run() {
                    try {
                        for (int i = 0; i < 100; i++) {
                            try {
                                sendTestTaskAndWait(leader);
                            }
                            catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            readIndexRandom(cluster);
                        }
                    }
                    finally {
                        latch.countDown();
                    }
                }

                private void readIndexRandom(TestCluster cluster) {
                    CountDownLatch readLatch = new CountDownLatch(1);
                    byte[] requestContext = TestUtils.getRandomBytes();
                    cluster.getNodes().get(ThreadLocalRandom.current().nextInt(3))
                        .readIndex(requestContext, new ReadIndexClosure() {

                            @Override
                            public void run(Status status, long index, byte[] reqCtx) {
                                if (status.isOk()) {
                                    assertTrue(status.isOk(), status.toString());
                                    assertTrue(index > 0);
                                    assertArrayEquals(requestContext, reqCtx);
                                }
                                readLatch.countDown();
                            }
                        });
                    try {
                        readLatch.await();
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        latch.await();

        cluster.ensureSame();

        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(10000, fsm.getLogs().size());
    }

    @Test
    public void testNodeMetrics() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), false, 300, true));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        assertEquals(3, leader.listPeers().size());
        // apply tasks to leader
        sendTestTaskAndWait(leader);

        {
            ByteBuffer data = ByteBuffer.wrap("no closure".getBytes(UTF_8));
            Task task = new Task(data, null);
            leader.apply(task);
        }

        cluster.ensureSame();
        for (Node node : cluster.getNodes()) {
            System.out.println("-------------" + node.getNodeId() + "-------------");
            ConsoleReporter reporter = ConsoleReporter.forRegistry(node.getNodeMetrics().getMetricRegistry())
                .build();
            reporter.report();
            reporter.close();
            System.out.println();
        }
        // TODO check http status https://issues.apache.org/jira/browse/IGNITE-14832
        assertEquals(2, cluster.getFollowers().size());
    }

    @Test
    public void testLeaderFail() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        LOG.info("Current leader is {}", leader.getLeaderId());
        // apply tasks to leader
        sendTestTaskAndWait(leader);

        List<Node> followers = cluster.getFollowers();

        blockMessagesOnFollowers(followers, (msg, nodeId) -> {
            if (msg instanceof RpcRequests.RequestVoteRequest) {
                RpcRequests.RequestVoteRequest msg0 = (RpcRequests.RequestVoteRequest) msg;

                return !msg0.preVote();
            }

            return false;
        });

        // stop leader
        LOG.warn("Stop leader {}", leader.getNodeId().getPeerId());
        PeerId oldLeader = leader.getNodeId().getPeerId();
        assertTrue(cluster.stop(leader.getNodeId().getPeerId().getEndpoint()));

        assertFalse(followers.isEmpty());
        sendTestTaskAndWait("follower apply ", followers.get(0), -1); // Should fail, because no leader.

        stopBlockingMessagesOnFollowers(followers);

        // elect new leader
        cluster.waitLeader();
        leader = cluster.getLeader();
        LOG.info("Elect new leader is {}", leader.getLeaderId());
        // apply tasks to new leader
        CountDownLatch latch = new CountDownLatch(10);
        for (int i = 10; i < 20; i++) {
            ByteBuffer data = ByteBuffer.wrap(("hello" + i).getBytes(UTF_8));
            Task task = new Task(data, new ExpectClosure(latch));
            leader.apply(task);
        }
        waitLatch(latch);

        // restart old leader
        LOG.info("restart old leader {}", oldLeader);
        assertTrue(cluster.start(oldLeader.getEndpoint()));
        // apply something
        latch = new CountDownLatch(10);
        for (int i = 20; i < 30; i++) {
            ByteBuffer data = ByteBuffer.wrap(("hello" + i).getBytes(UTF_8));
            Task task = new Task(data, new ExpectClosure(latch));
            leader.apply(task);
        }
        waitLatch(latch);

        // stop and clean old leader
        cluster.stop(oldLeader.getEndpoint());
        cluster.clean(oldLeader.getEndpoint());

        // restart old leader
        LOG.info("Restart old leader with cleanup {}", oldLeader);
        assertTrue(cluster.start(oldLeader.getEndpoint()));
        cluster.ensureSame();

        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(30, fsm.getLogs().size());
    }

    @Test
    public void testJoinNodes() throws Exception {
        PeerId peer0 = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        PeerId peer1 = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 1);
        PeerId peer2 = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 2);
        PeerId peer3 = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 3);

        ArrayList<PeerId> peers = new ArrayList<>();
        peers.add(peer0);

        // start single cluster
        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        assertTrue(cluster.start(peer0.getEndpoint()));

        cluster.waitLeader();

        Node leader = cluster.getLeader();
        assertNotNull(leader);
        assertEquals(leader.getNodeId().getPeerId(), peer0);
        sendTestTaskAndWait(leader);

        // start peer1
        assertTrue(cluster.start(peer1.getEndpoint(), false, 300));

        // add peer1
        CountDownLatch latch = new CountDownLatch(1);
        peers.add(peer1);
        leader.addPeer(peer1, new ExpectClosure(latch));
        waitLatch(latch);

        cluster.ensureSame();
        assertEquals(2, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(10, fsm.getLogs().size());

        // add peer2 but not start
        peers.add(peer2);
        latch = new CountDownLatch(1);
        leader.addPeer(peer2, new ExpectClosure(RaftError.ECATCHUP, latch));
        waitLatch(latch);

        // start peer2 after 2 seconds
        Thread.sleep(2000);
        assertTrue(cluster.start(peer2.getEndpoint(), false, 300));

        // re-add peer2
        latch = new CountDownLatch(2);
        leader.addPeer(peer2, new ExpectClosure(latch));
        // concurrent configuration change
        leader.addPeer(peer3, new ExpectClosure(RaftError.EBUSY, latch));
        waitLatch(latch);

        // re-add peer2 directly

        try {
            leader.addPeer(peer2, new ExpectClosure(latch));
            fail();
        }
        catch (IllegalArgumentException e) {
            assertEquals("Peer already exists in current configuration", e.getMessage());
        }

        cluster.ensureSame();
        assertEquals(3, cluster.getFsms().size());
        assertEquals(2, cluster.getFollowers().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(10, fsm.getLogs().size());
    }

    @Test
    public void testRemoveFollower() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        // apply tasks to leader
        sendTestTaskAndWait(leader);

        cluster.ensureSame();

        List<Node> followers = cluster.getFollowers();
        assertEquals(2, followers.size());

        PeerId followerPeer = followers.get(0).getNodeId().getPeerId();
        Endpoint followerAddr = followerPeer.getEndpoint();

        // stop and clean follower
        LOG.info("Stop and clean follower {}", followerPeer);
        assertTrue(cluster.stop(followerAddr));
        cluster.clean(followerAddr);

        // remove follower
        LOG.info("Remove follower {}", followerPeer);
        CountDownLatch latch = new CountDownLatch(1);
        leader.removePeer(followerPeer, new ExpectClosure(latch));
        waitLatch(latch);

        sendTestTaskAndWait(leader, 10, RaftError.SUCCESS);
        followers = cluster.getFollowers();
        assertEquals(1, followers.size());

        peers = TestUtils.generatePeers(3);
        assertTrue(peers.remove(followerPeer));

        // start follower
        LOG.info("Start and add follower {}", followerPeer);
        assertTrue(cluster.start(followerAddr));
        // re-add follower
        latch = new CountDownLatch(1);
        leader.addPeer(followerPeer, new ExpectClosure(latch));
        waitLatch(latch);

        followers = cluster.getFollowers();
        assertEquals(2, followers.size());

        cluster.ensureSame();
        assertEquals(3, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(20, fsm.getLogs().size());
    }

    @Test
    public void testRemoveLeader() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unittest", dataPath, peers, testInfo);
        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        // elect leader
        cluster.waitLeader();

        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        // apply tasks to leader
        sendTestTaskAndWait(leader);

        cluster.ensureSame();

        List<Node> followers = cluster.getFollowers();
        assertEquals(2, followers.size());

        PeerId oldLeader = leader.getNodeId().getPeerId().copy();
        Endpoint oldLeaderAddr = oldLeader.getEndpoint();

        // remove old leader
        LOG.info("Remove old leader {}", oldLeader);
        CountDownLatch latch = new CountDownLatch(1);
        leader.removePeer(oldLeader, new ExpectClosure(latch));
        waitLatch(latch);

        // elect new leader
        cluster.waitLeader();
        leader = cluster.getLeader();
        LOG.info("New leader is {}", leader);
        assertNotNull(leader);
        // apply tasks to new leader
        sendTestTaskAndWait(leader, 10, RaftError.SUCCESS);

        // stop and clean old leader
        LOG.info("Stop and clean old leader {}", oldLeader);
        assertTrue(cluster.stop(oldLeaderAddr));
        cluster.clean(oldLeaderAddr);

        // Add and start old leader
        LOG.info("Start and add old leader {}", oldLeader);
        assertTrue(cluster.start(oldLeaderAddr));

        peers = TestUtils.generatePeers(3);
        assertTrue(peers.remove(oldLeader));
        latch = new CountDownLatch(1);
        leader.addPeer(oldLeader, new ExpectClosure(latch));
        waitLatch(latch);

        followers = cluster.getFollowers();
        assertEquals(2, followers.size());
        cluster.ensureSame();
        assertEquals(3, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(20, fsm.getLogs().size());
    }

    @Test
    public void testPreVote() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();
        // get leader
        Node leader = cluster.getLeader();
        long savedTerm = ((NodeImpl) leader).getCurrentTerm();
        assertNotNull(leader);
        // apply tasks to leader
        sendTestTaskAndWait(leader);

        cluster.ensureSame();

        List<Node> followers = cluster.getFollowers();
        assertEquals(2, followers.size());

        PeerId followerPeer = followers.get(0).getNodeId().getPeerId();
        Endpoint followerAddr = followerPeer.getEndpoint();

        // remove follower
        LOG.info("Remove follower {}", followerPeer);
        CountDownLatch latch = new CountDownLatch(1);
        leader.removePeer(followerPeer, new ExpectClosure(latch));
        waitLatch(latch);

        sendTestTaskAndWait(leader, 10, RaftError.SUCCESS);

        Thread.sleep(2000);

        // add follower
        LOG.info("Add follower {}", followerAddr);
        peers = TestUtils.generatePeers(3);
        assertTrue(peers.remove(followerPeer));
        latch = new CountDownLatch(1);
        leader.addPeer(followerPeer, new ExpectClosure(latch));
        waitLatch(latch);
        leader = cluster.getLeader();
        assertNotNull(leader);
        // leader term should not be changed.
        assertEquals(savedTerm, ((NodeImpl) leader).getCurrentTerm());
    }

    @Test
    public void testSetPeer1() throws Exception {
        cluster = new TestCluster("testSetPeer1", dataPath, new ArrayList<>(), testInfo);

        PeerId bootPeer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        assertTrue(cluster.start(bootPeer.getEndpoint()));
        List<Node> nodes = cluster.getFollowers();
        assertEquals(1, nodes.size());

        List<PeerId> peers = new ArrayList<>();
        peers.add(bootPeer);
        // reset peers from empty
        assertTrue(nodes.get(0).resetPeers(new Configuration(peers)).isOk());
        cluster.waitLeader();
        assertNotNull(cluster.getLeader());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "https://issues.apache.org/jira/browse/IGNITE-17601")
    public void testSetPeer2() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();
        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        // apply tasks to leader
        sendTestTaskAndWait(leader);

        cluster.ensureSame();

        List<Node> followers = cluster.getFollowers();
        assertEquals(2, followers.size());

        PeerId followerPeer1 = followers.get(0).getNodeId().getPeerId();
        Endpoint followerAddr1 = followerPeer1.getEndpoint();
        PeerId followerPeer2 = followers.get(1).getNodeId().getPeerId();
        Endpoint followerAddr2 = followerPeer2.getEndpoint();

        LOG.info("Stop and clean follower {}", followerPeer1);
        assertTrue(cluster.stop(followerAddr1));
        cluster.clean(followerAddr1);

        // apply tasks to leader again
        sendTestTaskAndWait(leader, 10, RaftError.SUCCESS);
        // set peer when no quorum die
        Endpoint leaderAddr = leader.getLeaderId().getEndpoint().copy();
        LOG.info("Set peers to {}", leaderAddr);

        LOG.info("Stop and clean follower {}", followerPeer2);
        assertTrue(cluster.stop(followerAddr2));
        cluster.clean(followerAddr2);

        assertTrue(waitForTopology(cluster, leaderAddr, 1, 5_000));

        // leader will step-down, become follower
        Thread.sleep(2000);
        List<PeerId> newPeers = new ArrayList<>();
        newPeers.add(new PeerId(leaderAddr, 0));

        // new peers equal to current conf
        assertTrue(leader.resetPeers(new Configuration(peers)).isOk());
        // set peer when quorum die
        LOG.warn("Set peers to {}", leaderAddr);
        assertTrue(leader.resetPeers(new Configuration(newPeers)).isOk());

        cluster.waitLeader();
        leader = cluster.getLeader();
        assertNotNull(leader);
        assertEquals(leaderAddr, leader.getNodeId().getPeerId().getEndpoint());

        LOG.info("start follower {}", followerAddr1);
        assertTrue(cluster.start(followerAddr1, true, 300));
        LOG.info("start follower {}", followerAddr2);
        assertTrue(cluster.start(followerAddr2, true, 300));

        CountDownLatch latch = new CountDownLatch(1);
        LOG.info("Add old follower {}", followerAddr1);
        leader.addPeer(followerPeer1, new ExpectClosure(latch));
        waitLatch(latch);

        latch = new CountDownLatch(1);
        LOG.info("Add old follower {}", followerAddr2);
        leader.addPeer(followerPeer2, new ExpectClosure(latch));
        waitLatch(latch);

        newPeers.add(followerPeer1);
        newPeers.add(followerPeer2);

        cluster.ensureSame();
        assertEquals(3, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(20, fsm.getLogs().size());
    }

    /**
     * @throws Exception
     */
    @Test
    public void testRestoreSnapshot() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();
        // get leader
        Node leader = cluster.getLeader();

        LOG.info("Leader: " + leader);

        assertNotNull(leader);
        // apply tasks to leader
        sendTestTaskAndWait(leader);

        cluster.ensureSame();
        triggerLeaderSnapshot(cluster, leader);

        // stop leader
        Endpoint leaderAddr = leader.getNodeId().getPeerId().getEndpoint().copy();
        assertTrue(cluster.stop(leaderAddr));

        // restart leader
        cluster.waitLeader();
        assertEquals(0, cluster.getLeaderFsm().getLoadSnapshotTimes());
        assertTrue(cluster.start(leaderAddr));
        cluster.ensureSame();
        assertEquals(0, cluster.getLeaderFsm().getLoadSnapshotTimes());
    }

    /**
     * @throws Exception
     */
    @Test
    public void testRestoreSnapshotWithDelta() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();
        // get leader
        Node leader = cluster.getLeader();

        LOG.info("Leader: " + leader);

        assertNotNull(leader);
        // apply tasks to leader
        sendTestTaskAndWait(leader);

        cluster.ensureSame();
        triggerLeaderSnapshot(cluster, leader);

        // stop leader
        Endpoint leaderAddr = leader.getNodeId().getPeerId().getEndpoint().copy();
        assertTrue(cluster.stop(leaderAddr));

        // restart leader
        cluster.waitLeader();

        sendTestTaskAndWait(cluster.getLeader(), 10, RaftError.SUCCESS);

        assertEquals(0, cluster.getLeaderFsm().getLoadSnapshotTimes());
        assertTrue(cluster.start(leaderAddr));

        Node oldLeader = cluster.getNode(leaderAddr);

        cluster.ensureSame();
        assertEquals(0, cluster.getLeaderFsm().getLoadSnapshotTimes());

        MockStateMachine fsm = (MockStateMachine) oldLeader.getOptions().getFsm();
        assertEquals(1, fsm.getLoadSnapshotTimes());
    }

    @Test
    public void testInstallSnapshotWithThrottle() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), false, 200, false, new ThroughputSnapshotThrottle(1024, 1)));

        cluster.waitLeader();
        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        // apply tasks to leader
        sendTestTaskAndWait(leader);

        cluster.ensureSame();

        // stop follower1
        List<Node> followers = cluster.getFollowers();
        assertEquals(2, followers.size());

        Endpoint followerAddr = followers.get(0).getNodeId().getPeerId().getEndpoint();
        assertTrue(cluster.stop(followerAddr));

        cluster.waitLeader();

        // apply something more
        sendTestTaskAndWait(leader, 10, RaftError.SUCCESS);

        Thread.sleep(1000);

        // trigger leader snapshot
        triggerLeaderSnapshot(cluster, leader);
        // apply something more
        sendTestTaskAndWait(leader, 20, RaftError.SUCCESS);
        // trigger leader snapshot
        triggerLeaderSnapshot(cluster, leader, 2);

        // wait leader to compact logs
        Thread.sleep(1000);

        // restart follower.
        cluster.clean(followerAddr);
        assertTrue(cluster.start(followerAddr, true, 300, false, new ThroughputSnapshotThrottle(1024, 1)));

        Thread.sleep(2000);
        cluster.ensureSame();

        assertEquals(3, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(30, fsm.getLogs().size());
    }

    @Test // TODO add test for timeout on snapshot install https://issues.apache.org/jira/browse/IGNITE-14832
    @Disabled("https://issues.apache.org/jira/browse/IGNITE-16467")
    public void testInstallLargeSnapshotWithThrottle() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(4);
        cluster = new TestCluster("unitest", dataPath, peers.subList(0, 3), testInfo);
        for (int i = 0; i < peers.size() - 1; i++) {
            PeerId peer = peers.get(i);
            boolean started = cluster.start(peer.getEndpoint(), false, 200, false);
            assertTrue(started);
        }
        cluster.waitLeader();
        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        // apply tasks to leader
        sendTestTaskAndWait(leader, 0, RaftError.SUCCESS);

        cluster.ensureSame();

        // apply something more
        for (int i = 1; i < 100; i++)
            sendTestTaskAndWait(leader, i * 10, RaftError.SUCCESS);

        Thread.sleep(1000);

        // trigger leader snapshot
        triggerLeaderSnapshot(cluster, leader);

        // apply something more
        for (int i = 100; i < 200; i++)
            sendTestTaskAndWait(leader, i * 10, RaftError.SUCCESS);
        // trigger leader snapshot
        triggerLeaderSnapshot(cluster, leader, 2);

        // wait leader to compact logs
        Thread.sleep(1000);

        // add follower
        PeerId newPeer = peers.get(3);
        SnapshotThrottle snapshotThrottle = new ThroughputSnapshotThrottle(128, 1);
        boolean started = cluster.start(newPeer.getEndpoint(), false, 300, false, snapshotThrottle);
        assertTrue(started);

        CountDownLatch latch = new CountDownLatch(1);
        leader.addPeer(newPeer, status -> {
            assertTrue(status.isOk(), status.toString());
            latch.countDown();
        });
        waitLatch(latch);

        cluster.ensureSame();

        assertEquals(4, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(2000, fsm.getLogs().size());
    }

    @Test
    public void testInstallLargeSnapshot() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(4);
        cluster = new TestCluster("unitest", dataPath, peers.subList(0, 3), testInfo);
        for (int i = 0; i < peers.size() - 1; i++) {
            PeerId peer = peers.get(i);
            boolean started = cluster.start(peer.getEndpoint(), false, 200, false);
            assertTrue(started);
        }
        cluster.waitLeader();
        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        // apply tasks to leader
        sendTestTaskAndWait(leader, 0, RaftError.SUCCESS);

        cluster.ensureSame();

        // apply something more
        for (int i = 1; i < 100; i++)
            sendTestTaskAndWait(leader, i * 10, RaftError.SUCCESS);

        Thread.sleep(1000);

        // trigger leader snapshot
        triggerLeaderSnapshot(cluster, leader);

        // apply something more
        for (int i = 100; i < 200; i++)
            sendTestTaskAndWait(leader, i * 10, RaftError.SUCCESS);
        // trigger leader snapshot
        triggerLeaderSnapshot(cluster, leader, 2);

        // wait leader to compact logs
        Thread.sleep(1000);

        // add follower
        PeerId newPeer = peers.get(3);
        RaftOptions raftOptions = new RaftOptions();
        raftOptions.setMaxByteCountPerRpc(128);
        boolean started = cluster.start(newPeer.getEndpoint(), false, 300, false, null, raftOptions);
        assertTrue(started);

        CountDownLatch latch = new CountDownLatch(1);
        leader.addPeer(newPeer, status -> {
            assertTrue(status.isOk(), status.toString());
            latch.countDown();
        });
        waitLatch(latch);

        cluster.ensureSame();

        assertEquals(4, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(2000, fsm.getLogs().size());
    }

    @Test
    public void testInstallSnapshot() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();
        // get leader
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        // apply tasks to leader
        sendTestTaskAndWait(leader);

        cluster.ensureSame();

        // stop follower1
        List<Node> followers = cluster.getFollowers();
        assertEquals(2, followers.size());

        Endpoint followerAddr = followers.get(0).getNodeId().getPeerId().getEndpoint();
        assertTrue(cluster.stop(followerAddr));

        // apply something more
        sendTestTaskAndWait(leader, 10, RaftError.SUCCESS);

        // trigger leader snapshot
        triggerLeaderSnapshot(cluster, leader);
        // apply something more
        sendTestTaskAndWait(leader, 20, RaftError.SUCCESS);
        triggerLeaderSnapshot(cluster, leader, 2);

        // wait leader to compact logs
        Thread.sleep(50);

        //restart follower.
        cluster.clean(followerAddr);
        assertTrue(cluster.start(followerAddr, false, 300));

        cluster.ensureSame();

        assertEquals(3, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(30, fsm.getLogs().size(), fsm.getAddress().toString());
    }

    @Test
    public void testNoSnapshot() throws Exception {
        Endpoint addr = new Endpoint(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        NodeOptions nodeOptions = createNodeOptions(0);
        MockStateMachine fsm = new MockStateMachine(addr);
        nodeOptions.setFsm(fsm);
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
        nodeOptions.setInitialConf(new Configuration(Collections.singletonList(new PeerId(addr, 0))));

        RaftGroupService service = createService("unittest", new PeerId(addr, 0), nodeOptions);
        Node node = service.start();
        // wait node elect self as leader

        Thread.sleep(2000);

        sendTestTaskAndWait(node);

        assertEquals(0, fsm.getSaveSnapshotTimes());
        // do snapshot but returns error
        CountDownLatch latch = new CountDownLatch(1);
        node.snapshot(new ExpectClosure(RaftError.EINVAL, "Snapshot is not supported", latch));
        waitLatch(latch);
        assertEquals(0, fsm.getSaveSnapshotTimes());
    }

    @Test
    public void testAutoSnapshot() throws Exception {
        Endpoint addr = new Endpoint(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        NodeOptions nodeOptions = createNodeOptions(0);
        MockStateMachine fsm = new MockStateMachine(addr);
        nodeOptions.setFsm(fsm);
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
        nodeOptions.setSnapshotIntervalSecs(10);
        nodeOptions.setInitialConf(new Configuration(Collections.singletonList(new PeerId(addr, 0))));

        RaftGroupService service = createService("unittest", new PeerId(addr, 0), nodeOptions);
        Node node = service.start();
        // wait node elect self as leader
        Thread.sleep(2000);

        sendTestTaskAndWait(node);

        // wait for auto snapshot
        Thread.sleep(10000);
        // first snapshot will be triggered randomly
        int times = fsm.getSaveSnapshotTimes();
        assertTrue(times >= 1, "snapshotTimes=" + times);
        assertTrue(fsm.getSnapshotIndex() > 0);
    }

    @Test
    public void testLeaderShouldNotChange() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();
        // get leader
        Node leader0 = cluster.getLeader();
        assertNotNull(leader0);
        long savedTerm = ((NodeImpl) leader0).getCurrentTerm();
        LOG.info("Current leader is {}, term is {}", leader0, savedTerm);
        Thread.sleep(5000);
        cluster.waitLeader();
        Node leader1 = cluster.getLeader();
        assertNotNull(leader1);
        LOG.info("Current leader is {}", leader1);
        assertEquals(savedTerm, ((NodeImpl) leader1).getCurrentTerm());
    }

    @Test
    public void testRecoverFollower() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();

        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        List<Node> followers = cluster.getFollowers();
        assertEquals(2, followers.size());

        Endpoint followerAddr = followers.get(0).getNodeId().getPeerId().getEndpoint().copy();
        assertTrue(cluster.stop(followerAddr));

        sendTestTaskAndWait(leader);

        for (int i = 10; i < 30; i++) {
            ByteBuffer data = ByteBuffer.wrap(("no cluster" + i).getBytes(UTF_8));
            Task task = new Task(data, null);
            leader.apply(task);
        }
        // wait leader to compact logs
        Thread.sleep(5000);
        // restart follower
        assertTrue(cluster.start(followerAddr));
        cluster.ensureSame();
        assertEquals(3, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(30, fsm.getLogs().size());
    }

    @Test
    public void testLeaderTransfer() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, ELECTION_TIMEOUT_MILLIS, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();

        Node leader = cluster.getLeader();
        assertNotNull(leader);
        sendTestTaskAndWait(leader);

        Thread.sleep(100);

        List<Node> followers = cluster.getFollowers();
        assertEquals(2, followers.size());

        PeerId targetPeer = followers.get(0).getNodeId().getPeerId().copy();
        LOG.info("Transfer leadership from {} to {}", leader, targetPeer);
        assertTrue(leader.transferLeadershipTo(targetPeer).isOk());
        cluster.waitLeader();
        leader = cluster.getLeader();
        assertEquals(leader.getNodeId().getPeerId(), targetPeer);
    }

    @Test
    public void testLeaderTransferBeforeLogIsCompleted() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, ELECTION_TIMEOUT_MILLIS, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), false, 1));

        cluster.waitLeader();

        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        List<Node> followers = cluster.getFollowers();
        assertEquals(2, followers.size());

        PeerId targetPeer = followers.get(0).getNodeId().getPeerId().copy();
        assertTrue(cluster.stop(targetPeer.getEndpoint()));

        sendTestTaskAndWait(leader);
        LOG.info("Transfer leadership from {} to {}", leader, targetPeer);
        assertTrue(leader.transferLeadershipTo(targetPeer).isOk());

        CountDownLatch latch = new CountDownLatch(1);
        Task task = new Task(ByteBuffer.wrap("aaaaa".getBytes(UTF_8)), new ExpectClosure(RaftError.EBUSY, latch));
        leader.apply(task);
        waitLatch(latch);

        cluster.waitLeader();

        assertTrue(cluster.start(targetPeer.getEndpoint()));

        leader = cluster.getLeader();

        assertNotEquals(targetPeer, leader.getNodeId().getPeerId());
        cluster.ensureSame();
    }

    @Test
    public void testLeaderTransferResumeOnFailure() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, ELECTION_TIMEOUT_MILLIS, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint(), false, 1));

        cluster.waitLeader();

        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        List<Node> followers = cluster.getFollowers();
        assertEquals(2, followers.size());

        PeerId targetPeer = followers.get(0).getNodeId().getPeerId().copy();
        assertTrue(cluster.stop(targetPeer.getEndpoint()));

        sendTestTaskAndWait(leader);

        assertTrue(leader.transferLeadershipTo(targetPeer).isOk());
        Node savedLeader = leader;
        //try to apply task when transferring leadership
        CountDownLatch latch = new CountDownLatch(1);
        Task task = new Task(ByteBuffer.wrap("aaaaa".getBytes(UTF_8)), new ExpectClosure(RaftError.EBUSY, latch));
        leader.apply(task);
        waitLatch(latch);

        Thread.sleep(100);
        cluster.waitLeader();
        leader = cluster.getLeader();
        assertSame(leader, savedLeader);

        // restart target peer
        assertTrue(cluster.start(targetPeer.getEndpoint()));
        Thread.sleep(100);
        // retry apply task
        latch = new CountDownLatch(1);
        task = new Task(ByteBuffer.wrap("aaaaa".getBytes(UTF_8)), new ExpectClosure(latch));
        leader.apply(task);
        waitLatch(latch);

        cluster.ensureSame();
    }

    /**
     * mock state machine that fails to load snapshot.
     */
    static class MockFSM1 extends MockStateMachine {

        MockFSM1() {
            this(new Endpoint(Utils.IP_ANY, 0));
        }

        MockFSM1(Endpoint address) {
            super(address);
        }

        /** {@inheritDoc} */
        @Override
        public boolean onSnapshotLoad(SnapshotReader reader) {
            return false;
        }
    }

    @Test
    public void testShutdownAndJoinWorkAfterInitFails() throws Exception {
        Endpoint addr = new Endpoint(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        {
            NodeOptions nodeOptions = createNodeOptions(0);
            MockStateMachine fsm = new MockStateMachine(addr);
            nodeOptions.setFsm(fsm);
            nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
            nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
            nodeOptions.setSnapshotIntervalSecs(10);
            nodeOptions.setInitialConf(new Configuration(Collections.singletonList(new PeerId(addr, 0))));

            RaftGroupService service = createService("unittest", new PeerId(addr, 0), nodeOptions);
            Node node = service.start();

            Thread.sleep(1000);
            sendTestTaskAndWait(node);

            // save snapshot
            CountDownLatch latch = new CountDownLatch(1);
            node.snapshot(new ExpectClosure(latch));
            waitLatch(latch);
            service.shutdown();
        }
        {
            NodeOptions nodeOptions = createNodeOptions(1);
            MockStateMachine fsm = new MockFSM1(addr);
            nodeOptions.setFsm(fsm);
            nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
            nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
            nodeOptions.setSnapshotIntervalSecs(10);
            nodeOptions.setInitialConf(new Configuration(Collections.singletonList(new PeerId(addr, 0))));

            RaftGroupService service = createService("unittest", new PeerId(addr, 0), nodeOptions);
            try {
                service.start();

                fail();
            }
            catch (Exception e) {
                // Expected.
            }
        }
    }

    /**
     * 4.2.2 Removing the current leader
     *
     * @throws Exception If failed.
     */
    @Test
    public void testShuttingDownLeaderTriggerTimeoutNow() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, ELECTION_TIMEOUT_MILLIS, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();

        Node leader = cluster.getLeader();
        assertNotNull(leader);
        Node oldLeader = leader;

        LOG.info("Shutdown leader {}", leader);
        leader.shutdown();
        leader.join();

        cluster.waitLeader();
        leader = cluster.getLeader();

        assertNotNull(leader);
        assertNotSame(leader, oldLeader);
    }

    @Test
    public void testRemovingLeaderTriggerTimeoutNow() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, ELECTION_TIMEOUT_MILLIS, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();

        // Ensure the quorum before removing a leader, otherwise removePeer can be rejected.
        for (Node follower : cluster.getFollowers())
            assertTrue(waitForCondition(() -> follower.getLeaderId() != null, 5_000));

        Node leader = cluster.getLeader();
        assertNotNull(leader);
        Node oldLeader = leader;

        CountDownLatch latch = new CountDownLatch(1);
        oldLeader.removePeer(oldLeader.getNodeId().getPeerId(), new ExpectClosure(latch));
        waitLatch(latch);

        cluster.waitLeader();
        leader = cluster.getLeader();
        assertNotNull(leader);
        assertNotSame(leader, oldLeader);
    }

    @Test
    public void testTransferShouldWorkAfterInstallSnapshot() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, ELECTION_TIMEOUT_MILLIS, testInfo);

        for (int i = 0; i < peers.size() - 1; i++)
            assertTrue(cluster.start(peers.get(i).getEndpoint()));

        cluster.waitLeader();

        Node leader = cluster.getLeader();
        assertNotNull(leader);

        sendTestTaskAndWait(leader);

        List<Node> followers = cluster.getFollowers();
        assertEquals(1, followers.size());

        PeerId follower = followers.get(0).getNodeId().getPeerId();
        assertTrue(leader.transferLeadershipTo(follower).isOk());
        cluster.waitLeader();
        leader = cluster.getLeader();
        assertEquals(follower, leader.getNodeId().getPeerId());

        CountDownLatch latch = new CountDownLatch(1);
        leader.snapshot(new ExpectClosure(latch));
        waitLatch(latch);
        latch = new CountDownLatch(1);
        leader.snapshot(new ExpectClosure(latch));
        waitLatch(latch);

        // start the last peer which should be recover with snapshot.
        PeerId lastPeer = peers.get(2);
        assertTrue(cluster.start(lastPeer.getEndpoint()));
        Thread.sleep(5000);
        assertTrue(leader.transferLeadershipTo(lastPeer).isOk());
        Thread.sleep(2000);
        leader = cluster.getLeader();
        assertEquals(lastPeer, leader.getNodeId().getPeerId());
        assertEquals(3, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(10, fsm.getLogs().size());
    }

    @Test
    public void testAppendEntriesWhenFollowerIsInErrorState() throws Exception {
        // start five nodes
        List<PeerId> peers = TestUtils.generatePeers(5);

        cluster = new TestCluster("unitest", dataPath, peers, ELECTION_TIMEOUT_MILLIS, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();
        Node oldLeader = cluster.getLeader();
        assertNotNull(oldLeader);
        // apply something
        sendTestTaskAndWait(oldLeader);

        // set one follower into error state
        List<Node> followers = cluster.getFollowers();
        assertEquals(4, followers.size());
        Node errorNode = followers.get(0);
        PeerId errorPeer = errorNode.getNodeId().getPeerId().copy();
        Endpoint errorFollowerAddr = errorPeer.getEndpoint();
        LOG.info("Set follower {} into error state", errorNode);
        ((NodeImpl) errorNode).onError(new RaftException(EnumOutter.ErrorType.ERROR_TYPE_STATE_MACHINE, new Status(-1,
            "Follower has something wrong.")));

        // increase term  by stopping leader and electing a new leader again
        Endpoint oldLeaderAddr = oldLeader.getNodeId().getPeerId().getEndpoint().copy();
        assertTrue(cluster.stop(oldLeaderAddr));
        cluster.waitLeader();
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        LOG.info("Elect a new leader {}", leader);
        // apply something again
        sendTestTaskAndWait(leader, 10, RaftError.SUCCESS);

        // stop error follower
        Thread.sleep(20);
        LOG.info("Stop error follower {}", errorNode);
        assertTrue(cluster.stop(errorFollowerAddr));
        // restart error and old leader
        LOG.info("Restart error follower {} and old leader {}", errorFollowerAddr, oldLeaderAddr);

        assertTrue(cluster.start(errorFollowerAddr));
        assertTrue(cluster.start(oldLeaderAddr));
        cluster.ensureSame();
        assertEquals(5, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(20, fsm.getLogs().size());
    }

    @Test
    public void testFollowerStartStopFollowing() throws Exception {
        // start five nodes
        List<PeerId> peers = TestUtils.generatePeers(5);

        cluster = new TestCluster("unitest", dataPath, peers, ELECTION_TIMEOUT_MILLIS, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));
        cluster.waitLeader();
        Node firstLeader = cluster.getLeader();
        assertNotNull(firstLeader);
        cluster.ensureLeader(firstLeader);

        // apply something
        sendTestTaskAndWait(firstLeader);

        // assert follow times
        List<Node> firstFollowers = cluster.getFollowers();
        assertEquals(4, firstFollowers.size());
        for (Node node : firstFollowers) {
            assertTrue(
                waitForCondition(() -> ((MockStateMachine) node.getOptions().getFsm()).getOnStartFollowingTimes() == 1, 5_000));
            assertEquals(0, ((MockStateMachine) node.getOptions().getFsm()).getOnStopFollowingTimes());
        }

        // stop leader and elect new one
        Endpoint fstLeaderAddr = firstLeader.getNodeId().getPeerId().getEndpoint();
        assertTrue(cluster.stop(fstLeaderAddr));
        cluster.waitLeader();
        Node secondLeader = cluster.getLeader();
        assertNotNull(secondLeader);
        sendTestTaskAndWait(secondLeader, 10, RaftError.SUCCESS);

        // ensure start/stop following times
        List<Node> secondFollowers = cluster.getFollowers();
        assertEquals(3, secondFollowers.size());
        for (Node node : secondFollowers) {
            assertTrue(
                waitForCondition(() -> ((MockStateMachine) node.getOptions().getFsm()).getOnStartFollowingTimes() == 2, 5_000));
            assertEquals(1, ((MockStateMachine) node.getOptions().getFsm()).getOnStopFollowingTimes());
        }

        // transfer leadership to a follower
        PeerId targetPeer = secondFollowers.get(0).getNodeId().getPeerId().copy();
        assertTrue(secondLeader.transferLeadershipTo(targetPeer).isOk());
        Thread.sleep(100);
        cluster.waitLeader();
        Node thirdLeader = cluster.getLeader();
        assertEquals(targetPeer, thirdLeader.getNodeId().getPeerId());
        sendTestTaskAndWait(thirdLeader, 20, RaftError.SUCCESS);

        List<Node> thirdFollowers = cluster.getFollowers();
        assertEquals(3, thirdFollowers.size());
        for (int i = 0; i < 3; i++) {
            Node follower = thirdFollowers.get(i);
            if (follower.getNodeId().getPeerId().equals(secondLeader.getNodeId().getPeerId())) {
                assertTrue(
                    waitForCondition(() -> ((MockStateMachine) follower.getOptions().getFsm()).getOnStartFollowingTimes() == 2, 5_000));
                assertEquals(1,
                    ((MockStateMachine) follower.getOptions().getFsm()).getOnStopFollowingTimes());
                continue;
            }

            assertTrue(waitForCondition(() -> ((MockStateMachine) follower.getOptions().getFsm()).getOnStartFollowingTimes() == 3, 5_000));
            assertEquals(2, ((MockStateMachine) follower.getOptions().getFsm()).getOnStopFollowingTimes());
        }

        cluster.ensureSame();
    }

    @Test
    public void testLeaderPropagatedBeforeVote() throws Exception {
        // start five nodes
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, 3_000, testInfo);

        for (PeerId peer : peers) {
            RaftOptions opts = new RaftOptions();
            opts.setElectionHeartbeatFactor(4); // Election timeout divisor.
            assertTrue(cluster.start(peer.getEndpoint(), false, 300, false, null, opts));
        }

        List<NodeImpl> nodes = cluster.getNodes();

        AtomicReference<String> guard = new AtomicReference();

        // Block only one vote message.
        for (NodeImpl node : nodes) {
            RpcClientEx rpcClientEx = sender(node);
            rpcClientEx.recordMessages((msg, nodeId) -> true);
            rpcClientEx.blockMessages((msg, nodeId) -> {
                if (msg instanceof RpcRequests.RequestVoteRequest) {
                    RpcRequests.RequestVoteRequest msg0 = (RpcRequests.RequestVoteRequest)msg;

                    if (msg0.preVote())
                        return false;

                    if (guard.compareAndSet(null, nodeId))
                        return true;
                }

                if (msg instanceof RpcRequests.AppendEntriesRequest && nodeId.equals(guard.get())) {
                    RpcRequests.AppendEntriesRequest tmp = (RpcRequests.AppendEntriesRequest) msg;

                    if (tmp.entriesList() != null && !tmp.entriesList().isEmpty()) {
                        return true;
                    }
                }

                return false;
            });
        }

        cluster.waitLeader();
        Node leader = cluster.getLeader();
        cluster.ensureLeader(leader);

        RpcClientEx client = sender(leader);

        client.stopBlock(1); // Unblock vote message.

        // The follower shouldn't stop following on receiving stale vote request.
        Node follower = cluster.getNode(new Endpoint(NetworkAddress.from(guard.get())));

        boolean res =
            waitForCondition(() -> ((MockStateMachine) follower.getOptions().getFsm()).getOnStopFollowingTimes() != 0, 1_000);

        assertFalse(res, "The follower shouldn't stop following");
    }

    @Test
    public void readCommittedUserLog() throws Exception {
        // setup cluster
        List<PeerId> peers = TestUtils.generatePeers(3);

        cluster = new TestCluster("unitest", dataPath, peers, ELECTION_TIMEOUT_MILLIS, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));
        cluster.waitLeader();

        Node leader = cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        int amount = 10;
        sendTestTaskAndWait(leader, amount);

        assertTrue(waitForCondition(() -> {
            try {
                // index == 1 is a CONFIGURATION log
                UserLog userLog = leader.readCommittedUserLog(1 + amount);

                return userLog != null;
            } catch (Exception ignore) {
                // There is a gap between task is applied to FSM and FSMCallerImpl.lastAppliedIndex
                // is updated, so we need to wait.
                return false;
            }
        }, 10_000));

        // index == 1 is a CONFIGURATION log, so real_index will be 2 when returned.
        UserLog userLog = leader.readCommittedUserLog(1);
        assertNotNull(userLog);
        assertEquals(2, userLog.getIndex());
        assertEquals("hello0", stringFromBytes(userLog.getData().array()));

        // index == 5 is a DATA log(a user log)
        userLog = leader.readCommittedUserLog(5);
        assertNotNull(userLog);
        assertEquals(5, userLog.getIndex());
        assertEquals("hello3", stringFromBytes(userLog.getData().array()));

        // index == 15 is greater than last_committed_index
        try {
            assertNull(leader.readCommittedUserLog(15));
            fail();
        }
        catch (LogIndexOutOfBoundsException e) {
            assertEquals("Request index 15 is greater than lastAppliedIndex: 11", e.getMessage());
        }

        // index == 0 invalid request
        try {
            assertNull(leader.readCommittedUserLog(0));
            fail();
        }
        catch (LogIndexOutOfBoundsException e) {
            assertEquals("Request index is invalid: 0", e.getMessage());
        }
        LOG.info("Trigger leader snapshot");
        CountDownLatch latch = new CountDownLatch(1);
        leader.snapshot(new ExpectClosure(latch));
        waitLatch(latch);

        // remove and add a peer to add two CONFIGURATION logs
        List<Node> followers = cluster.getFollowers();
        assertEquals(2, followers.size());
        Node testFollower = followers.get(0);
        latch = new CountDownLatch(1);
        leader.removePeer(testFollower.getNodeId().getPeerId(), new ExpectClosure(latch));
        waitLatch(latch);
        latch = new CountDownLatch(1);
        leader.addPeer(testFollower.getNodeId().getPeerId(), new ExpectClosure(latch));
        waitLatch(latch);

        sendTestTaskAndWait(leader, amount, RaftError.SUCCESS);

        // trigger leader snapshot for the second time, after this the log of index 1~11 will be deleted.
        LOG.info("Trigger leader snapshot");
        latch = new CountDownLatch(1);
        leader.snapshot(new ExpectClosure(latch));
        waitLatch(latch);
        Thread.sleep(100);

        // index == 5 log has been deleted in log_storage.
        try {
            leader.readCommittedUserLog(5);
            fail();
        }
        catch (LogNotFoundException e) {
            assertEquals("User log is deleted at index: 5", e.getMessage());
        }

        // index == 12、index == 13、index=14、index=15 are 4 CONFIGURATION logs(joint consensus), so real_index will be 16 when returned.
        userLog = leader.readCommittedUserLog(12);
        assertNotNull(userLog);
        assertEquals(16, userLog.getIndex());
        assertEquals("hello10", stringFromBytes(userLog.getData().array()));

        // now index == 17 is a user log
        userLog = leader.readCommittedUserLog(17);
        assertNotNull(userLog);
        assertEquals(17, userLog.getIndex());
        assertEquals("hello11", stringFromBytes(userLog.getData().array()));

        cluster.ensureSame();
        assertEquals(3, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms()) {
            assertEquals(20, fsm.getLogs().size());
            for (int i = 0; i < 20; i++)
                assertEquals("hello" + i, stringFromBytes(fsm.getLogs().get(i).array()));
        }
    }

    @Test
    public void testBootStrapWithSnapshot() throws Exception {
        Endpoint addr = new Endpoint("127.0.0.1", 5006);
        MockStateMachine fsm = new MockStateMachine(addr);

        Path path = Path.of(dataPath, "node0", "log");
        Files.createDirectories(path);

        for (char ch = 'a'; ch <= 'z'; ch++)
            fsm.getLogs().add(ByteBuffer.wrap(new byte[] {(byte) ch}));

        BootstrapOptions opts = new BootstrapOptions();
        DefaultLogStorageFactory logStorageProvider = new DefaultLogStorageFactory(path);
        logStorageProvider.start();
        opts.setServiceFactory(new IgniteJraftServiceFactory(logStorageProvider));
        opts.setLastLogIndex(fsm.getLogs().size());
        opts.setRaftMetaUri(dataPath + File.separator + "meta");
        opts.setSnapshotUri(dataPath + File.separator + "snapshot");
        opts.setLogUri("test");
        opts.setGroupConf(JRaftUtils.getConfiguration("127.0.0.1:5006"));
        opts.setFsm(fsm);

        NodeOptions nodeOpts = new NodeOptions();
        opts.setNodeOptions(nodeOpts);

        assertTrue(JRaftUtils.bootstrap(opts));
        logStorageProvider.close();

        nodeOpts.setRaftMetaUri(dataPath + File.separator + "meta");
        nodeOpts.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOpts.setLogUri("test");
        DefaultLogStorageFactory log2 = new DefaultLogStorageFactory(path);
        log2.start();
        nodeOpts.setServiceFactory(new IgniteJraftServiceFactory(log2));
        nodeOpts.setFsm(fsm);

        RaftGroupService service = createService("test", new PeerId(addr, 0), nodeOpts);

        Node node = service.start();
        assertEquals(26, fsm.getLogs().size());

        for (int i = 0; i < 26; i++)
            assertEquals('a' + i, fsm.getLogs().get(i).get());

        // Group configuration will be restored from snapshot meta.
        while (!node.isLeader())
            Thread.sleep(20);
        sendTestTaskAndWait(node);
        assertEquals(36, fsm.getLogs().size());
    }

    @Test
    public void testBootStrapWithoutSnapshot() throws Exception {
        Endpoint addr = new Endpoint("127.0.0.1", 5006);
        MockStateMachine fsm = new MockStateMachine(addr);

        Path path = Path.of(dataPath, "node0", "log");
        Files.createDirectories(path);

        BootstrapOptions opts = new BootstrapOptions();
        DefaultLogStorageFactory logStorageProvider = new DefaultLogStorageFactory(path);
        logStorageProvider.start();
        opts.setServiceFactory(new IgniteJraftServiceFactory(logStorageProvider));
        opts.setLastLogIndex(0);
        opts.setRaftMetaUri(dataPath + File.separator + "meta");
        opts.setSnapshotUri(dataPath + File.separator + "snapshot");
        opts.setLogUri("test");
        opts.setGroupConf(JRaftUtils.getConfiguration("127.0.0.1:5006"));
        opts.setFsm(fsm);
        NodeOptions nodeOpts = new NodeOptions();
        opts.setNodeOptions(nodeOpts);

        assertTrue(JRaftUtils.bootstrap(opts));
        logStorageProvider.close();

        nodeOpts.setRaftMetaUri(dataPath + File.separator + "meta");
        nodeOpts.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOpts.setLogUri("test");
        nodeOpts.setFsm(fsm);
        DefaultLogStorageFactory log2 = new DefaultLogStorageFactory(path);
        log2.start();
        nodeOpts.setServiceFactory(new IgniteJraftServiceFactory(log2));

        RaftGroupService service = createService("test", new PeerId(addr, 0), nodeOpts);

        Node node = service.start();
        while (!node.isLeader())
            Thread.sleep(20);
        sendTestTaskAndWait(node);
        assertEquals(10, fsm.getLogs().size());
    }

    @Test
    public void testChangePeers() throws Exception {
        changePeers(false);
    }

    @Test
    public void testChangeAsyncPeers() throws Exception {
        changePeers(true);
    }

    private void changePeers(boolean async) throws Exception {
        PeerId peer0 = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        cluster = new TestCluster("testChangePeers", dataPath, Collections.singletonList(peer0), testInfo);
        assertTrue(cluster.start(peer0.getEndpoint()));

        cluster.waitLeader();
        Node leader = cluster.getLeader();
        sendTestTaskAndWait(leader);

        for (int i = 1; i < 10; i++) {
            PeerId peer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + i);
            assertTrue(cluster.start(peer.getEndpoint(), false, 300));
        }
        for (int i = 0; i < 9; i++) {
            cluster.waitLeader();
            leader = cluster.getLeader();
            assertNotNull(leader);
            PeerId leaderPeer = new PeerId(TestUtils.getLocalAddress(), peer0.getEndpoint().getPort() + i);
            assertEquals(leaderPeer, leader.getNodeId().getPeerId());
            PeerId newLeaderPeer = new PeerId(TestUtils.getLocalAddress(), peer0.getEndpoint().getPort() + i + 1);
            if (async) {
                SynchronizedClosure done = new SynchronizedClosure();
                leader.changePeersAsync(new Configuration(Collections.singletonList(newLeaderPeer)),
                        leader.getCurrentTerm(), done);
                Status status = done.await();
                assertTrue(status.isOk(), status.getRaftError().toString());
                assertTrue(waitForCondition(() -> {
                    if (cluster.getLeader() != null) {
                        return newLeaderPeer.equals(cluster.getLeader().getLeaderId());
                    }
                    return false;
                }, 10_000));
            } else {
                SynchronizedClosure done = new SynchronizedClosure();
                leader.changePeers(new Configuration(Collections.singletonList(newLeaderPeer)), done);
                Status status = done.await();
                assertTrue(status.isOk(), status.getRaftError().toString());
            }
        }

        cluster.waitLeader();

        for (MockStateMachine fsm : cluster.getFsms()) {
            assertEquals(10, fsm.getLogs().size());
        }
    }

    @Test
    public void testOnReconfigurationErrorListener() throws Exception {
        PeerId peer0 = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        cluster = new TestCluster("testChangePeers", dataPath, Collections.singletonList(peer0), testInfo);

        var raftGrpEvtsLsnr = mock(RaftGroupEventsListener.class);

        cluster.setRaftGrpEvtsLsnr(raftGrpEvtsLsnr);
        assertTrue(cluster.start(peer0.getEndpoint()));

        cluster.waitLeader();

        Node leader = cluster.getLeader();
        sendTestTaskAndWait(leader);

        verify(raftGrpEvtsLsnr, never()).onNewPeersConfigurationApplied(any());

        PeerId newPeer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 1);

        SynchronizedClosure done = new SynchronizedClosure();

        leader.changePeersAsync(new Configuration(Collections.singletonList(newPeer)),
                leader.getCurrentTerm(), done);
        assertEquals(done.await(), Status.OK());

        verify(raftGrpEvtsLsnr, timeout(10_000))
                .onReconfigurationError(argThat(st -> st.getRaftError() == RaftError.ECATCHUP), any(), anyLong());
    }

    @Test
    public void testNewPeersConfigurationAppliedListener() throws Exception {
        PeerId peer0 = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        cluster = new TestCluster("testChangePeers", dataPath, Collections.singletonList(peer0), testInfo);

        var raftGrpEvtsLsnr = mock(RaftGroupEventsListener.class);

        cluster.setRaftGrpEvtsLsnr(raftGrpEvtsLsnr);
        assertTrue(cluster.start(peer0.getEndpoint()));

        cluster.waitLeader();

        Node leader = cluster.getLeader();
        sendTestTaskAndWait(leader);

        for (int i = 1; i < 5; i++) {
            PeerId peer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + i);
            assertTrue(cluster.start(peer.getEndpoint(), false, 300));
        }

        verify(raftGrpEvtsLsnr, never()).onNewPeersConfigurationApplied(any());

        for (int i = 0; i < 4; i++) {
            leader = cluster.getLeader();
            assertNotNull(leader);
            PeerId peer = new PeerId(TestUtils.getLocalAddress(), peer0.getEndpoint().getPort() + i);
            assertEquals(peer, leader.getNodeId().getPeerId());
            PeerId newPeer = new PeerId(TestUtils.getLocalAddress(), peer0.getEndpoint().getPort() + i + 1);

            SynchronizedClosure done = new SynchronizedClosure();
            leader.changePeersAsync(new Configuration(Collections.singletonList(newPeer)),
                    leader.getCurrentTerm(), done);
            assertEquals(done.await(), Status.OK());
            assertTrue(waitForCondition(() -> {
                if (cluster.getLeader() != null) {
                    return newPeer.equals(cluster.getLeader().getLeaderId());
                }
                return false;
            }, 10_000));

            verify(raftGrpEvtsLsnr, times(1)).onNewPeersConfigurationApplied(Collections.singletonList(newPeer));
        }
    }

    @Test
    public void testChangePeersOnLeaderElected() throws Exception {
        List<PeerId> peers = IntStream.range(0, 6)
                .mapToObj(i -> new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + i))
                .collect(toList());

        cluster = new TestCluster("testChangePeers", dataPath, peers, testInfo);

        var raftGrpEvtsLsnr = mock(RaftGroupEventsListener.class);

        cluster.setRaftGrpEvtsLsnr(raftGrpEvtsLsnr);

        for (PeerId p: peers) {
            assertTrue(cluster.start(p.getEndpoint(), false, 300));
        }

        cluster.waitLeader();

        verify(raftGrpEvtsLsnr, times(1)).onLeaderElected(anyLong());

        cluster.stop(cluster.getLeader().getLeaderId().getEndpoint());

        cluster.waitLeader();

        verify(raftGrpEvtsLsnr, times(2)).onLeaderElected(anyLong());

        cluster.stop(cluster.getLeader().getLeaderId().getEndpoint());

        cluster.waitLeader();

        verify(raftGrpEvtsLsnr, times(3)).onLeaderElected(anyLong());
    }

    @Test
    public void changePeersAsyncResponses() throws Exception {
        PeerId peer0 = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        cluster = new TestCluster("testChangePeers", dataPath, Collections.singletonList(peer0), testInfo);
        assertTrue(cluster.start(peer0.getEndpoint()));

        cluster.waitLeader();
        Node leader = cluster.getLeader();
        sendTestTaskAndWait(leader);

        PeerId peer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + 1);
        assertTrue(cluster.start(peer.getEndpoint(), false, 300));

        cluster.waitLeader();
        leader = cluster.getLeader();
        assertNotNull(leader);
        PeerId leaderPeer = new PeerId(TestUtils.getLocalAddress(), peer0.getEndpoint().getPort());
        assertEquals(leaderPeer, leader.getNodeId().getPeerId());

        PeerId newLeaderPeer = new PeerId(TestUtils.getLocalAddress(), peer0.getEndpoint().getPort() + 1);

        // wrong leader term, do nothing
        SynchronizedClosure done = new SynchronizedClosure();
        leader.changePeersAsync(new Configuration(Collections.singletonList(newLeaderPeer)),
                leader.getCurrentTerm() - 1, done);
        assertEquals(done.await(), Status.OK());

        // the same config, do nothing
        done = new SynchronizedClosure();
        leader.changePeersAsync(new Configuration(Collections.singletonList(leaderPeer)),
                leader.getCurrentTerm(), done);
        assertEquals(done.await(), Status.OK());

        // change peer to new conf containing only new node
        done = new SynchronizedClosure();
        leader.changePeersAsync(new Configuration(Collections.singletonList(newLeaderPeer)),
                leader.getCurrentTerm(), done);
        assertEquals(done.await(), Status.OK());

        assertTrue(waitForCondition(() -> {
            if (cluster.getLeader() != null)
                return newLeaderPeer.equals(cluster.getLeader().getLeaderId());
            return false;
        }, 10_000));

        for (MockStateMachine fsm : cluster.getFsms()) {
            assertEquals(10, fsm.getLogs().size());
        }

        // check concurrent start of two async change peers.
        Node newLeader = cluster.getLeader();

        sendTestTaskAndWait(newLeader);

        ExecutorService executor = Executors.newFixedThreadPool(10);

        List<SynchronizedClosure> dones = new ArrayList<>();
        List<Future> futs = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            SynchronizedClosure newDone = new SynchronizedClosure();
            dones.add(newDone);
            futs.add(executor.submit(() -> {
                newLeader.changePeersAsync(new Configuration(Collections.singletonList(peer0)), 2, newDone);
            }));
        }
        futs.get(0).get();
        futs.get(1).get();

        Status firstDoneStatus = dones.get(0).await();
        Status secondDoneStatus = dones.get(1).await();
        assertThat(
                List.of(firstDoneStatus.getRaftError(), secondDoneStatus.getRaftError()),
                containsInAnyOrder(RaftError.SUCCESS, RaftError.EBUSY)
        );

        assertTrue(waitForCondition(() -> {
            if (cluster.getLeader() != null)
                return peer0.equals(cluster.getLeader().getLeaderId());
            return false;
        }, 10_000));

        for (MockStateMachine fsm : cluster.getFsms()) {
            assertEquals(20, fsm.getLogs().size());
        }
    }

    @Test
    public void testChangePeersAddMultiNodes() throws Exception {
        PeerId peer0 = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT);
        cluster = new TestCluster("testChangePeers", dataPath, Collections.singletonList(peer0), testInfo);
        assertTrue(cluster.start(peer0.getEndpoint()));

        cluster.waitLeader();
        Node leader = cluster.getLeader();
        sendTestTaskAndWait(leader);

        Configuration conf = new Configuration();
        for (int i = 0; i < 3; i++) {
            PeerId peer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + i);
            conf.addPeer(peer);
        }

        PeerId peer = new PeerId(TestUtils.getLocalAddress(), peer0.getEndpoint().getPort() + 1);
        // fail, because the peers are not started.
        SynchronizedClosure done = new SynchronizedClosure();
        leader.changePeers(new Configuration(Collections.singletonList(peer)), done);
        assertEquals(RaftError.ECATCHUP, done.await().getRaftError());

        // start peer1
        assertTrue(cluster.start(peer.getEndpoint()));
        // still fail, because peer2 is not started
        done.reset();
        leader.changePeers(conf, done);
        assertEquals(RaftError.ECATCHUP, done.await().getRaftError());
        // start peer2
        peer = new PeerId(TestUtils.getLocalAddress(), peer0.getEndpoint().getPort() + 2);
        assertTrue(cluster.start(peer.getEndpoint()));
        done.reset();
        // works
        leader.changePeers(conf, done);
        Status await = done.await();
        assertTrue(await.isOk(), await.getErrorMsg());

        cluster.ensureSame();
        assertEquals(3, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertEquals(10, fsm.getLogs().size());
    }

    @Test
    public void testChangePeersStepsDownInJointConsensus() throws Exception {
        List<PeerId> peers = new ArrayList<>();

        PeerId peer0 = JRaftUtils.getPeerId(TestUtils.getLocalAddress() + ":5006");
        PeerId peer1 = JRaftUtils.getPeerId(TestUtils.getLocalAddress() + ":5007");
        PeerId peer2 = JRaftUtils.getPeerId(TestUtils.getLocalAddress() + ":5008");
        PeerId peer3 = JRaftUtils.getPeerId(TestUtils.getLocalAddress() + ":5009");

        // start single cluster
        peers.add(peer0);
        cluster = new TestCluster("testChangePeersStepsDownInJointConsensus", dataPath, peers, testInfo);
        assertTrue(cluster.start(peer0.getEndpoint()));

        cluster.waitLeader();
        Node leader = cluster.getLeader();
        assertNotNull(leader);
        sendTestTaskAndWait(leader);

        // start peer1-3
        assertTrue(cluster.start(peer1.getEndpoint()));
        assertTrue(cluster.start(peer2.getEndpoint()));
        assertTrue(cluster.start(peer3.getEndpoint()));

        // Make sure the topology is ready before adding peers.
        assertTrue(waitForTopology(cluster, leader.getNodeId().getPeerId().getEndpoint(), 4, 3_000));

        Configuration conf = new Configuration();
        conf.addPeer(peer0);
        conf.addPeer(peer1);
        conf.addPeer(peer2);
        conf.addPeer(peer3);

        // change peers
        SynchronizedClosure done = new SynchronizedClosure();
        leader.changePeers(conf, done);
        assertTrue(done.await().isOk());

        // stop peer3
        assertTrue(cluster.stop(peer3.getEndpoint()));

        conf.removePeer(peer0);
        conf.removePeer(peer1);

        // Change peers to [peer2, peer3], which must fail since peer3 is stopped
        done.reset();
        leader.changePeers(conf, done);
        assertEquals(RaftError.EPERM, done.await().getRaftError());
        LOG.info(done.getStatus().toString());

        assertFalse(((NodeImpl) leader).getConf().isStable());

        leader = cluster.getLeader();
        assertNull(leader);

        assertTrue(cluster.start(peer3.getEndpoint()));
        Thread.sleep(1000);
        cluster.waitLeader();
        leader = cluster.getLeader();
        List<PeerId> thePeers = leader.listPeers();
        assertTrue(!thePeers.isEmpty());
        assertEquals(conf.getPeerSet(), new HashSet<>(thePeers));
    }

    static class ChangeArg {
        TestCluster c;
        List<PeerId> peers;
        volatile boolean stop;
        boolean dontRemoveFirstPeer;

        ChangeArg(TestCluster c, List<PeerId> peers, boolean stop,
            boolean dontRemoveFirstPeer) {
            super();
            this.c = c;
            this.peers = peers;
            this.stop = stop;
            this.dontRemoveFirstPeer = dontRemoveFirstPeer;
        }

    }

    private Future<?> startChangePeersThread(ChangeArg arg) {
        Set<RaftError> expectedErrors = new HashSet<>();
        expectedErrors.add(RaftError.EBUSY);
        expectedErrors.add(RaftError.EPERM);
        expectedErrors.add(RaftError.ECATCHUP);

        ExecutorService executor = Executors.newSingleThreadExecutor();

        executors.add(executor);

        return Utils.runInThread(executor, () -> {
            try {
                while (!arg.stop) {
                    arg.c.waitLeader();
                    Node leader = arg.c.getLeader();
                    if (leader == null)
                        continue;
                    // select peers in random
                    Configuration conf = new Configuration();
                    if (arg.dontRemoveFirstPeer)
                        conf.addPeer(arg.peers.get(0));
                    for (int i = 0; i < arg.peers.size(); i++) {
                        boolean select = ThreadLocalRandom.current().nextInt(64) < 32;
                        if (select && !conf.contains(arg.peers.get(i)))
                            conf.addPeer(arg.peers.get(i));
                    }
                    if (conf.isEmpty()) {
                        LOG.warn("No peer has been selected");
                        continue;
                    }
                    SynchronizedClosure done = new SynchronizedClosure();
                    leader.changePeers(conf, done);
                    done.await();
                    assertTrue(done.getStatus().isOk() || expectedErrors.contains(done.getStatus().getRaftError()), done.getStatus().toString());
                }
            }
            catch (InterruptedException e) {
                LOG.error("ChangePeersThread is interrupted", e);
            }
        });
    }

    @Test
    public void testChangePeersChaosWithSnapshot() throws Exception {
        // start cluster
        List<PeerId> peers = new ArrayList<>();
        peers.add(new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT));
        cluster = new TestCluster("unittest", dataPath, peers, ELECTION_TIMEOUT_MILLIS, testInfo);
        assertTrue(cluster.start(peers.get(0).getEndpoint(), false, 2));
        // start other peers
        for (int i = 1; i < 10; i++) {
            PeerId peer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + i);
            peers.add(peer);
            assertTrue(cluster.start(peer.getEndpoint()));
        }

        ChangeArg arg = new ChangeArg(cluster, peers, false, false);

        Future<?> future = startChangePeersThread(arg);
        for (int i = 0; i < 5000; ) {
            cluster.waitLeader();
            Node leader = cluster.getLeader();
            if (leader == null)
                continue;
            SynchronizedClosure done = new SynchronizedClosure();
            Task task = new Task(ByteBuffer.wrap(("hello" + i).getBytes(UTF_8)), done);
            leader.apply(task);
            Status status = done.await();
            if (status.isOk()) {
                if (++i % 100 == 0)
                    System.out.println("Progress:" + i);
            }
            else
                assertEquals(RaftError.EPERM, status.getRaftError());
        }
        arg.stop = true;
        future.get();
        cluster.waitLeader();
        SynchronizedClosure done = new SynchronizedClosure();
        Node leader = cluster.getLeader();
        leader.changePeers(new Configuration(peers), done);
        Status st = done.await();
        assertTrue(st.isOk(), st.getErrorMsg());
        cluster.ensureSame();
        assertEquals(10, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms())
            assertTrue(fsm.getLogs().size() >= 5000);
    }

    @Test
    public void testChangePeersChaosWithoutSnapshot() throws Exception {
        // start cluster
        List<PeerId> peers = new ArrayList<>();
        peers.add(new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT));
        cluster = new TestCluster("unittest", dataPath, peers, ELECTION_TIMEOUT_MILLIS, testInfo);
        assertTrue(cluster.start(peers.get(0).getEndpoint(), false, 100000));
        // start other peers
        for (int i = 1; i < 10; i++) {
            PeerId peer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + i);
            peers.add(peer);
            assertTrue(cluster.start(peer.getEndpoint(), false, 10000));
        }

        ChangeArg arg = new ChangeArg(cluster, peers, false, true);

        Future<?> future = startChangePeersThread(arg);
        final int tasks = 5000;
        for (int i = 0; i < tasks; ) {
            cluster.waitLeader();
            Node leader = cluster.getLeader();
            if (leader == null)
                continue;
            SynchronizedClosure done = new SynchronizedClosure();
            Task task = new Task(ByteBuffer.wrap(("hello" + i).getBytes(UTF_8)), done);
            leader.apply(task);
            Status status = done.await();
            if (status.isOk()) {
                if (++i % 100 == 0)
                    System.out.println("Progress:" + i);
            }
            else
                assertEquals(RaftError.EPERM, status.getRaftError());
        }
        arg.stop = true;
        future.get();
        cluster.waitLeader();
        SynchronizedClosure done = new SynchronizedClosure();
        Node leader = cluster.getLeader();
        leader.changePeers(new Configuration(peers), done);
        assertTrue(done.await().isOk());
        cluster.ensureSame();
        assertEquals(10, cluster.getFsms().size());
        for (MockStateMachine fsm : cluster.getFsms()) {
            assertTrue(fsm.getLogs().size() >= tasks);
            assertTrue(fsm.getLogs().size() - tasks < 100);
        }
    }

    @Test
    public void testChangePeersChaosApplyTasks() throws Exception {
        // start cluster
        List<PeerId> peers = new ArrayList<>();
        peers.add(new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT));
        cluster = new TestCluster("unittest", dataPath, peers, ELECTION_TIMEOUT_MILLIS, testInfo);
        assertTrue(cluster.start(peers.get(0).getEndpoint(), false, 100000));
        // start other peers
        for (int i = 1; i < 10; i++) {
            PeerId peer = new PeerId(TestUtils.getLocalAddress(), TestUtils.INIT_PORT + i);
            peers.add(peer);
            assertTrue(cluster.start(peer.getEndpoint(), false, 100000));
        }

        final int threads = 3;
        List<ChangeArg> args = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        executors.add(executor);

        for (int t = 0; t < threads; t++) {
            ChangeArg arg = new ChangeArg(cluster, peers, false, true);
            args.add(arg);
            futures.add(startChangePeersThread(arg));

            Utils.runInThread(executor, () -> {
                try {
                    for (int i = 0; i < 5000; ) {
                        cluster.waitLeader();
                        Node leader = cluster.getLeader();
                        if (leader == null)
                            continue;
                        SynchronizedClosure done = new SynchronizedClosure();
                        Task task = new Task(ByteBuffer.wrap(("hello" + i).getBytes(UTF_8)), done);
                        leader.apply(task);
                        Status status = done.await();
                        if (status.isOk()) {
                            if (++i % 100 == 0)
                                System.out.println("Progress:" + i);
                        }
                        else
                            assertEquals(RaftError.EPERM, status.getRaftError());
                    }
                }
                catch (Exception e) {
                    LOG.error("Failed to run tasks", e);
                }
                finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        for (ChangeArg arg : args)
            arg.stop = true;
        for (Future<?> future : futures)
            future.get();

        cluster.waitLeader();
        SynchronizedClosure done = new SynchronizedClosure();
        Node leader = cluster.getLeader();
        leader.changePeers(new Configuration(peers), done);
        assertTrue(done.await().isOk());
        cluster.ensureSame();
        assertEquals(10, cluster.getFsms().size());

        for (MockStateMachine fsm : cluster.getFsms()) {
            int logSize = fsm.getLogs().size();
            assertTrue(logSize >= 5000 * threads, "logSize= " + logSize);
            assertTrue(logSize - 5000 * threads < 100, "logSize= " + logSize);
        }
    }

    @Test
    public void testBlockedElection() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);
        cluster = new TestCluster("unittest", dataPath, peers, testInfo);

        for (PeerId peer : peers)
            assertTrue(cluster.start(peer.getEndpoint()));

        cluster.waitLeader();

        Node leader = cluster.getLeader();

        LOG.warn("Current leader {}, electTimeout={}", leader.getNodeId().getPeerId(), leader.getOptions().getElectionTimeoutMs());

        List<Node> followers = cluster.getFollowers();

        blockMessagesOnFollowers(followers, (msg, nodeId) -> {
            if (msg instanceof RpcRequests.RequestVoteRequest) {
                RpcRequests.RequestVoteRequest msg0 = (RpcRequests.RequestVoteRequest) msg;

                return !msg0.preVote();
            }

            return false;
        });

        LOG.warn("Stop leader {}, curTerm={}", leader.getNodeId().getPeerId(), ((NodeImpl) leader).getCurrentTerm());

        assertTrue(cluster.stop(leader.getNodeId().getPeerId().getEndpoint()));

        assertNull(cluster.getLeader());

        Thread.sleep(2000);

        assertNull(cluster.getLeader());

        stopBlockingMessagesOnFollowers(followers);

        // elect new leader
        cluster.waitLeader();
        leader = cluster.getLeader();
        LOG.info("Elect new leader is {}, curTerm={}", leader.getLeaderId(), ((NodeImpl) leader).getCurrentTerm());
    }

    @Test
    public void testElectionTimeoutAutoAdjustWhenBlockedAllMessages() throws Exception {
        testElectionTimeoutAutoAdjustWhenBlockedMessages((msg, nodeId) -> true);
    }

    @Test
    public void testElectionTimeoutAutoAdjustWhenBlockedRequestVoteMessages() throws Exception {
        testElectionTimeoutAutoAdjustWhenBlockedMessages((msg, nodeId) -> {
            if (msg instanceof RpcRequests.RequestVoteRequest) {
                RpcRequests.RequestVoteRequest msg0 = (RpcRequests.RequestVoteRequest) msg;

                return !msg0.preVote();
            }

            return false;
        });
    }

    private void testElectionTimeoutAutoAdjustWhenBlockedMessages(BiPredicate<Object, String> blockingPredicate) throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(4);
        int maxElectionRoundsWithoutAdjusting = 3;

        cluster = new TestCluster("unittest", dataPath, peers, new LinkedHashSet<>(), ELECTION_TIMEOUT_MILLIS,
                opts -> opts.setElectionTimeoutStrategy(new ExponentialBackoffTimeoutStrategy(11_000, maxElectionRoundsWithoutAdjusting)),
                testInfo);

        for (PeerId peer : peers) {
            assertTrue(cluster.start(peer.getEndpoint()));
        }

        cluster.waitLeader();

        Node leader = cluster.getLeader();

        int initElectionTimeout = leader.getOptions().getElectionTimeoutMs();

        LOG.warn("Current leader {}, electTimeout={}", leader.getNodeId().getPeerId(), leader.getOptions().getElectionTimeoutMs());

        List<Node> followers = cluster.getFollowers();

        for (Node follower : followers) {
            NodeImpl follower0 = (NodeImpl) follower;

            assertEquals(initElectionTimeout, follower0.getOptions().getElectionTimeoutMs());
        }

        blockMessagesOnFollowers(followers, blockingPredicate);

        LOG.warn("Stop leader {}, curTerm={}", leader.getNodeId().getPeerId(), ((NodeImpl) leader).getCurrentTerm());

        assertTrue(cluster.stop(leader.getNodeId().getPeerId().getEndpoint()));

        assertNull(cluster.getLeader());

        assertTrue(waitForCondition(() -> followers.stream().allMatch(f -> f.getOptions().getElectionTimeoutMs() > initElectionTimeout),
                (long) maxElectionRoundsWithoutAdjusting
                        // need to multiply to 2 because stepDown happens after voteTimer timeout
                        * (initElectionTimeout + followers.get(0).getOptions().getRaftOptions().getMaxElectionDelayMs()) * 2));

        stopBlockingMessagesOnFollowers(followers);

        // elect new leader
        cluster.waitLeader();
        leader = cluster.getLeader();

        LOG.info("Elected new leader is {}, curTerm={}", leader.getLeaderId(), ((NodeImpl) leader).getCurrentTerm());

        assertTrue(
                waitForCondition(() -> followers.stream().allMatch(f -> f.getOptions().getElectionTimeoutMs() == initElectionTimeout),
                        3_000));
    }

    /**
     * Tests if a read using leader leases works correctly after previous leader segmentation.
     */
    @Test
    public void testLeaseReadAfterSegmentation() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(3);
        cluster = new TestCluster("unittest", dataPath, peers, 3_000, testInfo);

        for (PeerId peer : peers) {
            RaftOptions opts = new RaftOptions();
            opts.setElectionHeartbeatFactor(2); // Election timeout divisor.
            opts.setReadOnlyOptions(ReadOnlyOption.ReadOnlyLeaseBased);
            assertTrue(cluster.start(peer.getEndpoint(), false, 300, false, null, opts));
        }

        cluster.waitLeader();

        NodeImpl leader = (NodeImpl) cluster.getLeader();
        assertNotNull(leader);
        cluster.ensureLeader(leader);

        sendTestTaskAndWait(leader);
        cluster.ensureSame();

        DefaultRaftClientService rpcService = (DefaultRaftClientService) leader.getRpcClientService();
        RpcClientEx rpcClientEx = (RpcClientEx) rpcService.getRpcClient();

        AtomicInteger cnt = new AtomicInteger();

        rpcClientEx.blockMessages((msg, nodeId) -> {
            assertTrue(msg instanceof RpcRequests.AppendEntriesRequest);

            if (cnt.get() >= 2)
                return true;

            LOG.info("Send heartbeat: " + msg + " to " + nodeId);

            cnt.incrementAndGet();

            return false;
        });

        assertTrue(waitForCondition(() -> cluster.getLeader() != null &&
            !leader.getNodeId().equals(cluster.getLeader().getNodeId()), 10_000));

        CompletableFuture<Status> res = new CompletableFuture<>();

        cluster.getLeader().readIndex(null, new ReadIndexClosure() {
            @Override public void run(Status status, long index, byte[] reqCtx) {
                res.complete(status);
            }
        });

        assertTrue(res.get().isOk());
    }

    /**
     * Tests propagation of HLC on heartbeat request and response.
     */
    @Test
    public void testHlcPropagation() throws Exception {
        List<PeerId> peers = TestUtils.generatePeers(2);

        cluster = new TestCluster("unitest", dataPath, peers, 3_000, testInfo);

        for (PeerId peer : peers) {
            RaftOptions opts = new RaftOptions();
            opts.setElectionHeartbeatFactor(4); // Election timeout divisor.
            HybridClock clock = new HybridClock();
            assertTrue(cluster.start(peer.getEndpoint(), false, 300, false, null, opts, clock));
        }

        List<NodeImpl> nodes = cluster.getNodes();

        for (NodeImpl node : nodes) {
            RpcClientEx rpcClientEx = sender(node);
            rpcClientEx.recordMessages((msg, nodeId) -> {
                if (msg instanceof AppendEntriesRequestImpl ||
                    msg instanceof AppendEntriesResponseImpl) {
                    return true;
                }

                return false;

            });
        }

        cluster.waitLeader();
        Node leader = cluster.getLeader();
        cluster.ensureLeader(leader);

        RpcClientEx client = sender(leader);

        AtomicBoolean heartbeatRequest = new AtomicBoolean(false);
        AtomicBoolean appendEntriesRequest = new AtomicBoolean(false);
        AtomicBoolean heartbeatResponse = new AtomicBoolean(false);
        AtomicBoolean appendEntriesResponse = new AtomicBoolean(false);

        waitForCondition(() -> {
            client.recordedMessages().forEach(msgs -> {
                if (msgs[0] instanceof AppendEntriesRequestImpl) {
                    AppendEntriesRequestImpl msg = (AppendEntriesRequestImpl) msgs[0];

                    if (msg.entriesList() == null && msg.data() == null) {
                        heartbeatRequest.set(true);
                        assertTrue(msg.timestamp() != null);
                    } else {
                        appendEntriesRequest.set(true);
                        assertTrue(msg.timestamp() == null);
                    }
                } else if (msgs[0] instanceof AppendEntriesResponseImpl) {
                    AppendEntriesResponseImpl msg = (AppendEntriesResponseImpl) msgs[0];
                    if (msg.timestamp() == null) {
                        appendEntriesResponse.set(true);
                    } else {
                        heartbeatResponse.set(true);
                    }
                }
            });

            return heartbeatRequest.get() &&
                    appendEntriesRequest.get() &&
                    heartbeatResponse.get() &&
                    appendEntriesResponse.get();
        },
                5000);

        assertTrue(heartbeatRequest.get());
        assertTrue(appendEntriesRequest.get());
        assertTrue(heartbeatResponse.get());
        assertTrue(appendEntriesResponse.get());
    }

    private NodeOptions createNodeOptions(int nodeIdx) {
        NodeOptions options = new NodeOptions();

        DefaultLogStorageFactory log = new DefaultLogStorageFactory(Path.of(dataPath, "node" + nodeIdx, "log"));
        log.start();

        options.setServiceFactory(new IgniteJraftServiceFactory(log));
        options.setLogUri("test");

        return options;
    }

    /**
     * TODO asch get rid of waiting for topology IGNITE-14832
     *
     * @param cluster
     * @param addr
     * @param expected
     * @param timeout
     * @return
     */
    private boolean waitForTopology(TestCluster cluster, Endpoint addr, int expected, long timeout) {
        RaftGroupService grp = cluster.getServer(addr);

        if (grp == null) {
            LOG.warn("Node has not been found {}", addr);

            return false;
        }

        RpcServer rpcServer = grp.getRpcServer();

        if (!(rpcServer instanceof IgniteRpcServer))
            return true;

        ClusterService service = ((IgniteRpcServer) grp.getRpcServer()).clusterService();

        long stop = System.currentTimeMillis() + timeout;

        while (System.currentTimeMillis() < stop) {
            if (service.topologyService().allMembers().size() >= expected)
                return true;

            try {
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                return false;
            }
        }

        return false;
    }

    /**
     * @param cond The condition.
     * @param timeout The timeout.
     * @return {@code True} if the condition is satisfied.
     */
    private boolean waitForCondition(BooleanSupplier cond, long timeout) {
        long stop = System.currentTimeMillis() + timeout;

        while (System.currentTimeMillis() < stop) {
            if (cond.getAsBoolean())
                return true;

            try {
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                return false;
            }
        }

        return false;
    }

    /**
     * @param groupId Group id.
     * @param peerId Peer id.
     * @param nodeOptions Node options.
     * @return Raft group service.
     */
    private RaftGroupService createService(String groupId, PeerId peerId, NodeOptions nodeOptions) {
        Configuration initialConf = nodeOptions.getInitialConf();
        nodeOptions.setStripes(1);

        Stream<PeerId> peers = initialConf == null ?
            Stream.empty() :
            Stream.concat(initialConf.getPeers().stream(), initialConf.getLearners().stream());

        List<NetworkAddress> addressList = peers
            .map(PeerId::getEndpoint)
            .map(JRaftUtils::addressFromEndpoint)
            .collect(toList());

        var nodeManager = new NodeManager();

        ClusterService clusterService = ClusterServiceTestUtils.clusterService(
                testInfo,
                peerId.getEndpoint().getPort(),
                new StaticNodeFinder(addressList)
        );

        ExecutorService requestExecutor = JRaftUtils.createRequestExecutor(nodeOptions);

        executors.add(requestExecutor);

        IgniteRpcServer rpcServer = new TestIgniteRpcServer(clusterService, nodeManager, nodeOptions, requestExecutor);

        nodeOptions.setRpcClient(new IgniteRpcClient(clusterService));

        clusterService.start();

        var service = new RaftGroupService(groupId, peerId, nodeOptions, rpcServer, nodeManager) {
            @Override public synchronized void shutdown() {
                rpcServer.shutdown();

                super.shutdown();

                clusterService.stop();
            }
        };

        services.add(service);

        return service;
    }

    private void sendTestTaskAndWait(Node node) throws InterruptedException {
        this.sendTestTaskAndWait(node, 0, 10, RaftError.SUCCESS);
    }

    private void sendTestTaskAndWait(Node node, int amount) throws InterruptedException {
        this.sendTestTaskAndWait(node, 0, amount, RaftError.SUCCESS);
    }

    private void sendTestTaskAndWait(Node node, RaftError err) throws InterruptedException {
        this.sendTestTaskAndWait(node, 0, 10, err);
    }

    // Note that waiting for the latch when tasks are applying doesn't guarantee that FSMCallerImpl.lastAppliedIndex
    // will be updated immediately.
    private void sendTestTaskAndWait(Node node, int start, int amount,
                                     RaftError err) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(amount);
        for (int i = start; i < start + amount; i++) {
            ByteBuffer data = ByteBuffer.wrap(("hello" + i).getBytes(UTF_8));
            Task task = new Task(data, new ExpectClosure(err, latch));
            node.apply(task);
        }
        waitLatch(latch);
    }

    private void sendTestTaskAndWait(Node node, int start,
                                     RaftError err) throws InterruptedException {
        sendTestTaskAndWait(node, start, 10, err);
    }

    @SuppressWarnings("SameParameterValue")
    private void sendTestTaskAndWait(String prefix, Node node, int code) throws InterruptedException {
        sendTestTaskAndWait(prefix, node, 10, code);
    }

    @SuppressWarnings("SameParameterValue")
    private void sendTestTaskAndWait(String prefix, Node node, int amount,
                                     int code) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(10);
        for (int i = 0; i < amount; i++) {
            ByteBuffer data = ByteBuffer.wrap((prefix + i).getBytes(UTF_8));
            Task task = new Task(data, new ExpectClosure(code, null, latch));
            node.apply(task);
        }
        waitLatch(latch);
    }

    private void triggerLeaderSnapshot(TestCluster cluster, Node leader) throws InterruptedException {
        triggerLeaderSnapshot(cluster, leader, 1);
    }

    private void triggerLeaderSnapshot(TestCluster cluster, Node leader, int times)
        throws InterruptedException {
        // trigger leader snapshot
        // first snapshot will be triggered randomly
        int snapshotTimes = cluster.getLeaderFsm().getSaveSnapshotTimes();
        assertTrue(snapshotTimes == times - 1 || snapshotTimes == times, "snapshotTimes=" + snapshotTimes + ", times=" + times);
        CountDownLatch latch = new CountDownLatch(1);
        leader.snapshot(new ExpectClosure(latch));
        waitLatch(latch);
        assertEquals(snapshotTimes + 1, cluster.getLeaderFsm().getSaveSnapshotTimes());
    }

    private void waitLatch(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(30, TimeUnit.SECONDS));
    }

    @SuppressWarnings({"unused", "SameParameterValue"})
    private boolean assertReadIndex(Node node, int index) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        byte[] requestContext = TestUtils.getRandomBytes();
        AtomicBoolean success = new AtomicBoolean(false);
        node.readIndex(requestContext, new ReadIndexClosure() {

            @Override
            public void run(Status status, long theIndex, byte[] reqCtx) {
                if (status.isOk()) {
                    assertEquals(index, theIndex);
                    assertArrayEquals(requestContext, reqCtx);
                    success.set(true);
                }
                else {
                    assertTrue(status.getErrorMsg().contains("RPC exception:Check connection["), status.getErrorMsg());
                    assertTrue(status.getErrorMsg().contains("] fail and try to create new one"), status.getErrorMsg());
                }
                latch.countDown();
            }
        });
        latch.await();
        return success.get();
    }

    private void blockMessagesOnFollowers(List<Node> followers, BiPredicate<Object, String> blockingPredicate) {
        for (Node follower : followers) {
            RpcClientEx rpcClientEx = sender(follower);
            rpcClientEx.blockMessages(blockingPredicate);
        }
    }

    private void stopBlockingMessagesOnFollowers(List<Node> followers) {
        for (Node follower : followers) {
            RpcClientEx rpcClientEx = sender(follower);
            rpcClientEx.stopBlock();
        }
    }
}
