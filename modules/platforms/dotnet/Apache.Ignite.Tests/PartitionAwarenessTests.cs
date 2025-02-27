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

namespace Apache.Ignite.Tests;

using System;
using System.Linq;
using System.Threading.Tasks;
using Ignite.Table;
using Internal.Proto;
using NUnit.Framework;

/// <summary>
/// Tests partition awareness.
/// <para />
/// TODO IGNITE-17969:
/// * testCustomColocationKey
/// * testCompositeKey.
/// </summary>
public class PartitionAwarenessTests
{
    private static readonly object[] KeyNodeCases =
    {
        new object[] { 3, 1 },
        new object[] { 5, 1 },
        new object[] { 8, 1 },
        new object[] { 1, 2 },
        new object[] { 4, 2 },
        new object[] { 0, 2 },
        new object[] { int.MaxValue, 2 },
        new object[] { int.MaxValue - 1, 1 },
        new object[] { int.MinValue, 2 }
    };

    private FakeServer _server1 = null!;
    private FakeServer _server2 = null!;

    [SetUp]
    public void SetUp()
    {
        _server1 = new FakeServer(nodeName: "srv1");
        _server2 = new FakeServer(nodeName: "srv2");

        var assignment = new[] { _server1.Node.Id, _server2.Node.Id };
        _server1.PartitionAssignment = assignment;
        _server2.PartitionAssignment = assignment;
    }

    [TearDown]
    public void TearDown()
    {
        _server1.Dispose();
        _server2.Dispose();
    }

    [Test]
    public async Task TestPutRoutesRequestToPrimaryNode()
    {
        using var client = await GetClient();
        var recordView = (await client.Tables.GetTableAsync(FakeServer.ExistingTableName))!.GetRecordView<int>();
        var (defaultServer, _) = GetServerPair();

        // Warm up.
        await recordView.UpsertAsync(null, 1);

        Assert.AreEqual(
            new[] { ClientOp.TableGet, ClientOp.SchemasGet, ClientOp.PartitionAssignmentGet },
            defaultServer.ClientOps.Take(3));

        // Check.
        await AssertOpOnNode(async () => await recordView.UpsertAsync(null, 1), ClientOp.TupleUpsert, _server2, _server1);
        await AssertOpOnNode(async () => await recordView.UpsertAsync(null, 3), ClientOp.TupleUpsert, _server1, _server2);
        await AssertOpOnNode(async () => await recordView.UpsertAsync(null, 4), ClientOp.TupleUpsert, _server2, _server1);
        await AssertOpOnNode(async () => await recordView.UpsertAsync(null, 5), ClientOp.TupleUpsert, _server1, _server2);
    }

    [Test]
    public async Task TestPutWithTxUsesDefaultNode()
    {
        using var client = await GetClient();
        var recordView = (await client.Tables.GetTableAsync(FakeServer.ExistingTableName))!.GetRecordView<int>();
        var tx = await client.Transactions.BeginAsync();
        var (defaultServer, secondaryServer) = GetServerPair();

        // Second server.
        await recordView.UpsertAsync(tx, 1);
        await recordView.UpsertAsync(tx, 3);

        Assert.AreEqual(
            new[] { ClientOp.TableGet, ClientOp.TxBegin, ClientOp.SchemasGet, ClientOp.TupleUpsert, ClientOp.TupleUpsert },
            defaultServer.ClientOps);

        CollectionAssert.IsEmpty(secondaryServer.ClientOps);
    }

    [Test]
    public async Task TestClientReceivesPartitionAssignmentUpdates()
    {
        using var client = await GetClient();
        var recordView = (await client.Tables.GetTableAsync(FakeServer.ExistingTableName))!.GetRecordView<int>();
        var (defaultServer, _) = GetServerPair();

        // Check default assignment.
        await recordView.UpsertAsync(null, 1);
        await AssertOpOnNode(() => recordView.UpsertAsync(null, 1), ClientOp.TupleUpsert, _server2);

        // Update assignment.
        foreach (var server in new[] { _server1, _server2 })
        {
            server.ClearOps();
            server.PartitionAssignment = server.PartitionAssignment.Reverse().ToArray();
            server.PartitionAssignmentChanged = true;
        }

        // First request on default node receives update flag.
        await AssertOpOnNode(() => client.Tables.GetTablesAsync(), ClientOp.TablesGet, defaultServer);

        // Second request loads and uses new assignment.
        await AssertOpOnNode(() => recordView.UpsertAsync(null, 1), ClientOp.TupleUpsert, _server1, allowExtraOps: true);
    }

    [Test]
    [TestCaseSource(nameof(KeyNodeCases))]
    public async Task TestAllRecordBinaryViewOperations(int keyId, int node)
    {
        using var client = await GetClient();
        var recordView = (await client.Tables.GetTableAsync(FakeServer.ExistingTableName))!.RecordBinaryView;

        // Warm up (retrieve assignment).
        var key = new IgniteTuple { ["ID"] = keyId };
        await recordView.UpsertAsync(null, key);

        // Single-key operations.
        var expectedNode = node == 1 ? _server1 : _server2;

        await AssertOpOnNode(() => recordView.GetAsync(null, key), ClientOp.TupleGet, expectedNode);
        await AssertOpOnNode(() => recordView.GetAndDeleteAsync(null, key), ClientOp.TupleGetAndDelete, expectedNode);
        await AssertOpOnNode(() => recordView.GetAndReplaceAsync(null, key), ClientOp.TupleGetAndReplace, expectedNode);
        await AssertOpOnNode(() => recordView.GetAndUpsertAsync(null, key), ClientOp.TupleGetAndUpsert, expectedNode);
        await AssertOpOnNode(() => recordView.UpsertAsync(null, key), ClientOp.TupleUpsert, expectedNode);
        await AssertOpOnNode(() => recordView.InsertAsync(null, key), ClientOp.TupleInsert, expectedNode);
        await AssertOpOnNode(() => recordView.ReplaceAsync(null, key), ClientOp.TupleReplace, expectedNode);
        await AssertOpOnNode(() => recordView.ReplaceAsync(null, key, key), ClientOp.TupleReplaceExact, expectedNode);
        await AssertOpOnNode(() => recordView.DeleteAsync(null, key), ClientOp.TupleDelete, expectedNode);
        await AssertOpOnNode(() => recordView.DeleteExactAsync(null, key), ClientOp.TupleDeleteExact, expectedNode);

        // Multi-key operations use the first key for colocation.
        var keys = new[] { key, new IgniteTuple { ["ID"] = keyId - 1 }, new IgniteTuple { ["ID"] = keyId + 1 } };
        await AssertOpOnNode(() => recordView.GetAllAsync(null, keys), ClientOp.TupleGetAll, expectedNode);
        await AssertOpOnNode(() => recordView.InsertAllAsync(null, keys), ClientOp.TupleInsertAll, expectedNode);
        await AssertOpOnNode(() => recordView.UpsertAllAsync(null, keys), ClientOp.TupleUpsertAll, expectedNode);
        await AssertOpOnNode(() => recordView.DeleteAllAsync(null, keys), ClientOp.TupleDeleteAll, expectedNode);
        await AssertOpOnNode(() => recordView.DeleteAllExactAsync(null, keys), ClientOp.TupleDeleteAllExact, expectedNode);
    }

    [Test]
    [TestCaseSource(nameof(KeyNodeCases))]
    public async Task TestAllRecordViewOperations(int key, int node)
    {
        using var client = await GetClient();
        var recordView = (await client.Tables.GetTableAsync(FakeServer.ExistingTableName))!.GetRecordView<int>();

        // Warm up (retrieve assignment).
        await recordView.UpsertAsync(null, 1);

        // Single-key operations.
        var expectedNode = node == 1 ? _server1 : _server2;

        await AssertOpOnNode(() => recordView.GetAsync(null, key), ClientOp.TupleGet, expectedNode);
        await AssertOpOnNode(() => recordView.GetAndDeleteAsync(null, key), ClientOp.TupleGetAndDelete, expectedNode);
        await AssertOpOnNode(() => recordView.GetAndReplaceAsync(null, key), ClientOp.TupleGetAndReplace, expectedNode);
        await AssertOpOnNode(() => recordView.GetAndUpsertAsync(null, key), ClientOp.TupleGetAndUpsert, expectedNode);
        await AssertOpOnNode(() => recordView.UpsertAsync(null, key), ClientOp.TupleUpsert, expectedNode);
        await AssertOpOnNode(() => recordView.InsertAsync(null, key), ClientOp.TupleInsert, expectedNode);
        await AssertOpOnNode(() => recordView.ReplaceAsync(null, key), ClientOp.TupleReplace, expectedNode);
        await AssertOpOnNode(() => recordView.ReplaceAsync(null, key, key), ClientOp.TupleReplaceExact, expectedNode);
        await AssertOpOnNode(() => recordView.DeleteAsync(null, key), ClientOp.TupleDelete, expectedNode);
        await AssertOpOnNode(() => recordView.DeleteExactAsync(null, key), ClientOp.TupleDeleteExact, expectedNode);

        // Multi-key operations use the first key for colocation.
        var keys = new[] { key, key - 1, key + 1 };
        await AssertOpOnNode(() => recordView.GetAllAsync(null, keys), ClientOp.TupleGetAll, expectedNode);
        await AssertOpOnNode(() => recordView.InsertAllAsync(null, keys), ClientOp.TupleInsertAll, expectedNode);
        await AssertOpOnNode(() => recordView.UpsertAllAsync(null, keys), ClientOp.TupleUpsertAll, expectedNode);
        await AssertOpOnNode(() => recordView.DeleteAllAsync(null, keys), ClientOp.TupleDeleteAll, expectedNode);
        await AssertOpOnNode(() => recordView.DeleteAllExactAsync(null, keys), ClientOp.TupleDeleteAllExact, expectedNode);
    }

    [Test]
    [TestCaseSource(nameof(KeyNodeCases))]
    public async Task TestAllKeyValueBinaryViewOperations(int keyId, int node)
    {
        using var client = await GetClient();
        var kvView = (await client.Tables.GetTableAsync(FakeServer.ExistingTableName))!.KeyValueBinaryView;

        // Warm up (retrieve assignment).
        var key = new IgniteTuple { ["ID"] = keyId };
        var val = new IgniteTuple { ["VAL"] = 0 };
        await kvView.PutAsync(null, key, val);

        // Single-key operations.
        var expectedNode = node == 1 ? _server1 : _server2;

        await AssertOpOnNode(() => kvView.GetAsync(null, key), ClientOp.TupleGet, expectedNode);
        await AssertOpOnNode(() => kvView.GetAndRemoveAsync(null, key), ClientOp.TupleGetAndDelete, expectedNode);
        await AssertOpOnNode(() => kvView.GetAndReplaceAsync(null, key, val), ClientOp.TupleGetAndReplace, expectedNode);
        await AssertOpOnNode(() => kvView.GetAndPutAsync(null, key, val), ClientOp.TupleGetAndUpsert, expectedNode);
        await AssertOpOnNode(() => kvView.PutAsync(null, key, val), ClientOp.TupleUpsert, expectedNode);
        await AssertOpOnNode(() => kvView.PutIfAbsentAsync(null, key, val), ClientOp.TupleInsert, expectedNode);
        await AssertOpOnNode(() => kvView.ReplaceAsync(null, key, val), ClientOp.TupleReplace, expectedNode);
        await AssertOpOnNode(() => kvView.ReplaceAsync(null, key, val, val), ClientOp.TupleReplaceExact, expectedNode);
        await AssertOpOnNode(() => kvView.RemoveAsync(null, key), ClientOp.TupleDelete, expectedNode);
        await AssertOpOnNode(() => kvView.RemoveAsync(null, key, val), ClientOp.TupleDeleteExact, expectedNode);
        await AssertOpOnNode(() => kvView.ContainsAsync(null, key), ClientOp.TupleContainsKey, expectedNode);

        // Multi-key operations use the first key for colocation.
        var keys = new[] { key, new IgniteTuple { ["ID"] = keyId - 1 }, new IgniteTuple { ["ID"] = keyId + 1 } };
        var pairs = keys.ToDictionary(x => (IIgniteTuple)x, _ => (IIgniteTuple)val);

        await AssertOpOnNode(() => kvView.GetAllAsync(null, keys), ClientOp.TupleGetAll, expectedNode);
        await AssertOpOnNode(() => kvView.PutAllAsync(null, pairs), ClientOp.TupleUpsertAll, expectedNode);
        await AssertOpOnNode(() => kvView.RemoveAllAsync(null, keys), ClientOp.TupleDeleteAll, expectedNode);
        await AssertOpOnNode(() => kvView.RemoveAllAsync(null, pairs), ClientOp.TupleDeleteAllExact, expectedNode);
    }

    [Test]
    [TestCaseSource(nameof(KeyNodeCases))]
    public async Task TestAllKeyValueViewOperations(int key, int node)
    {
        using var client = await GetClient();
        var kvView = (await client.Tables.GetTableAsync(FakeServer.ExistingTableName))!.GetKeyValueView<int, int>();

        // Warm up (retrieve assignment).
        var val = 0;
        await kvView.PutAsync(null, 1, val);

        // Single-key operations.
        var expectedNode = node == 1 ? _server1 : _server2;

        await AssertOpOnNode(() => kvView.GetAsync(null, key), ClientOp.TupleGet, expectedNode);
        await AssertOpOnNode(() => kvView.GetAndRemoveAsync(null, key), ClientOp.TupleGetAndDelete, expectedNode);
        await AssertOpOnNode(() => kvView.GetAndReplaceAsync(null, key, val), ClientOp.TupleGetAndReplace, expectedNode);
        await AssertOpOnNode(() => kvView.GetAndPutAsync(null, key, val), ClientOp.TupleGetAndUpsert, expectedNode);
        await AssertOpOnNode(() => kvView.PutAsync(null, key, val), ClientOp.TupleUpsert, expectedNode);
        await AssertOpOnNode(() => kvView.PutIfAbsentAsync(null, key, val), ClientOp.TupleInsert, expectedNode);
        await AssertOpOnNode(() => kvView.ReplaceAsync(null, key, val), ClientOp.TupleReplace, expectedNode);
        await AssertOpOnNode(() => kvView.ReplaceAsync(null, key, val, val), ClientOp.TupleReplaceExact, expectedNode);
        await AssertOpOnNode(() => kvView.RemoveAsync(null, key), ClientOp.TupleDelete, expectedNode);
        await AssertOpOnNode(() => kvView.RemoveAsync(null, key, val), ClientOp.TupleDeleteExact, expectedNode);
        await AssertOpOnNode(() => kvView.ContainsAsync(null, key), ClientOp.TupleContainsKey, expectedNode);

        // Multi-key operations use the first key for colocation.
        var keys = new[] { key, key - 1, key + 1 };
        var pairs = keys.ToDictionary(x => x, _ => val);
        await AssertOpOnNode(() => kvView.GetAllAsync(null, keys), ClientOp.TupleGetAll, expectedNode);
        await AssertOpOnNode(() => kvView.PutAllAsync(null, pairs), ClientOp.TupleUpsertAll, expectedNode);
        await AssertOpOnNode(() => kvView.RemoveAllAsync(null, keys), ClientOp.TupleDeleteAll, expectedNode);
        await AssertOpOnNode(() => kvView.RemoveAllAsync(null, pairs), ClientOp.TupleDeleteAllExact, expectedNode);
    }

    private static async Task AssertOpOnNode(
        Func<Task> action,
        ClientOp op,
        FakeServer node,
        FakeServer? node2 = null,
        bool allowExtraOps = false)
    {
        node.ClearOps();
        node2?.ClearOps();

        await action();

        if (allowExtraOps)
        {
            CollectionAssert.Contains(node.ClientOps, op);
        }
        else
        {
            Assert.AreEqual(new[] { op }, node.ClientOps);
        }

        if (node2 != null)
        {
            CollectionAssert.IsEmpty(node2.ClientOps);
        }
    }

    private async Task<IIgniteClient> GetClient()
    {
        var cfg = new IgniteClientConfiguration
        {
            Endpoints =
            {
                "127.0.0.1: " + _server1.Port,
                "127.0.0.1: " + _server2.Port
            }
        };

        return await IgniteClient.StartAsync(cfg);
    }

    private (FakeServer Default, FakeServer Secondary) GetServerPair()
    {
        // Any server can be primary due to round-robin balancing in ClientFailoverSocket.
        return _server1.ClientOps.Count > 0 ? (_server1, _server2) : (_server2, _server1);
    }
}
