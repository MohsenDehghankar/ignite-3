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

package org.apache.ignite.internal.cli.deprecated;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockserver.matchers.MatchType.ONLY_MATCHING_FIELDS;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.INTERNAL_SERVER_ERROR_500;
import static org.mockserver.model.HttpStatusCode.OK_200;
import static org.mockserver.model.JsonBody.json;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import org.apache.ignite.internal.cli.commands.TopLevelCliCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerExtension;
import org.mockserver.model.MediaType;
import picocli.CommandLine;

/**
 * Smoke test for Ignite CLI features and its UI. Structure of tests should be self-documented and repeat the structure of Ignite CLI
 * subcommands.
 */
@DisplayName("ignite")
@ExtendWith(MockitoExtension.class)
@ExtendWith(MockServerExtension.class)
public class IgniteCliInterfaceTest extends AbstractCliTest {
    /** DI application context. */
    ApplicationContext ctx;

    /** stderr. */
    ByteArrayOutputStream err;

    /** stdout. */
    ByteArrayOutputStream out;

    private final ClientAndServer clientAndServer;

    private final String mockUrl;

    public IgniteCliInterfaceTest(ClientAndServer clientAndServer) {
        this.clientAndServer = clientAndServer;
        mockUrl = "http://localhost:" + clientAndServer.getPort();
    }

    /**
     * Sets up environment before test execution.
     */
    @BeforeEach
    void setup() {
        ctx = ApplicationContext.run(Environment.TEST);

        err = new ByteArrayOutputStream();
        out = new ByteArrayOutputStream();

        clientAndServer.reset();
    }

    /**
     * Stops application context after a test.
     */
    @AfterEach
    void tearDown() {
        ctx.stop();
    }

    /**
     * Creates a new command line interpreter with the given application context.
     *
     * @return New {@code CommandLine} interpreter.
     */
    CommandLine cmd(ApplicationContext applicationCtx) {
        CommandLine.IFactory factory = new CommandFactory(applicationCtx);

        return new CommandLine(TopLevelCliCommand.class, factory)
                .setErr(new PrintWriter(err, true))
                .setOut(new PrintWriter(out, true));
    }

    private int execute(String cmdLine) {
        return cmd(ctx).execute(cmdLine.split(" "));
    }

    /**
     * Tests "node" command.
     */
    @Nested
    @DisplayName("node")
    class Node {

        /**
         * Tests "config" command.
         */
        @Nested
        @DisplayName("config")
        class Config {
            @Test
            @DisplayName("show --node-url http://localhost:10300")
            void show() {
                clientAndServer
                        .when(request()
                                .withMethod("GET")
                                .withPath("/management/v1/configuration/node")
                        )
                        .respond(response("{\"autoAdjust\":{\"enabled\":true}}"));

                int exitCode = execute("node config show --node-url " + mockUrl);

                assertThatExitCodeMeansSuccess(exitCode);

                assertOutputEqual("{\n"
                                + "  \"autoAdjust\" : {\n"
                                + "    \"enabled\" : true\n"
                                + "  }\n"
                                + "}\n"
                );
                assertThatStderrIsEmpty();
            }

            @Test
            @DisplayName("show --node-url http://localhost:10300 local.baseline")
            void showSubtree() {
                clientAndServer
                        .when(request()
                                .withMethod("GET")
                                .withPath("/management/v1/configuration/node/local.baseline")
                        )
                        .respond(response("{\"autoAdjust\":{\"enabled\":true}}"));

                int exitCode = execute("node config show --node-url " + mockUrl + " local.baseline");

                assertThatExitCodeMeansSuccess(exitCode);

                assertOutputEqual("{\n"
                                + "  \"autoAdjust\" : {\n"
                                + "    \"enabled\" : true\n"
                                + "  }\n"
                                + "}\n"
                );
                assertThatStderrIsEmpty();
            }

            @Test
            @DisplayName("update --node-url http://localhost:10300 local.baseline.autoAdjust.enabled=true")
            void updateHocon() {
                clientAndServer
                        .when(request()
                                .withMethod("PATCH")
                                .withPath("/management/v1/configuration/node")
                                .withBody("local.baseline.autoAdjust.enabled=true")
                        )
                        .respond(response(null));

                int exitCode = execute("node config update --node-url " + mockUrl + " local.baseline.autoAdjust.enabled=true");

                assertThatExitCodeMeansSuccess(exitCode);

                assertOutputEqual("Node configuration was updated successfully");
                assertThatStderrIsEmpty();
            }
        }

        @Nested
        @DisplayName("metric")
        class Metric {
            @Test
            @DisplayName("metric enable srcName")
            void enable() {
                clientAndServer
                        .when(request()
                                .withMethod("POST")
                                .withPath("/management/v1/metric/node/enable")
                                .withBody("srcName")
                        )
                        .respond(response(null));

                int exitCode = execute("node metric enable --node-url " + mockUrl + " srcName");

                assertThatExitCodeMeansSuccess(exitCode);

                assertOutputEqual("Metric source was enabled successfully");
                assertThatStderrIsEmpty();
            }

            @Test
            @DisplayName("metric disable srcName")
            void disable() {
                clientAndServer
                        .when(request()
                                .withMethod("POST")
                                .withPath("/management/v1/metric/node/disable")
                                .withBody("srcName")
                        )
                        .respond(response(null));

                int exitCode = execute("node metric disable --node-url " + mockUrl + " srcName");

                assertThatExitCodeMeansSuccess(exitCode);

                assertOutputEqual("Metric source was disabled successfully");
                assertThatStderrIsEmpty();
            }

            @Test
            @DisplayName("metric list")
            void list() {
                String responseBody = "[{\"name\":\"enabledMetric\",\"enabled\":true},{\"name\":\"disabledMetric\",\"enabled\":false}]";
                clientAndServer
                        .when(request()
                                .withMethod("GET")
                                .withPath("/management/v1/metric/node")
                        )
                        .respond(response(responseBody));

                int exitCode = execute("node metric list --node-url " + mockUrl);

                assertThatExitCodeMeansSuccess(exitCode);

                assertOutputEqual("Enabled metric sources:\nenabledMetric\nDisabled metric sources:\ndisabledMetric\n");
                assertThatStderrIsEmpty();
            }
        }
    }

    /**
     * Tests "cluster" command.
     */
    @Nested
    @DisplayName("cluster")
    class Cluster {
        @Test
        @DisplayName("init --cluster-endpoint-url http://localhost:10300 --meta-storage-node node1ConsistentId --meta-storage-node node2ConsistentId "
                + "--cmg-node node2ConsistentId --cmg-node node3ConsistentId --cluster-name cluster")
        void initSuccess() {
            var expectedSentContent = "{\"metaStorageNodes\":[\"node1ConsistentId\",\"node2ConsistentId\"],"
                    + "\"cmgNodes\":[\"node2ConsistentId\",\"node3ConsistentId\"],"
                    + "\"clusterName\":\"cluster\"}";

            clientAndServer
                    .when(request()
                            .withMethod("POST")
                            .withPath("/management/v1/cluster/init")
                            .withBody(json(expectedSentContent, ONLY_MATCHING_FIELDS))
                            .withContentType(MediaType.APPLICATION_JSON_UTF_8)
                    )
                    .respond(response(null));


            int exitCode = cmd(ctx).execute(
                    "cluster", "init",
                    "--cluster-endpoint-url", mockUrl,
                    "--meta-storage-node", "node1ConsistentId",
                    "--meta-storage-node", "node2ConsistentId",
                    "--cmg-node", "node2ConsistentId",
                    "--cmg-node", "node3ConsistentId",
                    "--cluster-name", "cluster"
            );

            assertThatExitCodeMeansSuccess(exitCode);

            assertOutputEqual("Cluster was initialized successfully");
            assertThatStderrIsEmpty();
        }

        @Test
        void initError() {
            clientAndServer
                    .when(request()
                            .withMethod("POST")
                            .withPath("/management/v1/cluster/init")
                    )
                    .respond(response()
                            .withStatusCode(INTERNAL_SERVER_ERROR_500.code())
                            .withBody("{\"status\":500, \"detail\":\"Oops\"}")
                    );

            int exitCode = cmd(ctx).execute(
                    "cluster", "init",
                    "--cluster-endpoint-url", mockUrl,
                    "--meta-storage-node", "node1ConsistentId",
                    "--meta-storage-node", "node2ConsistentId",
                    "--cmg-node", "node2ConsistentId",
                    "--cmg-node", "node3ConsistentId",
                    "--cluster-name", "cluster"
            );

            assertThatExitCodeIs(1, exitCode);

            assertThatStdoutIsEmpty();
            assertErrOutputEqual("Oops");
        }

        @Test
        @DisplayName("init --cluster-endpoint-url http://localhost:10300 --cmg-node node2ConsistentId --cmg-node node3ConsistentId")
        void metastorageNodesAreMandatoryForInit() {
            int exitCode = cmd(ctx).execute(
                    "cluster", "init",
                    "--cluster-endpoint-url", mockUrl,
                    "--cmg-node", "node2ConsistentId",
                    "--cmg-node", "node3ConsistentId",
                    "--cluster-name", "cluster"
            );

            assertThatExitCodeIs(2, exitCode);

            assertThatStdoutIsEmpty();
            assertThat(err.toString(UTF_8), startsWith("Missing required option: '--meta-storage-node=<metaStorageNodes>'"));
        }

        @Test
        @DisplayName("init --cluster-endpoint-url http://localhost:10300 --meta-storage-node node2ConsistentId --meta-storage-node node3ConsistentId")
        void cmgNodesAreNotMandatoryForInit() {
            clientAndServer
                    .when(request()
                            .withMethod("POST")
                            .withPath("/management/v1/cluster/init")
                    )
                    .respond(response().withStatusCode(OK_200.code()));

            int exitCode = cmd(ctx).execute(
                    "cluster", "init",
                    "--cluster-endpoint-url", mockUrl,
                    "--meta-storage-node", "node1ConsistentId",
                    "--meta-storage-node", "node2ConsistentId",
                    "--cluster-name", "cluster"
            );

            assertThatExitCodeMeansSuccess(exitCode);

            assertOutputEqual("Cluster was initialized successfully");
            assertThatStderrIsEmpty();
        }

        @Test
        @DisplayName("init --cluster-endpoint-url http://localhost:10300 --meta-storage-node node1ConsistentId --cmg-node node2ConsistentId")
        void clusterNameIsMandatoryForInit() {
            int exitCode = cmd(ctx).execute(
                    "cluster", "init",
                    "--cluster-endpoint-url", mockUrl,
                    "--meta-storage-node", "node1ConsistentId",
                    "--cmg-node", "node2ConsistentId"
            );

            assertThatExitCodeIs(2, exitCode);

            assertThatStdoutIsEmpty();
            assertThat(err.toString(UTF_8), startsWith("Missing required option: '--cluster-name=<clusterName>'"));
        }

        @Nested
        @DisplayName("config")
        class Config {
            @Test
            @DisplayName("show --cluster-endpoint-url http://localhost:10300")
            void show() {
                clientAndServer
                        .when(request()
                                .withMethod("GET")
                                .withPath("/management/v1/configuration/cluster")
                        )
                        .respond(response("{\"autoAdjust\":{\"enabled\":true}}"));

                int exitCode = execute("cluster config show --cluster-endpoint-url " + mockUrl);

                assertThatExitCodeMeansSuccess(exitCode);

                assertOutputEqual("{\n"
                        + "  \"autoAdjust\" : {\n"
                        + "    \"enabled\" : true\n"
                        + "  }\n"
                        + "}\n");
                assertThatStderrIsEmpty();
            }

            @Test
            @DisplayName("show --cluster-endpoint-url http://localhost:10300 local.baseline")
            void showSubtree() {
                clientAndServer
                        .when(request()
                                .withMethod("GET")
                                .withPath("/management/v1/configuration/cluster/local.baseline")
                        )
                        .respond(response("{\"autoAdjust\":{\"enabled\":true}}"));

                int exitCode = execute("cluster config show --cluster-endpoint-url " + mockUrl + " local.baseline");

                assertThatExitCodeMeansSuccess(exitCode);

                assertOutputEqual("{\n"
                        + "  \"autoAdjust\" : {\n"
                        + "    \"enabled\" : true\n"
                        + "  }\n"
                        + "}\n");
                assertThatStderrIsEmpty();
            }

            @Test
            @DisplayName("update --cluster-endpoint-url http://localhost:10300 local.baseline.autoAdjust.enabled=true")
            void updateHocon() {
                clientAndServer
                        .when(request()
                                .withMethod("PATCH")
                                .withPath("/management/v1/configuration/cluster")
                                .withBody("local.baseline.autoAdjust.enabled=true")
                        )
                        .respond(response(null));

                int exitCode = execute("cluster config update --cluster-endpoint-url "
                        + mockUrl + " local.baseline.autoAdjust.enabled=true");

                assertThatExitCodeMeansSuccess(exitCode);

                assertOutputEqual("Cluster configuration was updated successfully");
                assertThatStderrIsEmpty();
            }
        }
    }

    private void assertThatStdoutIsEmpty() {
        assertThat(out.toString(UTF_8), is(""));
    }

    private void assertThatStderrIsEmpty() {
        assertThat(err.toString(UTF_8), is(""));
    }

    private void assertThatExitCodeMeansSuccess(int exitCode) {
        assertThatExitCodeIs(0, exitCode);
    }

    private void assertThatExitCodeIs(int expectedCode, int exitCode) {
        assertEquals(expectedCode, exitCode, outputStreams());
    }

    private String outputStreams() {
        return "stdout:\n" + out.toString(UTF_8) + "\n" + "stderr:\n" + err.toString(UTF_8);
    }

    /**
     * <em>Assert</em> that {@code expected} and {@code actual} are equals ignoring differences in line separators.
     *
     * <p>If both are {@code null}, they are considered equal.
     *
     * @param exp Expected result.
     * @param actual Actual result.
     * @see Object#equals(Object)
     */
    private static void assertEqualsIgnoreLineSeparators(String exp, String actual) {
        assertEquals(
                exp.lines().collect(toList()),
                actual.lines().collect(toList())
        );
    }

    private void assertOutputEqual(String exp) {
        assertEqualsIgnoreLineSeparators(exp, out.toString(UTF_8));
    }

    private void assertErrOutputEqual(String exp) {
        assertEqualsIgnoreLineSeparators(exp, err.toString(UTF_8));
    }
}
