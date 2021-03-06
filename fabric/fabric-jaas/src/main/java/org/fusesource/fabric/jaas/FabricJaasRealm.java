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
package org.fusesource.fabric.jaas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.security.auth.login.AppConfigurationEntry;

import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.karaf.jaas.boot.ProxyLoginModule;
import org.apache.karaf.jaas.config.JaasRealm;
import org.fusesource.fabric.api.jcip.ThreadSafe;
import org.fusesource.fabric.api.scr.AbstractComponent;
import org.fusesource.fabric.api.scr.ValidatingReference;
import org.osgi.framework.BundleContext;

@ThreadSafe
@Component(name = "org.fusesource.fabric.jaas", description = "Fabric Jaas Realm") // Done
@Service(JaasRealm.class)
public final class FabricJaasRealm extends AbstractComponent implements JaasRealm {

    private static final String REALM = "karaf";
    private static final String ZK_LOGIN_MODULE = "org.fusesource.fabric.jaas.ZookeeperLoginModule";

    private static final String PATH = "path";
    private static final String ENCRYPTION_NAME = "encryption.name";
    private static final String ENCRYPTION_ENABLED = "encryption.enabled";
    private static final String ENCRYPTION_PREFIX = "encryption.prefix";
    private static final String ENCRYPTION_SUFFIX = "encryption.suffix";
    private static final String ENCRYPTION_ALGORITHM = "encryption.algorithm";
    private static final String ENCRYPTION_ENCODING = "encryption.encoding";
    private static final String MODULE = "org.apache.karaf.jaas.module";

    @Property(name = MODULE, value = ZK_LOGIN_MODULE)
    private String module;
    @Property(name = ENCRYPTION_NAME, value = "")
    private String encryptionName;
    @Property(name = ENCRYPTION_ENABLED, boolValue = true)
    private Boolean encryptionEnabled;
    @Property(name = ENCRYPTION_PREFIX, value = "{CRYPT}")
    private String encryptionPrefix;
    @Property(name = ENCRYPTION_SUFFIX, value = "{CRYPT}")
    private String encryptionSuffix;
    @Property(name = ENCRYPTION_ALGORITHM, value = "MD5")
    private String encryptionAlgorithm;
    @Property(name = ENCRYPTION_ENCODING, value = "hexadecimal")
    private String encryptionEncoding;
    @Property(name = PATH, value = "/fabric/authentication/users")
    private String path;

    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();

    private final List<AppConfigurationEntry> enties = new ArrayList<AppConfigurationEntry>();

    @Activate
    void activate(BundleContext bundleContext, Map<String, Object> properties) {
        activateComponent();
        try {
            Map<String, Object> options = new HashMap<String, Object>();
            options.putAll(properties);
            options.put(BundleContext.class.getName(), bundleContext);
            options.put(ProxyLoginModule.PROPERTY_MODULE, ZK_LOGIN_MODULE);
            options.put(ProxyLoginModule.PROPERTY_BUNDLE, Long.toString(bundleContext.getBundle().getBundleId()));
            enties.add(new AppConfigurationEntry(ProxyLoginModule.class.getName(), AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options));
        } catch (RuntimeException rte) {
            deactivateComponent();
            throw rte;
        }
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }

    @Override
    public String getName() {
        assertValid();
        return REALM;
    }

    @Override
    public int getRank() {
        assertValid();
        return 1;
    }

    @Override
    public AppConfigurationEntry[] getEntries() {
        assertValid();
        return enties.toArray(new AppConfigurationEntry[enties.size()]);
    }

    void bindCurator(CuratorFramework curator) {
        this.curator.set(curator);
    }

    void unbindCurator(CuratorFramework curator) {
        this.curator.set(null);
    }
}
