/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.prestosql.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.sql.planner.Symbol;
import io.prestosql.sql.planner.assertions.RowNumberSymbolMatcher;
import io.prestosql.sql.planner.iterative.rule.test.BaseRuleTest;
import io.prestosql.sql.planner.iterative.rule.test.PlanBuilder;
import io.prestosql.sql.planner.plan.Assignments;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.prestosql.sql.planner.assertions.PlanMatchPattern.expression;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.filter;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.project;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.rowNumber;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.values;

public class TestPushPredicateThroughProjectIntoRowNumber
        extends BaseRuleTest
{
    @Test
    public void testRowNumberSymbolPruned()
    {
        tester().assertThat(new PushPredicateThroughProjectIntoRowNumber(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol rowNumber = p.symbol("row_number");
                    return p.filter(
                            PlanBuilder.expression("a = 1"),
                            p.project(
                                    Assignments.identity(a),
                                    p.rowNumber(
                                            ImmutableList.of(),
                                            Optional.empty(),
                                            rowNumber,
                                            p.values(a))));
                })
                .doesNotFire();
    }

    @Test
    public void testNoUpperBoundForRowNumberSymbol()
    {
        tester().assertThat(new PushPredicateThroughProjectIntoRowNumber(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol rowNumber = p.symbol("row_number");
                    return p.filter(
                            PlanBuilder.expression("a = 1"),
                            p.project(
                                    Assignments.identity(a, rowNumber),
                                    p.rowNumber(
                                            ImmutableList.of(),
                                            Optional.empty(),
                                            rowNumber,
                                            p.values(a))));
                })
                .doesNotFire();
    }

    @Test
    public void testNonPositiveUpperBoundForRowNumberSymbol()
    {
        tester().assertThat(new PushPredicateThroughProjectIntoRowNumber(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol rowNumber = p.symbol("row_number");
                    return p.filter(
                            PlanBuilder.expression("a = 1 AND row_number < -10"),
                            p.project(
                                    Assignments.identity(a, rowNumber),
                                    p.rowNumber(
                                            ImmutableList.of(),
                                            Optional.empty(),
                                            rowNumber,
                                            p.values(a))));
                })
                .matches(values("a", "row_number"));
    }

    @Test
    public void testPredicateNotSatisfied()
    {
        tester().assertThat(new PushPredicateThroughProjectIntoRowNumber(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol rowNumber = p.symbol("row_number");
                    return p.filter(
                            PlanBuilder.expression("row_number > 2 AND row_number < 5"),
                            p.project(
                                    Assignments.identity(rowNumber),
                                    p.rowNumber(
                                            ImmutableList.of(),
                                            Optional.empty(),
                                            rowNumber,
                                            p.values(a))));
                })
                .matches(filter(
                        "row_number > 2 AND row_number < 5",
                        project(
                                ImmutableMap.of("row_number", expression("row_number")),
                                rowNumber(
                                        pattern -> pattern
                                                .maxRowCountPerPartition(Optional.of(4)),
                                        values(ImmutableList.of("a")))
                                        .withAlias("row_number", new RowNumberSymbolMatcher()))));
    }

    @Test
    public void testPredicateNotSatisfiedAndMaxRowCountNotUpdated()
    {
        tester().assertThat(new PushPredicateThroughProjectIntoRowNumber(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol rowNumber = p.symbol("row_number");
                    return p.filter(
                            PlanBuilder.expression("row_number > 2 AND row_number < 5"),
                            p.project(
                                    Assignments.identity(rowNumber),
                                    p.rowNumber(
                                            ImmutableList.of(),
                                            Optional.of(3),
                                            rowNumber,
                                            p.values(a))));
                })
                .doesNotFire();
    }

    @Test
    public void testPredicateSatisfied()
    {
        tester().assertThat(new PushPredicateThroughProjectIntoRowNumber(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol rowNumber = p.symbol("row_number");
                    return p.filter(
                            PlanBuilder.expression("row_number < 5"),
                            p.project(
                                    Assignments.identity(rowNumber),
                                    p.rowNumber(
                                            ImmutableList.of(),
                                            Optional.of(3),
                                            rowNumber,
                                            p.values(a))));
                })
                .matches(project(
                        ImmutableMap.of("row_number", expression("row_number")),
                        rowNumber(
                                pattern -> pattern
                                        .maxRowCountPerPartition(Optional.of(3)),
                                values(ImmutableList.of("a")))
                                .withAlias("row_number", new RowNumberSymbolMatcher())));

        tester().assertThat(new PushPredicateThroughProjectIntoRowNumber(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol rowNumber = p.symbol("row_number");
                    return p.filter(
                            PlanBuilder.expression("row_number < 3"),
                            p.project(
                                    Assignments.identity(rowNumber),
                                    p.rowNumber(
                                            ImmutableList.of(),
                                            Optional.of(5),
                                            rowNumber,
                                            p.values(a))));
                })
                .matches(project(
                        ImmutableMap.of("row_number", expression("row_number")),
                        rowNumber(
                                pattern -> pattern
                                        .maxRowCountPerPartition(Optional.of(2)),
                                values(ImmutableList.of("a")))
                                .withAlias("row_number", new RowNumberSymbolMatcher())));
    }

    @Test
    public void testPredicatePartiallySatisfied()
    {
        tester().assertThat(new PushPredicateThroughProjectIntoRowNumber(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol rowNumber = p.symbol("row_number");
                    return p.filter(
                            PlanBuilder.expression("row_number < 5 AND a > 0"),
                            p.project(
                                    Assignments.identity(rowNumber, a),
                                    p.rowNumber(
                                            ImmutableList.of(),
                                            Optional.of(3),
                                            rowNumber,
                                            p.values(a))));
                })
                .matches(filter(
                        "a > 0",
                        project(
                                ImmutableMap.of("row_number", expression("row_number"), "a", expression("a")),
                                rowNumber(
                                        pattern -> pattern
                                                .maxRowCountPerPartition(Optional.of(3)),
                                        values(ImmutableList.of("a")))
                                        .withAlias("row_number", new RowNumberSymbolMatcher()))));

        tester().assertThat(new PushPredicateThroughProjectIntoRowNumber(tester().getMetadata()))
                .on(p -> {
                    Symbol a = p.symbol("a");
                    Symbol rowNumber = p.symbol("row_number");
                    return p.filter(
                            PlanBuilder.expression("row_number < 5 AND row_number % 2 = 0"),
                            p.project(
                                    Assignments.identity(rowNumber),
                                    p.rowNumber(
                                            ImmutableList.of(),
                                            Optional.of(3),
                                            rowNumber,
                                            p.values(a))));
                })
                .matches(filter(
                        "row_number % 2 = 0",
                        project(
                                ImmutableMap.of("row_number", expression("row_number")),
                                rowNumber(
                                        pattern -> pattern
                                                .maxRowCountPerPartition(Optional.of(3)),
                                        values(ImmutableList.of("a")))
                                        .withAlias("row_number", new RowNumberSymbolMatcher()))));
    }
}
