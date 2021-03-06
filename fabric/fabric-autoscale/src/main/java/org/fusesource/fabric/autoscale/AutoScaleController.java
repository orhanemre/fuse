/*
 * Copyright (C) FuseSource, Inc.
 *   http://fusesource.com
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.fusesource.fabric.autoscale;

import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.ContainerAutoScaler;
import org.fusesource.fabric.api.Containers;
import org.fusesource.fabric.api.DataStore;
import org.fusesource.fabric.api.FabricRequirements;
import org.fusesource.fabric.api.FabricService;
import org.fusesource.fabric.api.ProfileRequirements;
import org.fusesource.fabric.api.jcip.GuardedBy;
import org.fusesource.fabric.api.jcip.ThreadSafe;
import org.fusesource.fabric.api.scr.AbstractComponent;
import org.fusesource.fabric.api.scr.ValidatingReference;
import org.fusesource.fabric.groups.Group;
import org.fusesource.fabric.groups.GroupListener;
import org.fusesource.fabric.groups.internal.ZooKeeperGroup;
import org.fusesource.fabric.utils.Closeables;
import org.fusesource.fabric.utils.SystemProperties;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * A Fabric auto-scaler which when it becomes the master auto-scales
 * profiles according to their requirements defined via
 * {@link FabricService#setRequirements(org.fusesource.fabric.api.FabricRequirements)}
 */
@ThreadSafe
@Component(name = "org.fusesource.fabric.autoscale", description = "Fabric auto scaler", immediate = true)
public final class AutoScaleController  extends AbstractComponent implements GroupListener<AutoScalerNode> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoScaleController.class);

    private static final String KARAF_NAME = System.getProperty(SystemProperties.KARAF_NAME);

    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configAdmin = new ValidatingReference<ConfigurationAdmin>();
    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();
    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();
    @Reference(referenceInterface = ContainerAutoScaler.class)
    private final ValidatingReference<ContainerAutoScaler> containerAutoScaler = new ValidatingReference<ContainerAutoScaler>();

    @GuardedBy("volatile") private volatile Group<AutoScalerNode> group;

    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
            onConfigurationChanged();
        }
    };

    @Activate
    void activate(ComponentContext context) {
        group = new ZooKeeperGroup<AutoScalerNode>(curator.get(), ZkPath.AUTO_SCALE.getPath(), AutoScalerNode.class);
        group.add(this);
        group.start();
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
        group.remove(this);
        Closeables.closeQuitely(group);
        group = null;
    }

    @Override
    public void groupEvent(Group<AutoScalerNode> group, GroupEvent event) {
        if (isValid()) {
            if (group.isMaster()) {
                LOGGER.info("AutoScaleController is the master");
            } else {
                LOGGER.info("AutoScaleController is not the master");
            }
            try {
                DataStore dataStore = fabricService.get().getDataStore();
                if (group.isMaster()) {
                    LOGGER.info("tracking configuration");
                    group.update(createState());
                    dataStore.trackConfiguration(runnable);
                    onConfigurationChanged();
                } else {
                    LOGGER.info("untracking configuration");
                    dataStore.untrackConfiguration(runnable);
                }
            } catch (IllegalStateException e) {
                // Ignore
            }
        }
    }


    private void onConfigurationChanged() {
        LOGGER.info("Configuration has changed; so checking the auto-scaling requirements");
        autoScale();
    }

    private void autoScale() {
        ContainerAutoScaler autoScaler = getContainerAutoScaler();
        if (autoScaler != null) {
            FabricRequirements requirements = fabricService.get().getRequirements();
            List<ProfileRequirements> profileRequirements = requirements.getProfileRequirements();
            for (ProfileRequirements profileRequirement : profileRequirements) {
                autoScaleProfile(autoScaler, requirements, profileRequirement);
            }
        }
    }

    private ContainerAutoScaler getContainerAutoScaler() {
        if (containerAutoScaler == null) {
            // lets create one based on the current container providers
            containerAutoScaler.set(fabricService.get().createContainerAutoScaler());
            LOGGER.info("Creating auto scaler " + containerAutoScaler);
        }
        return containerAutoScaler.get();
    }

    private void autoScaleProfile(ContainerAutoScaler autoScaler, FabricRequirements requirements, ProfileRequirements profileRequirement) {
        String profile = profileRequirement.getProfile();
        Integer minimumInstances = profileRequirement.getMinimumInstances();
        if (minimumInstances != null) {
            // lets check if we need to provision more
            List<Container> containers = containersForProfile(profile);
            int count = containers.size();
            int delta = minimumInstances - count;
            try {
                if (delta < 0) {
                    autoScaler.destroyContainers(profile, -delta, containers);
                } else if (delta > 0) {
                    if (requirementsSatisfied(requirements, profileRequirement)) {
                        // TODO should we figure out the version from the requirements?
                        String version = fabricService.get().getDefaultVersion().getId();
                        autoScaler.createContainers(version, profile, delta);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to auto-scale " + profile + ". Caught: " + e, e);
            }
        }
    }

    private boolean requirementsSatisfied(FabricRequirements requirements, ProfileRequirements profileRequirement) {
        List<String> dependentProfiles = profileRequirement.getDependentProfiles();
        if (dependentProfiles != null) {
            for (String dependentProfile : dependentProfiles) {
                ProfileRequirements dependentProfileRequirements = requirements.getOrCreateProfileRequirement(dependentProfile);
                Integer minimumInstances = dependentProfileRequirements.getMinimumInstances();
                if (minimumInstances != null) {
                    List<Container> containers = containersForProfile(dependentProfile);
                    int dependentSize = containers.size();
                    if (minimumInstances > dependentSize) {
                        LOGGER.info("Cannot yet auto-scale profile " + profileRequirement.getProfile()
                                + " since dependent profile " + dependentProfile + " has only " + dependentSize
                                + " container(s) when it requires " + minimumInstances + " container(s)");
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private List<Container> containersForProfile(String profile) {
        return Containers.containersForProfile(fabricService.get().getContainers(), profile);
    }

    private AutoScalerNode createState() {
        AutoScalerNode state = new AutoScalerNode();
        state.setContainer(KARAF_NAME);
        return state;
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

    void bindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.set(service);
    }

    void unbindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.set(null);
    }

    void bindContainerAutoScaler(ContainerAutoScaler containerAutoScaler) {
        this.containerAutoScaler.set(containerAutoScaler);
    }

    void unbindContainerAutoScaler(ContainerAutoScaler containerAutoScaler) {
        this.containerAutoScaler.set(null);
    }
}
