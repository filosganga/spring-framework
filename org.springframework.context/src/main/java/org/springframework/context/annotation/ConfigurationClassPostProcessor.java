/*
 * Copyright 2002-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.context.annotation;

import static java.lang.String.format;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.PassThroughSourceExtractor;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ExecutorContext;
import org.springframework.context.FeatureSpecification;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.SourceAwareSpecification;
import org.springframework.context.SpecificationExecutor;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * {@link BeanFactoryPostProcessor} used for bootstrapping processing of
 * {@link Configuration @Configuration} classes.
 *
 * <p>Registered by default when using {@code <context:annotation-config/>} or
 * {@code <context:component-scan/>}. Otherwise, may be declared manually as
 * with any other BeanFactoryPostProcessor.
 *
 * <p>This post processor is {@link Ordered#HIGHEST_PRECEDENCE} as it is important
 * that any {@link Bean} methods declared in Configuration classes have their
 * respective bean definitions registered before any other BeanFactoryPostProcessor
 * executes.
 * 
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.0
 */
public class ConfigurationClassPostProcessor implements BeanDefinitionRegistryPostProcessor,
		ResourceLoaderAware, BeanClassLoaderAware, EnvironmentAware {

	/** Whether the CGLIB2 library is present on the classpath */
	private static final boolean cglibAvailable = ClassUtils.isPresent(
			"net.sf.cglib.proxy.Enhancer", ConfigurationClassPostProcessor.class.getClassLoader());


	private final Log logger = LogFactory.getLog(getClass());

	private SourceExtractor sourceExtractor = new PassThroughSourceExtractor();

	private ProblemReporter problemReporter = new FailFastProblemReporter();

	private ResourceLoader resourceLoader = new DefaultResourceLoader();

	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	private MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory();

	private boolean setMetadataReaderFactoryCalled = false;

	private boolean postProcessBeanDefinitionRegistryCalled = false;

	private boolean postProcessBeanFactoryCalled = false;

	private Environment environment;

	private ConfigurationClassBeanDefinitionReader reader;

	private EarlyBeanReferenceProxyStatus earlyBeanReferenceProxyStatus = new EarlyBeanReferenceProxyStatus();


	/**
	 * Set the {@link SourceExtractor} to use for generated bean definitions
	 * that correspond to {@link Bean} factory methods.
	 */
	public void setSourceExtractor(SourceExtractor sourceExtractor) {
		this.sourceExtractor = (sourceExtractor != null ? sourceExtractor : new PassThroughSourceExtractor());
	}

	/**
	 * Set the {@link ProblemReporter} to use.
	 * <p>Used to register any problems detected with {@link Configuration} or {@link Bean}
	 * declarations. For instance, an @Bean method marked as {@code final} is illegal
	 * and would be reported as a problem. Defaults to {@link FailFastProblemReporter}.
	 */
	public void setProblemReporter(ProblemReporter problemReporter) {
		this.problemReporter = (problemReporter != null ? problemReporter : new FailFastProblemReporter());
	}

	/**
	 * Set the {@link MetadataReaderFactory} to use.
	 * <p>Default is a {@link CachingMetadataReaderFactory} for the specified
	 * {@linkplain #setBeanClassLoader bean class loader}.
	 */
	public void setMetadataReaderFactory(MetadataReaderFactory metadataReaderFactory) {
		Assert.notNull(metadataReaderFactory, "MetadataReaderFactory must not be null");
		this.metadataReaderFactory = metadataReaderFactory;
		this.setMetadataReaderFactoryCalled = true;
	}

	public void setResourceLoader(ResourceLoader resourceLoader) {
		Assert.notNull(resourceLoader, "ResourceLoader must not be null");
		this.resourceLoader = resourceLoader;
	}

	public void setBeanClassLoader(ClassLoader beanClassLoader) {
		this.beanClassLoader = beanClassLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(beanClassLoader);
		}
	}

	public void setEnvironment(Environment environment) {
		Assert.notNull(environment, "Environment must not be null");
		this.environment = environment;
	}


	/**
	 * Derive further bean definitions from the configuration classes in the registry.
	 */
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		if (this.postProcessBeanDefinitionRegistryCalled) {
			throw new IllegalStateException(
					"postProcessBeanDefinitionRegistry already called for this post-processor");
		}
		if (this.postProcessBeanFactoryCalled) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called for this post-processor");
		}
		this.postProcessBeanDefinitionRegistryCalled = true;
		processConfigurationClasses(registry);
		processFeatureConfigurationClasses((ConfigurableListableBeanFactory) registry);
	}

	/**
	 * Prepare the Configuration classes for servicing bean requests at runtime
	 * by replacing them with CGLIB-enhanced subclasses.
	 */
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		if (this.postProcessBeanFactoryCalled) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called for this post-processor");
		}
		this.postProcessBeanFactoryCalled = true;
		if (!this.postProcessBeanDefinitionRegistryCalled) {
			// BeanDefinitionRegistryPostProcessor hook apparently not supported...
			// Simply call processConfigClasses lazily at this point then.
			processConfigurationClasses((BeanDefinitionRegistry)beanFactory);
			processFeatureConfigurationClasses(beanFactory);
		}
	}

	/**
	 * Find and process all @Configuration classes with @Feature methods in the given registry.
	 */
	private void processConfigurationClasses(BeanDefinitionRegistry registry) {
		ConfigurationClassBeanDefinitionReader reader = getConfigurationClassBeanDefinitionReader(registry);
		processConfigBeanDefinitions(registry, reader);
		enhanceConfigurationClasses((ConfigurableListableBeanFactory)registry);
	}

	/**
	 * Process any @FeatureConfiguration classes
	 */
	private void processFeatureConfigurationClasses(final ConfigurableListableBeanFactory beanFactory) {
		this.earlyBeanReferenceProxyStatus.createEarlyBeanReferenceProxies = true;
		Map<String, Object> featureConfigBeans = getFeatureConfigurationBeans(beanFactory);
		for (final Object featureConfigBean : featureConfigBeans.values()) {
			checkForBeanMethods(featureConfigBean.getClass());
		}
		for (final Object featureConfigBean : featureConfigBeans.values()) {
			ReflectionUtils.doWithMethods(featureConfigBean.getClass(),
					new ReflectionUtils.MethodCallback() {
						public void doWith(Method featureMethod) throws IllegalArgumentException, IllegalAccessException {
							processFeatureMethod(featureMethod, featureConfigBean, beanFactory);
						} },
					new ReflectionUtils.MethodFilter() {
						public boolean matches(Method candidateMethod) {
							if (AnnotationUtils.findAnnotation(candidateMethod, Bean.class) != null) {
								throw new FeatureMethodExecutionException(
										format("@FeatureConfiguration classes must not contain @Bean-annotated methods. " +
												"%s.%s() is annotated with @Bean and must be removed in order to proceed. " +
												"Consider moving this method into a dedicated @Configuration class and " +
												"injecting the bean as a parameter into any @Feature method(s) that need it.",
												candidateMethod.getDeclaringClass().getSimpleName(), candidateMethod.getName()));
							}
							return candidateMethod.isAnnotationPresent(Feature.class);
						} });
		}
		this.earlyBeanReferenceProxyStatus.createEarlyBeanReferenceProxies = false;
	}

	/**
	 * Alternative to {@link ListableBeanFactory#getBeansWithAnnotation(Class)} that avoids
	 * instantiating FactoryBean objects.  FeatureConfiguration types cannot be registered as
	 * FactoryBeans, so ignoring them won't cause a problem.  On the other hand, using gBWA()
	 * at this early phase of the container would cause all @Bean methods to be invoked, as they
	 * are ultimately FactoryBeans underneath.
	 */
	private Map<String, Object> getFeatureConfigurationBeans(ConfigurableListableBeanFactory beanFactory) {
		Map<String, Object> fcBeans = new HashMap<String, Object>();
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			if (!(beanDef instanceof AbstractBeanDefinition) || !((AbstractBeanDefinition)beanDef).hasBeanClass()) {
				continue;
			}
			Class<?> beanClass = ((AbstractBeanDefinition)beanDef).getBeanClass();
			if (AnnotationUtils.findAnnotation(beanClass, FeatureConfiguration.class) != null) {
				fcBeans.put(beanName, beanFactory.getBean(beanName));
			}
		}
		return fcBeans;
	}

	private void checkForBeanMethods(final Class<?> featureConfigClass) {
		ReflectionUtils.doWithMethods(featureConfigClass,
				new ReflectionUtils.MethodCallback() {
					public void doWith(Method beanMethod) throws IllegalArgumentException, IllegalAccessException {
						throw new FeatureMethodExecutionException(
								format("@FeatureConfiguration classes must not contain @Bean-annotated methods. " +
										"%s.%s() is annotated with @Bean and must be removed in order to proceed. " +
										"Consider moving this method into a dedicated @Configuration class and " +
										"injecting the bean as a parameter into any @Feature method(s) that need it.",
										beanMethod.getDeclaringClass().getSimpleName(), beanMethod.getName()));
					} },
				new ReflectionUtils.MethodFilter() {
					public boolean matches(Method candidateMethod) {
						return (AnnotationUtils.findAnnotation(candidateMethod, Bean.class) != null);
					} });
	}

	/**
	 * TODO SPR-7420: this method invokes user-supplied code, which is not going to fly for STS
	 * consider introducing some kind of check to see if we're in a tooling context and make guesses
	 * based on return type rather than actually invoking the method and processing the the specification
	 * object that returns.
	 * @param beanFactory 
	 * @throws SecurityException 
	 */
	private void processFeatureMethod(final Method featureMethod, Object configInstance, ConfigurableListableBeanFactory beanFactory) {
		try {
			// get the return type
			if (!(FeatureSpecification.class.isAssignableFrom(featureMethod.getReturnType()))) {
				// TODO: raise a Problem instead?
				throw new IllegalArgumentException("return type from @Feature methods must be assignable to FeatureSpecification");
			}

			List<Object> beanArgs = new ArrayList<Object>();
			Class<?>[] parameterTypes = featureMethod.getParameterTypes();
			for (int i = 0; i < parameterTypes.length; i++) {
				MethodParameter mp = new MethodParameter(featureMethod, i);
				DependencyDescriptor dd = new DependencyDescriptor(mp, true, false);
				beanArgs.add(createProxy(dd, beanFactory));
			}

			// reflectively invoke that method
			FeatureSpecification spec;
			featureMethod.setAccessible(true);
			spec = (FeatureSpecification) featureMethod.invoke(configInstance, beanArgs.toArray(new Object[beanArgs.size()]));

			Assert.notNull(spec,
					format("The specification returned from @Feature method %s.%s() must not be null",
							featureMethod.getDeclaringClass().getSimpleName(), featureMethod.getName()));

			SpecificationExecutor executor = BeanUtils.instantiateClass(spec.getExecutorType());

			if (spec instanceof SourceAwareSpecification) {
				((SourceAwareSpecification)spec).setSource(featureMethod);
				((SourceAwareSpecification)spec).setSourceName(featureMethod.getName());
			}
			executor.execute(spec, createExecutorContext(beanFactory));
		} catch (Exception ex) {
			throw new FeatureMethodExecutionException(ex);
		}
	}

	private Object createProxy(DependencyDescriptor dd, ConfigurableListableBeanFactory beanFactory) {
		EarlyBeanReferenceProxyCreator proxyCreator = new EarlyBeanReferenceProxyCreator(beanFactory, this.earlyBeanReferenceProxyStatus);
		return proxyCreator.createProxy(dd);
	}

	private ExecutorContext createExecutorContext(ConfigurableListableBeanFactory beanFactory) {
		final BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
		ExecutorContext executorContext = new ExecutorContext();
		executorContext.setEnvironment(this.environment);
		executorContext.setResourceLoader(this.resourceLoader);
		executorContext.setRegistry(registry);
		executorContext.setRegistrar(new SimpleComponentRegistrar(registry));
		return executorContext;
	}

	private ConfigurationClassBeanDefinitionReader getConfigurationClassBeanDefinitionReader(BeanDefinitionRegistry registry) {
		if (this.reader == null) {
			this.reader = new ConfigurationClassBeanDefinitionReader(
					registry, this.sourceExtractor, this.problemReporter, this.metadataReaderFactory, this.resourceLoader, this.environment);
		}
		return this.reader;
	}

	/**
	 * Build and validate a configuration model based on the registry of
	 * {@link Configuration} classes.
	 */
	public void processConfigBeanDefinitions(BeanDefinitionRegistry registry, ConfigurationClassBeanDefinitionReader reader) {
		Set<BeanDefinitionHolder> configCandidates = new LinkedHashSet<BeanDefinitionHolder>();
		for (String beanName : registry.getBeanDefinitionNames()) {
			BeanDefinition beanDef = registry.getBeanDefinition(beanName);
			if (ConfigurationClassBeanDefinitionReader.checkConfigurationClassCandidate(beanDef, this.metadataReaderFactory)) {
				configCandidates.add(new BeanDefinitionHolder(beanDef, beanName));
			}
		}

		// Return immediately if no @Configuration classes were found
		if (configCandidates.isEmpty()) {
			return;
		}

		// Populate a new configuration model by parsing each @Configuration classes
		ConfigurationClassParser parser = new ConfigurationClassParser(this.metadataReaderFactory, this.problemReporter, this.environment);
		for (BeanDefinitionHolder holder : configCandidates) {
			BeanDefinition bd = holder.getBeanDefinition();
			try {
				if (bd instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) bd).hasBeanClass()) {
					parser.parse(((AbstractBeanDefinition) bd).getBeanClass(), holder.getBeanName());
				}
				else {
					parser.parse(bd.getBeanClassName(), holder.getBeanName());
				}
			}
			catch (IOException ex) {
				throw new BeanDefinitionStoreException("Failed to load bean class: " + bd.getBeanClassName(), ex);
			}
		}
		parser.validate();

		// Read the model and create bean definitions based on its content
		reader.loadBeanDefinitions(parser.getConfigurationClasses());
	}

	/**
	 * Post-processes a BeanFactory in search of Configuration class BeanDefinitions;
	 * any candidates are then enhanced by a {@link ConfigurationClassEnhancer}.
	 * Candidate status is determined by BeanDefinition attribute metadata.
	 * @see ConfigurationClassEnhancer
	 */
	public void enhanceConfigurationClasses(ConfigurableListableBeanFactory beanFactory) {
		Map<String, AbstractBeanDefinition> configBeanDefs = new LinkedHashMap<String, AbstractBeanDefinition>();
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			if (ConfigurationClassBeanDefinitionReader.isFullConfigurationClass(beanDef)) {
				if (!(beanDef instanceof AbstractBeanDefinition)) {
					throw new BeanDefinitionStoreException("Cannot enhance @Configuration bean definition '" +
							beanName + "' since it is not stored in an AbstractBeanDefinition subclass");
				}
				configBeanDefs.put(beanName, (AbstractBeanDefinition) beanDef);
			}
		}
		if (configBeanDefs.isEmpty()) {
			// nothing to enhance -> return immediately
			return;
		}
		if (!cglibAvailable) {
			throw new IllegalStateException("CGLIB is required to process @Configuration classes. " +
					"Either add CGLIB to the classpath or remove the following @Configuration bean definitions: " +
					configBeanDefs.keySet());
		}
		ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer(beanFactory, this.earlyBeanReferenceProxyStatus);
		for (Map.Entry<String, AbstractBeanDefinition> entry : configBeanDefs.entrySet()) {
			AbstractBeanDefinition beanDef = entry.getValue();
			try {
				Class<?> configClass = beanDef.resolveBeanClass(this.beanClassLoader);
				Class<?> enhancedClass = enhancer.enhance(configClass);
				if (logger.isDebugEnabled()) {
					logger.debug(String.format("Replacing bean definition '%s' existing class name '%s' " +
							"with enhanced class name '%s'", entry.getKey(), configClass.getName(), enhancedClass.getName()));
				}
				beanDef.setBeanClass(enhancedClass);
			}
			catch (Throwable ex) {
				throw new IllegalStateException("Cannot load configuration class: " + beanDef.getBeanClassName(), ex);
			}
		}
	}

}
