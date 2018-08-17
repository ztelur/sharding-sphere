/*
 * Copyright 2016-2018 shardingsphere.io.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package io.shardingsphere.proxy;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import io.shardingsphere.jdbc.orchestration.internal.OrchestrationFacade;
import io.shardingsphere.jdbc.orchestration.internal.OrchestrationProxyConfiguration;
import io.shardingsphere.jdbc.orchestration.internal.config.ConfigurationService;
import io.shardingsphere.jdbc.orchestration.internal.eventbus.state.circuit.CircuitStateEventBusInstance;
import io.shardingsphere.jdbc.orchestration.internal.eventbus.state.disabled.DisabledStateEventBusInstance;
import io.shardingsphere.jdbc.orchestration.internal.eventbus.config.proxy.ProxyConfigurationEventBusInstance;
import io.shardingsphere.proxy.config.RuleRegistry;
import io.shardingsphere.proxy.frontend.ShardingProxy;
import io.shardingsphere.proxy.listener.ProxyListenerRegister;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Sharding-Proxy Bootstrap.
 *
 * @author zhangliang
 * @author wangkai
 * @author panjuan
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Bootstrap {
    
    private static final int DEFAULT_PORT = 3307;
    
    private static final String DEFAULT_CONFIG_PATH = "/conf/";
    
    private static final String DEFAULT_CONFIG_FILE = "config.yaml";
    
    private static final RuleRegistry RULE_REGISTRY = RuleRegistry.getInstance();
    
    /**
     * Main Entrance.
     * 
     * @param args startup arguments
     * @throws InterruptedException interrupted exception
     * @throws IOException IO exception
     */
    public static void main(final String[] args) throws InterruptedException, IOException {
        ProxyListenerRegister.register();
        OrchestrationProxyConfiguration localConfig = loadLocalConfiguration(new File(Bootstrap.class.getResource(getConfig(args)).getFile()));
        int port = getPort(args);
        if (null == localConfig.getOrchestration()) {
            startWithoutRegistryCenter(localConfig, port);
        } else {
            startWithRegistryCenter(localConfig, port);
        }
    }
    
    private static OrchestrationProxyConfiguration loadLocalConfiguration(final File yamlFile) throws IOException {
        try (
                FileInputStream fileInputStream = new FileInputStream(yamlFile);
                InputStreamReader inputStreamReader = new InputStreamReader(fileInputStream, "UTF-8")
        ) {
            OrchestrationProxyConfiguration result = new Yaml(new Constructor(OrchestrationProxyConfiguration.class)).loadAs(inputStreamReader, OrchestrationProxyConfiguration.class);
            Preconditions.checkNotNull(result, String.format("Configuration file `%s` is invalid.", yamlFile.getName()));
            Preconditions.checkState(!result.getDataSources().isEmpty() || null != result.getOrchestration(), "Data sources configuration can not be empty.");
            Preconditions.checkState(null != result.getShardingRule() || null != result.getMasterSlaveRule() || null != result.getOrchestration(), 
                    "Configuration invalid, sharding rule, local and orchestration configuration can not be both null.");
            Preconditions.checkState(!Strings.isNullOrEmpty(result.getProxyAuthority().getUsername()) || null != result.getOrchestration(), "Authority configuration is invalid.");
            return result;
        }
    }
    
    private static int getPort(final String[] args) {
        if (0 == args.length) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(args[0]);
        } catch (final NumberFormatException ex) {
            return DEFAULT_PORT;
        }
    }
    
    private static String getConfig(final String[] args) {
        if (2 != args.length) {
            return DEFAULT_CONFIG_PATH + DEFAULT_CONFIG_FILE;
        }
        return DEFAULT_CONFIG_PATH + args[1];
    }
    
    private static void startWithoutRegistryCenter(final OrchestrationProxyConfiguration config, final int port) throws InterruptedException {
        RULE_REGISTRY.init(config);
        new ShardingProxy().start(port);
    }
    
    private static void startWithRegistryCenter(final OrchestrationProxyConfiguration localConfig, final int port) throws InterruptedException {
        try (OrchestrationFacade orchestrationFacade = new OrchestrationFacade(localConfig.getOrchestration().getOrchestrationConfiguration())) {
            if (null != localConfig.getShardingRule() || null != localConfig.getMasterSlaveRule()) {
                orchestrationFacade.init(localConfig);
            }
            initRuleRegistry(orchestrationFacade.getConfigService());
            new ShardingProxy().start(port);
        }
    }
    
    private static void initRuleRegistry(final ConfigurationService configService) {
        RULE_REGISTRY.init(new OrchestrationProxyConfiguration(configService.loadDataSources(), configService.loadProxyConfiguration()));
        ProxyConfigurationEventBusInstance.getInstance().register(RULE_REGISTRY);
        DisabledStateEventBusInstance.getInstance().register(RULE_REGISTRY);
        CircuitStateEventBusInstance.getInstance().register(RULE_REGISTRY);
    }
}
