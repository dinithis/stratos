/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.stratos.integration.tests.policies;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.common.beans.partition.NetworkPartitionBean;
import org.apache.stratos.common.beans.partition.PartitionBean;
import org.apache.stratos.integration.common.RestConstants;
import org.apache.stratos.integration.tests.StratosIntegrationTest;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.AssertJUnit.*;

/**
 * Test to handle Network partition CRUD operations
 */
public class NetworkPartitionTest extends StratosIntegrationTest {
    private static final Log log = LogFactory.getLog(NetworkPartitionTest.class);
    private static final String RESOURCES_PATH = "/network-partition-test";

    @Test(timeOut = GLOBAL_TEST_TIMEOUT)
    public void testNetworkPartition() {
        try {
            String networkPartitionId = "network-partition-network-partition-test";
            log.info("-------------------------Started network partition test case-------------------------");

            boolean added = restClientTenant1.addEntity(RESOURCES_PATH + RestConstants.NETWORK_PARTITIONS_PATH + "/" +
                            networkPartitionId + ".json",
                    RestConstants.NETWORK_PARTITIONS, RestConstants.NETWORK_PARTITIONS_NAME);

            assertEquals(added, true);
            NetworkPartitionBean bean = (NetworkPartitionBean) restClientTenant1.
                    getEntity(RestConstants.NETWORK_PARTITIONS, networkPartitionId,
                            NetworkPartitionBean.class, RestConstants.NETWORK_PARTITIONS_NAME);

            PartitionBean p1 = bean.getPartitions().get(0);
            assertEquals(bean.getId(), "network-partition-network-partition-test");
            assertEquals(bean.getPartitions().size(), 1);
            assertEquals(p1.getId(), "partition-1");
            assertEquals(p1.getProperty().get(0).getName(), "region");
            assertEquals(p1.getProperty().get(0).getValue(), "default");

            boolean updated =
                    restClientTenant1.updateEntity(RESOURCES_PATH + RestConstants.NETWORK_PARTITIONS_PATH + "/" +
                                    networkPartitionId + "-v1.json",
                            RestConstants.NETWORK_PARTITIONS, RestConstants.NETWORK_PARTITIONS_NAME);

            assertEquals(updated, true);
            NetworkPartitionBean updatedBean = (NetworkPartitionBean) restClientTenant1.
                    getEntity(RestConstants.NETWORK_PARTITIONS, networkPartitionId,
                            NetworkPartitionBean.class, RestConstants.NETWORK_PARTITIONS_NAME);

            PartitionBean p2 = updatedBean.getPartitions().get(1);
            assertEquals(updatedBean.getId(), "network-partition-network-partition-test");
            assertEquals(updatedBean.getPartitions().size(), 2);
            assertEquals(p2.getId(), "partition-2");
            assertEquals(p2.getProperty().get(0).getName(), "region");
            assertEquals(p2.getProperty().get(0).getValue(), "default1");
            assertEquals(p2.getProperty().get(1).getName(), "zone");
            assertEquals(p2.getProperty().get(1).getValue(), "z1");

            updatedBean = (NetworkPartitionBean) restClientTenant2.
                    getEntity(RestConstants.NETWORK_PARTITIONS, networkPartitionId,
                            NetworkPartitionBean.class, RestConstants.NETWORK_PARTITIONS_NAME);
            assertNull("Network partition found in tenant 2", updatedBean);

            added = restClientTenant2.addEntity(RESOURCES_PATH + RestConstants.NETWORK_PARTITIONS_PATH + "/" +
                            networkPartitionId + ".json",
                    RestConstants.NETWORK_PARTITIONS, RestConstants.NETWORK_PARTITIONS_NAME);

            assertEquals(added, true);
            bean = (NetworkPartitionBean) restClientTenant2.
                    getEntity(RestConstants.NETWORK_PARTITIONS, networkPartitionId,
                            NetworkPartitionBean.class, RestConstants.NETWORK_PARTITIONS_NAME);
            assertNotNull("Network partition not exist in other tenant", bean);

            boolean removed = restClientTenant1.removeEntity(RestConstants.NETWORK_PARTITIONS,
                    networkPartitionId, RestConstants.NETWORK_PARTITIONS_NAME);
            assertEquals(removed, true);

            NetworkPartitionBean beanRemoved = (NetworkPartitionBean) restClientTenant1.
                    getEntity(RestConstants.NETWORK_PARTITIONS, networkPartitionId,
                            NetworkPartitionBean.class, RestConstants.NETWORK_PARTITIONS_NAME);
            assertEquals(beanRemoved, null);

            bean = (NetworkPartitionBean) restClientTenant2.
                    getEntity(RestConstants.NETWORK_PARTITIONS, networkPartitionId,
                            NetworkPartitionBean.class, RestConstants.NETWORK_PARTITIONS_NAME);
            assertNotNull("Network partition not exist in other tenant", bean);

            removed = restClientTenant2.removeEntity(RestConstants.NETWORK_PARTITIONS,
                    networkPartitionId, RestConstants.NETWORK_PARTITIONS_NAME);
            assertEquals(removed, true);

            beanRemoved = (NetworkPartitionBean) restClientTenant2.
                    getEntity(RestConstants.NETWORK_PARTITIONS, networkPartitionId,
                            NetworkPartitionBean.class, RestConstants.NETWORK_PARTITIONS_NAME);
            assertEquals(beanRemoved, null);

            log.info("-------------------------Ended network partition test case-------------------------");
        }
        catch (Exception e) {
            log.error("An error occurred while handling network partitions", e);
            assertTrue("An error occurred while handling network partitions", false);
        }
    }
}