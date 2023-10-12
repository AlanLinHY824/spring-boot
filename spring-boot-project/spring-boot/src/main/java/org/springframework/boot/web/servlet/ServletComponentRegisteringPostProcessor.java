/*
 * Copyright 2012-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.web.servlet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.TypeReference;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@link BeanFactoryPostProcessor} that registers beans for Servlet components found via
 * package scanning.
 *
 * @author Andy Wilkinson
 * @see ServletComponentScan
 * @see ServletComponentScanRegistrar
 */
class ServletComponentRegisteringPostProcessor
		implements BeanFactoryPostProcessor, ApplicationContextAware, BeanFactoryInitializationAotProcessor {

	private static final List<ServletComponentHandler> HANDLERS;

	static {
		List<ServletComponentHandler> servletComponentHandlers = new ArrayList<>();
		servletComponentHandlers.add(new WebServletHandler());
		servletComponentHandlers.add(new WebFilterHandler());
		servletComponentHandlers.add(new WebListenerHandler());
		HANDLERS = Collections.unmodifiableList(servletComponentHandlers);
	}

	private final Set<String> packagesToScan;

	private ApplicationContext applicationContext;

	ServletComponentRegisteringPostProcessor(Set<String> packagesToScan) {
		this.packagesToScan = packagesToScan;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		if (isRunningInEmbeddedWebServer()) {
			ClassPathScanningCandidateComponentProvider componentProvider = createComponentProvider();
			for (String packageToScan : this.packagesToScan) {
				scanPackage(componentProvider, packageToScan);
			}
		}
	}

	private void scanPackage(ClassPathScanningCandidateComponentProvider componentProvider, String packageToScan) {
		for (BeanDefinition candidate : componentProvider.findCandidateComponents(packageToScan)) {
			if (candidate instanceof AnnotatedBeanDefinition annotatedBeanDefinition) {
				for (ServletComponentHandler handler : HANDLERS) {
					handler.handle(annotatedBeanDefinition, (BeanDefinitionRegistry) this.applicationContext);
				}
			}
		}
	}

	private boolean isRunningInEmbeddedWebServer() {
		return this.applicationContext instanceof WebApplicationContext webApplicationContext
				&& webApplicationContext.getServletContext() == null;
	}

	private ClassPathScanningCandidateComponentProvider createComponentProvider() {
		ClassPathScanningCandidateComponentProvider componentProvider = new ClassPathScanningCandidateComponentProvider(
				false);
		componentProvider.setEnvironment(this.applicationContext.getEnvironment());
		componentProvider.setResourceLoader(this.applicationContext);
		for (ServletComponentHandler handler : HANDLERS) {
			componentProvider.addIncludeFilter(handler.getTypeFilter());
		}
		return componentProvider;
	}

	Set<String> getPackagesToScan() {
		return Collections.unmodifiableSet(this.packagesToScan);
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	@Override
	public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
		return new BeanFactoryInitializationAotContribution() {

			@Override
			public void applyTo(GenerationContext generationContext,
					BeanFactoryInitializationCode beanFactoryInitializationCode) {
				for (String beanName : beanFactory.getBeanDefinitionNames()) {
					BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
					if (Objects.equals(definition.getBeanClassName(),
							WebListenerHandler.ServletComponentWebListenerRegistrar.class.getName())) {
						String listenerClassName = (String) definition.getConstructorArgumentValues()
							.getArgumentValue(0, String.class)
							.getValue();
						generationContext.getRuntimeHints()
							.reflection()
							.registerType(TypeReference.of(listenerClassName),
									MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
					}
				}
			}

		};
	}

}
