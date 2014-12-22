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
package org.apache.stratos.cloud.controller.functions;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.cloud.controller.context.CloudControllerContext;
import org.apache.stratos.cloud.controller.domain.*;
import org.apache.stratos.cloud.controller.util.CloudControllerUtil;
import org.apache.stratos.common.Properties;
import org.apache.stratos.common.Property;
import org.apache.stratos.common.beans.NameValuePair;
import org.apache.stratos.common.constants.StratosConstants;
import org.apache.stratos.kubernetes.client.model.Container;
import org.apache.stratos.kubernetes.client.model.EnvironmentVariable;
import org.apache.stratos.kubernetes.client.model.Port;

import com.google.common.base.Function;

/**
 * Is responsible for converting a MemberContext object to a Kubernetes
 * {@link Container} Object.
 */
public class ContainerClusterContextToKubernetesContainer implements Function<MemberContext, Container> {

    private static final Log log = LogFactory.getLog(ContainerClusterContextToKubernetesContainer.class);

    @Override
    public Container apply(MemberContext memberContext) {
        String clusterId = memberContext.getClusterId();
        ClusterContext clusterContext = CloudControllerContext.getInstance().getClusterContext(clusterId);

        Container container = new Container();
        container.setName(clusterContext.findContainerHostName());

        Cartridge cartridge = CloudControllerContext.getInstance().getCartridge(clusterContext.getCartridgeType());
        if (cartridge == null) {
            String message = "Could not find cartridge: [cartridge-type] " + clusterContext.getCartridgeType();
            log.error(message);
            throw new RuntimeException(message);
        }

        Partition partition = memberContext.getPartition();
        if(partition == null) {
            String message = "Partition not found in member context: [member-id] " + memberContext.getMemberId();
            log.error(message);
            throw new RuntimeException(message);
        }

        IaasProvider iaasProvider = cartridge.getIaasProviderOfPartition(partition.getId());
        if(iaasProvider == null) {
            String message = "Could not find iaas provider: [partition-id] " + partition.getId();
            log.error(message);
            throw new RuntimeException(message);
        }
        container.setImage(iaasProvider.getImage());
        container.setPorts(getPorts(cartridge));
        container.setEnv(getEnvironmentVariables(memberContext, clusterContext));
        return container;
    }

    private Port[] getPorts(Cartridge cartridge) {
        Port[] ports = new Port[cartridge.getPortMappings().size()];
        List<Port> portList = new ArrayList<Port>();

        for (PortMapping portMapping : cartridge.getPortMappings()) {
            Port p = new Port();
            p.setName(p.getProtocol() + "-" + p.getContainerPort());
            // In kubernetes transport protocol always be 'tcp'
            p.setProtocol("tcp");
            p.setContainerPort(Integer.parseInt(portMapping.getPort()));
            portList.add(p);
        }
        return portList.toArray(ports);
    }

    private EnvironmentVariable[] getEnvironmentVariables(MemberContext memberContext, ClusterContext clusterContext) {
        String kubernetesClusterId = CloudControllerUtil.getProperty(clusterContext.getProperties(),
                StratosConstants.KUBERNETES_CLUSTER_ID);

        List<EnvironmentVariable> environmentVariables = new ArrayList<EnvironmentVariable>();

        // Set dynamic payload
        List<NameValuePair> payload = memberContext.getDynamicPayload();
        if (payload != null) {
            for (NameValuePair parameter : payload) {
                addToEnvironmentVariables(environmentVariables, parameter.getName(), parameter.getValue());
            }
        }

        // Set member properties
        Properties properties = memberContext.getProperties();
        if (properties != null) {
            for (Property property : properties.getProperties()) {
                addToEnvironmentVariables(environmentVariables, property.getName(),
                        property.getValue());
            }
        }

        // Set kubernetes cluster id
        addToEnvironmentVariables(environmentVariables, StratosConstants.KUBERNETES_CLUSTER_ID,
                kubernetesClusterId);

        if(log.isDebugEnabled()) {
            log.debug(String.format("Environment variables: [cluster-id] %s [member-id] %s [variables] %s",
                    memberContext.getClusterId(), memberContext.getMemberId(), environmentVariables.toString()));
        }

        EnvironmentVariable[] array = new EnvironmentVariable[environmentVariables.size()];
        return environmentVariables.toArray(array);
    }

    private void addToEnvironment(List<EnvironmentVariable> envVars, String payload) {
        if (payload != null) {
            String[] entries = payload.split(",");
            for (String entry : entries) {
                String[] var = entry.split("=");
                if (var.length != 2) {
                    continue;
                }
                addToEnvironmentVariables(envVars, var[0], var[1]);
            }
        }
    }

    private void addToEnvironmentVariables(List<EnvironmentVariable> envVars, String name, String value) {
        EnvironmentVariable var = new EnvironmentVariable();
        var.setName(name);
        var.setValue(value);
        envVars.add(var);
    }
}
