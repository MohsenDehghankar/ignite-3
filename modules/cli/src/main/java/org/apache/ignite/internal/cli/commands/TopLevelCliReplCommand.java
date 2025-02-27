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

package org.apache.ignite.internal.cli.commands;

import org.apache.ignite.internal.cli.commands.cliconfig.CliReplCommand;
import org.apache.ignite.internal.cli.commands.cluster.ClusterReplCommand;
import org.apache.ignite.internal.cli.commands.connect.ConnectCommand;
import org.apache.ignite.internal.cli.commands.connect.DisconnectCommand;
import org.apache.ignite.internal.cli.commands.node.NodeReplCommand;
import org.apache.ignite.internal.cli.commands.sql.SqlReplCommand;
import org.apache.ignite.internal.cli.commands.version.VersionCommand;
import picocli.CommandLine;
import picocli.shell.jline3.PicocliCommands;

/**
 * Top-level command that just prints help.
 */
@CommandLine.Command(name = "",
        footer = {"", "Press Ctrl-D to exit."},
        subcommands = {
                SqlReplCommand.class,
                PicocliCommands.ClearScreen.class,
                CommandLine.HelpCommand.class,
                VersionCommand.class,
                CliReplCommand.class,
                ConnectCommand.class,
                DisconnectCommand.class,
                NodeReplCommand.class,
                ClusterReplCommand.class
        })
public class TopLevelCliReplCommand {
}
