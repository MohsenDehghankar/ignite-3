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

package org.apache.ignite.internal.cli.commands.node;

import jakarta.inject.Inject;
import org.apache.ignite.internal.cli.commands.ProfileMixin;
import org.apache.ignite.internal.cli.config.ConfigConstants;
import org.apache.ignite.internal.cli.config.ConfigManager;
import org.apache.ignite.internal.cli.config.ConfigManagerProvider;
import picocli.CommandLine.Mixin;

/**
 * Mixin class to combine node URL and profile options.
 */
public class NodeUrlProfileMixin {
    /** Node URL option. */
    @Mixin
    private NodeUrlMixin nodeUrl;

    /** Profile to get default values from. */
    @Mixin
    private ProfileMixin profileName;

    @Inject
    private ConfigManagerProvider configManagerProvider;

    /**
     * Gets node URL from either the command line or from the config with specified or default profile.
     *
     * @return node URL
     */
    public String getNodeUrl() {
        if (nodeUrl.getNodeUrl() != null) {
            return nodeUrl.getNodeUrl();
        } else {
            ConfigManager configManager = configManagerProvider.get();
            return configManager.getProperty(ConfigConstants.CLUSTER_URL, profileName.getProfileName());
        }
    }
}
