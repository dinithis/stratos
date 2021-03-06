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
package org.apache.stratos.autoscaler.services.impl;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.autoscaler.algorithms.networkpartition.NetworkPartitionAlgorithmContext;
import org.apache.stratos.autoscaler.applications.ApplicationHolder;
import org.apache.stratos.autoscaler.applications.parser.ApplicationParser;
import org.apache.stratos.autoscaler.applications.parser.DefaultApplicationParser;
import org.apache.stratos.autoscaler.applications.pojo.*;
import org.apache.stratos.autoscaler.applications.topic.ApplicationBuilder;
import org.apache.stratos.autoscaler.client.AutoscalerCloudControllerClient;
import org.apache.stratos.autoscaler.context.AutoscalerContext;
import org.apache.stratos.autoscaler.context.InstanceContext;
import org.apache.stratos.autoscaler.context.cluster.ClusterInstanceContext;
import org.apache.stratos.autoscaler.context.partition.ClusterLevelPartitionContext;
import org.apache.stratos.autoscaler.context.partition.network.NetworkPartitionContext;
import org.apache.stratos.autoscaler.event.publisher.ClusterStatusEventPublisher;
import org.apache.stratos.autoscaler.exception.*;
import org.apache.stratos.autoscaler.exception.application.ApplicationDefinitionException;
import org.apache.stratos.autoscaler.exception.application.InvalidApplicationPolicyException;
import org.apache.stratos.autoscaler.exception.application.InvalidServiceGroupException;
import org.apache.stratos.autoscaler.exception.partition.PartitionValidationException;
import org.apache.stratos.autoscaler.exception.policy.*;
import org.apache.stratos.autoscaler.monitor.cluster.ClusterMonitor;
import org.apache.stratos.autoscaler.monitor.component.ApplicationMonitor;
import org.apache.stratos.autoscaler.pojo.Dependencies;
import org.apache.stratos.autoscaler.pojo.ServiceGroup;
import org.apache.stratos.autoscaler.pojo.policy.PolicyManager;
import org.apache.stratos.autoscaler.pojo.policy.autoscale.AutoscalePolicy;
import org.apache.stratos.autoscaler.pojo.policy.deployment.ApplicationPolicy;
import org.apache.stratos.autoscaler.pojo.policy.deployment.DeploymentPolicy;
import org.apache.stratos.autoscaler.registry.RegistryManager;
import org.apache.stratos.autoscaler.services.AutoscalerService;
import org.apache.stratos.autoscaler.util.AutoscalerUtil;
import org.apache.stratos.cloud.controller.stub.CloudControllerServiceInvalidCartridgeTypeExceptionException;
import org.apache.stratos.cloud.controller.stub.CloudControllerServiceInvalidPartitionExceptionException;
import org.apache.stratos.cloud.controller.stub.domain.MemberContext;
import org.apache.stratos.cloud.controller.stub.domain.NetworkPartition;
import org.apache.stratos.cloud.controller.stub.domain.Partition;
import org.apache.stratos.common.Properties;
import org.apache.stratos.common.client.CloudControllerServiceClient;
import org.apache.stratos.common.client.StratosManagerServiceClient;
import org.apache.stratos.common.constants.StratosConstants;
import org.apache.stratos.common.partition.NetworkPartitionRef;
import org.apache.stratos.common.partition.PartitionRef;
import org.apache.stratos.common.util.CommonUtil;
import org.apache.stratos.manager.service.stub.domain.application.signup.ApplicationSignUp;
import org.apache.stratos.manager.service.stub.domain.application.signup.ArtifactRepository;
import org.apache.stratos.messaging.domain.application.Application;
import org.apache.stratos.messaging.domain.application.ClusterDataHolder;
import org.apache.stratos.messaging.domain.instance.ClusterInstance;
import org.apache.stratos.messaging.domain.topology.Cluster;
import org.apache.stratos.messaging.domain.topology.Member;
import org.apache.stratos.messaging.message.receiver.application.ApplicationManager;
import org.apache.stratos.messaging.message.receiver.topology.TopologyManager;
import org.wso2.carbon.registry.api.RegistryException;

import java.rmi.RemoteException;
import java.util.*;

/**
 * Autoscaler Service API is responsible getting Partitions and Policies.
 */
public class AutoscalerServiceImpl implements AutoscalerService {

    private static final Log log = LogFactory.getLog(AutoscalerServiceImpl.class);

    public AutoscalePolicy[] getAutoScalingPolicies() {
        return PolicyManager.getInstance().getAutoscalePolicyList();
    }

    @Override
    public AutoscalePolicy[] getAutoScalingPoliciesByTenant(int tenantId) {
        AutoscalePolicy[] allAutoscalePolicies = getAutoScalingPolicies();
        List<AutoscalePolicy> autoscalePolicies = new ArrayList<AutoscalePolicy>();

        if(allAutoscalePolicies!=null){
            for(AutoscalePolicy autoscalePolicy: allAutoscalePolicies){
                if(autoscalePolicy.getTenantId()==tenantId){
                    autoscalePolicies.add(autoscalePolicy);
                }
            }
        }
        return autoscalePolicies.toArray(new AutoscalePolicy[autoscalePolicies.size()]);
    }

    @Override
    public boolean addAutoScalingPolicy(AutoscalePolicy autoscalePolicy)
            throws AutoScalingPolicyAlreadyExistException {
        String autoscalePolicyId = autoscalePolicy.getId();
        if (PolicyManager.getInstance().getAutoscalePolicyById(autoscalePolicyId) != null && PolicyManager
                .getInstance().getAutoscalePolicyById(autoscalePolicyId).getTenantId() == autoscalePolicy.getTenantId()) {
            String message = String.format("Autoscaling policy already exists: [tenant-id] %d " +
                    "[autoscaling-policy-uuid] %s [autoscaling-policy-id] %s", autoscalePolicy.getTenantId(),
                    autoscalePolicy.getUuid(), autoscalePolicyId);
            log.error(message);
            throw new AutoScalingPolicyAlreadyExistException(message);
        }
        return PolicyManager.getInstance().addAutoscalePolicy(autoscalePolicy);
    }

    @Override
    public boolean updateAutoScalingPolicy(AutoscalePolicy autoscalePolicy) throws InvalidPolicyException {
        return PolicyManager.getInstance().updateAutoscalePolicy(autoscalePolicy);
    }

    @Override
    public boolean removeAutoScalingPolicy(String autoscalePolicyId) throws UnremovablePolicyException,
            PolicyDoesNotExistException {
        if (removableAutoScalerPolicy(autoscalePolicyId)) {
            return PolicyManager.getInstance().removeAutoscalePolicy(autoscalePolicyId);
        } else {
            throw new UnremovablePolicyException(String.format("Autoscaling policy cannot be removed, " +
                    "since it is used in applications: [autoscaling-policy-id] %s", autoscalePolicyId));
        }
    }

    /**
     * Validate the Auto Scalar policy removal
     *
     * @param autoscalePolicyId Auto Scalar policy id boolean
     * @return
     */
    private boolean removableAutoScalerPolicy(String autoscalePolicyId) {
        Collection<ApplicationContext> applicationContexts = AutoscalerContext.getInstance().
                getApplicationContexts();
        for (ApplicationContext applicationContext : applicationContexts) {
            if(applicationContext.getComponents().getCartridgeContexts() != null) {
                for(CartridgeContext cartridgeContext : applicationContext.getComponents().
                        getCartridgeContexts()) {
                    if(autoscalePolicyId.equals(cartridgeContext.getSubscribableInfoContext().
		                    getAutoscalingPolicyUuid())) {
                        return false;
                    }
                }
            }

            if(applicationContext.getComponents().getGroupContexts() != null) {
                return findAutoscalingPolicyInGroup(applicationContext.getComponents().getGroupContexts(),
                        autoscalePolicyId);
            }
        }
        return true;
    }


    private boolean findAutoscalingPolicyInGroup(GroupContext[] groupContexts,
                                                 String autoscalePolicyId) {
        for(GroupContext groupContext : groupContexts) {
            if(groupContext.getCartridgeContexts() != null) {
                for(CartridgeContext cartridgeContext : groupContext.getCartridgeContexts()) {
                    if(autoscalePolicyId.equals(cartridgeContext.getSubscribableInfoContext().
		                    getAutoscalingPolicyUuid())) {
                        return false;
                    }
                }

            }
            if(groupContext.getGroupContexts() != null) {
                return findAutoscalingPolicyInGroup(groupContext.getGroupContexts(),
                        autoscalePolicyId);
            }
        }
        return true;
    }

    /**
     * Validate the deployment policy removal
     *
     * @param deploymentPolicyId
     * @return
     */
    private boolean removableDeploymentPolicy(String deploymentPolicyId) {
        boolean canRemove = true;
        Map<String, Application> applications = ApplicationHolder.getApplications().getApplications();
        for (Application application : applications.values()) {
            List<String> deploymentPolicyIdsReferredInApplication = AutoscalerUtil.
                    getDeploymentPolicyIdsReferredInApplication(application.getUniqueIdentifier());
            for (String deploymentPolicyIdInApp : deploymentPolicyIdsReferredInApplication) {
                if (deploymentPolicyId.equals(deploymentPolicyIdInApp)) {
                    canRemove = false;
                }
            }
        }
        return canRemove;
    }

    @Override
    public AutoscalePolicy getAutoscalingPolicy(String autoscalingPolicyId) {
        return PolicyManager.getInstance().getAutoscalePolicy(autoscalingPolicyId);
    }

	@Override
	public AutoscalePolicy getAutoscalingPolicyForTenant(String autoscalingPolicyId, int tenantId) {
		AutoscalePolicy[] autoscalePolicies=getAutoScalingPolicies();
		for(AutoscalePolicy autoscalePolicy:autoscalePolicies){
			if(autoscalePolicy.getId().equals(autoscalingPolicyId)&&(autoscalePolicy.getTenantId()==tenantId)){
				return autoscalePolicy;
			}
		}
		return null;
	}


	@Override
    public boolean addApplication(ApplicationContext applicationContext)
            throws ApplicationDefinitionException, CartridgeGroupNotFoundException,
            CartridgeNotFoundException {

        if (log.isInfoEnabled()) {
            log.info(String.format("Adding application: [tenant-id] %d [application-uuid] %s [application-name] %s",
                    applicationContext.getTenantId(), applicationContext.getApplicationUuid(), applicationContext.getApplicationId()));
        }

        ApplicationParser applicationParser = new DefaultApplicationParser();
        Application application = applicationParser.parse(applicationContext);
        ApplicationHolder.persistApplication(application);

        List<ApplicationClusterContext> applicationClusterContexts = applicationParser.getApplicationClusterContexts();
        ApplicationClusterContext[] applicationClusterContextsArray = applicationClusterContexts.toArray(
                new ApplicationClusterContext[applicationClusterContexts.size()]);
        applicationContext.getComponents().setApplicationClusterContexts(applicationClusterContextsArray);

        applicationContext.setStatus(ApplicationContext.STATUS_CREATED);
        AutoscalerContext.getInstance().addApplicationContext(applicationContext);

        if (log.isInfoEnabled()) {
            log.info(String.format("Application added successfully: [tenant-id] %d [application-uuid] %s " +
                            "[application-name] %s", applicationContext.getTenantId(),
                    applicationContext.getApplicationUuid(), applicationContext.getName()));
        }
        return true;
    }

    @Override
    public boolean updateApplication(ApplicationContext applicationContext)
            throws ApplicationDefinitionException, CartridgeGroupNotFoundException,
            CartridgeNotFoundException {

        String applicationId = applicationContext.getApplicationUuid();
        if (log.isInfoEnabled()) {
            log.info(String.format("Updating application: [tenant-id] %d [application-uuid] %s [application-name] %s",
                    applicationContext.getTenantId(), applicationContext.getApplicationUuid(), applicationContext.getName()));
        }

        if (AutoscalerContext.getInstance().getApplicationContext(applicationId) == null) {
            String message = "Application not found as ApplicationContext. Please add application before updating it: " +
                    "[application-id] " + applicationId;
            log.error(message);
            throw new ApplicationDefinitionException(message);
        }

        if (ApplicationHolder.getApplications().getApplication(applicationId) == null) {
            String message = "Application not found as Application. Please add application before updating it: " +
                    "[application-id] " + applicationId;
            log.error(message);
            throw new ApplicationDefinitionException(message);
        }

        ApplicationParser applicationParser = new DefaultApplicationParser();
        Application application = applicationParser.parse(applicationContext);

        ApplicationContext existingApplicationContext = AutoscalerContext.getInstance().
                getApplicationContext(applicationId);

        if (existingApplicationContext.getStatus().equals(ApplicationContext.STATUS_DEPLOYED)) {

            //Need to update the application
            AutoscalerUtil.getInstance().updateApplicationsTopology(application);

            //Update the clusterMonitors
            AutoscalerUtil.getInstance().updateClusterMonitor(application);

        }

        applicationContext.setStatus(existingApplicationContext.getStatus());

        List<ApplicationClusterContext> applicationClusterContexts = applicationParser.getApplicationClusterContexts();
        ApplicationClusterContext[] applicationClusterContextsArray = applicationClusterContexts.toArray(
                new ApplicationClusterContext[applicationClusterContexts.size()]);
        applicationContext.getComponents().setApplicationClusterContexts(applicationClusterContextsArray);

        //updating the applicationContext
        AutoscalerContext.getInstance().updateApplicationContext(applicationContext);

        if (log.isInfoEnabled()) {
            log.info(String.format("Application updated successfully: [tenant-id] %d [application-uuid] %s " +
                            "[application-name] %s", applicationContext.getTenantId(),
                    applicationContext.getApplicationUuid(), applicationContext.getName()));
        }
        return true;
    }

    @Override
    public ApplicationContext getApplication(String applicationId) {
        return AutoscalerContext.getInstance().getApplicationContext(applicationId);
    }

    @Override
    public ApplicationContext getApplicationByTenant(String applicationId, int tenantId) {
        return AutoscalerContext.getInstance().getApplicationContextByTenant(applicationId, tenantId);
    }

    @Override
    public boolean existApplication(String applicationId,int tenantId) {
        return AutoscalerContext.getInstance().getApplicationContextByTenant(applicationId,tenantId) != null;
    }

    @Override
    public ApplicationContext[] getApplications() {
        return AutoscalerContext.getInstance().getApplicationContexts().
                toArray(new ApplicationContext[AutoscalerContext.getInstance().
                        getApplicationContexts().size()]);
    }

    @Override
    public ApplicationContext[] getApplicationsByTenant(int tenantId) {
        List<ApplicationContext> applicationContexts = new ArrayList<ApplicationContext>();
        ApplicationContext[] allApps = getApplications();
        if(allApps != null) {
            for (ApplicationContext applicationContext : allApps) {
                if (applicationContext.getTenantId() == tenantId) {
                    applicationContexts.add(applicationContext);
                }
            }
        }
        return applicationContexts.toArray(new ApplicationContext[applicationContexts.size()]);
    }

    @Override
    public boolean deployApplication(String applicationUuid, String applicationPolicyId)
            throws ApplicationDefinitionException {
        try {
            Application application = ApplicationHolder.getApplications().getApplication(applicationUuid);
            if (application == null) {
                throw new RuntimeException(String.format("Application not found: [application-uuid] : %s ",
                        applicationUuid));
            }

            ApplicationContext applicationContext = RegistryManager.getInstance().
                    getApplicationContext(applicationUuid);
            if (applicationContext == null) {
                throw new RuntimeException(String.format("Application context not found: [application-uuid] %s",
                        applicationUuid));
            }

            // validating application policy against the application
            AutoscalerUtil.validateApplicationPolicyAgainstApplication(applicationUuid, applicationPolicyId);

            // Create application clusters in cloud controller and send application created event
            ApplicationBuilder.handleApplicationDeployment(application,
                    applicationContext.getComponents().getApplicationClusterContexts());

            // Setting application policy id in application object
            try {
                ApplicationHolder.acquireWriteLock();
                application = ApplicationHolder.getApplications().getApplication(applicationUuid);
                application.setApplicationPolicyId(applicationPolicyId);
                ApplicationHolder.persistApplication(application);
            } finally {
                ApplicationHolder.releaseWriteLock();
            }

            // adding network partition algorithm context to registry
            NetworkPartitionAlgorithmContext algorithmContext =
                    new NetworkPartitionAlgorithmContext(applicationUuid, applicationPolicyId, 0);
            AutoscalerContext.getInstance().addNetworkPartitionAlgorithmContext(algorithmContext);

            if (!applicationContext.isMultiTenant()) {
                // Add application signup for single tenant applications
                addApplicationSignUp(applicationContext, application.getKey(),
                        findApplicationClusterIds(application));
            }
            applicationContext.setStatus(ApplicationContext.STATUS_DEPLOYED);
            AutoscalerContext.getInstance().updateApplicationContext(applicationContext);

            log.info("Waiting for application clusters to be created: [application-uuid] " + applicationUuid);
            return true;
        } catch (Exception e) {
            ApplicationContext applicationContext = RegistryManager.getInstance().
                    getApplicationContext(applicationUuid);
            if (applicationContext != null) {
                // Revert application status
                applicationContext.setStatus(ApplicationContext.STATUS_CREATED);
                AutoscalerContext.getInstance().updateApplicationContext(applicationContext);
            }
            String message = String.format("Application deployment failed: [application-uuid] %s ", applicationUuid);
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
    }

    /**
     * Find application cluster ids.
     *
     * @param application
     * @return
     */
    private List<String> findApplicationClusterIds(Application application) {
        List<String> clusterIds = new ArrayList<String>();
        for (ClusterDataHolder clusterDataHolder : application.getClusterDataRecursively()) {
            clusterIds.add(clusterDataHolder.getClusterId());
        }
        return clusterIds;
    }

    /**
     * Add application signup.
     *
     * @param applicationContext
     * @param applicationKey
     * @param clusterIds
     */
    private void addApplicationSignUp(ApplicationContext applicationContext, String applicationKey,
                                      List<String> clusterIds) {

        try {
            if (log.isInfoEnabled()) {
                log.info(String.format("Adding application signup: [tenant-id] %d [application-uuid] %s " +
                                "[application-id] %s", applicationContext.getTenantId(),
                        applicationContext.getApplicationUuid(), applicationContext.getApplicationId()));
            }

            ComponentContext components = applicationContext.getComponents();
            if (components != null) {
                ApplicationSignUp applicationSignUp = new ApplicationSignUp();
                applicationSignUp.setApplicationId(applicationContext.getApplicationUuid());
                applicationSignUp.setTenantId(applicationContext.getTenantId());
                String[] clusterIdsArray = clusterIds.toArray(new String[clusterIds.size()]);
                applicationSignUp.setClusterIds(clusterIdsArray);

                List<ArtifactRepository> artifactRepositoryList = new ArrayList<ArtifactRepository>();
                CartridgeContext[] cartridgeContexts = components.getCartridgeContexts();
                if (cartridgeContexts != null) {
                    updateArtifactRepositoryList(artifactRepositoryList, cartridgeContexts);
                }

                if (components.getGroupContexts() != null) {
                    CartridgeContext[] cartridgeContextsOfGroups = getCartridgeContextsOfGroupsRecursively(
                            components.getGroupContexts());
                    if (cartridgeContextsOfGroups != null) {
                        updateArtifactRepositoryList(artifactRepositoryList, cartridgeContextsOfGroups);
                    }
                }

                ArtifactRepository[] artifactRepositoryArray = artifactRepositoryList.toArray(
                        new ArtifactRepository[artifactRepositoryList.size()]);
                applicationSignUp.setArtifactRepositories(artifactRepositoryArray);

                // Encrypt artifact repository passwords
                encryptRepositoryPasswords(applicationSignUp, applicationKey);

                StratosManagerServiceClient serviceClient = StratosManagerServiceClient.getInstance();
                serviceClient.addApplicationSignUp(applicationSignUp);

                if (log.isInfoEnabled()) {
                    log.info(String.format("Application signup added successfully: [tenant-id] %d [application-uuid] " +
                                    "%s [application-id] %s", applicationContext.getTenantId(),
                            applicationContext.getApplicationUuid(), applicationContext.getApplicationId()));
                }
            }
        } catch (Exception e) {
            String message = String.format("Could not add application signup: [tenant-id] %d [application-uuid] %s " +
            "[application-id] %s", applicationContext.getTenantId(), applicationContext.getApplicationUuid(),
                    applicationContext.getApplicationId());
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
    }


    private CartridgeContext[] getCartridgeContextsOfGroupsRecursively(GroupContext[] passedGroupContexts) {

        List<CartridgeContext> cartridgeContextsList = new ArrayList<CartridgeContext>();

        for (GroupContext groupContext : passedGroupContexts) {
            if (groupContext.getCartridgeContexts() != null) {
                for (CartridgeContext cartridgeContext : groupContext.getCartridgeContexts()) {

                    cartridgeContextsList.add(cartridgeContext);
                }
            }
            if (groupContext.getGroupContexts() != null) {
                for (CartridgeContext cartridgeContext :
                        getCartridgeContextsOfGroupsRecursively(groupContext.getGroupContexts())) {

                    cartridgeContextsList.add(cartridgeContext);
                }
            }
        }
        return cartridgeContextsList.toArray(new CartridgeContext[0]);
    }

    private void removeApplicationSignUp(ApplicationContext applicationContext) {
        try {
            if (log.isInfoEnabled()) {
                log.info(String.format("Removing application signup: [tenant-id] %d [application-uuid] %s " +
                                "[application-id] %s", applicationContext.getTenantId(),
                        applicationContext.getApplicationUuid(), applicationContext.getApplicationId()));
            }

            StratosManagerServiceClient serviceClient = StratosManagerServiceClient.getInstance();

            ApplicationSignUp applicationSignUp[] = serviceClient.getApplicationSignUps(applicationContext.getApplicationUuid());
            if (applicationSignUp != null) {
                for (ApplicationSignUp appSignUp : applicationSignUp) {
                    if (appSignUp != null) {
                        serviceClient.removeApplicationSignUp(appSignUp.getApplicationId(), appSignUp.getTenantId());
                    }
                }
            }

        } catch (Exception e) {
            String message = "Could not remove application signup(s): [application-id]";
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
    }

    /**
     * Encrypt artifact repository passwords.
     *
     * @param applicationSignUp
     * @param applicationKey
     */
    private void encryptRepositoryPasswords(ApplicationSignUp applicationSignUp, String applicationKey) {
        if (applicationSignUp.getArtifactRepositories() != null) {
            for (ArtifactRepository artifactRepository : applicationSignUp.getArtifactRepositories()) {
                String repoPassword = artifactRepository.getRepoPassword();
                if ((artifactRepository != null) && (StringUtils.isNotBlank(repoPassword))) {
                    String encryptedRepoPassword = CommonUtil.encryptPassword(repoPassword,
                            applicationKey);
                    artifactRepository.setRepoPassword(encryptedRepoPassword);

                    if (log.isDebugEnabled()) {
                        log.debug(String.format("Artifact repository password encrypted: [application-id] %s " +
                                        "[tenant-id] %d [repo-url] %s", applicationSignUp.getApplicationId(),
                                applicationSignUp.getTenantId(), artifactRepository.getRepoUrl()));
                    }
                }
            }
        }
    }

    private void updateArtifactRepositoryList(List<ArtifactRepository> artifactRepositoryList, CartridgeContext[] cartridgeContexts) {

        if (cartridgeContexts == null) {
            return;
        }

        for (CartridgeContext cartridgeContext : cartridgeContexts) {
            SubscribableInfoContext subscribableInfoContext = cartridgeContext.getSubscribableInfoContext();
            ArtifactRepositoryContext artifactRepositoryContext = subscribableInfoContext.getArtifactRepositoryContext();
            if (artifactRepositoryContext != null) {

                ArtifactRepository artifactRepository = new ArtifactRepository();
                artifactRepository.setCartridgeType(cartridgeContext.getUuid());
                artifactRepository.setAlias(subscribableInfoContext.getAlias());
                artifactRepository.setRepoUrl(artifactRepositoryContext.getRepoUrl());
                artifactRepository.setPrivateRepo(artifactRepositoryContext.isPrivateRepo());
                artifactRepository.setRepoUsername(artifactRepositoryContext.getRepoUsername());
                artifactRepository.setRepoPassword(artifactRepositoryContext.getRepoPassword());

                artifactRepositoryList.add(artifactRepository);
            }
        }
    }

    public boolean undeployApplication(String applicationId, boolean force) {

        AutoscalerContext asCtx = AutoscalerContext.getInstance();
        ApplicationMonitor appMonitor = asCtx.getAppMonitor(applicationId);

        if (appMonitor == null) {
            log.info(String.format("Could not find application monitor for the application %s, " +
                    "hence returning", applicationId));
            return false;
        }
        if (!force) {
            // Graceful un-deployment flow
            if (appMonitor.isTerminating()) {
                log.info("Application monitor is already in terminating, graceful " +
                        "un-deployment is has already been attempted thus not invoking again");
                return false;
            } else {
                log.info(String.format("Gracefully un-deploying the [application] %s ", applicationId));
                appMonitor.setTerminating(true);
                undeployApplicationGracefully(applicationId);
            }
        } else {
            // force un-deployment flow
            if (appMonitor.isTerminating()) {

                if (appMonitor.isForce()) {
                    log.warn(String.format("Force un-deployment is already in progress, " +
                            "hence not invoking again " +
                            "[application-id] %s", applicationId));
                    return false;
                } else {
                    log.info(String.format("Previous graceful un-deployment is in progress for " +
                            "[application-id] %s , thus  terminating instances directly",
                            applicationId));
                    appMonitor.setForce(true);
                    terminateAllMembersAndClustersForcefully(applicationId);
                }
            } else {
                log.info(String.format("Forcefully un-deploying the application " + applicationId));
                appMonitor.setTerminating(true);
                appMonitor.setForce(true);
                undeployApplicationGracefully(applicationId);
            }
        }
        return true;
    }

    private void undeployApplicationGracefully(String applicationId) {
        try {
            if (log.isInfoEnabled()) {
                log.info("Starting to undeploy application: [application-id] " + applicationId);
            }

            ApplicationContext applicationContext = AutoscalerContext.getInstance().
                    getApplicationContext(applicationId);
            Application application = ApplicationHolder.getApplications().getApplication(applicationId);
            if ((applicationContext == null) || (application == null)) {
                String msg = String.format("Application not found: [application-id] %s", applicationId);
                throw new RuntimeException(msg);
            }

            if (!applicationContext.getStatus().equals(ApplicationContext.STATUS_DEPLOYED)) {
                String message = String.format("Application is not deployed: [tenant-id] %d " +
                        "[application-uuid] %s [application-id] %s", applicationContext.getTenantId(),
                        applicationContext.getApplicationUuid(), applicationId);
                log.error(message);
                throw new RuntimeException(message);
            }
            applicationContext.setStatus(ApplicationContext.STATUS_UNDEPLOYING);
            // Remove application signup(s) in stratos manager
            removeApplicationSignUp(applicationContext);

            // Remove network partition algorithm context
            AutoscalerContext.getInstance().removeNetworkPartitionAlgorithmContext(applicationId);

            ApplicationBuilder.handleApplicationUnDeployedEvent(applicationId);

            if (log.isInfoEnabled()) {
                log.info(String.format("Application undeployment process started: [tenant-id] %d " +
                                "[application-uuid] %s [application-id] %s", applicationContext.getTenantId(),
                        applicationContext.getApplicationUuid(), applicationId));
            }
        } catch (Exception e) {
            String message = "Could not start application undeployment process: [application-id] " + applicationId;
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
    }

    @Override
    public boolean deleteApplication(String applicationId) {
        try {
            ApplicationContext applicationContext = AutoscalerContext.getInstance().getApplicationContext(applicationId);
            Application application = ApplicationHolder.getApplications().getApplication(applicationId);
            if ((applicationContext == null) || (application == null)) {
                String msg = String.format("Application cannot be deleted, application not found: [application-id] %s",
                        applicationId);
                throw new RuntimeException(msg);
            }


            if (ApplicationContext.STATUS_DEPLOYED.equals(applicationContext.getStatus())) {
                String msg = String.format("Application is in deployed state, please undeploy it before deleting: " +
                        "[application-id] %s", applicationId);
                throw new AutoScalerException(msg);
            }

            if (application.getInstanceContextCount() > 0) {
                String message = String.format("Application cannot be deleted, undeployment process is still in " +
                                "progress: [tenant-id] %d [application-uuid] %s [application-id] %s",
                        applicationContext.getTenantId(), applicationContext.getApplicationUuid(), applicationId);
                log.error(message);
                throw new RuntimeException(message);
            }

            ApplicationBuilder.handleApplicationRemoval(applicationId);
            log.info(String.format("Application deleted successfully: [tenant-id] %d [application-uuid] %s " +
                            "[application-id] %s", applicationContext.getTenantId(),
                    applicationContext.getApplicationUuid(), applicationId));
        } catch (Exception e) {
            String message = String.format("Could not delete application: [application-id] %s", applicationId);
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
        return true;
    }

    public boolean updateClusterMonitor(String clusterId, Properties properties) throws InvalidArgumentException {
        if (log.isDebugEnabled()) {
            log.debug(String.format("Updating Cluster monitor [cluster-id] %s ", clusterId));
        }
        AutoscalerContext asCtx = AutoscalerContext.getInstance();
        ClusterMonitor monitor = asCtx.getClusterMonitor(clusterId);

        if (monitor != null) {
            monitor.handleDynamicUpdates(properties);
        } else {
            log.debug(String.format("Updating Cluster monitor failed: Cluster monitor [cluster-id] %s not found.",
                    clusterId));
        }
        return true;
    }

    public boolean addServiceGroup(ServiceGroup servicegroup) throws InvalidServiceGroupException {

        if (servicegroup == null || StringUtils.isEmpty(servicegroup.getUuid())) {
            String msg = "Cartridge group is null or cartridge group name is empty.";
            log.error(msg);
            throw new InvalidServiceGroupException(msg);
        }

        if (log.isInfoEnabled()) {
            log.info(String.format("Adding cartridge group: [tenant-id] %d [group-uuid] %s [group-name] %s",
                    servicegroup.getTenantId(), servicegroup.getUuid(), servicegroup.getName()));
        }

        String groupUuid = servicegroup.getUuid();
        if (RegistryManager.getInstance().serviceGroupExist(groupUuid)) {
            throw new InvalidServiceGroupException("Cartridge group already exists.");
        }

        if (log.isDebugEnabled()) {
            log.debug(String.format("Adding cartridge group %s", servicegroup.getUuid()));
        }

        String[] subGroups = servicegroup.getCartridges();
        if (log.isDebugEnabled()) {
            log.debug("SubGroups" + Arrays.toString(subGroups));
            if (subGroups != null) {
                log.debug("subGroups:size" + subGroups.length);
            } else {
                log.debug("subGroups: are null");
            }
        }

        Dependencies dependencies = servicegroup.getDependencies();
        if (log.isDebugEnabled()) {
            log.debug("Dependencies" + dependencies);
        }

        if (dependencies != null) {
            String[] startupOrders = dependencies.getStartupOrders();
            AutoscalerUtil.validateStartupOrders(groupUuid, startupOrders);

            if (log.isDebugEnabled()) {
                log.debug("StartupOrders " + Arrays.toString(startupOrders));

                if (startupOrders != null) {
                    log.debug("StartupOrder:size  " + startupOrders.length);
                } else {
                    log.debug("StartupOrder: is null");
                }
            }

            String[] scalingDependents = dependencies.getScalingDependants();
            AutoscalerUtil.validateScalingDependencies(groupUuid, scalingDependents);

            if (log.isDebugEnabled()) {
                log.debug("ScalingDependent " + Arrays.toString(scalingDependents));

                if (scalingDependents != null) {
                    log.debug("ScalingDependents:size " + scalingDependents.length);
                } else {
                    log.debug("ScalingDependent: is null");
                }
            }
        }

        RegistryManager.getInstance().persistServiceGroup(servicegroup);
        if (log.isInfoEnabled()) {
            log.info(String.format("Cartridge group successfully added: [tenant-id] %d [group-uuid] %s [group-name] %s",
                    servicegroup.getTenantId(), servicegroup.getUuid(), servicegroup.getName()));
        }
        return true;
    }

    public boolean updateServiceGroup(ServiceGroup cartridgeGroup) throws InvalidServiceGroupException {

        if (cartridgeGroup == null || StringUtils.isEmpty(cartridgeGroup.getUuid())) {
            String msg = "Cartridge group cannot be null or service name cannot be empty.";
            log.error(msg);
            throw new IllegalArgumentException(msg);
        }

        if (log.isInfoEnabled()) {
            log.info(String.format("Updating cartridge group: [tenant-id] %d [group-uuid] %s [group-name] %s",
                    cartridgeGroup.getTenantId(), cartridgeGroup.getUuid(), cartridgeGroup.getName()));
        }

        String groupName = cartridgeGroup.getUuid();
        if (!RegistryManager.getInstance().serviceGroupExist(groupName)) {
            throw new InvalidServiceGroupException("Cartridge group does not exist.");
        }

        Dependencies dependencies = cartridgeGroup.getDependencies();
        if (dependencies != null) {
            String[] startupOrders = dependencies.getStartupOrders();
            AutoscalerUtil.validateStartupOrders(groupName, startupOrders);

            if (log.isDebugEnabled()) {
                log.debug("StartupOrders " + Arrays.toString(startupOrders));

                if (startupOrders != null) {
                    log.debug("StartupOrder:size  " + startupOrders.length);
                } else {
                    log.debug("StartupOrder: is null");
                }
            }

            String[] scalingDependents = dependencies.getScalingDependants();
            AutoscalerUtil.validateScalingDependencies(groupName, scalingDependents);

            if (log.isDebugEnabled()) {
                log.debug("ScalingDependent " + Arrays.toString(scalingDependents));

                if (scalingDependents != null) {
                    log.debug("ScalingDependents:size " + scalingDependents.length);
                } else {
                    log.debug("ScalingDependent: is null");
                }
            }
        }

        try {
            RegistryManager.getInstance().updateServiceGroup(cartridgeGroup);
        } catch (org.wso2.carbon.registry.core.exceptions.RegistryException e) {
            String message = (String.format("Cannot update cartridge group: [group-name] %s",
                    cartridgeGroup.getName()));
            throw new RuntimeException(message, e);
        }

        if (log.isInfoEnabled()) {
            log.info(String.format("Cartridge group successfully updated: [tenant-id] %d [group-uuid] %s [group-name] %s",
                    cartridgeGroup.getTenantId(), cartridgeGroup.getUuid(), cartridgeGroup.getName()));
        }
        return true;
    }

    @Override
    public boolean removeServiceGroup(String groupName) throws CartridgeGroupNotFoundException {
        try {
            if (log.isInfoEnabled()) {
                log.info(String.format("Starting to remove cartridge group: [group-name] %s", groupName));
            }
            if (RegistryManager.getInstance().serviceGroupExist(groupName)) {
                RegistryManager.getInstance().removeServiceGroup(groupName);
                if (log.isInfoEnabled()) {
                    log.info(String.format("Cartridge group removed: [group-name] %s", groupName));
                }
            } else {
                String msg = String.format("Cartridge group not found: [group-name] %s", groupName);
                if (log.isWarnEnabled()) {
                    log.warn(msg);
                }
                throw new CartridgeGroupNotFoundException(msg);
            }
        } catch (org.wso2.carbon.registry.core.exceptions.RegistryException e) {
            String message = "Could not remove cartridge group: " + groupName;
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
        return true;
    }

    public ServiceGroup getServiceGroup(String uuid) {
        if (StringUtils.isEmpty(uuid)) {
            return null;
        }
        try {
            return RegistryManager.getInstance().getServiceGroup(uuid);
        } catch (Exception e) {
            throw new AutoScalerException("Error occurred while retrieving cartridge group", e);
        }
    }

	public ServiceGroup getServiceGroupByTenant(String name,int tenantId) {
        ServiceGroup selectedGroup=null;
		if (StringUtils.isEmpty(name)) {
			return null;
		}
		try {
            ServiceGroup[] serviceGroups=getServiceGroupsByTenant(tenantId);
            for(ServiceGroup serviceGroup:serviceGroups) {
                if (serviceGroup.getName().equals(name)) {
                    selectedGroup = serviceGroup;
                    return selectedGroup;
                }
            }
            for(ServiceGroup serviceGroup:serviceGroups) {
                ServiceGroup[] innerGroups=serviceGroup.getGroups();
                   while(innerGroups!=null){
                       for(ServiceGroup nestedGroup:innerGroups) {
                           if(nestedGroup.getName().equals(name)){
                               return nestedGroup;
                           }
                           else {
                               innerGroups = nestedGroup.getGroups();
                           }
                       }
                   }
            }
            return selectedGroup;
		} catch (Exception e) {
			throw new AutoScalerException("Error occurred while retrieving cartridge group", e);
		}
	}

    public ServiceGroup getOuterServiceGroupByTenant(String name, int tenantId) {
        ServiceGroup outerGroup = null;
        if (StringUtils.isEmpty(name)) {
            return null;
        }
        try {
            ServiceGroup[] serviceGroups = getServiceGroupsByTenant(tenantId);
            for (ServiceGroup serviceGroup : serviceGroups) {
                if (serviceGroup.getName().equals(name)) {
                    outerGroup = serviceGroup;
                    break;
                }
            }
            return outerGroup;
        } catch (Exception e) {
            throw new AutoScalerException("Error occurred while retrieving cartridge group", e);
        }
    }

    public DeploymentPolicy getDeploymentPolicyByTenant(String deploymentPolicyId, int tenantId) {
        DeploymentPolicy[] deploymentPolicies = getDeploymentPolicies();
        for(DeploymentPolicy deploymentPolicy : deploymentPolicies) {
            if(deploymentPolicy.getId().equals(deploymentPolicyId ) && deploymentPolicy.getTenantId() == tenantId) {
                return deploymentPolicy;
            }
        }
        return null;
    }

    @Override
    public String findClusterId(String applicationId, String alias) {
        try {
            Application application = ApplicationManager.getApplications().getApplication(applicationId);
            if (application != null) {

                ClusterDataHolder clusterData = application.getClusterDataHolderRecursivelyByAlias(alias);
                if (clusterData != null) {
                    return clusterData.getClusterId();
                }
            }
            return null;
        } catch (Exception e) {
            String message = String.format("Could not find cluster id: [application-id] %s [alias] %s",
                    applicationId, alias);
            throw new AutoScalerException(message, e);
        }
    }

    public ServiceGroup[] getServiceGroups() throws AutoScalerException {
        return RegistryManager.getInstance().getServiceGroups();
    }

    public ServiceGroup[] getServiceGroupsByTenant(int tenantId) {
        List<ServiceGroup> serviceGroups = new ArrayList<ServiceGroup>();
        ServiceGroup[] allGroups = getServiceGroups();
        if(allGroups != null) {
            for (ServiceGroup serviceGroup : allGroups) {
                if (serviceGroup.getTenantId() == tenantId) {
                    serviceGroups.add(serviceGroup);
                }
            }
        }
        return serviceGroups.toArray(new ServiceGroup[serviceGroups.size()]);
    }

    public boolean serviceGroupExist(String serviceName) {
        return false;
    }

    public boolean undeployServiceGroup(String name) throws AutoScalerException {
        try {
            RegistryManager.getInstance().removeServiceGroup(name);
        } catch (RegistryException e) {
            throw new AutoScalerException("Error occurred while removing the cartridge groups", e);
        }
        return true;
    }

    @Override
    public String[] getApplicationNetworkPartitions(String applicationId)
            throws AutoScalerException {
        List<String> networkPartitionIds = AutoscalerUtil.getNetworkPartitionIdsReferedInApplication(applicationId);
        if (networkPartitionIds == null) {
            return null;
        }
        return networkPartitionIds.toArray(new String[networkPartitionIds.size()]);
    }

    @Override
    public boolean addApplicationPolicy(ApplicationPolicy applicationPolicy)
            throws RemoteException, InvalidApplicationPolicyException, ApplicationPolicyAlreadyExistsException {

        // validating application policy
        AutoscalerUtil.validateApplicationPolicy(applicationPolicy);

        if (log.isInfoEnabled()) {
            log.info(String.format("Adding application policy: [tenant-id] %d [application-policy-uuid] %s " +
                    "[application-policy-id] %s", applicationPolicy.getTenantId(), applicationPolicy.getUuid(),
                    applicationPolicy.getId()));
        }
        if (log.isDebugEnabled()) {
            log.debug("Application policy definition: " + applicationPolicy.toString());
        }

        String applicationPolicyId = applicationPolicy.getId();
        boolean isExist = PolicyManager.getInstance().checkApplicationPolicyInTenant
                (applicationPolicy.getId(), applicationPolicy.getTenantId());
        if (isExist) {
            String message = String.format("Application policy already exists: [tenant-id] %d " +
                    "[application-policy-uuid] %s [application-policy-id] %s", applicationPolicy.getTenantId(),
                    applicationPolicy.getUuid(), applicationPolicy.getId());
            log.error(message);
            throw new ApplicationPolicyAlreadyExistsException(message);
        }

        String applicationPolicyUuid = applicationPolicy.getUuid();
        if (PolicyManager.getInstance().getApplicationPolicyByUuid(applicationPolicyUuid) != null && PolicyManager
                .getInstance().getApplicationPolicyByUuid(applicationPolicyUuid).getTenantId() == applicationPolicy
                .getTenantId()) {
            String message = String.format("Application policy already exists: [tenant-id] %d " +
                            "[application-policy-uuid] %s [application-policy-id] %s", applicationPolicy.getTenantId(),
                    applicationPolicy.getUuid(), applicationPolicy.getId());
            log.error(message);
            throw new ApplicationPolicyAlreadyExistsException(message);
        }

        // Add application policy to the registry
        PolicyManager.getInstance().addApplicationPolicy(applicationPolicy);
        return true;
    }

    @Override
    public ApplicationPolicy getApplicationPolicyByUuid(String applicationPolicyUuid) {
        return PolicyManager.getInstance().getApplicationPolicyByUuid(applicationPolicyUuid);
    }

    @Override
    public ApplicationPolicy getApplicationPolicy(String applicationPolicyId, int tenantId) {
        ApplicationPolicy[] applicationPolicies = getApplicationPolicies();
        ApplicationPolicy applicationPolicy = null;
        if (applicationPolicies != null) {
            for (ApplicationPolicy applicationPolicy1 : applicationPolicies) {
                if (applicationPolicy1.getTenantId() == tenantId && applicationPolicy1.getId().equals
                        (applicationPolicyId)) {
                    applicationPolicy = applicationPolicy1;
                }
            }
        }
        return applicationPolicy;
    }

	@Override
	public ApplicationPolicy getApplicationPolicyByTenant(String applicationPolicyId,int tenantId) {
		ApplicationPolicy[] applicationPolicies=getApplicationPolicies();
		for(ApplicationPolicy applicationPolicy:applicationPolicies){
			if(applicationPolicy.getId().equals(applicationPolicyId)&&applicationPolicy.getTenantId()==tenantId){
				return applicationPolicy;
			}
		}
		return null;
	}

    @Override
    public boolean removeApplicationPolicy(String applicationPolicyId) throws InvalidPolicyException, UnremovablePolicyException {

        if (removableApplicationPolicy(applicationPolicyId)) {
            return PolicyManager.getInstance().removeApplicationPolicy(applicationPolicyId);
        } else {
            throw new UnremovablePolicyException(String.format("Application policy cannot be removed, " +
                    "since it is used in applications: [application-policy-id] %s", applicationPolicyId));
        }
    }

    private boolean removableApplicationPolicy(String applicationPolicyId) {

        for (Application application : ApplicationHolder.getApplications().getApplications().values()) {
            if (applicationPolicyId.equals(application.getApplicationPolicyId())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean updateApplicationPolicy(ApplicationPolicy applicationPolicy)
            throws InvalidApplicationPolicyException, RemoteException, ApplicatioinPolicyNotExistsException {

        if (applicationPolicy == null) {
            String msg = "Application policy is null";
            log.error(msg);
            throw new InvalidApplicationPolicyException(msg);
        }

        String applicationPolicyId = applicationPolicy.getUuid();
        ApplicationPolicy existingApplicationPolicy = PolicyManager.getInstance().getApplicationPolicy(applicationPolicyId);
        if (existingApplicationPolicy == null) {
            String msg = String.format("No such application policy found [application-policy-id] %s", applicationPolicyId);
            log.error(msg);
            throw new ApplicatioinPolicyNotExistsException(msg);
        }

        // validating application policy
        AutoscalerUtil.validateApplicationPolicy(applicationPolicy);

        // updating application policy
        PolicyManager.getInstance().updateApplicationPolicy(applicationPolicy);
        return true;
    }

    @Override
    public ApplicationPolicy[] getApplicationPolicies() {
        return PolicyManager.getInstance().getApplicationPolicies();
    }


    @Override
    public ApplicationPolicy[] getApplicationPoliciesByTenant(int tenantId) {
        ApplicationPolicy[] allApplicationPolicies = getApplicationPolicies();
        List<ApplicationPolicy> applicationPolicies = new ArrayList<ApplicationPolicy>();

        if (allApplicationPolicies != null) {
            for (ApplicationPolicy applicationPolicy : allApplicationPolicies) {
                if (applicationPolicy.getTenantId() == tenantId) {
                    applicationPolicies.add(applicationPolicy);
                }
            }
        }
        return applicationPolicies.toArray(new ApplicationPolicy[applicationPolicies.size()]);
    }

	@Override
	public boolean validateNetworkPartitionWithApplication(String networkPartitionId, int tenantId)
			throws PartitionValidationException {

		ApplicationContext[] applicationContexts = getApplicationsByTenant(tenantId);
		if (applicationContexts != null) {
			for (ApplicationContext applicationContext : applicationContexts) {
				if (applicationContext != null) {
					String[] networkPartitions = getApplicationNetworkPartitions(
							applicationContext.getApplicationUuid());
					if (networkPartitions != null) {
						for (int i = 0; i < networkPartitions.length; i++) {
							if (networkPartitions[i].equals(networkPartitionId)) {
								String message = String.format("Cannot remove the network partition since it is used" +
                                                " in application: [tenant-id] %d [network-partition-id] %s " +
                                                "[application-uuid] %s [application-id] %s", tenantId,
                                        networkPartitionId, applicationContext.getApplicationUuid(),
                                        applicationContext.getApplicationId());
								log.error(message);
								throw new PartitionValidationException(message);
							}
						}
					}
				}
			}
		}

		return true;
	}

	private void terminateAllMembersAndClustersForcefully(String applicationId) {
        if (StringUtils.isEmpty(applicationId)) {
            throw new IllegalArgumentException("Application Id cannot be empty");
        }

        Application application;
        try {
            ApplicationManager.acquireReadLockForApplication(applicationId);
            application = ApplicationManager.getApplications().getApplication(applicationId);
            if (application == null) {
                log.warn(String.format("Could not find application, thus no members to be terminated " +
                        "[application-id] %s", applicationId));
                return;
            }
        } finally {
            ApplicationManager.releaseReadLockForApplication(applicationId);
        }

        Set<ClusterDataHolder> allClusters = application.getClusterDataRecursively();
        for (ClusterDataHolder clusterDataHolder : allClusters) {
            String serviceUuid = clusterDataHolder.getServiceUuid();
            String clusterId = clusterDataHolder.getClusterId();

            Cluster cluster;
            try {
                TopologyManager.acquireReadLockForCluster(serviceUuid, clusterId);
                cluster = TopologyManager.getTopology().getService(serviceUuid).getCluster(clusterId);
            } finally {
                TopologyManager.releaseReadLockForCluster(serviceUuid, clusterId);
            }

            //If there are no members in cluster Instance, send cluster Terminated Event
            //Stopping the cluster monitor thread
            ClusterMonitor clusterMonitor = AutoscalerContext.getInstance().
                    getClusterMonitor(clusterId);
            if(clusterMonitor != null) {
                clusterMonitor.destroy();
            } else {
                if(log.isDebugEnabled()) {
                    log.debug(String.format("Cluster monitor cannot be found for [application] %s " +
                            "[cluster] %s", applicationId, clusterId));
                }
            }
            if(cluster != null) {
                Collection<ClusterInstance> allClusterInstances = cluster.getClusterInstances();
                for (ClusterInstance clusterInstance : allClusterInstances) {
                    ClusterStatusEventPublisher.sendClusterTerminatedEvent(applicationId, cluster.getServiceName(),
                            clusterId, clusterInstance.getInstanceId());
                }

                List<String> memberListToTerminate = new LinkedList<String>();
                for (Member member : cluster.getMembers()) {
                    memberListToTerminate.add(member.getMemberId());
                }

                for (String memberIdToTerminate : memberListToTerminate) {
                    try {
                        log.info(String.format("Terminating member forcefully [member-id] %s of the cluster [cluster-id] %s " +
                                "[application-id] %s", memberIdToTerminate, clusterId, application));
                        AutoscalerCloudControllerClient.getInstance().terminateInstanceForcefully(memberIdToTerminate);
                    } catch (Exception e) {
                        log.error(String.format("Forceful termination of member %s has failed, but continuing forceful " +
                                "deletion of other members", memberIdToTerminate));
                    }
                }
            }

        }

    }


    @Override
    public boolean addDeployementPolicy(DeploymentPolicy deploymentPolicy) throws RemoteException,
            InvalidDeploymentPolicyException, DeploymentPolicyAlreadyExistsException {

        validateDeploymentPolicy(deploymentPolicy);

        if (log.isInfoEnabled()) {
            log.info(String.format("Adding deployment policy: [tenant-id] %d [deployment-policy-uuid] %s " +
                    "[deployment-policy-id] %s ",deploymentPolicy.getTenantId(), deploymentPolicy.getUuid(),
                    deploymentPolicy.getId()));
        }
        if (log.isDebugEnabled()) {
            log.debug("Deployment policy definition: " + deploymentPolicy.toString());
        }

        String deploymentPolicyUuid = deploymentPolicy.getUuid();
        if (PolicyManager.getInstance().getDeploymentPolicy(deploymentPolicyUuid) != null) {
            String message = String.format("Deployment policy already exists: [tenant-id] %d [deployment-policy-uuid]" +
                            " %s [deployment-policy-id] %s ",deploymentPolicy.getTenantId(), deploymentPolicy.getUuid(),
                    deploymentPolicy.getId());
            log.error(message);
            throw new DeploymentPolicyAlreadyExistsException(message);
        }

        String deploymentPolicyId = deploymentPolicy.getId();
        if (PolicyManager.getInstance().getDeploymentPolicyById(deploymentPolicyId) != null && PolicyManager
                .getInstance().getDeploymentPolicyById(deploymentPolicyId).getTenantId() == deploymentPolicy.getTenantId()) {
            String message = String.format("Deployment policy already exists: [tenant-id] %d [deployment-policy-uuid]" +
                            " %s [deployment-policy-id] %s ",deploymentPolicy.getTenantId(), deploymentPolicy.getUuid(),
                    deploymentPolicy.getId());
            log.error(message);
            throw new DeploymentPolicyAlreadyExistsException(message);
        }

        // Add cartridge to the cloud controller context and persist
        PolicyManager.getInstance().addDeploymentPolicy(deploymentPolicy);

        if (log.isInfoEnabled()) {
            log.info(String.format("Successfully added deployment policy: [tenant-id] %d [deployment-policy-uuid]" +
                            " %s [deployment-policy-id] %s ",deploymentPolicy.getTenantId(), deploymentPolicy.getUuid(),
                    deploymentPolicy.getId()));
        }
        return true;
    }

    private void validateDeploymentPolicy(DeploymentPolicy deploymentPolicy) throws
            InvalidDeploymentPolicyException, RemoteException {

        // deployment policy can't be null
        if (null == deploymentPolicy) {
            String msg = "Deployment policy is null";
            log.error(msg);
            throw new InvalidDeploymentPolicyException(msg);
        }

        if (log.isInfoEnabled()) {
            log.info(String.format("Validating deployment policy %s", deploymentPolicy.toString()));
        }

        // deployment policy id can't be null or empty
        String deploymentPolicyUuid = deploymentPolicy.getUuid();
        String deploymentPolicyId = deploymentPolicy.getId();
        if (StringUtils.isBlank(deploymentPolicyId)) {
            String msg = String.format("Deployment policy id is blank");
            log.error(msg);
            throw new InvalidDeploymentPolicyException(msg);
        }

        // deployment policy should contain at least one network partition reference
        if (null == deploymentPolicy.getNetworkPartitionRefs() || deploymentPolicy.getNetworkPartitionRefs().length == 0) {
            String message = String.format("Deployment policy does not have any network partition references: " +
                    "[tenant-id] %d [deployment-policy-uuid] %s [deployment-policy-id] %s",
                    deploymentPolicy.getTenantId(), deploymentPolicyUuid, deploymentPolicyId);
            log.error(message);
            throw new InvalidDeploymentPolicyException(message);
        }

        // validate each network partition references
        for (NetworkPartitionRef networkPartitionRef : deploymentPolicy.getNetworkPartitionRefs()) {
            // network partition id can't be null or empty
            //String networkPartitionUuid = networkPartitionRef.getUuid();
            String networkPartitionId = networkPartitionRef.getId();
            if (StringUtils.isBlank(networkPartitionId)) {
                String message = String.format("Network partition id is blank: [tenant-id] " +
                        "%d [deployment-policy-uuid] %s [deployment-policy-id] %s", deploymentPolicy.getTenantId(),
                        deploymentPolicyUuid, deploymentPolicyId);
                log.error(message);
                throw new InvalidDeploymentPolicyException(message);
            }

            // network partitions should be already added
            NetworkPartition[] networkPartitions = CloudControllerServiceClient.getInstance().getNetworkPartitions();
            NetworkPartition networkPartitionForTenant = null;
            if (networkPartitions != null) {
                for (NetworkPartition networkPartition : networkPartitions) {
                    if (deploymentPolicy.getTenantId() == networkPartition.getTenantId() && networkPartition.getId()
                            .equals(networkPartitionRef.getId())) {
                        networkPartitionForTenant = networkPartition;
                    }
                }
            }
            if (networkPartitionForTenant == null) {
                String message = String.format("Network partition not found: [tenant-id] %d " +
                                "[deployment-policy-uuid] %s [deployment-policy-id] %s [network-partition-id] %s",
                        deploymentPolicy.getTenantId(), deploymentPolicyUuid, deploymentPolicyId, networkPartitionId);
                log.error(message);
                throw new InvalidDeploymentPolicyException(message);
            }

            // network partition - partition id should be already added
            for (PartitionRef partitionRef : networkPartitionRef.getPartitionRefs()) {
                String partitionUuid = partitionRef.getUuid();
                boolean isPartitionFound = false;

                for (Partition partition : networkPartitionForTenant.getPartitions()) {
                    if (partition.getUuid().equals(partitionUuid)) {
                        isPartitionFound = true;
                    }
                }
                if (!isPartitionFound) {
                    String message = String.format("Partition not found: [tenant-id] " +
                                    "%d [deployment-policy-uuid] %s [deployment-policy-id] %s [network-partition-id] " +
                                    "%s [network=partition-uuid] %s [partition-id] %s ",
                            deploymentPolicy.getTenantId(), deploymentPolicy.getUuid(), deploymentPolicyId,
                            networkPartitionRef.getUuid(), networkPartitionId, partitionRef.getId());
                    log.error(message);
                    throw new InvalidDeploymentPolicyException(message);
                }
            }

            // partition algorithm can't be null or empty
            String partitionAlgorithm = networkPartitionRef.getPartitionAlgo();
            if (StringUtils.isBlank(partitionAlgorithm)) {
                String message = String.format("Partition algorithm is blank: [tenant-id] %d " +
                                "[deployment-policy-uuid] %s [deployment-policy-id] %s [network-partition-uuid] %s " +
                                "[network-partition-id] %s [partition-algorithm] %s",
                        deploymentPolicy.getTenantId(), deploymentPolicyUuid, deploymentPolicyId,
                        networkPartitionRef.getUuid(), networkPartitionId, partitionAlgorithm);
                log.error(message);
                throw new InvalidDeploymentPolicyException(message);
            }

            // partition algorithm should be either one-after-another or round-robin
            if ((!StratosConstants.PARTITION_ROUND_ROBIN_ALGORITHM_ID.equals(partitionAlgorithm))
                    && (!StratosConstants.PARTITION_ONE_AFTER_ANOTHER_ALGORITHM_ID.equals(partitionAlgorithm))) {
                String message = String.format("Partition algorithm is not valid: [tenant-id] %d " +
                                "[deployment-policy-uuid] %s [deployment-policy-id] %s [network-partition-uuid] %s " +
                                "[network-partition-id] %s [partition-algorithm] %s", deploymentPolicy.getTenantId(),
                        deploymentPolicyUuid, deploymentPolicyId, networkPartitionRef.getUuid(), networkPartitionId,
                        partitionAlgorithm);
                log.error(message);
                throw new InvalidDeploymentPolicyException(message);
            }

            // a network partition reference should contain at least one partition reference
            PartitionRef[] partitions = networkPartitionRef.getPartitionRefs();
            if (null == partitions || partitions.length == 0) {
                String message = String.format("Network partition does not have any partition references: " +
                                "[tenant-id] %d [deployment-policy-uuid] %s [deployment-policy-id] %s " +
                                "[network-partition-uuid] %s [network-partition-id] %s ", deploymentPolicy.getTenantId(),
                        deploymentPolicyUuid, deploymentPolicyId, networkPartitionRef.getUuid(), networkPartitionId);
                log.error(message);
                throw new InvalidDeploymentPolicyException(message);
            }
        }
    }

    @Override
    public boolean updateDeploymentPolicy(DeploymentPolicy deploymentPolicy) throws RemoteException,
            InvalidDeploymentPolicyException, DeploymentPolicyNotExistsException, InvalidPolicyException, CloudControllerConnectionException {

        validateDeploymentPolicy(deploymentPolicy);

        if (log.isInfoEnabled()) {
            log.info(String.format("Updating deployment policy: [tenant-id] %d [deployment-policy-uuid] %s " +
                            "[deployment-policy-id] %s", deploymentPolicy.getTenantId(), deploymentPolicy.getUuid(),
                    deploymentPolicy.getId()));
        }
        if (log.isDebugEnabled()) {
            log.debug("Updating Deployment policy definition: " + deploymentPolicy.toString());
        }

        String deploymentPolicyUuid = deploymentPolicy.getUuid();
        if (PolicyManager.getInstance().getDeploymentPolicy(deploymentPolicyUuid) == null) {
            String message = String.format("Deployment policy not exists: [tenant-id] %d [deployment-policy-uuid] " +
                    "%s [deployment-policy-id] %s", deploymentPolicy.getTenantId(), deploymentPolicyUuid,
                    deploymentPolicy.getId());
            log.error(message);
            throw new DeploymentPolicyNotExistsException(message);
        }

        // Add cartridge to the cloud controller context and persist
        PolicyManager.getInstance().updateDeploymentPolicy(deploymentPolicy);
        updateClusterMonitors(deploymentPolicy);

        if (log.isInfoEnabled()) {
            log.info(String.format("Successfully updated deployment policy: [tenant-id] %d [deployment-policy-uuid] " +
                            "%s [deployment-policy-id] %s", deploymentPolicy.getTenantId(), deploymentPolicyUuid,
                    deploymentPolicy.getId()));
        }
        return true;
    }

    private void updateClusterMonitors(DeploymentPolicy deploymentPolicy) throws InvalidDeploymentPolicyException,
            CloudControllerConnectionException {

        for (ClusterMonitor clusterMonitor : AutoscalerContext.getInstance().getClusterMonitors().values()) {
            //Following if statement checks the relevant clusters for the updated deployment policy
            if (deploymentPolicy.getUuid().equals(clusterMonitor.getDeploymentPolicyId())) {
                for (NetworkPartitionRef networkPartition : deploymentPolicy.getNetworkPartitionRefs()) {
                    NetworkPartitionContext clusterLevelNetworkPartitionContext
                            = clusterMonitor.getClusterContext().getNetworkPartitionCtxt(networkPartition.getId());
                    if (clusterLevelNetworkPartitionContext != null) {
                        try {
                            addNewPartitionsToClusterMonitor(clusterLevelNetworkPartitionContext, networkPartition,
                                    deploymentPolicy.getUuid(), clusterMonitor.getClusterContext().getServiceId());
                        } catch (RemoteException e) {

                            String message = "Connection to cloud controller failed, Cluster monitor update failed for" +
                                    " [deployment-policy] " + deploymentPolicy.getId();
                            log.error(message);
                            throw new CloudControllerConnectionException(message, e);
                        } catch (CloudControllerServiceInvalidPartitionExceptionException e) {

                            String message = "Invalid partition, Cluster monitor update failed for [deployment-policy] "
                                    + deploymentPolicy.getId();
                            log.error(message);
                            throw new InvalidDeploymentPolicyException(message, e);
                        } catch (CloudControllerServiceInvalidCartridgeTypeExceptionException e) {

                            String message = "Invalid cartridge type, Cluster monitor update failed for [deployment-policy] "
                                    + deploymentPolicy.getId() + " [cartridge] "
                                    + clusterMonitor.getClusterContext().getServiceId();
                            log.error(message);
                            throw new InvalidDeploymentPolicyException(message, e);
                        }
                        removeOldPartitionsFromClusterMonitor(clusterLevelNetworkPartitionContext, networkPartition);
                    }
                }
            }
        }
    }

    private void removeOldPartitionsFromClusterMonitor(NetworkPartitionContext clusterLevelNetworkPartitionContext,
                                                       NetworkPartitionRef networkPartition) {

        for (InstanceContext instanceContext : clusterLevelNetworkPartitionContext.getInstanceIdToInstanceContextMap().values()) {

            ClusterInstanceContext clusterInstanceContext = (ClusterInstanceContext) instanceContext;

            for (ClusterLevelPartitionContext clusterLevelPartitionContext : clusterInstanceContext.getPartitionCtxts()) {

                if (null == networkPartition.getPartitionRef(clusterLevelPartitionContext.getPartitionId())) {

                    //It has found that this partition context which is in cluster monitor is removed in updated policy
                    clusterLevelPartitionContext.setIsObsoletePartition(true);
                    Iterator<MemberContext> memberContextIterator = clusterLevelPartitionContext.getActiveMembers().iterator();
                    while (memberContextIterator.hasNext()) {

                        clusterLevelPartitionContext.moveActiveMemberToTerminationPendingMembers(
                                memberContextIterator.next().getMemberId());
                    }

                    memberContextIterator = clusterLevelPartitionContext.getPendingMembers().iterator();
                    while (memberContextIterator.hasNext()) {

                        clusterLevelPartitionContext.movePendingMemberToObsoleteMembers(
                                memberContextIterator.next().getMemberId());

                    }
                }
            }
        }
    }

    private void addNewPartitionsToClusterMonitor(NetworkPartitionContext clusterLevelNetworkPartitionContext,
                                                  NetworkPartitionRef networkPartitionRef, String deploymentPolicyID,
                                                  String cartridgeType) throws RemoteException,
            CloudControllerServiceInvalidPartitionExceptionException,
            CloudControllerServiceInvalidCartridgeTypeExceptionException {

        boolean validationOfNetworkPartitionRequired = false;
        for (PartitionRef partition : networkPartitionRef.getPartitionRefs()) {

            //Iterating through instances
            for (InstanceContext instanceContext : clusterLevelNetworkPartitionContext.getInstanceIdToInstanceContextMap().values()) {

                ClusterInstanceContext clusterInstanceContext = (ClusterInstanceContext) instanceContext;
                if (null == clusterInstanceContext.getPartitionCtxt(partition.getUuid())) {

                    //It has found that this partition which is in deployment policy/network partition is new
                    ClusterLevelPartitionContext clusterLevelPartitionContext = new ClusterLevelPartitionContext(
                            partition, networkPartitionRef.getUuid(), deploymentPolicyID);
                    validationOfNetworkPartitionRequired = true;
                    clusterInstanceContext.addPartitionCtxt(clusterLevelPartitionContext);
                }
            }
        }

        if (validationOfNetworkPartitionRequired) {

            CloudControllerServiceClient.getInstance().validateNetworkPartitionOfDeploymentPolicy(cartridgeType,
                    clusterLevelNetworkPartitionContext.getId());
        }
    }

    @Override
    public boolean removeDeployementPolicy(String deploymentPolicyId) throws DeploymentPolicyNotExistsException,
            UnremovablePolicyException {
        if (log.isInfoEnabled()) {
            log.info("Removing deployment policy: [deployment-policy_id] " + deploymentPolicyId);
        }
        if (PolicyManager.getInstance().getDeploymentPolicy(deploymentPolicyId) == null) {
            String message = "Deployment policy not exists: [deployment-policy-id] " + deploymentPolicyId;
            log.error(message);
            throw new DeploymentPolicyNotExistsException(message);
        }
        if (removableDeploymentPolicy(deploymentPolicyId)) {
            PolicyManager.getInstance().removeDeploymentPolicy(deploymentPolicyId);
        } else {
            throw new UnremovablePolicyException("This deployment policy cannot be removed, since it is used in an " +
                    "application.");
        }
        if (log.isInfoEnabled()) {
            log.info("Successfully removed deployment policy: [deployment_policy_id] " + deploymentPolicyId);
        }
        return true;
    }

    @Override
    public DeploymentPolicy getDeploymentPolicy(String deploymentPolicyId) {
        if (log.isDebugEnabled()) {
            log.debug("Getting deployment policy: [deployment-policy_id] " + deploymentPolicyId);
        }
        return PolicyManager.getInstance().getDeploymentPolicy(deploymentPolicyId);
    }

	@Override
	public DeploymentPolicy getDeploymentPolicyForTenant(String deploymentPolicyID, int tenantId) {
		DeploymentPolicy[] deploymentPolicies=getDeploymentPolicies();
		for(DeploymentPolicy deploymentPolicy:deploymentPolicies){
			if(deploymentPolicy.getId().equals(deploymentPolicyID)&&(deploymentPolicy.getTenantId()==tenantId)){
				return  deploymentPolicy;
			}
		}
		return null;
	}

	@Override
    public DeploymentPolicy[] getDeploymentPolicies() {
        try {
            Collection<DeploymentPolicy> deploymentPolicies = PolicyManager.getInstance().getDeploymentPolicies();
            return deploymentPolicies.toArray(new DeploymentPolicy[deploymentPolicies.size()]);
        } catch (Exception e) {
            String message = "Could not get deployment policies";
            log.error(message);
            throw new AutoScalerException(message, e);
        }
    }

    @Override
    public DeploymentPolicy[] getDeploymentPoliciesByTenant(int tenantId) {
        DeploymentPolicy[] allDeploymentPolicies = getDeploymentPolicies();
        List<DeploymentPolicy> deploymentPolicies = new ArrayList<DeploymentPolicy>();

        if (allDeploymentPolicies != null) {
            for (DeploymentPolicy deploymentPolicy : allDeploymentPolicies) {
                if (deploymentPolicy.getTenantId() == tenantId) {
                    deploymentPolicies.add(deploymentPolicy);
                }
            }
        }
        return deploymentPolicies.toArray(new DeploymentPolicy[deploymentPolicies.size()]);
    }

}
