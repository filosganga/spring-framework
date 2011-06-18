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

package org.springframework.test.context.support;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Unit tests for {@link AnnotationConfigContextLoader}.
 * 
 * @author Sam Brannen
 * @since 3.1
 */
public class AnnotationConfigContextLoaderTests {

	private final AnnotationConfigContextLoader contextLoader = new AnnotationConfigContextLoader();


	// Developer's note: AnnotationConfigContextLoader currently generates a
	// default config class named exactly ContextConfiguration, which is a
	// static inner class of the test class itself.

	@Test
	public void generateDefaultConfigurationClassesForAnnotatedInnerClassNamedContextConfiguration() {
		Class<?>[] defaultLocations = contextLoader.generateDefaultConfigurationClasses(ContextConfigurationInnerClassTestCase.class);
		assertNotNull(defaultLocations);
		assertEquals("ContextConfigurationInnerClassTestCase.ContextConfiguration should be found", 1,
			defaultLocations.length);
	}

	@Test
	public void generateDefaultConfigurationClassesForAnnotatedInnerClass() {
		Class<?>[] defaultLocations = contextLoader.generateDefaultConfigurationClasses(AnnotatedFooConfigInnerClassTestCase.class);
		assertNotNull(defaultLocations);
		assertEquals("AnnotatedFooConfigInnerClassTestCase.FooConfig should NOT be found", 0, defaultLocations.length);
	}

	@Test
	public void generateDefaultConfigurationClassesForNonAnnotatedInnerClass() {
		Class<?>[] defaultLocations = contextLoader.generateDefaultConfigurationClasses(PlainVanillaFooConfigInnerClassTestCase.class);
		assertNotNull(defaultLocations);
		assertEquals("PlainVanillaFooConfigInnerClassTestCase.FooConfig should NOT be found", 0,
			defaultLocations.length);
	}

}