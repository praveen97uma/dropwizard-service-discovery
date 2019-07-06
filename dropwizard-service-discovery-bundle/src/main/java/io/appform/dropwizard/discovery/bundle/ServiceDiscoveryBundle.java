/*
 * Copyright (c) 2019 Santanu Sinha <santanu.sinha@gmail.com>
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
 *
 */

package io.appform.dropwizard.discovery.bundle;

import com.codahale.metrics.health.HealthCheck;
import com.flipkart.ranger.ServiceProviderBuilders;
import com.flipkart.ranger.healthcheck.Healthcheck;
import com.flipkart.ranger.healthcheck.HealthcheckStatus;
import com.flipkart.ranger.healthservice.monitor.IsolatedHealthMonitor;
import com.flipkart.ranger.serviceprovider.ServiceProvider;
import com.flipkart.ranger.serviceprovider.ServiceProviderBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import io.appform.dropwizard.discovery.bundle.id.IdGenerator;
import io.appform.dropwizard.discovery.bundle.id.NodeIdManager;
import io.appform.dropwizard.discovery.bundle.id.constraints.IdValidationConstraint;
import io.appform.dropwizard.discovery.bundle.rotationstatus.BIRTask;
import io.appform.dropwizard.discovery.bundle.rotationstatus.OORTask;
import io.appform.dropwizard.discovery.bundle.rotationstatus.RotationStatus;
import io.appform.dropwizard.discovery.client.ServiceDiscoveryClient;
import io.appform.dropwizard.discovery.common.ShardInfo;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryForever;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

/**
 * A dropwizard bundle for service discovery.
 */
@Slf4j
public abstract class ServiceDiscoveryBundle<T extends Configuration> implements ConfiguredBundle<T> {

    private ServiceDiscoveryConfiguration serviceDiscoveryConfiguration;
    private List<Healthcheck> healthchecks = Lists.newArrayList();
    private ServiceProvider<ShardInfo> serviceProvider;

    private final List<IdValidationConstraint> globalIdConstraints;

    @Getter
    private CuratorFramework curator;

    @Getter
    private ServiceDiscoveryClient serviceDiscoveryClient;

    @Getter
    @VisibleForTesting
    private RotationStatus rotationStatus;

    protected ServiceDiscoveryBundle() {
        globalIdConstraints = Collections.emptyList();
    }

    protected ServiceDiscoveryBundle(List<IdValidationConstraint> globalIdConstraints) {
        this.globalIdConstraints = globalIdConstraints != null
                ? globalIdConstraints
                : Collections.emptyList();
    }

    @Override
    public void initialize(Bootstrap<?> bootstrap) {

    }

    @Override
    public void run(T configuration, Environment environment) throws Exception {
        serviceDiscoveryConfiguration = getRangerConfiguration(configuration);
        val objectMapper = environment.getObjectMapper();
        final String namespace = serviceDiscoveryConfiguration.getNamespace();
        final String serviceName = getServiceName(configuration);
        final String hostname = getHost();
        final int port = getPort(configuration);
        final long validdRegistationTime = System.currentTimeMillis() + serviceDiscoveryConfiguration.getInitialDelaySeconds() * 1000;
        rotationStatus = new RotationStatus(serviceDiscoveryConfiguration.isInitialRotationStatus());

        int refreshTime = serviceDiscoveryConfiguration.getRefreshTimeMs() == 0
                ? 1000
                : serviceDiscoveryConfiguration.getRefreshTimeMs();
        curator = CuratorFrameworkFactory.builder()
                .connectString(serviceDiscoveryConfiguration.getZookeeper())
                .namespace(namespace)
                .retryPolicy(new RetryForever(serviceDiscoveryConfiguration.getConnectionRetryIntervalMillis()))
                .build();

        ServiceProviderBuilder<ShardInfo> serviceProviderBuilder = ServiceProviderBuilders.<ShardInfo>shardedServiceProviderBuilder()
                .withCuratorFramework(curator)
                .withNamespace(namespace)
                .withServiceName(serviceName)
                .withSerializer(data -> {
                    try {
                        return objectMapper.writeValueAsBytes(data);
                    }
                    catch (Exception e) {
                        log.warn("Could not parse node data", e);
                    }
                    return null;
                })
                .withHostname(hostname)
                .withPort(port)
                .withNodeData(ShardInfo.builder()
                                      .environment(serviceDiscoveryConfiguration.getEnvironment())
                                      .build())
                //Standard healthchecks
                .withHealthcheck(() -> {
                    for (Healthcheck healthcheck : healthchecks) {
                        if (HealthcheckStatus.unhealthy == healthcheck.check()) {
                            return HealthcheckStatus.unhealthy;
                        }
                    }
                    return HealthcheckStatus.healthy;
                })
                //This allows the node to be taken offline in the cluster but still keep running

                .withHealthcheck(() -> (rotationStatus.status())
                        ? HealthcheckStatus.healthy
                        : HealthcheckStatus.unhealthy)
                //The following will return healthy only after stipulated time
                //This will give other bundles eetc to startup properly
                //By the time the node joins the cluster
                .withHealthcheck(() -> System.currentTimeMillis() > validdRegistationTime
                        ? HealthcheckStatus.healthy
                        : HealthcheckStatus.unhealthy)
                .withHealthcheck(() -> (null != environment.healthChecks()
                        && environment.healthChecks()
                        .runHealthChecks()
                        .values()
                        .stream()
                        .allMatch(HealthCheck.Result::isHealthy))
                        ? HealthcheckStatus.healthy
                        : HealthcheckStatus.unhealthy)
                .withHealthUpdateIntervalMs(refreshTime);

        List<IsolatedHealthMonitor> healthMonitors = getHealthMonitors();
        if (healthMonitors != null && !healthMonitors.isEmpty()) {
            healthMonitors.forEach(serviceProviderBuilder::withIsolatedHealthMonitor);
        }
        serviceProvider = serviceProviderBuilder.buildServiceDiscovery();
        serviceDiscoveryClient = ServiceDiscoveryClient.fromCurator()
                .curator(curator)
                .namespace(namespace)
                .serviceName(serviceName)
                .environment(serviceDiscoveryConfiguration.getEnvironment())
                .objectMapper(environment.getObjectMapper())
                .refreshTimeMs(refreshTime)
                .disableWatchers(serviceDiscoveryConfiguration.isDisableWatchers())
                .build();

        environment.lifecycle()
                .manage(new Managed() {
                    @Override
                    public void start() throws Exception {
                        curator.start();
                        serviceProvider.start();
                        serviceDiscoveryClient.start();
                        NodeIdManager nodeIdManager = new NodeIdManager(curator, serviceName);
                        IdGenerator.initialize(nodeIdManager.fixNodeId(), globalIdConstraints, Collections.emptyMap());
                    }

                    @Override
                    public void stop() throws Exception {
                        serviceDiscoveryClient.stop();
                        serviceProvider.stop();
                        curator.close();
                    }
                });

        environment.jersey()
                .register(new InfoResource(serviceDiscoveryClient));
        environment.admin()
                .addTask(new OORTask(rotationStatus));
        environment.admin()
                .addTask(new BIRTask(rotationStatus));
    }

    protected abstract ServiceDiscoveryConfiguration getRangerConfiguration(T configuration);

    protected abstract String getServiceName(T configuration);

    protected List<IsolatedHealthMonitor> getHealthMonitors() {
        return Lists.newArrayList();
    }

    protected int getPort(T configuration) {
        Preconditions.checkArgument(
                Constants.DEFAULT_PORT != serviceDiscoveryConfiguration.getPublishedPort()
                        && 0 != serviceDiscoveryConfiguration.getPublishedPort(),
                "Looks like publishedPost has not been set and getPort() has not been overridden. This is wrong. \n" +
                        "Either set publishedPort in config or override getPort() to return the port on which the service is running");
        return serviceDiscoveryConfiguration.getPublishedPort();
    }

    protected String getHost() throws UnknownHostException {
        final String host = serviceDiscoveryConfiguration.getPublishedHost();

        if (Strings.isNullOrEmpty(host) || host.equals(Constants.DEFAULT_HOST)) {
            return InetAddress.getLocalHost()
                    .getCanonicalHostName();
        }
        return host;
    }

    public void registerHealthcheck(Healthcheck healthcheck) {
        this.healthchecks.add(healthcheck);
    }

    public void registerHealthchecks(List<Healthcheck> healthchecks) {
        this.healthchecks.addAll(healthchecks);
    }

}