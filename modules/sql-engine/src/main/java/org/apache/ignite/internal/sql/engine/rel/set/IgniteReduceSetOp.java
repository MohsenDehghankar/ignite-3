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

package org.apache.ignite.internal.sql.engine.rel.set;

import java.util.List;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.util.Pair;
import org.apache.ignite.internal.sql.engine.exec.exp.agg.AggregateType;
import org.apache.ignite.internal.sql.engine.trait.IgniteDistributions;
import org.apache.ignite.internal.sql.engine.trait.RewindabilityTrait;
import org.apache.ignite.internal.sql.engine.trait.TraitUtils;
import org.apache.ignite.internal.sql.engine.util.Commons;

/**
 * Physical node for REDUCE phase of set op (MINUS, INTERSECT).
 */
public interface IgniteReduceSetOp extends IgniteSetOp {
    /** {@inheritDoc} */
    @Override
    public default List<Pair<RelTraitSet, List<RelTraitSet>>> deriveRewindability(
            RelTraitSet nodeTraits,
            List<RelTraitSet> inputTraits
    ) {
        return List.of(
                Pair.of(nodeTraits.replace(RewindabilityTrait.ONE_WAY), List.of(inputTraits.get(0))));
    }

    /** {@inheritDoc} */
    @Override
    public default Pair<RelTraitSet, List<RelTraitSet>> passThroughDistribution(RelTraitSet nodeTraits,
            List<RelTraitSet> inTraits) {
        if (TraitUtils.distribution(nodeTraits) == IgniteDistributions.single()) {
            return Pair.of(nodeTraits, Commons.transform(inTraits, t -> t.replace(IgniteDistributions.single())));
        }

        return null;
    }

    /** {@inheritDoc} */
    @Override
    public default List<Pair<RelTraitSet, List<RelTraitSet>>> deriveDistribution(
            RelTraitSet nodeTraits,
            List<RelTraitSet> inputTraits
    ) {
        return List.of(Pair.of(nodeTraits.replace(IgniteDistributions.single()),
                List.of(inputTraits.get(0).replace(IgniteDistributions.single()))));
    }

    /** {@inheritDoc} */
    @Override
    public default List<Pair<RelTraitSet, List<RelTraitSet>>> deriveCorrelation(
            RelTraitSet nodeTraits,
            List<RelTraitSet> inTraits
    ) {
        return List.of(Pair.of(nodeTraits.replace(TraitUtils.correlation(inTraits.get(0))),
                inTraits));
    }

    /** {@inheritDoc} */
    @Override
    public default AggregateType aggregateType() {
        return AggregateType.REDUCE;
    }
}
