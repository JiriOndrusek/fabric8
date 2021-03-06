/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.deployer;

import io.fabric8.deployer.dto.DeployResults;

/**
 * Represents the JMX API exposed by the {@link ProjectDeployerImpl}
 */
public interface ProjectDeployerMXBean {
    DeployResults deployProjectJson(String requirementsJson) throws Exception;

    /**
     * 
     * This method was added with a different name than {@link ProjectDeployerMXBean#deployProjectJson(String)} to preserve backward compatibility.
     * 
     * @param appendProfileBundles  When false and a matching Profile exists, the existing bundle list will be overridden and not appended
     */
    DeployResults deployProjectJsonMergeOption(String requirementsJson, boolean appendProfileBundles) throws Exception;
}
