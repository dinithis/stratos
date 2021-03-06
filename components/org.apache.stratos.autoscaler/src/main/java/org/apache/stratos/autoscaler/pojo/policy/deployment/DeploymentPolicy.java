/*
 * Licensed to the Apache Software Foundation (ASF) under one 
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY 
 * KIND, either express or implied.  See the License for the 
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.stratos.autoscaler.pojo.policy.deployment;


import org.apache.stratos.common.partition.NetworkPartitionRef;
import org.apache.stratos.common.partition.PartitionRef;

import java.io.Serializable;
import java.util.Arrays;

/**
 * The model class for Deployment-Policy definition.
 */
public class DeploymentPolicy implements Serializable {

    private static final long serialVersionUID = 5675507196284400099L;
    private String uuid;
    private int tenantId;
    private  String id;
    private NetworkPartitionRef[] networkPartitionRefs;

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }


    public int getTenantId() {
        return tenantId;
    }

    public void setTenantId(int tenantId) {
        this.tenantId = tenantId;
    }

    public NetworkPartitionRef[] getNetworkPartitionRefs() {
        return networkPartitionRefs;
    }

    public void setNetworkPartitionRefs(NetworkPartitionRef[] networkPartitionRefs) {
        this.networkPartitionRefs = networkPartitionRefs;
    }

    /**
     * Get network partition reference object by network partition id.
     *
     * @param networkPartitionId
     * @return the {@link NetworkPartitionRef}
     */
    public NetworkPartitionRef getNetworkPartitionByNetworkPartitionId(String networkPartitionId) {
        if (networkPartitionRefs != null) {
            for (NetworkPartitionRef networkPartition : networkPartitionRefs) {
                if (networkPartition.getUuid().equals(networkPartitionId)) {
                    return networkPartition;
                }
            }
        }
        return null;
    }

    /**
     * Get partition references by network partition id.
     *
     * @param networkPartitionId
     * @return an array of {@link PartitionRef}
     */
    public PartitionRef[] getPartitionsByNetworkPartitionId(String networkPartitionId) {
        if (networkPartitionRefs != null) {
            for (NetworkPartitionRef networkPartitionRef : networkPartitionRefs) {
                if (networkPartitionRef.getUuid().equals(networkPartitionId)) {
                    return networkPartitionRef.getPartitionRefs();
                }
            }
        }
        return null;
    }

    public String toString() {
        return String.format("{ deployment-policy-id : %s, network-partitions : %s", uuid,
                Arrays.toString(networkPartitionRefs));
    }

}
