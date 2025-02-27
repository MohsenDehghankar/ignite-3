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

package org.apache.ignite.internal.sql.engine.rel;

import static org.apache.ignite.internal.sql.engine.trait.TraitUtils.changeTraits;

import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelInput;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.ignite.internal.sql.engine.prepare.bounds.SearchBounds;
import org.apache.ignite.internal.sql.engine.schema.IgniteIndex;
import org.jetbrains.annotations.Nullable;

/**
 * Relational operator that returns the contents of a table using an index.
 */
public class IgniteIndexScan extends AbstractIndexScan implements SourceAwareIgniteRel {
    private final long sourceId;

    /**
     * Constructor used for deserialization.
     *
     * @param input Serialized representation.
     */
    public IgniteIndexScan(RelInput input) {
        super(changeTraits(input, IgniteConvention.INSTANCE));

        Object srcIdObj = input.get("sourceId");
        if (srcIdObj != null) {
            sourceId = ((Number) srcIdObj).longValue();
        } else {
            sourceId = -1;
        }
    }

    /**
     * Creates a IndexScan.
     *
     * @param cluster Cluster that this relational expression belongs to
     * @param traits Traits of this relational expression
     * @param tbl Table definition.
     * @param idxName Index name.
     * @param type Type of the index.
     * @param proj Projects.
     * @param cond Filters.
     * @param searchBounds Index search conditions.
     * @param requiredCols Participating columns.
     */
    public IgniteIndexScan(
            RelOptCluster cluster,
            RelTraitSet traits,
            RelOptTable tbl,
            String idxName,
            IgniteIndex.Type type,
            @Nullable List<RexNode> proj,
            @Nullable RexNode cond,
            @Nullable List<SearchBounds> searchBounds,
            @Nullable ImmutableBitSet requiredCols
    ) {
        this(-1L, cluster, traits, tbl, idxName, type, proj, cond, searchBounds, requiredCols);
    }

    /**
     * Creates a IndexScan.
     *
     * @param cluster      Cluster that this relational expression belongs to
     * @param traits       Traits of this relational expression
     * @param tbl          Table definition.
     * @param idxName      Index name.
     * @param proj         Projects.
     * @param cond         Filters.
     * @param searchBounds Index search conditions.
     * @param requiredCols Participating columns.
     */
    private IgniteIndexScan(
            long sourceId,
            RelOptCluster cluster,
            RelTraitSet traits,
            RelOptTable tbl,
            String idxName,
            IgniteIndex.Type type,
            @Nullable List<RexNode> proj,
            @Nullable RexNode cond,
            @Nullable List<SearchBounds> searchBounds,
            @Nullable ImmutableBitSet requiredCols
    ) {
        super(cluster, traits, List.of(), tbl, idxName, type, proj, cond, searchBounds, requiredCols);

        this.sourceId = sourceId;
    }

    /** {@inheritDoc} */
    @Override
    public long sourceId() {
        return sourceId;
    }

    /** {@inheritDoc} */
    @Override
    protected RelWriter explainTerms0(RelWriter pw) {
        return super.explainTerms0(pw)
                .itemIf("sourceId", sourceId, sourceId != -1);
    }

    /** {@inheritDoc} */
    @Override
    public <T> T accept(IgniteRelVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /** {@inheritDoc} */
    @Override
    public IgniteRel clone(long sourceId) {
        return new IgniteIndexScan(sourceId, getCluster(), getTraitSet(), getTable(),
                idxName, type, projects, condition, searchBounds, requiredColumns);
    }

    /** {@inheritDoc} */
    @Override
    public IgniteRel clone(RelOptCluster cluster, List<IgniteRel> inputs) {
        return new IgniteIndexScan(sourceId, cluster, getTraitSet(), getTable(),
                idxName, type, projects, condition, searchBounds, requiredColumns);
    }
}
