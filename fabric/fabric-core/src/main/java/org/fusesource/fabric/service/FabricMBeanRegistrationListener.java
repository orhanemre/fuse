/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.fabric.service;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.fusesource.fabric.api.FabricService;
import org.fusesource.fabric.api.jcip.GuardedBy;
import org.fusesource.fabric.api.jcip.ThreadSafe;
import org.fusesource.fabric.api.jmx.FabricManager;
import org.fusesource.fabric.api.jmx.FileSystem;
import org.fusesource.fabric.api.jmx.HealthCheck;
import org.fusesource.fabric.api.jmx.ZooKeeperFacade;
import org.fusesource.fabric.api.scr.AbstractComponent;
import org.fusesource.fabric.api.scr.ValidatingReference;
import org.fusesource.fabric.utils.SystemProperties;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.MBeanServerNotification;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.ObjectName;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.fusesource.fabric.zookeeper.ZkPath.CONTAINER_DOMAIN;
import static org.fusesource.fabric.zookeeper.ZkPath.CONTAINER_DOMAINS;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.create;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.delete;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.deleteSafe;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.exists;
import static org.fusesource.fabric.zookeeper.utils.ZooKeeperUtils.setData;

@ThreadSafe
@Component(name = "org.fusesource.fabric.mbeanserver.listener", description = "Fabric MBean Server Listener")
@Service(ConnectionStateListener.class)
public final class FabricMBeanRegistrationListener extends AbstractComponent implements NotificationListener, ConnectionStateListener {

    private transient Logger LOGGER = LoggerFactory.getLogger(FabricMBeanRegistrationListener.class);

    private static final String KARAF_NAME = System.getProperty(SystemProperties.KARAF_NAME);

    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();
    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();
    @Reference(referenceInterface = MBeanServer.class, bind = "bindMBeanServer", unbind = "unbindMBeanServer")
    private final ValidatingReference<MBeanServer> mbeanServer = new ValidatingReference<MBeanServer>();

    @GuardedBy("this") private final Set<String> domains = new HashSet<String>();
    @GuardedBy("this") private HealthCheck healthCheck;
    @GuardedBy("this") private FabricManager managerMBean;
    @GuardedBy("this") private ZooKeeperFacade zooKeeperMBean;
    @GuardedBy("this") private FileSystem fileSystemMBean;

    @Activate
    void activate(ComponentContext context) {
        registerMBeanServer();
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        unregisterMBeanServer();
        deactivateComponent();
    }

    @Override
    public synchronized void handleNotification(Notification notif, Object o) {
        if (isValid()) {
            LOGGER.trace("handleNotification[{}]", notif);
            if (notif instanceof MBeanServerNotification) {
                MBeanServerNotification notification = (MBeanServerNotification) notif;
                String domain = notification.getMBeanName().getDomain();
                String path = CONTAINER_DOMAIN.getPath((String) o, domain);
                try {
                    if (MBeanServerNotification.REGISTRATION_NOTIFICATION.equals(notification.getType())) {
                        if (domains.add(domain) && exists(curator.get(), path) == null) {
                            setData(curator.get(), path, "");
                        }
                    } else if (MBeanServerNotification.UNREGISTRATION_NOTIFICATION.equals(notification.getType())) {
                        domains.clear();
                        domains.addAll(Arrays.asList(mbeanServer.get().getDomains()));
                        if (!domains.contains(domain)) {
                            // domain is no present any more
                            deleteSafe(curator.get(), path);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Exception while jmx domain synchronization from event: " + notif + ". This exception will be ignored.", e);
                }
            }
        }
    }

    @Override
    public void stateChanged(CuratorFramework client, ConnectionState newState) {
        if (isValid()) {
            switch (newState) {
            case CONNECTED:
            case RECONNECTED:
                try {
                    updateProcessId();
                } catch (Exception ex) {
                    LOGGER.error("Error while updating the process id.");
                }
                break;
        }
        }
    }

    private void updateProcessId() throws Exception {
        String processName = (String) mbeanServer.get().getAttribute(new ObjectName("java.lang:type=Runtime"), "Name");
        Long processId = Long.parseLong(processName.split("@")[0]);

        String path = ZkPath.CONTAINER_PROCESS_ID.getPath(KARAF_NAME);
        Stat stat = exists(curator.get(), path);
        if (stat != null) {
            if (stat.getEphemeralOwner() != curator.get().getZookeeperClient().getZooKeeper().getSessionId()) {
                delete(curator.get(), path);
                if( processId!=null ) {
                    create(curator.get(), path, processId.toString(), CreateMode.EPHEMERAL);
                }
            }
        } else {
            if( processId!=null ) {
                create(curator.get(), path, processId.toString(), CreateMode.EPHEMERAL);
            }
        }
    }

    void bindFabricService(FabricService fabricService) {
        this.fabricService.set(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.set(null);
    }

    void bindCurator(CuratorFramework curator) {
        this.curator.set(curator);
    }

    void unbindCurator(CuratorFramework curator) {
        this.curator.set(null);
    }

    void bindMBeanServer(MBeanServer mbeanServer) {
        this.mbeanServer.set(mbeanServer);
    }

    void unbindMBeanServer(MBeanServer mbeanServer) {
        this.mbeanServer.set(null);
    }


    private synchronized void registerMBeanServer() {
        try {
            mbeanServer.get().addNotificationListener(new ObjectName("JMImplementation:type=MBeanServerDelegate"), this, null, KARAF_NAME);
            registerDomains();
            registerFabricMBeans();
        } catch (Exception e) {
            LOGGER.warn("An error occurred during mbean server registration. This exception will be ignored.", e);
        }
    }

    private synchronized void unregisterMBeanServer() {
        try {
            mbeanServer.get().removeNotificationListener(new ObjectName("JMImplementation:type=MBeanServerDelegate"), this);
            unregisterDomains();
            unregisterFabricMBeans();
        } catch (Exception e) {
            LOGGER.warn("An error occurred during mbean server unregistration. This exception will be ignored.", e);
        }
    }

    private void registerDomains() throws Exception {
        String name = System.getProperty(SystemProperties.KARAF_NAME);
        synchronized (this) {
            domains.addAll(Arrays.asList(mbeanServer.get().getDomains()));
        }
        for (String domain : mbeanServer.get().getDomains()) {
            setData(curator.get(), CONTAINER_DOMAIN.getPath(name, domain), (byte[]) null);
        }
    }

    private void unregisterDomains() throws Exception {
        String name = System.getProperty(SystemProperties.KARAF_NAME);
        String domainsPath = CONTAINER_DOMAINS.getPath(name);
        deleteSafe(curator.get(), domainsPath);
    }

    private synchronized void registerFabricMBeans() {
        this.healthCheck = new HealthCheck(fabricService.get());
        this.managerMBean = new FabricManager((FabricServiceImpl) fabricService.get());
        this.zooKeeperMBean = new ZooKeeperFacade((FabricServiceImpl) fabricService.get());
        this.fileSystemMBean = new FileSystem();
        healthCheck.registerMBeanServer(mbeanServer.get());
        managerMBean.registerMBeanServer(mbeanServer.get());
        fileSystemMBean.registerMBeanServer(mbeanServer.get());
        zooKeeperMBean.registerMBeanServer(mbeanServer.get());
    }

    private synchronized void unregisterFabricMBeans() {
        zooKeeperMBean.unregisterMBeanServer(mbeanServer.get());
        fileSystemMBean.unregisterMBeanServer(mbeanServer.get());
        managerMBean.unregisterMBeanServer(mbeanServer.get());
        healthCheck.unregisterMBeanServer(mbeanServer.get());
    }
}
