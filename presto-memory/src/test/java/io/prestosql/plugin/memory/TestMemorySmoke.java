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
package io.prestosql.plugin.memory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prestosql.Session;
import io.prestosql.execution.QueryStats;
import io.prestosql.metadata.QualifiedObjectName;
import io.prestosql.operator.OperatorStats;
import io.prestosql.spi.QueryId;
import io.prestosql.sql.analyzer.FeaturesConfig;
import io.prestosql.testing.AbstractTestQueryFramework;
import io.prestosql.testing.DistributedQueryRunner;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.testing.MaterializedRow;
import io.prestosql.testing.QueryRunner;
import io.prestosql.testing.ResultWithQueryId;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Set;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.SystemSessionProperties.ENABLE_LARGE_DYNAMIC_FILTERS;
import static io.prestosql.SystemSessionProperties.JOIN_DISTRIBUTION_TYPE;
import static io.prestosql.SystemSessionProperties.JOIN_REORDERING_STRATEGY;
import static io.prestosql.sql.analyzer.FeaturesConfig.JoinDistributionType.BROADCAST;
import static io.prestosql.sql.analyzer.FeaturesConfig.JoinDistributionType.PARTITIONED;
import static io.prestosql.sql.analyzer.FeaturesConfig.JoinReorderingStrategy.NONE;
import static io.prestosql.testing.assertions.Assert.assertEquals;
import static java.lang.String.format;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestMemorySmoke
        extends AbstractTestQueryFramework
{
    private static final int LINEITEM_COUNT = 60175;
    private static final int ORDERS_COUNT = 15000;
    private static final int PART_COUNT = 2000;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return MemoryQueryRunner.createQueryRunner(
                // Reduced broadcast join limit for large DF to make withLargeDynamicFilters use range DF collection
                ImmutableMap.of("dynamic-filtering.large-broadcast.max-distinct-values-per-driver", "100"));
    }

    @Test
    public void testCreateAndDropTable()
    {
        int tablesBeforeCreate = listMemoryTables().size();
        assertUpdate("CREATE TABLE test AS SELECT * FROM tpch.tiny.nation", "SELECT count(*) FROM nation");
        assertEquals(listMemoryTables().size(), tablesBeforeCreate + 1);

        assertUpdate("DROP TABLE test");
        assertEquals(listMemoryTables().size(), tablesBeforeCreate);
    }

    // it has to be RuntimeException as FailureInfo$FailureException is private
    @Test(expectedExceptions = RuntimeException.class, expectedExceptionsMessageRegExp = "line 1:1: Destination table 'memory.default.nation' already exists")
    public void testCreateTableWhenTableIsAlreadyCreated()
    {
        @Language("SQL") String createTableSql = "CREATE TABLE nation AS SELECT * FROM tpch.tiny.nation";
        assertUpdate(createTableSql);
    }

    @Test
    public void testSelect()
    {
        assertUpdate("CREATE TABLE test_select AS SELECT * FROM tpch.tiny.nation", "SELECT count(*) FROM nation");

        assertQuery("SELECT * FROM test_select ORDER BY nationkey", "SELECT * FROM nation ORDER BY nationkey");

        assertQueryResult("INSERT INTO test_select SELECT * FROM tpch.tiny.nation", 25L);

        assertQueryResult("INSERT INTO test_select SELECT * FROM tpch.tiny.nation", 25L);

        assertQueryResult("SELECT count(*) FROM test_select", 75L);
    }

    @Test
    public void testJoinDynamicFilteringNone()
    {
        // Probe-side is not scanned at all, due to dynamic filtering:
        assertDynamicFiltering(
                "SELECT * FROM lineitem JOIN orders ON lineitem.orderkey = orders.orderkey AND orders.totalprice < 0",
                withBroadcastJoin(),
                0,
                ImmutableSet.of(0, ORDERS_COUNT));
    }

    @Test
    public void testJoinLargeBuildSideDynamicFiltering()
    {
        @Language("SQL") String sql = "SELECT * FROM lineitem JOIN orders ON lineitem.orderkey = orders.orderkey and orders.custkey BETWEEN 300 AND 700";
        int expectedRowCount = 15793;
        // Probe-side is fully scanned because the build-side is too large for dynamic filtering:
        assertDynamicFiltering(
                sql,
                withBroadcastJoin(),
                expectedRowCount,
                ImmutableSet.of(LINEITEM_COUNT, ORDERS_COUNT));
        // Probe-side is partially scanned because we extract min/max from large build-side for dynamic filtering
        assertDynamicFiltering(
                sql,
                withLargeDynamicFilters(),
                expectedRowCount,
                ImmutableSet.of(60139, ORDERS_COUNT));
    }

    @Test
    public void testPartitionedJoinNoDynamicFiltering()
    {
        // Probe-side is fully scanned, because local dynamic filtering does not work for partitioned joins:
        assertDynamicFiltering(
                "SELECT * FROM lineitem JOIN orders ON lineitem.orderkey = orders.orderkey AND orders.totalprice < 0",
                withPartitionedJoin(),
                0,
                ImmutableSet.of(LINEITEM_COUNT, ORDERS_COUNT));
    }

    @Test
    public void testJoinDynamicFilteringSingleValue()
    {
        assertQueryResult("SELECT orderkey FROM orders WHERE comment = 'nstructions sleep furiously among '", 1L);
        assertQueryResult("SELECT COUNT() FROM lineitem WHERE orderkey = 1", 6L);

        assertQueryResult("SELECT partkey FROM part WHERE comment = 'onic deposits'", 1552L);
        assertQueryResult("SELECT COUNT() FROM lineitem WHERE partkey = 1552", 39L);

        // Join lineitem with a single row of orders
        assertDynamicFiltering(
                "SELECT * FROM lineitem JOIN orders ON lineitem.orderkey = orders.orderkey AND orders.comment = 'nstructions sleep furiously among '",
                withBroadcastJoin(),
                6,
                ImmutableSet.of(6, ORDERS_COUNT));

        // Join lineitem with a single row of part
        assertDynamicFiltering(
                "SELECT l.comment FROM  lineitem l, part p WHERE p.partkey = l.partkey AND p.comment = 'onic deposits'",
                withBroadcastJoin(),
                39,
                ImmutableSet.of(39, PART_COUNT));
    }

    @Test
    public void testJoinDynamicFilteringBlockProbeSide()
    {
        // Wait for both build sides to finish before starting the scan of 'lineitem' table (should be very selective given the dynamic filters).
        assertDynamicFiltering(
                "SELECT l.comment" +
                        " FROM  lineitem l, part p, orders o" +
                        " WHERE l.orderkey = o.orderkey AND o.comment = 'nstructions sleep furiously among '" +
                        " AND p.partkey = l.partkey AND p.comment = 'onic deposits'",
                withBroadcastJoinNonReordering(),
                1,
                ImmutableSet.of(1, ORDERS_COUNT, PART_COUNT));
    }

    @Test
    public void testSemiJoinDynamicFilteringNone()
    {
        // Probe-side is not scanned at all, due to dynamic filtering:
        assertDynamicFiltering(
                "SELECT * FROM lineitem WHERE lineitem.orderkey IN (SELECT orders.orderkey FROM orders WHERE orders.totalprice < 0)",
                withBroadcastJoin(),
                0,
                ImmutableSet.of(0, ORDERS_COUNT));
    }

    @Test
    public void testSemiJoinLargeBuildSideDynamicFiltering()
    {
        // Probe-side is fully scanned because the build-side is too large for dynamic filtering:
        @Language("SQL") String sql = "SELECT * FROM lineitem WHERE lineitem.orderkey IN " +
                "(SELECT orders.orderkey FROM orders WHERE orders.custkey BETWEEN 300 AND 700)";
        int expectedRowCount = 15793;
        // Probe-side is fully scanned because the build-side is too large for dynamic filtering:
        assertDynamicFiltering(
                sql,
                withBroadcastJoin(),
                expectedRowCount,
                ImmutableSet.of(LINEITEM_COUNT, ORDERS_COUNT));
        // Probe-side is partially scanned because we extract min/max from large build-side for dynamic filtering
        assertDynamicFiltering(
                sql,
                withLargeDynamicFilters(),
                expectedRowCount,
                ImmutableSet.of(60139, ORDERS_COUNT));
    }

    @Test
    public void testPartitionedSemiJoinNoDynamicFiltering()
    {
        // Probe-side is fully scanned, because local dynamic filtering does not work for partitioned joins:
        assertDynamicFiltering(
                "SELECT * FROM lineitem WHERE lineitem.orderkey IN (SELECT orders.orderkey FROM orders WHERE orders.totalprice < 0)",
                withPartitionedJoin(),
                0,
                ImmutableSet.of(LINEITEM_COUNT, ORDERS_COUNT));
    }

    @Test
    public void testSemiJoinDynamicFilteringSingleValue()
    {
        // Join lineitem with a single row of orders
        assertDynamicFiltering(
                "SELECT * FROM lineitem WHERE lineitem.orderkey IN (SELECT orders.orderkey FROM orders WHERE orders.comment = 'nstructions sleep furiously among ')",
                withBroadcastJoin(),
                6,
                ImmutableSet.of(6, ORDERS_COUNT));

        // Join lineitem with a single row of part
        assertDynamicFiltering(
                "SELECT l.comment FROM lineitem l WHERE l.partkey IN (SELECT p.partkey FROM part p WHERE p.comment = 'onic deposits')",
                withBroadcastJoin(),
                39,
                ImmutableSet.of(39, PART_COUNT));
    }

    @Test
    public void testSemiJoinDynamicFilteringBlockProbeSide()
    {
        // Wait for both build sides to finish before starting the scan of 'lineitem' table (should be very selective given the dynamic filters).
        assertDynamicFiltering(
                "SELECT t.comment FROM " +
                        "(SELECT * FROM lineitem l WHERE l.orderkey IN (SELECT o.orderkey FROM orders o WHERE o.comment = 'nstructions sleep furiously among ')) t " +
                        "WHERE t.partkey IN (SELECT p.partkey FROM part p WHERE p.comment = 'onic deposits')",
                withBroadcastJoinNonReordering(),
                1,
                ImmutableSet.of(1, ORDERS_COUNT, PART_COUNT));
    }

    private void assertDynamicFiltering(@Language("SQL") String selectQuery, Session session, int expectedRowCount, Set<Integer> expectedOperatorRowsRead)
    {
        DistributedQueryRunner runner = (DistributedQueryRunner) getQueryRunner();
        ResultWithQueryId<MaterializedResult> result = runner.executeWithQueryId(session, selectQuery);

        assertEquals(result.getResult().getRowCount(), expectedRowCount);
        assertEquals(getOperatorRowsRead(runner, result.getQueryId()), expectedOperatorRowsRead);
    }

    private Session withBroadcastJoin()
    {
        return Session.builder(this.getQueryRunner().getDefaultSession())
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, BROADCAST.name())
                .build();
    }

    private Session withLargeDynamicFilters()
    {
        return Session.builder(this.getQueryRunner().getDefaultSession())
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, BROADCAST.name())
                .setSystemProperty(ENABLE_LARGE_DYNAMIC_FILTERS, "true")
                .build();
    }

    private Session withBroadcastJoinNonReordering()
    {
        return Session.builder(this.getQueryRunner().getDefaultSession())
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, BROADCAST.name())
                .setSystemProperty(JOIN_REORDERING_STRATEGY, NONE.name())
                .build();
    }

    private Session withPartitionedJoin()
    {
        return Session.builder(this.getQueryRunner().getDefaultSession())
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, PARTITIONED.name())
                .build();
    }

    private static Set<Integer> getOperatorRowsRead(DistributedQueryRunner runner, QueryId queryId)
    {
        QueryStats stats = runner.getCoordinator().getQueryManager().getFullQueryInfo(queryId).getQueryStats();
        return stats.getOperatorSummaries()
                .stream()
                .filter(summary -> summary.getOperatorType().equals("ScanFilterAndProjectOperator"))
                .map(OperatorStats::getInputPositions)
                .map(Math::toIntExact)
                .collect(toImmutableSet());
    }

    @Test
    public void testJoinDynamicFilteringMultiJoin()
    {
        assertUpdate("CREATE TABLE t0 (k0 integer, v0 real)");
        assertUpdate("CREATE TABLE t1 (k1 integer, v1 real)");
        assertUpdate("CREATE TABLE t2 (k2 integer, v2 real)");
        assertUpdate("INSERT INTO t0 VALUES (1, 1.0)", 1);
        assertUpdate("INSERT INTO t1 VALUES (1, 2.0)", 1);
        assertUpdate("INSERT INTO t2 VALUES (1, 3.0)", 1);

        String query = "SELECT k0, k1, k2 FROM t0, t1, t2 WHERE (k0 = k1) AND (k0 = k2) AND (v0 + v1 = v2)";
        Session session = Session.builder(getSession())
                .setSystemProperty(JOIN_DISTRIBUTION_TYPE, FeaturesConfig.JoinDistributionType.BROADCAST.name())
                .setSystemProperty(JOIN_REORDERING_STRATEGY, FeaturesConfig.JoinReorderingStrategy.NONE.name())
                .build();
        assertQuery(session, query, "SELECT 1, 1, 1");
    }

    @Test
    public void testCreateTableWithNoData()
    {
        assertUpdate("CREATE TABLE test_empty (a BIGINT)");
        assertQueryResult("SELECT count(*) FROM test_empty", 0L);
        assertQueryResult("INSERT INTO test_empty SELECT nationkey FROM tpch.tiny.nation", 25L);
        assertQueryResult("SELECT count(*) FROM test_empty", 25L);
    }

    @Test
    public void testCreateFilteredOutTable()
    {
        assertUpdate("CREATE TABLE filtered_out AS SELECT nationkey FROM tpch.tiny.nation WHERE nationkey < 0", "SELECT count(nationkey) FROM nation WHERE nationkey < 0");
        assertQueryResult("SELECT count(*) FROM filtered_out", 0L);
        assertQueryResult("INSERT INTO filtered_out SELECT nationkey FROM tpch.tiny.nation", 25L);
        assertQueryResult("SELECT count(*) FROM filtered_out", 25L);
    }

    @Test
    public void testSelectFromEmptyTable()
    {
        assertUpdate("CREATE TABLE test_select_empty AS SELECT * FROM tpch.tiny.nation WHERE nationkey > 1000", "SELECT count(*) FROM nation WHERE nationkey > 1000");

        assertQueryResult("SELECT count(*) FROM test_select_empty", 0L);
    }

    @Test
    public void testSelectSingleRow()
    {
        assertQuery("SELECT * FROM tpch.tiny.nation WHERE nationkey = 1", "SELECT * FROM nation WHERE nationkey = 1");
    }

    @Test
    public void testSelectColumnsSubset()
    {
        assertQuery("SELECT nationkey, regionkey FROM tpch.tiny.nation ORDER BY nationkey", "SELECT nationkey, regionkey FROM nation ORDER BY nationkey");
    }

    @Test
    public void testCreateSchema()
    {
        assertQueryFails("DROP SCHEMA schema1", "line 1:1: Schema 'memory.schema1' does not exist");
        assertUpdate("CREATE SCHEMA schema1");
        assertQueryFails("CREATE SCHEMA schema1", "line 1:1: Schema 'memory.schema1' already exists");
        assertUpdate("CREATE TABLE schema1.x(t int)");
        assertQueryFails("DROP SCHEMA schema1", "Schema not empty: schema1");
        assertUpdate("DROP TABLE schema1.x");
        assertUpdate("DROP SCHEMA schema1");
        assertQueryFails("DROP SCHEMA schema1", "line 1:1: Schema 'memory.schema1' does not exist");
        assertUpdate("DROP SCHEMA IF EXISTS schema1");
    }

    @Test
    public void testCreateTableInNonDefaultSchema()
    {
        assertUpdate("CREATE SCHEMA schema1");
        assertUpdate("CREATE SCHEMA schema2");

        assertQueryResult("SHOW SCHEMAS", "default", "information_schema", "schema1", "schema2");
        assertUpdate("CREATE TABLE schema1.nation AS SELECT * FROM tpch.tiny.nation WHERE nationkey % 2 = 0", "SELECT count(*) FROM nation WHERE MOD(nationkey, 2) = 0");
        assertUpdate("CREATE TABLE schema2.nation AS SELECT * FROM tpch.tiny.nation WHERE nationkey % 2 = 1", "SELECT count(*) FROM nation WHERE MOD(nationkey, 2) = 1");

        assertQueryResult("SELECT count(*) FROM schema1.nation", 13L);
        assertQueryResult("SELECT count(*) FROM schema2.nation", 12L);
    }

    @Test
    public void testCreateTableAndViewInNotExistSchema()
    {
        int tablesBeforeCreate = listMemoryTables().size();

        assertQueryFails("CREATE TABLE schema3.test_table3 (x date)", "Schema schema3 not found");
        assertQueryFails("CREATE VIEW schema4.test_view4 AS SELECT 123 x", "Schema schema4 not found");
        assertQueryFails("CREATE OR REPLACE VIEW schema5.test_view5 AS SELECT 123 x", "Schema schema5 not found");

        int tablesAfterCreate = listMemoryTables().size();
        assertEquals(tablesBeforeCreate, tablesAfterCreate);
    }

    @Test
    public void testRenameTable()
    {
        assertUpdate("CREATE TABLE test_table_to_be_renamed (a BIGINT)");
        assertQueryFails("ALTER TABLE test_table_to_be_renamed RENAME TO memory.test_schema_not_exist.test_table_renamed", "Schema test_schema_not_exist not found");
        assertUpdate("ALTER TABLE test_table_to_be_renamed RENAME TO test_table_renamed");
        assertQueryResult("SELECT count(*) FROM test_table_renamed", 0L);

        assertUpdate("CREATE SCHEMA test_different_schema");
        assertUpdate("ALTER TABLE test_table_renamed RENAME TO test_different_schema.test_table_renamed");
        assertQueryResult("SELECT count(*) FROM test_different_schema.test_table_renamed", 0L);

        assertUpdate("DROP TABLE test_different_schema.test_table_renamed");
        assertUpdate("DROP SCHEMA test_different_schema");
    }

    @Test
    public void testViews()
    {
        @Language("SQL") String query = "SELECT orderkey, orderstatus, totalprice / 2 half FROM orders";

        assertUpdate("CREATE VIEW test_view AS SELECT 123 x");
        assertUpdate("CREATE OR REPLACE VIEW test_view AS " + query);

        assertQueryFails("CREATE TABLE test_view (x date)", "View \\[default.test_view] already exists");
        assertQueryFails("CREATE VIEW test_view AS SELECT 123 x", "View already exists: default.test_view");

        assertQuery("SELECT * FROM test_view", query);

        assertTrue(computeActual("SHOW TABLES").getOnlyColumnAsSet().contains("test_view"));

        assertUpdate("DROP VIEW test_view");
        assertQueryFails("DROP VIEW test_view", "line 1:1: View 'memory.default.test_view' does not exist");
    }

    @Test
    public void testRenameView()
    {
        @Language("SQL") String query = "SELECT orderkey, orderstatus, totalprice / 2 half FROM orders";

        assertUpdate("CREATE VIEW test_view_to_be_renamed AS " + query);
        assertQueryFails("ALTER VIEW test_view_to_be_renamed RENAME TO memory.test_schema_not_exist.test_view_renamed", "Schema test_schema_not_exist not found");
        assertUpdate("ALTER VIEW test_view_to_be_renamed RENAME TO test_view_renamed");
        assertQuery("SELECT * FROM test_view_renamed", query);

        assertUpdate("CREATE SCHEMA test_different_schema");
        assertUpdate("ALTER VIEW test_view_renamed RENAME TO test_different_schema.test_view_renamed");
        assertQuery("SELECT * FROM test_different_schema.test_view_renamed", query);

        assertUpdate("DROP VIEW test_different_schema.test_view_renamed");
        assertUpdate("DROP SCHEMA test_different_schema");
    }

    private List<QualifiedObjectName> listMemoryTables()
    {
        return getQueryRunner().listTables(getSession(), "memory", "default");
    }

    private void assertQueryResult(@Language("SQL") String sql, Object... expected)
    {
        MaterializedResult rows = computeActual(sql);
        assertEquals(rows.getRowCount(), expected.length);

        for (int i = 0; i < expected.length; i++) {
            MaterializedRow materializedRow = rows.getMaterializedRows().get(i);
            int fieldCount = materializedRow.getFieldCount();
            assertEquals(fieldCount, 1, format("Expected only one column, but got '%d'", fieldCount));
            Object value = materializedRow.getField(0);
            assertEquals(value, expected[i]);
            assertEquals(materializedRow.getFieldCount(), 1);
        }
    }
}
