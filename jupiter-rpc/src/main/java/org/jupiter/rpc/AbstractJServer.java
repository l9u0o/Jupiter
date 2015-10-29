package org.jupiter.rpc;

import org.jupiter.common.util.*;
import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;
import org.jupiter.registry.RegisterMeta;
import org.jupiter.registry.RegistryService;
import org.jupiter.rpc.annotation.ServiceProvider;
import org.jupiter.rpc.model.metadata.ServiceMetadata;
import org.jupiter.rpc.model.metadata.ServiceWrapper;

import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import static org.jupiter.common.util.JConstants.*;
import static org.jupiter.common.util.Preconditions.checkArgument;
import static org.jupiter.common.util.Preconditions.checkNotNull;

/**
 * jupiter
 * org.jupiter.rpc
 *
 * @author jiachun.fjc
 */
public abstract class AbstractJServer implements JServer {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractJServer.class);

    private final ServiceProviderContainer providerContainer = new DefaultServiceProviderContainer();
    // SPI
    private final RegistryService registryService = JServiceLoader.load(RegistryService.class);

    @Override
    public void initRegistryService(Object... args) {
        registryService.init(args);
    }

    @Override
    public ServiceRegistry serviceRegistry() {
        return new DefaultServiceRegistry();
    }

    @Override
    public ServiceWrapper lookupService(Directory directory) {
        return providerContainer.lookupService(directory.directory());
    }

    @Override
    public ServiceWrapper removeService(Directory directory) {
        return providerContainer.removeService(directory.directory());
    }

    @Override
    public List<ServiceWrapper> getRegisteredServices() {
        return providerContainer.getAllServices();
    }

    @Override
    public void publish(ServiceWrapper serviceWrapper, int port) {
        publish(serviceWrapper, null, port, DEFAULT_WEIGHT);
    }

    @Override
    public void publish(ServiceWrapper serviceWrapper, int port, int weight) {
        publish(serviceWrapper, null, port, weight);
    }

    @Override
    public void publish(ServiceWrapper serviceWrapper, String host, int port, int weight) {
        checkArgument(port > 0 && port < 0xFFFF, "port out of range:" + port);

        ServiceMetadata metadata = serviceWrapper.getMetadata();

        RegisterMeta meta = new RegisterMeta();
        meta.setHost(host);
        meta.setPort(port);
        meta.setGroup(metadata.getGroup());
        meta.setVersion(metadata.getVersion());
        meta.setServiceProviderName(metadata.getServiceProviderName());
        meta.setWeight(weight <= 0 ? DEFAULT_WEIGHT : weight);

        registryService.register(meta);
    }

    @Override
    public void publishAll(int port) {
        publishAll(null, port, DEFAULT_WEIGHT);
    }

    @Override
    public void publishAll(int port, int weight) {
        publishAll(null, port, weight);
    }

    @Override
    public void publishAll(String host, int port, int weight) {
        for (ServiceWrapper wrapper : providerContainer.getAllServices()) {
            publish(wrapper, host, port, weight);
        }
    }

    ServiceWrapper registerService(String group, String version, String name, Object serviceProvider, Executor executor) {
        ServiceWrapper serviceWrapper = new ServiceWrapper(group, version, name, serviceProvider);
        serviceWrapper.setExecutor(executor);

        providerContainer.registerService(serviceWrapper.getMetadata().directory(), serviceWrapper);

        return serviceWrapper;
    }

    class DefaultServiceRegistry implements ServiceRegistry {

        private Object serviceProvider;
        protected Executor executor;

        @Override
        public ServiceRegistry provider(Object serviceProvider) {
            this.serviceProvider = serviceProvider;
            return this;
        }

        @Override
        public ServiceRegistry executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        @Override
        public ServiceWrapper register() {
            checkNotNull(serviceProvider, "serviceProvider");

            Class<?>[] interfaces = serviceProvider.getClass().getInterfaces();
            ServiceProvider annotation = null;
            String name = null;
            if (interfaces != null) {
                for (Class<?> clazz : interfaces) {
                    annotation = clazz.getAnnotation(ServiceProvider.class);
                    if (annotation == null) {
                        continue;
                    }

                    name = annotation.value();
                    name = Strings.isNotBlank(name) ? name : clazz.getSimpleName();
                    break;
                }
            }
            checkArgument(annotation != null, serviceProvider.getClass() + " is not a ServiceProvider");

            String group = annotation.group();
            String version = annotation.version();

            checkNotNull(group, "group");
            checkNotNull(version, "version");

            return registerService(group, version, name, serviceProvider, executor);
        }
    }

    /**
     * Service provider 容器
     */
    interface ServiceProviderContainer {

        void registerService(String uniqueKey, ServiceWrapper serviceWrapper);

        ServiceWrapper lookupService(String uniqueKey);

        ServiceWrapper removeService(String uniqueKey);

        List<ServiceWrapper> getAllServices();
    }

    class DefaultServiceProviderContainer implements ServiceProviderContainer {

        private final ConcurrentMap<String, ServiceWrapper> serviceProviders = Maps.newConcurrentHashMap();

        @Override
        public void registerService(String uniqueKey, ServiceWrapper serviceWrapper) {
            serviceProviders.put(uniqueKey, serviceWrapper);

            logger.debug("ServiceProvider [{}, {}] is registered.", uniqueKey, serviceWrapper.getServiceProvider());
        }

        @Override
        public ServiceWrapper lookupService(String uniqueKey) {
            return serviceProviders.get(uniqueKey);
        }

        @Override
        public ServiceWrapper removeService(String uniqueKey) {
            ServiceWrapper provider = serviceProviders.remove(uniqueKey);
            if (provider == null) {
                logger.warn("ServiceProvider [{}] not found.", uniqueKey);
            } else {
                logger.debug("ServiceProvider [{}, {}] is removed.", uniqueKey, provider.getServiceProvider());
            }
            return provider;
        }

        @Override
        public List<ServiceWrapper> getAllServices() {
            return Lists.newArrayList(serviceProviders.values());
        }
    }
}
