/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.cli.builtins.init;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import javax.inject.Inject;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.apache.ignite.cli.CliPathsConfigLoader;
import org.apache.ignite.cli.builtins.SystemPathResolver;
import org.apache.ignite.cli.builtins.module.MavenArtifactResolver;
import org.apache.ignite.cli.builtins.module.ResolveResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine.Help.ColorScheme;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MicronautTest
public class InitIgniteCommandTest {

    @Inject SystemPathResolver pathResolver;
    @Inject MavenArtifactResolver mavenArtifactResolver;
    @Inject InitIgniteCommand initIgniteCommand;
    @Inject CliPathsConfigLoader cliPathsConfigLoader;

    @TempDir Path homeDir;
    @TempDir Path currentDir;

    @Test
    void init() throws IOException {
        when(pathResolver.osHomeDirectoryPath()).thenReturn(homeDir);

        when(mavenArtifactResolver.resolve(any(), any(), any(), any(), any()))
            .thenReturn(new ResolveResult(Arrays.asList()));

        var out = new PrintWriter(System.out, true);
        initIgniteCommand.init(null, out, new ColorScheme.Builder().build());

        var ignitePaths = cliPathsConfigLoader.loadIgnitePathsConfig().get();
        assertTrue(ignitePaths.validateDirs());
    }

    @Test
    void reinit() throws IOException {
        when(pathResolver.osHomeDirectoryPath()).thenReturn(homeDir);

        when(mavenArtifactResolver.resolve(any(), any(), any(), any(), any()))
            .thenReturn(new ResolveResult(Collections.emptyList()));

        var out = new PrintWriter(System.out, true);
        initIgniteCommand.init(null, out, new ColorScheme.Builder().build());

        var ignitePaths = cliPathsConfigLoader.loadIgnitePathsOrThrowError();
        recursiveDirRemove(ignitePaths.binDir);

        assertFalse(ignitePaths::validateDirs);

        initIgniteCommand.init(null, out, new ColorScheme.Builder().build());
        assertTrue(ignitePaths::validateDirs);
    }

    @MockBean(MavenArtifactResolver.class) MavenArtifactResolver mavenArtifactResolver() {
        return mock(MavenArtifactResolver.class);
    }

    @MockBean(SystemPathResolver.class) SystemPathResolver systemPathResolver() {
        return mock(SystemPathResolver.class);
    }

    private void recursiveDirRemove(Path dir) throws IOException {
        Files.walk(dir)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
        dir.toFile().delete();

    }
}
