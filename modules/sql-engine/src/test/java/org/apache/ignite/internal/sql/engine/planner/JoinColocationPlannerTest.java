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

package org.apache.ignite.internal.sql.engine.planner;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.ignite.internal.sql.engine.rel.IgniteExchange;
import org.apache.ignite.internal.sql.engine.rel.IgniteIndexScan;
import org.apache.ignite.internal.sql.engine.rel.IgniteMergeJoin;
import org.apache.ignite.internal.sql.engine.rel.IgniteRel;
import org.apache.ignite.internal.sql.engine.schema.IgniteIndex;
import org.apache.ignite.internal.sql.engine.schema.IgniteSchema;
import org.apache.ignite.internal.sql.engine.trait.IgniteDistributions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Test suite to verify join colocation.
 */
@Disabled("https://issues.apache.org/jira/browse/IGNITE-17748")
public class JoinColocationPlannerTest extends AbstractPlannerTest {
    /**
     * Join of the same tables with a simple affinity is expected to be colocated.
     */
    @Test
    public void joinSameTableSimpleAff() throws Exception {
        TestTable tbl = createTable(
                "TEST_TBL",
                IgniteDistributions.affinity(0, "default", "hash"),
                "ID", Integer.class,
                "VAL", String.class
        );

        tbl.addIndex(new IgniteIndex(TestSortedIndex.create(RelCollations.of(0), "PK", tbl)));

        IgniteSchema schema = createSchema(tbl);

        String sql = "select count(*) "
                + "from TEST_TBL t1 "
                + "join TEST_TBL t2 on t1.id = t2.id";

        RelNode phys = physicalPlan(sql, schema, "NestedLoopJoinConverter", "CorrelatedNestedLoopJoin");

        IgniteMergeJoin join = findFirstNode(phys, byClass(IgniteMergeJoin.class));

        String invalidPlanMsg = "Invalid plan:\n" + RelOptUtil.toString(phys);

        assertThat(invalidPlanMsg, join, notNullValue());
        assertThat(invalidPlanMsg, join.distribution().function().affinity(), is(true));
        assertThat(invalidPlanMsg, join.getLeft(), instanceOf(IgniteIndexScan.class));
        assertThat(invalidPlanMsg, join.getRight(), instanceOf(IgniteIndexScan.class));
    }

    /**
     * Join of the same tables with a complex affinity is expected to be colocated.
     */
    @Test
    public void joinSameTableComplexAff() throws Exception {
        TestTable tbl = createTable(
                "TEST_TBL",
                IgniteDistributions.affinity(ImmutableIntList.of(0, 1), ThreadLocalRandom.current().nextInt(), "hash"),
                "ID1", Integer.class,
                "ID2", Integer.class,
                "VAL", String.class
        );

        tbl.addIndex(new IgniteIndex(TestSortedIndex.create(RelCollations.of(ImmutableIntList.of(0, 1)), "PK", tbl)));

        IgniteSchema schema = createSchema(tbl);

        String sql = "select count(*) "
                + "from TEST_TBL t1 "
                + "join TEST_TBL t2 on t1.id1 = t2.id1 and t1.id2 = t2.id2";

        RelNode phys = physicalPlan(sql, schema, "NestedLoopJoinConverter", "CorrelatedNestedLoopJoin");

        IgniteMergeJoin join = findFirstNode(phys, byClass(IgniteMergeJoin.class));

        String invalidPlanMsg = "Invalid plan:\n" + RelOptUtil.toString(phys);

        assertThat(invalidPlanMsg, join, notNullValue());
        assertThat(invalidPlanMsg, join.distribution().function().affinity(), is(true));
        assertThat(invalidPlanMsg, join.getLeft(), instanceOf(IgniteIndexScan.class));
        assertThat(invalidPlanMsg, join.getRight(), instanceOf(IgniteIndexScan.class));
    }

    /**
     * Re-hashing based on simple affinity is possible, so bigger table with complex affinity should be sended to the smaller one.
     */
    @Test
    public void joinComplexToSimpleAff() throws Exception {
        TestTable complexTbl = createTable(
                "COMPLEX_TBL",
                2 * DEFAULT_TBL_SIZE,
                IgniteDistributions.affinity(ImmutableIntList.of(0, 1), ThreadLocalRandom.current().nextInt(), "hash"),
                "ID1", Integer.class,
                "ID2", Integer.class,
                "VAL", String.class
        );

        complexTbl.addIndex(new IgniteIndex(TestSortedIndex.create(RelCollations.of(ImmutableIntList.of(0, 1)), "PK", complexTbl)));

        TestTable simpleTbl = createTable(
                "SIMPLE_TBL",
                DEFAULT_TBL_SIZE,
                IgniteDistributions.affinity(0, "default", "hash"),
                "ID", Integer.class,
                "VAL", String.class
        );

        simpleTbl.addIndex(new IgniteIndex(TestSortedIndex.create(RelCollations.of(0), "PK", simpleTbl)));

        IgniteSchema schema = createSchema(complexTbl, simpleTbl);

        String sql = "select count(*) "
                + "from COMPLEX_TBL t1 "
                + "join SIMPLE_TBL t2 on t1.id1 = t2.id";

        RelNode phys = physicalPlan(sql, schema, "NestedLoopJoinConverter", "CorrelatedNestedLoopJoin");

        IgniteMergeJoin join = findFirstNode(phys, byClass(IgniteMergeJoin.class));

        String invalidPlanMsg = "Invalid plan:\n" + RelOptUtil.toString(phys);

        assertThat(invalidPlanMsg, join, notNullValue());
        assertThat(invalidPlanMsg, join.distribution().function().affinity(), is(true));

        List<IgniteExchange> exchanges = findNodes(phys, node -> node instanceof IgniteExchange
                && ((IgniteRel) node).distribution().function().affinity());

        assertThat(invalidPlanMsg, exchanges, hasSize(1));
        assertThat(invalidPlanMsg, exchanges.get(0).getInput(0), instanceOf(IgniteIndexScan.class));
        assertThat(invalidPlanMsg, exchanges.get(0).getInput(0)
                .getTable().unwrap(TestTable.class), equalTo(complexTbl));
    }

    /**
     * Re-hashing for complex affinity is not supported.
     */
    @Test
    public void joinComplexToComplexAffWithDifferentOrder() throws Exception {
        TestTable complexTblDirect = createTable(
                "COMPLEX_TBL_DIRECT",
                IgniteDistributions.affinity(ImmutableIntList.of(0, 1), ThreadLocalRandom.current().nextInt(), "hash"),
                "ID1", Integer.class,
                "ID2", Integer.class,
                "VAL", String.class
        );

        complexTblDirect.addIndex(new IgniteIndex(
                TestSortedIndex.create(RelCollations.of(ImmutableIntList.of(0, 1)), "PK", complexTblDirect)));

        TestTable complexTblIndirect = createTable(
                "COMPLEX_TBL_INDIRECT",
                IgniteDistributions.affinity(ImmutableIntList.of(1, 0), ThreadLocalRandom.current().nextInt(), "hash"),
                "ID1", Integer.class,
                "ID2", Integer.class,
                "VAL", String.class
        );

        complexTblIndirect.addIndex(new IgniteIndex(
                TestSortedIndex.create(RelCollations.of(ImmutableIntList.of(0, 1)), "PK", complexTblIndirect)));

        IgniteSchema schema = createSchema(complexTblDirect, complexTblIndirect);

        String sql = "select count(*) "
                + "from COMPLEX_TBL_DIRECT t1 "
                + "join COMPLEX_TBL_INDIRECT t2 on t1.id1 = t2.id1 and t1.id2 = t2.id2";

        RelNode phys = physicalPlan(sql, schema, "NestedLoopJoinConverter", "CorrelatedNestedLoopJoin");

        IgniteMergeJoin exchange = findFirstNode(phys, node -> node instanceof IgniteExchange
                && ((IgniteRel) node).distribution().function().affinity());

        String invalidPlanMsg = "Invalid plan:\n" + RelOptUtil.toString(phys);

        assertThat(invalidPlanMsg, exchange, nullValue());
    }
}
