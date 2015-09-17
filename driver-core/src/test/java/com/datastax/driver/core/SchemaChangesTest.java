/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import java.util.List;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.testng.annotations.*;

import com.datastax.driver.core.utils.Bytes;
import com.datastax.driver.core.utils.CassandraVersion;

import static com.datastax.driver.core.Assertions.assertThat;

public class SchemaChangesTest {
    private static final String CREATE_KEYSPACE =
        "CREATE KEYSPACE %s WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor': '1' }";

    CCMBridge ccm;
    Cluster cluster;
    Cluster cluster2; // a second cluster to check that other clients also get notified

    // The metadatas of the two clusters (we'll test that they're kept in sync)
    List<Metadata> metadatas;

    Session session;

    @BeforeClass(groups = "short")
    public void setup() {
        CCMBridge.Builder builder = CCMBridge.builder("schemaChangesTest")
                .withNodes(1);

        if(TestUtils.getDesiredProtocolVersion().compareTo(ProtocolVersion.V4) >= 0) {
            builder = builder.withCassandraConfiguration("enable_user_defined_functions", "true");
        }
        ccm = builder.build();

        cluster = Cluster.builder().addContactPoint(CCMBridge.ipOfNode(1)).build();
        cluster2 = Cluster.builder().addContactPoint(CCMBridge.ipOfNode(1)).build();

        metadatas = Lists.newArrayList(cluster.getMetadata(), cluster2.getMetadata());

        session = cluster.connect();
        session.execute(String.format(CREATE_KEYSPACE, "lowercase"));
        session.execute(String.format(CREATE_KEYSPACE, "\"CaseSensitive\""));
    }

    @AfterClass(groups = "short")
    public void teardown() {
        if (cluster != null)
            cluster.close();
        if (cluster2 != null)
            cluster2.close();
        if (ccm != null)
            ccm.remove();
    }


    @DataProvider(name = "existingKeyspaceName")
    public static Object[][] existingKeyspaceName() {
        return new Object[][]{ { "lowercase" }, { "\"CaseSensitive\"" } };
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    public void should_notify_of_table_creation(String keyspace) {
        session.execute(String.format("CREATE TABLE %s.table1(i int primary key)", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getTable("table1"))
                .isNotNull();
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    public void should_notify_of_table_update(String keyspace) {
        session.execute(String.format("CREATE TABLE %s.table1(i int primary key)", keyspace));
        session.execute(String.format("ALTER TABLE %s.table1 ADD j int", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getTable("table1").getColumn("j"))
                .isNotNull();
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    public void should_notify_of_table_drop(String keyspace) {
        session.execute(String.format("CREATE TABLE %s.table1(i int primary key)", keyspace));
        session.execute(String.format("DROP TABLE %s.table1", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getTable("table1"))
                .isNull();
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    @CassandraVersion(major = 2.1)
    public void should_notify_of_udt_creation(String keyspace) {
        session.execute(String.format("CREATE TYPE %s.type1(i int)", keyspace));

        for (Metadata m : metadatas)
            assertThat((DataType) m.getKeyspace(keyspace).getUserType("type1"))
                .isNotNull();
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    @CassandraVersion(major = 2.1)
    public void should_notify_of_udt_update(String keyspace) {
        session.execute(String.format("CREATE TYPE %s.type1(i int)", keyspace));
        session.execute(String.format("ALTER TYPE %s.type1 ADD j int", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getUserType("type1").getFieldType("j"))
                .isNotNull();
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    @CassandraVersion(major = 2.1)
    public void should_notify_of_udt_drop(String keyspace) {
        session.execute(String.format("CREATE TYPE %s.type1(i int)", keyspace));
        session.execute(String.format("DROP TYPE %s.type1", keyspace));

        for (Metadata m : metadatas)
            assertThat((DataType) m.getKeyspace(keyspace).getUserType("type1"))
                .isNull();
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    @CassandraVersion(major = 2.2)
    public void should_notify_of_function_creation(String keyspace) {
        session.execute(String.format("CREATE FUNCTION %s.id(i int) RETURNS NULL ON NULL INPUT RETURNS int LANGUAGE java AS 'return i;'", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getFunction("id", DataType.cint()))
                .isNotNull();
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    @CassandraVersion(major = 2.2)
    public void should_notify_of_function_update(String keyspace) {
        session.execute(String.format("CREATE FUNCTION %s.id(i int) RETURNS NULL ON NULL INPUT RETURNS int LANGUAGE java "
            + "AS 'return i;'", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getFunction("id", DataType.cint()).getBody())
                .isEqualTo("return i;");

        session.execute(String.format("CREATE OR REPLACE FUNCTION %s.id(i int) RETURNS NULL ON NULL INPUT RETURNS int LANGUAGE java "
            + "AS 'return i + 1;'", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getFunction("id", DataType.cint()).getBody())
                .isEqualTo("return i + 1;");
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    @CassandraVersion(major = 2.2)
    public void should_notify_of_function_drop(String keyspace) {
        session.execute(String.format("CREATE FUNCTION %s.id(i int) RETURNS NULL ON NULL INPUT RETURNS int LANGUAGE java AS 'return i;'", keyspace));
        session.execute(String.format("DROP FUNCTION %s.id", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getFunction("id", DataType.cint()))
                .isNull();
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    @CassandraVersion(major = 2.2)
    public void should_notify_of_aggregate_creation(String keyspace) {
        session.execute(String.format("CREATE FUNCTION %s.plus(s int, v int) RETURNS NULL ON NULL INPUT RETURNS int LANGUAGE java"
            + " AS 'return s+v;'", keyspace));
        session.execute(String.format("CREATE AGGREGATE %s.sum(int) SFUNC plus STYPE int INITCOND 0;", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getAggregate("sum", DataType.cint()))
                .isNotNull();
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    @CassandraVersion(major = 2.2)
    public void should_notify_of_aggregate_update(String keyspace) {
        session.execute(String.format("CREATE FUNCTION %s.plus(s int, v int) RETURNS NULL ON NULL INPUT RETURNS int LANGUAGE java"
            + " AS 'return s+v;'", keyspace));
        session.execute(String.format("CREATE AGGREGATE %s.sum(int) SFUNC plus STYPE int INITCOND 0", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getAggregate("sum", DataType.cint()).getInitCond())
                .isEqualTo(0);

        session.execute(String.format("CREATE OR REPLACE AGGREGATE %s.sum(int) SFUNC plus STYPE int INITCOND 1", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getAggregate("sum", DataType.cint()).getInitCond())
                .isEqualTo(1);
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    @CassandraVersion(major = 2.2)
    public void should_notify_of_aggregate_drop(String keyspace) {
        session.execute(String.format("CREATE FUNCTION %s.plus(s int, v int) RETURNS NULL ON NULL INPUT RETURNS int LANGUAGE java"
            + " AS 'return s+v;'", keyspace));
        session.execute(String.format("CREATE AGGREGATE %s.sum(int) SFUNC plus STYPE int INITCOND 0", keyspace));
        session.execute(String.format("DROP AGGREGATE %s.sum", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getAggregate("sum", DataType.cint()))
                .isNull();
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    @CassandraVersion(major = 3.0)
    public void should_notify_of_view_creation(String keyspace) {
        session.execute(String.format("CREATE TABLE %s.table1 (pk int PRIMARY KEY, c int)", keyspace));
        session.execute(String.format("CREATE MATERIALIZED VIEW %s.mv1 AS SELECT c FROM %s.table1 WHERE c IS NOT NULL PRIMARY KEY (pk, c)", keyspace, keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getMaterializedView("mv1")).isNotNull();
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    @CassandraVersion(major = 3.0)
    public void should_notify_of_view_update(String keyspace) {
        session.execute(String.format("CREATE TABLE %s.table1 (pk int PRIMARY KEY, c int)", keyspace));
        session.execute(String.format("CREATE MATERIALIZED VIEW %s.mv1 AS SELECT c FROM %s.table1 WHERE c IS NOT NULL PRIMARY KEY (pk, c) WITH compaction = { 'class' : 'SizeTieredCompactionStrategy' }", keyspace, keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getMaterializedView("mv1").getOptions().getCompaction().get("class")).contains("SizeTieredCompactionStrategy");

        session.execute(String.format("ALTER MATERIALIZED VIEW %s.mv1 WITH compaction = { 'class' : 'LeveledCompactionStrategy' }", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getMaterializedView("mv1").getOptions().getCompaction().get("class")).contains("LeveledCompactionStrategy");
    }

    @Test(groups = "short", dataProvider = "existingKeyspaceName")
    @CassandraVersion(major = 3.0)
    public void should_notify_of_view_drop(String keyspace) {
        session.execute(String.format("CREATE TABLE %s.table1 (pk int PRIMARY KEY, c int)", keyspace));
        session.execute(String.format("CREATE MATERIALIZED VIEW %s.mv1 AS SELECT c FROM %s.table1 WHERE c IS NOT NULL PRIMARY KEY (pk, c)", keyspace, keyspace));
        session.execute(String.format("DROP MATERIALIZED VIEW %s.mv1", keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).getMaterializedView("mv1")).isNull();
    }

    @DataProvider(name = "newKeyspaceName")
    public static Object[][] newKeyspaceName() {
        return new Object[][]{ { "lowercase2" }, { "\"CaseSensitive2\"" } };
    }

    @Test(groups = "short", dataProvider = "newKeyspaceName")
    public void should_notify_of_keyspace_creation(String keyspace) {
        session.execute(String.format(CREATE_KEYSPACE, keyspace));

        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace))
                .isNotNull();
    }

    @Test(groups = "short", dataProvider = "newKeyspaceName")
    public void should_notify_of_keyspace_update(String keyspace) {
        session.execute(String.format(CREATE_KEYSPACE, keyspace));
        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).isDurableWrites())
                .isTrue();

        session.execute(String.format("ALTER KEYSPACE %s WITH durable_writes = false", keyspace));
        for (Metadata m : metadatas)
            assertThat(m.getKeyspace(keyspace).isDurableWrites())
                .isFalse();
    }

    @Test(groups = "short", dataProvider = "newKeyspaceName")
    public void should_notify_of_keyspace_drop(String keyspace) {
        session.execute(String.format(CREATE_KEYSPACE, keyspace));
        for (Metadata m : metadatas)
            assertThat(m.getReplicas(keyspace, Bytes.fromHexString("0xCAFEBABE")))
                .isNotEmpty();

        session.execute(String.format("DROP KEYSPACE %s", keyspace));

        for (Metadata m : metadatas) {
            assertThat(m.getKeyspace(keyspace))
                .isNull();
            assertThat(m.getReplicas(keyspace, Bytes.fromHexString("0xCAFEBABE")))
                .isEmpty();
        }
    }

    @AfterMethod(groups = "short")
    public void cleanup() {
        ListenableFuture<List<ResultSet>> f = Futures.successfulAsList(Lists.newArrayList(
            session.executeAsync("DROP TABLE lowercase.table1"),
            session.executeAsync("DROP TABLE \"CaseSensitive\".table1"),
            session.executeAsync("DROP TYPE lowercase.type1"),
            session.executeAsync("DROP TYPE \"CaseSensitive\".type1"),
            session.executeAsync("DROP FUNCTION lowercase.id"),
            session.executeAsync("DROP FUNCTION \"CaseSensitive\".id"),
            session.executeAsync("DROP FUNCTION lowercase.plus"),
            session.executeAsync("DROP FUNCTION \"CaseSensitive\".plus"),
            session.executeAsync("DROP AGGREGATE lowercase.sum"),
            session.executeAsync("DROP AGGREGATE \"CaseSensitive\".sum"),
            session.executeAsync("DROP MATERIALIZED VIEW lowercase.mv1"),
            session.executeAsync("DROP MATERIALIZED VIEW \"CaseSensitive\".mv1"),
            session.executeAsync("DROP KEYSPACE lowercase2"),
            session.executeAsync("DROP KEYSPACE \"CaseSensitive2\"")
        ));
        Futures.getUnchecked(f);
    }
}
