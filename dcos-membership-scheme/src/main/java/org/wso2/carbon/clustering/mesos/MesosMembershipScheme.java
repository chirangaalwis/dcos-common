/*
* Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.wso2.carbon.clustering.mesos;

import static org.apache.commons.httpclient.HttpStatus.SC_NOT_FOUND;
import static org.apache.commons.httpclient.HttpStatus.SC_REQUEST_TIMEOUT;
import static org.wso2.carbon.clustering.mesos.MesosConstants.CONNECTION_TIMEOUT;
import static org.wso2.carbon.clustering.mesos.MesosConstants.DEFAULT_DNS_UPDATE_TIMEOUT;
import static org.wso2.carbon.clustering.mesos.MesosConstants.DEFAULT_MARATHON_ENDPOINT;
import static org.wso2.carbon.clustering.mesos.MesosConstants.DEFAULT_MESOS_DNS_ENDPOINT;
import static org.wso2.carbon.clustering.mesos.MesosConstants.DNS_RETRY_INTERVAL;
import static org.wso2.carbon.clustering.mesos.MesosConstants.DNS_UPDATE_TIMEOUT;
import static org.wso2.carbon.clustering.mesos.MesosConstants.ENABLE_BASIC_AUTH;
import static org.wso2.carbon.clustering.mesos.MesosConstants.IS_OVERLAY_NETWORK_AND_DOCKER;
import static org.wso2.carbon.clustering.mesos.MesosConstants.MARATHON_APPLICATIONS;
import static org.wso2.carbon.clustering.mesos.MesosConstants.MARATHON_APP_ID;
import static org.wso2.carbon.clustering.mesos.MesosConstants.MARATHON_ENDPOINT;
import static org.wso2.carbon.clustering.mesos.MesosConstants.MARATHON_PASSWORD;
import static org.wso2.carbon.clustering.mesos.MesosConstants.MARATHON_USERNAME;
import static org.wso2.carbon.clustering.mesos.MesosConstants.MESOS_DNS_DISCOVERY_SCHEME;
import static org.wso2.carbon.clustering.mesos.MesosConstants.MESOS_DNS_ENDPOINT;
import static org.wso2.carbon.clustering.mesos.MesosConstants.MESOS_MARATHON_DISCOVERY_SCHEME;
import static org.wso2.carbon.clustering.mesos.MesosConstants.MESOS_MEMBER_DISCOVERY_SCHEME;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.axis2.clustering.ClusteringFault;
import org.apache.axis2.clustering.ClusteringMessage;
import org.apache.axis2.description.Parameter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.clustering.mesos.client.Marathon;
import org.wso2.carbon.clustering.mesos.client.MesosDNS;
import org.wso2.carbon.clustering.mesos.client.MesosDNSClient;
import org.wso2.carbon.clustering.mesos.client.MesosMarathonClient;
import org.wso2.carbon.clustering.mesos.client.model.dns.v1.MesosDNSSRVRecord;
import org.wso2.carbon.clustering.mesos.client.model.marathon.v2.App;
import org.wso2.carbon.clustering.mesos.client.model.marathon.v2.IpAddress;
import org.wso2.carbon.clustering.mesos.client.model.marathon.v2.Port;
import org.wso2.carbon.clustering.mesos.client.model.marathon.v2.Task;
import org.wso2.carbon.clustering.mesos.client.utils.MesosException;
import org.wso2.carbon.core.clustering.hazelcast.HazelcastCarbonClusterImpl;
import org.wso2.carbon.core.clustering.hazelcast.HazelcastMembershipScheme;
import org.wso2.carbon.core.clustering.hazelcast.HazelcastUtil;
import org.wso2.carbon.utils.xml.StringUtils;

import com.hazelcast.config.Config;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.TcpIpConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.Member;
import com.hazelcast.core.MemberAttributeEvent;
import com.hazelcast.core.MembershipEvent;
import com.hazelcast.core.MembershipListener;

/**
 * Mesos membership scheme provides cluster discovery for WSO2 Carbon servers on
 * Apache Mesos platform
 */
public class MesosMembershipScheme implements HazelcastMembershipScheme {
    private static final Log log = LogFactory.getLog(MesosMembershipScheme.class);
    private final NetworkConfig nwConfig;
    private final Map<String, Parameter> parameters;
    private final List<ClusteringMessage> messageBuffer;
    private HazelcastInstance primaryHazelcastInstance;
    private HazelcastCarbonClusterImpl carbonCluster;

    // Mesos memberschip scheme specific configs
    private List<String> marathonAppIdList;
    private String memberDiscoveryScheme;
    private String marathonEndpoint;
    private boolean enableBasicAuth;
    private String marathonUsername;
    private String marathonPassword;
    private String mesosDNSEndpoint;
    private boolean isOverlayNetwork;
    private int dnsUpdateTimeout;

    public MesosMembershipScheme(Map<String, Parameter> parameters, String primaryDomain, Config config,
                                 HazelcastInstance primaryHazelcastInstance, List<ClusteringMessage> messageBuffer) {
        this.parameters = parameters;
        this.primaryHazelcastInstance = primaryHazelcastInstance;
        this.messageBuffer = messageBuffer;
        this.nwConfig = config.getNetworkConfig();
    }

    @Override
    public void setPrimaryHazelcastInstance(HazelcastInstance hazelcastInstance) {
        this.primaryHazelcastInstance = hazelcastInstance;
    }

    @Override
    public void setLocalMember(Member localMember) {
    }

    @Override
    public void setCarbonCluster(HazelcastCarbonClusterImpl hazelcastCarbonCluster) {
        this.carbonCluster = hazelcastCarbonCluster;
    }

    @Override
    public void init() throws ClusteringFault {
        try {
            log.info("Initializing clustering membership scheme for Mesos...");
            configureTcpIpParameters();
            loadConfiguration();
            if (MESOS_DNS_DISCOVERY_SCHEME.equals(memberDiscoveryScheme)) {
                addMembersFromMesosDNS();
            } else if (MESOS_MARATHON_DISCOVERY_SCHEME.equals(memberDiscoveryScheme)) {
                addMembersFromMesosMarathon();
            } else {
                throw new ClusteringFault(
                        String.format("Unsupported Mesos member discovery scheme [Discovery-Scheme] %s", memberDiscoveryScheme));
            }
            log.info("Mesos clustering membership scheme initialized successfully");
        } catch (Exception e) {
            throw new ClusteringFault("Mesos clustering membership initialization failed", e);
        }
    }

    private void addMembersFromMesosDNS() {
        log.info(String.format("Creating Mesos DNS client using [Endpoint] %s", mesosDNSEndpoint));
        MesosDNS mesosDNSClient = MesosDNSClient.getInstance(mesosDNSEndpoint);
        for (String marathonAppId : marathonAppIdList) {
            if (StringUtils.isEmpty(marathonAppId)) {
                log.warn("Skipping on empty Marathon application ID");
                continue;
            }
            String mesosServiceName = "_" + marathonAppId + "._tcp.marathon.mesos.";
            if (log.isDebugEnabled()) {
                log.debug(String.format("Retrieving member information for service [Name] %s via Mesos DNS client", mesosServiceName));
            }
            try {
                // Mesos-DNS periodically queries the Mesos master and retrieves
                // a list of services and populates DNS
                // SRV records. There could be a delay to reflect the latest
                // state therefore we wait for a given timeout
                if (!hasDNSUpdated(mesosDNSClient, mesosServiceName)) {
                    log.error(String.format(
                            "Mesos DNS has not updated SRV records within [Timeout] %ss. Could not " + "add members in [AppId] %s",
                            DEFAULT_DNS_UPDATE_TIMEOUT, marathonAppId));
                    continue;
                }
                List<MesosDNSSRVRecord> mesosDNSSRVRecords = mesosDNSClient.getService(mesosServiceName);
                if (log.isDebugEnabled()) {
                    log.debug("Mesos DNS SRV record list: " + mesosDNSSRVRecords);
                }
                if (mesosDNSSRVRecords == null || mesosDNSSRVRecords.isEmpty()) {
                    log.error(String.format("Mesos DNS SRV record list is empty. Could not add members in [AppId] %s", marathonAppId));
                    continue;
                }

                //  loop through the DNS record entries and
                //  capture the Hazelcast service of each Marathon application instance
                int port;
                String nodeIP;
                String previousNodeIP = "";
                String memberAddress;
                for (MesosDNSSRVRecord record : mesosDNSSRVRecords) {
                    port = Integer.parseInt(record.getPort());
                    nodeIP = record.getIp();

                    if (!nodeIP.equals(previousNodeIP)) {
                        // add member to the cluster configuration
                        memberAddress = nodeIP + ":" + port;
                        nwConfig.getJoin().getTcpIpConfig().addMember(memberAddress);
                        log.info(String.format("Member added to cluster configuration: [Address] %s", memberAddress));
                    }
                    previousNodeIP = nodeIP;
                }
            } catch (MesosException e) {
                handleMesosException(e, marathonAppId);
            }
        }
    }

    private boolean hasDNSUpdated(MesosDNS mesosDNSClient, String mesosServiceName) throws MesosException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < dnsUpdateTimeout * 1000) {
            List<MesosDNSSRVRecord> mesosDNSSRVRecords = mesosDNSClient.getService(mesosServiceName);
            if (mesosDNSSRVRecords.isEmpty() || mesosDNSSRVRecords.get(0) == null || StringUtils.isEmpty(mesosDNSSRVRecords.get(0).getIp())
                    || StringUtils.isEmpty(mesosDNSSRVRecords.get(0).getPort())) {
                log.info(String.format("DNS records have not been updated for Mesos service [Name] %s. " + "Retrying in %ds...",
                        mesosServiceName, DNS_RETRY_INTERVAL));
                try {
                    Thread.sleep(DNS_RETRY_INTERVAL * 1000);
                } catch (InterruptedException e) {
                    log.error(e);
                }
            } else {
                return true;
            }
        }
        return false;
    }

    private void addMembersFromMesosMarathon() {
        log.info(String.format("Creating Mesos Marathon client using [Endpoint] %s, [Basic-Auth-Enabled] %s", marathonEndpoint,
                enableBasicAuth));
        Marathon marathonClient;
        if (enableBasicAuth) {
            marathonClient = MesosMarathonClient.getInstanceWithBasicAuth(marathonEndpoint, marathonUsername, marathonPassword);
        } else {
            marathonClient = MesosMarathonClient.getInstance(marathonEndpoint);
        }
        for (String marathonAppId : marathonAppIdList) {
            if (StringUtils.isEmpty(marathonAppId)) {
                log.warn("Skipping on empty Marathon application ID");
                continue;
            }
            if (log.isDebugEnabled()) {
                log.debug(String.format("Retrieving Marathon application information for [AppId] %s via Mesos " + "Marathon client",
                        marathonAppId));
            }
            Collection<Task> tasksCollection;
            try {
                if (isOverlayNetwork) {
                    addMembersFromMarathonDockerInfo(marathonAppId, marathonClient.getApp(marathonAppId).getApp());
                } else {
                    tasksCollection = marathonClient.getApp(marathonAppId).getApp().getTasks();
                    addMembersFromMarathonTasks(marathonAppId, tasksCollection);
                }
            } catch (MesosException e) {
                handleMesosException(e, marathonAppId);
            }
        }
    }

    private void handleMesosException(MesosException e, String marathonAppId) {
        if (e.getStatus() == SC_NOT_FOUND) {
            // Log a warning and continue, let the dependent applications (that
            // may not have been deployed yet) add
            // this node to the cluster
            log.warn(String.format("Marathon application [AppId] %s was not found", marathonAppId));
        } else if (e.getStatus() == SC_REQUEST_TIMEOUT) {
            log.error("Marathon REST API call timed out. Please check whether Marathon REST API is " + "accessible via the given endpoint",
                    e);
        } else {
            log.error(String.format("Failed to retrieve Marathon application information: [AppId] %s", marathonAppId), e);
        }
    }

    private void addMembersFromMarathonDockerInfo(String marathonAppId, App marathonApp) {
        if (marathonApp.getContainer() == null || marathonApp.getContainer().getDocker() == null
                || marathonApp.getContainer().getDocker().getPortMappings() == null
                || marathonApp.getContainer().getDocker().getPortMappings().isEmpty()) {
            log.warn(String.format("Docker containerPort collection is empty for Marathon app [AppId] %s", marathonAppId));
        }
        Integer hazelcastPort = marathonApp.getContainer().getDocker().getPortMappings().iterator().next().getContainerPort();
        for (Port port : marathonApp.getContainer().getDocker().getPortMappings()) {
            if (hazelcastPort > port.getContainerPort()) {
                hazelcastPort = port.getContainerPort();
            }
        }
        if (marathonApp.getTasks() == null || marathonApp.getTasks().isEmpty()) {
            log.warn(String.format("Task collection is empty for Marathon app [AppId] %s", marathonAppId));
            return;
        }
        for (Task task : marathonApp.getTasks()) {
            if (task.getIpAddresses() == null || task.getIpAddresses().isEmpty()) {
                log.warn(String.format("IPAddresses collection is empty for Marathon app [AppId] %s", marathonAppId));
                return;
            }
            // by default, get IPV4. We must configure if we want IPV6.
            String ipAddress = "";
            for (IpAddress address : task.getIpAddresses()) {
                if (address.getProtocol().equals(MesosConstants.IP_ADDRESS_PROTOCOL_IPV4)) {
                    ipAddress = address.getIpAddress();
                }
            }
            String memberAddress = ipAddress + ":" + hazelcastPort.toString();
            nwConfig.getJoin().getTcpIpConfig().addMember(memberAddress);
        }
    }

    private void addMembersFromMarathonTasks(String marathonAppId, Collection<Task> tasksCollection) {
        if (tasksCollection == null || tasksCollection.isEmpty()) {
            log.warn(String.format("Task collection is empty for Marathon app [AppId] %s", marathonAppId));
            return;
        }
        for (Task task : tasksCollection) {
            String hostname = task.getHost();
            if (StringUtils.isEmpty(hostname)) {
                log.warn(String.format("Hostname is empty for Marathon task [AppId] %s, [Task] %s", marathonAppId, task));
                continue;
            }
            if (task.getPorts() == null || task.getPorts().size() == 0) {
                log.warn(String.format("Port is empty for Marathon task [AppId] %s, [Task] %s", marathonAppId, task));
                continue;
            }
            Integer port = task.getPorts().iterator().next();
            String memberAddress = hostname + ":" + port.toString();
            nwConfig.getJoin().getTcpIpConfig().addMember(memberAddress);
            log.info(String.format("Member added to cluster configuration: [Address] %s", memberAddress));
        }
    }

    @Override
    public void joinGroup() throws ClusteringFault {
        primaryHazelcastInstance.getCluster().addMembershipListener(new MesosMembershipSchemeListener());
    }

    private void loadConfiguration() {
        memberDiscoveryScheme = getParameterValue(MESOS_MEMBER_DISCOVERY_SCHEME, MESOS_MARATHON_DISCOVERY_SCHEME);
        marathonEndpoint = getParameterValue(MARATHON_ENDPOINT, DEFAULT_MARATHON_ENDPOINT);
        enableBasicAuth = Boolean.parseBoolean(getParameterValue(ENABLE_BASIC_AUTH, Boolean.FALSE.toString()));
        marathonUsername = getParameterValue(MARATHON_USERNAME, "");
        marathonPassword = getParameterValue(MARATHON_PASSWORD, "");
        mesosDNSEndpoint = getParameterValue(MESOS_DNS_ENDPOINT, DEFAULT_MESOS_DNS_ENDPOINT);
        dnsUpdateTimeout = Integer.parseInt(getParameterValue(DNS_UPDATE_TIMEOUT, DEFAULT_DNS_UPDATE_TIMEOUT));
        marathonAppIdList = new ArrayList<>();
        isOverlayNetwork = Boolean.parseBoolean(getParameterValue(IS_OVERLAY_NETWORK_AND_DOCKER, Boolean.FALSE.toString()));

        String localAppId = getParameterValue(MARATHON_APP_ID, "");
        if (StringUtils.isEmpty(localAppId)) {
            throw new IllegalArgumentException(String.format("%s property was not found in environment variables", MARATHON_APP_ID));
        }
        // MARATHON_APP_ID environment variable passed to instance contains
        // forward-slash prefix
        if (localAppId.startsWith("/")) {
            localAppId = localAppId.substring(1);
        }
        String marathonAppIds = getParameterValue(MARATHON_APPLICATIONS, localAppId);
        for (String appId : Arrays.asList(marathonAppIds.split(","))) {
            if (appId.startsWith("/")) {
                marathonAppIdList.add(appId.substring(1));
            } else {
                marathonAppIdList.add(appId);
            }
        }
        if (!marathonAppIdList.contains(localAppId)) {
            marathonAppIdList.add(localAppId);
        }
        log.info(String.format(
                "Mesos clustering membership scheme configuration: [App-List] %s, [Local-AppId] %s, " + "[Discovery-Scheme] %s",
                marathonAppIdList, localAppId, memberDiscoveryScheme));
    }

    private String getParameterValue(String key, String def) {
        String value = System.getenv(key);
        if (StringUtils.isEmpty(value)) {
            value = System.getProperty(key);
        }
        if (StringUtils.isEmpty(value)) {
            Parameter parameter = parameters.get(key);
            if (parameter == null || StringUtils.isEmpty((String) parameter.getValue())) {
                if (def == null) {
                    throw new IllegalArgumentException(String.format("Clustering parameter [Name] %s not found", key));
                } else {
                    value = def;
                }
            } else {
                value = (String) parameter.getValue();
            }
        }
        return value;
    }

    private void configureTcpIpParameters() {
        nwConfig.getJoin().getMulticastConfig().setEnabled(false);
        nwConfig.getJoin().getAwsConfig().setEnabled(false);
        TcpIpConfig tcpIpConfig = nwConfig.getJoin().getTcpIpConfig();
        tcpIpConfig.setEnabled(true);
        Parameter connTimeoutParameter = parameters.get(CONNECTION_TIMEOUT);
        if (connTimeoutParameter != null && connTimeoutParameter.getValue() != null) {
            int connTimeout = Integer.parseInt(((String) (connTimeoutParameter.getValue())).trim());
            tcpIpConfig.setConnectionTimeoutSeconds(connTimeout);
        }
        log.info(String.format("Mesos membership scheme TCP IP parameters configured [Connection-Timeout] %ds",
                tcpIpConfig.getConnectionTimeoutSeconds()));
    }

    /**
     * Mesos clustering membership scheme listener
     */
    private class MesosMembershipSchemeListener implements MembershipListener {
        @Override
        public void memberAdded(MembershipEvent membershipEvent) {
            Member member = membershipEvent.getMember();

            // Send all cluster messages
            carbonCluster.memberAdded(member);
            log.info(String.format("Member joined: [UUID] %s, [Address] %s", member.getUuid(), member.getSocketAddress().toString()));
            // Wait for sometime for the member to completely join before
            // replaying messages
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
            }
            HazelcastUtil.sendMessagesToMember(messageBuffer, member, carbonCluster);
        }

        @Override
        public void memberRemoved(MembershipEvent membershipEvent) {
            Member member = membershipEvent.getMember();
            carbonCluster.memberRemoved(member);
            log.info(String.format("Member left: [UUID] %s, [Address] %s", member.getUuid(), member.getSocketAddress().toString()));
        }

        @Override
        public void memberAttributeChanged(MemberAttributeEvent memberAttributeEvent) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Member attribute changed: [Key] %s, [Value] %s", memberAttributeEvent.getKey(),
                        memberAttributeEvent.getValue()));
            }
        }
    }
}
