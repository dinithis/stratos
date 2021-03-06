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
 * "AS IS" BASIS, WITHOUT WARRANTIES     OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.stratos.common.partition;

import org.apache.stratos.common.Properties;
import org.apache.stratos.common.Property;

import java.io.Serializable;

/**
 * This is keep the partition information passed in deployment policy
 */
public class PartitionRef implements Serializable {

    private static final long serialVersionUID = 3725971287992010720L;

    private static final String KUBERNETES_CLUSTER = "cluster";

    /**
     * provider should match with an IaasProvider type.
     */
    private String uuid;
	private String id;
    private String description;
    private int partitionMax;
    private Properties properties = new Properties();
	private int tenantId;
    /**
     * Gets the value of the id property.
     *
     * @return possible object is
     * {@link String }
     */
    public String getUuid() {
        return uuid;
    }

    /**
     * Sets the value of the id property.
     *
     * @param uuid allowed object is
     *           {@link String }
     */
    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    /**
     * Gets the value of the description property.
     *
     * @return possible object is
     * {@link String }
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the value of the description property.
     *
     * @param description allowed object is
     *                    {@link String }
     */
    public void setDescription(String description) {
        this.description = description;
    }

    public Properties getProperties() {
        return properties;
    }

    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    public String getKubernetesClusterId() {
        Property property = properties.getProperty(KUBERNETES_CLUSTER);
        if (property != null) {
            return property.getValue();
        }
        return null;
    }

    @Override
    public String toString() {
        return "Partition [id=" + uuid + ", description=" + description + ", properties=" + properties + "]";
    }

    public boolean equals(Object obj) {
        if (obj != null && obj instanceof PartitionRef) {
            return this.uuid.equals(((PartitionRef) obj).getUuid());
        }
        return false;

    }

    @Override
    public int hashCode() {
        return this.uuid.hashCode();
    }

    public int getPartitionMax() {
        return partitionMax;
    }

    public void setPartitionMax(int partitionMax) {
        this.partitionMax = partitionMax;
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
}
