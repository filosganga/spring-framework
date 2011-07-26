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

package org.springframework.core.annotation;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.util.Assert;

/**
 * General utility methods for working with annotations, handling bridge methods (which the compiler
 * generates for generic declarations) as well as super methods (for optional &quot;annotation inheritance&quot;).
 * Note that none of this is provided by the JDK's introspection facilities themselves.
 *
 * <p>As a general rule for runtime-retained annotations (e.g. for transaction control, authorization or service
 * exposure), always use the lookup methods on this class (e.g., {@link #findAnnotation(Method, Class)}, {@link
 * #getAnnotation(Method, Class)}, and {@link #getAnnotations(Method)}) instead of the plain annotation lookup
 * methods in the JDK. You can still explicitly choose between lookup on the given class level only ({@link
 * #getAnnotation(Method, Class)}) and lookup in the entire inheritance hierarchy of the given method ({@link
 * #findAnnotation(Method, Class)}).
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Mark Fisher
 * @author Chris Beams
 * @since 2.0
 * @see java.lang.reflect.Method#getAnnotations()
 * @see java.lang.reflect.Method#getAnnotation(Class)
 */
public abstract class AnnotationUtils {

	/** The attribute name for annotations with a single element */
	static final String VALUE = "value";

	private static final Map<Class<?>, Boolean> annotatedInterfaceCache = new WeakHashMap<Class<?>, Boolean>();


	/**
	 * Get a single {@link Annotation} of {@code annotationType} from the supplied
	 * Method, Constructor or Field. Meta-annotations will be searched if the annotation
	 * is not declared locally on the supplied element.
	 * @param ae the Method, Constructor or Field from which to get the annotation
	 * @param annotationType the annotation class to look for, both locally and as a meta-annotation
	 * @return the matching annotation or {@code null} if not found
	 * @since 3.1
	 */
	public static <T extends Annotation> T getAnnotation(AnnotatedElement ae, Class<T> annotationType) {
		T ann = ae.getAnnotation(annotationType);
		if (ann == null) {
			for (Annotation metaAnn : ae.getAnnotations()) {
				ann = metaAnn.annotationType().getAnnotation(annotationType);
				if (ann != null) {
					break;
				}
			}
		}
		return ann;
	}

	/**
	 * Get all {@link Annotation Annotations} from the supplied {@link Method}.
	 * <p>Correctly handles bridge {@link Method Methods} generated by the compiler.
	 * @param method the method to look for annotations on
	 * @return the annotations found
	 * @see org.springframework.core.BridgeMethodResolver#findBridgedMethod(Method)
	 */
	public static Annotation[] getAnnotations(Method method) {
		return BridgeMethodResolver.findBridgedMethod(method).getAnnotations();
	}

	/**
	 * Get a single {@link Annotation} of <code>annotationType</code> from the supplied {@link Method}.
	 * <p>Correctly handles bridge {@link Method Methods} generated by the compiler.
	 * @param method the method to look for annotations on
	 * @param annotationType the annotation class to look for
	 * @return the annotations found
	 * @see org.springframework.core.BridgeMethodResolver#findBridgedMethod(Method)
	 */
	public static <A extends Annotation> A getAnnotation(Method method, Class<A> annotationType) {
		Method resolvedMethod = BridgeMethodResolver.findBridgedMethod(method);
		A ann = resolvedMethod.getAnnotation(annotationType);
		if (ann == null) {
			for (Annotation metaAnn : resolvedMethod.getAnnotations()) {
				ann = metaAnn.annotationType().getAnnotation(annotationType);
				if (ann != null) {
					break;
				}
			}
		}
		return ann;
	}

	/**
	 * Get a single {@link Annotation} of <code>annotationType</code> from the supplied {@link Method},
	 * traversing its super methods if no annotation can be found on the given method itself.
	 * <p>Annotations on methods are not inherited by default, so we need to handle this explicitly.
	 * @param method the method to look for annotations on
	 * @param annotationType the annotation class to look for
	 * @return the annotation found, or <code>null</code> if none found
	 */
	public static <A extends Annotation> A findAnnotation(Method method, Class<A> annotationType) {
		A annotation = getAnnotation(method, annotationType);
		Class<?> cl = method.getDeclaringClass();
		if (annotation == null) {
			annotation = searchOnInterfaces(method, annotationType, cl.getInterfaces());
		}
		while (annotation == null) {
			cl = cl.getSuperclass();
			if (cl == null || cl == Object.class) {
				break;
			}
			try {
				Method equivalentMethod = cl.getDeclaredMethod(method.getName(), method.getParameterTypes());
				annotation = getAnnotation(equivalentMethod, annotationType);
				if (annotation == null) {
					annotation = searchOnInterfaces(method, annotationType, cl.getInterfaces());
				}
			}
			catch (NoSuchMethodException ex) {
				// We're done...
			}
		}
		return annotation;
	}

	private static <A extends Annotation> A searchOnInterfaces(Method method, Class<A> annotationType, Class<?>[] ifcs) {
		A annotation = null;
		for (Class<?> iface : ifcs) {
			if (isInterfaceWithAnnotatedMethods(iface)) {
				try {
					Method equivalentMethod = iface.getMethod(method.getName(), method.getParameterTypes());
					annotation = getAnnotation(equivalentMethod, annotationType);
				}
				catch (NoSuchMethodException ex) {
					// Skip this interface - it doesn't have the method...
				}
				if (annotation != null) {
					break;
				}
			}
		}
		return annotation;
	}

	private static boolean isInterfaceWithAnnotatedMethods(Class<?> iface) {
		synchronized (annotatedInterfaceCache) {
			Boolean flag = annotatedInterfaceCache.get(iface);
			if (flag != null) {
				return flag;
			}
			boolean found = false;
			for (Method ifcMethod : iface.getMethods()) {
				if (ifcMethod.getAnnotations().length > 0) {
					found = true;
					break;
				}
			}
			annotatedInterfaceCache.put(iface, found);
			return found;
		}
	}

	/**
	 * Find a single {@link Annotation} of <code>annotationType</code> from the supplied {@link Class},
	 * traversing its interfaces and superclasses if no annotation can be found on the given class itself.
	 * <p>This method explicitly handles class-level annotations which are not declared as
	 * {@link java.lang.annotation.Inherited inherited} <i>as well as annotations on interfaces</i>.
	 * <p>The algorithm operates as follows: Searches for an annotation on the given class and returns
	 * it if found. Else searches all interfaces that the given class declares, returning the annotation
	 * from the first matching candidate, if any. Else proceeds with introspection of the superclass
	 * of the given class, checking the superclass itself; if no annotation found there, proceeds
	 * with the interfaces that the superclass declares. Recursing up through the entire superclass
	 * hierarchy if no match is found.
	 * @param clazz the class to look for annotations on
	 * @param annotationType the annotation class to look for
	 * @return the annotation found, or <code>null</code> if none found
	 */
	public static <A extends Annotation> A findAnnotation(Class<?> clazz, Class<A> annotationType) {
		Assert.notNull(clazz, "Class must not be null");
		A annotation = clazz.getAnnotation(annotationType);
		if (annotation != null) {
			return annotation;
		}
		for (Class<?> ifc : clazz.getInterfaces()) {
			annotation = findAnnotation(ifc, annotationType);
			if (annotation != null) {
				return annotation;
			}
		}
		if (!Annotation.class.isAssignableFrom(clazz)) {
			for (Annotation ann : clazz.getAnnotations()) {
				annotation = findAnnotation(ann.annotationType(), annotationType);
				if (annotation != null) {
					return annotation;
				}
			}
		}
		Class<?> superClass = clazz.getSuperclass();
		if (superClass == null || superClass == Object.class) {
			return null;
		}
		return findAnnotation(superClass, annotationType);
	}

	/**
	 * Find the first {@link Class} in the inheritance hierarchy of the specified <code>clazz</code>
	 * (including the specified <code>clazz</code> itself) which declares an annotation for the
	 * specified <code>annotationType</code>, or <code>null</code> if not found. If the supplied
	 * <code>clazz</code> is <code>null</code>, <code>null</code> will be returned.
	 * <p>If the supplied <code>clazz</code> is an interface, only the interface itself will be checked;
	 * the inheritance hierarchy for interfaces will not be traversed.
	 * <p>The standard {@link Class} API does not provide a mechanism for determining which class
	 * in an inheritance hierarchy actually declares an {@link Annotation}, so we need to handle
	 * this explicitly.
	 * @param annotationType the Class object corresponding to the annotation type
	 * @param clazz the Class object corresponding to the class on which to check for the annotation,
	 * or <code>null</code>
	 * @return the first {@link Class} in the inheritance hierarchy of the specified <code>clazz</code>
	 * which declares an annotation for the specified <code>annotationType</code>, or <code>null</code>
	 * if not found
	 * @see Class#isAnnotationPresent(Class)
	 * @see Class#getDeclaredAnnotations()
	 */
	public static Class<?> findAnnotationDeclaringClass(Class<? extends Annotation> annotationType, Class<?> clazz) {
		Assert.notNull(annotationType, "Annotation type must not be null");
		if (clazz == null || clazz.equals(Object.class)) {
			return null;
		}
		return (isAnnotationDeclaredLocally(annotationType, clazz)) ? clazz :
				findAnnotationDeclaringClass(annotationType, clazz.getSuperclass());
	}

	/**
	 * Determine whether an annotation for the specified <code>annotationType</code> is
	 * declared locally on the supplied <code>clazz</code>. The supplied {@link Class}
	 * may represent any type.
	 * <p>Note: This method does <strong>not</strong> determine if the annotation is
	 * {@link java.lang.annotation.Inherited inherited}. For greater clarity regarding inherited
	 * annotations, consider using {@link #isAnnotationInherited(Class, Class)} instead.
	 * @param annotationType the Class object corresponding to the annotation type
	 * @param clazz the Class object corresponding to the class on which to check for the annotation
	 * @return <code>true</code> if an annotation for the specified <code>annotationType</code>
	 * is declared locally on the supplied <code>clazz</code>
	 * @see Class#getDeclaredAnnotations()
	 * @see #isAnnotationInherited(Class, Class)
	 */
	public static boolean isAnnotationDeclaredLocally(Class<? extends Annotation> annotationType, Class<?> clazz) {
		Assert.notNull(annotationType, "Annotation type must not be null");
		Assert.notNull(clazz, "Class must not be null");
		boolean declaredLocally = false;
		for (Annotation annotation : Arrays.asList(clazz.getDeclaredAnnotations())) {
			if (annotation.annotationType().equals(annotationType)) {
				declaredLocally = true;
				break;
			}
		}
		return declaredLocally;
	}

	/**
	 * Determine whether an annotation for the specified <code>annotationType</code> is present
	 * on the supplied <code>clazz</code> and is {@link java.lang.annotation.Inherited inherited}
	 * i.e., not declared locally for the class).
	 * <p>If the supplied <code>clazz</code> is an interface, only the interface itself will be checked.
	 * In accordance with standard meta-annotation semantics, the inheritance hierarchy for interfaces
	 * will not be traversed. See the {@link java.lang.annotation.Inherited JavaDoc} for the
	 * &#064;Inherited meta-annotation for further details regarding annotation inheritance.
	 * @param annotationType the Class object corresponding to the annotation type
	 * @param clazz the Class object corresponding to the class on which to check for the annotation
	 * @return <code>true</code> if an annotation for the specified <code>annotationType</code> is present
	 * on the supplied <code>clazz</code> and is {@link java.lang.annotation.Inherited inherited}
	 * @see Class#isAnnotationPresent(Class)
	 * @see #isAnnotationDeclaredLocally(Class, Class)
	 */
	public static boolean isAnnotationInherited(Class<? extends Annotation> annotationType, Class<?> clazz) {
		Assert.notNull(annotationType, "Annotation type must not be null");
		Assert.notNull(clazz, "Class must not be null");
		return (clazz.isAnnotationPresent(annotationType) && !isAnnotationDeclaredLocally(annotationType, clazz));
	}

	/**
	 * Retrieve the given annotation's attributes as a Map, preserving all attribute types as-is.
	 * @param annotation the annotation to retrieve the attributes for
	 * @return the Map of annotation attributes, with attribute names as keys and
	 * corresponding attribute values as values
	 */
	public static Map<String, Object> getAnnotationAttributes(Annotation annotation) {
		return getAnnotationAttributes(annotation, false);
	}

	/**
	 * Retrieve the given annotation's attributes as a Map.
	 * @param annotation the annotation to retrieve the attributes for
	 * @param classValuesAsString whether to turn Class references into Strings (for compatibility with
	 * {@link org.springframework.core.type.AnnotationMetadata} or to preserve them as Class references
	 * @return the Map of annotation attributes, with attribute names as keys and
	 * corresponding attribute values as values
	 */
	public static Map<String, Object> getAnnotationAttributes(Annotation annotation, boolean classValuesAsString) {
		Map<String, Object> attrs = new HashMap<String, Object>();
		Method[] methods = annotation.annotationType().getDeclaredMethods();
		for (Method method : methods) {
			if (method.getParameterTypes().length == 0 && method.getReturnType() != void.class) {
				try {
					Object value = method.invoke(annotation);
					if (classValuesAsString) {
						if (value instanceof Class) {
							value = ((Class<?>) value).getName();
						}
						else if (value instanceof Class[]) {
							Class<?>[] clazzArray = (Class[]) value;
							String[] newValue = new String[clazzArray.length];
							for (int i = 0; i < clazzArray.length; i++) {
								newValue[i] = clazzArray[i].getName();
							}
							value = newValue;
						}
					}
					attrs.put(method.getName(), value);
				}
				catch (Exception ex) {
					throw new IllegalStateException("Could not obtain annotation attribute values", ex);
				}
			}
		}
		return attrs;
	}

	/**
	 * Return a list of attribute maps for all declarations of the given target annotation
	 * on the given annotated class. Meta annotations are ordered first in the list, and if
	 * the target annotation is declared directly on the class, its map of attributes will be
	 * ordered last in the list.
	 * @param targetAnnotation the annotation to search for, both locally and as a meta-annotation
	 * @param annotatedClassName the class to search
	 * @param classValuesAsString whether class attributes should be returned as strings
	 * @since 3.1
	 * @see {@link #findAllAnnotationAttributes(Class, String)}
	 */
	public static List<Map<String, Object>> findAllAnnotationAttributes(
			Class<? extends Annotation> targetAnnotation, String annotatedClassName, boolean classValuesAsString) throws IOException {

		List<Map<String, Object>> allAttribs = new ArrayList<Map<String, Object>>();

		MetadataReader reader = new SimpleMetadataReaderFactory().getMetadataReader(annotatedClassName);
		AnnotationMetadata metadata = reader.getAnnotationMetadata();
		String targetAnnotationType = targetAnnotation.getName();

		for (String annotationType : metadata.getAnnotationTypes()) {
			if (annotationType.equals(targetAnnotationType)) {
				continue;
			}
			MetadataReader metaReader = new SimpleMetadataReaderFactory().getMetadataReader(annotationType);
			Map<String, Object> targetAttribs =
				metaReader.getAnnotationMetadata().getAnnotationAttributes(targetAnnotationType, classValuesAsString);
			if (targetAttribs != null) {
				allAttribs.add(targetAttribs);
			}
		}

		Map<String, Object> localAttribs =
			metadata.getAnnotationAttributes(targetAnnotationType, classValuesAsString);
		if (localAttribs != null) {
			allAttribs.add(localAttribs);
		}

		return allAttribs;
	}

	/**
	 * Return a list of attribute maps for all declarations of the given target annotation
	 * on the given annotated class. Meta annotations are ordered first in the list, and if
	 * the target annotation is declared directly on the class, its map of attributes will be
	 * ordered last in the list.
	 * @param targetAnnotation the annotation to search for, both locally and as a meta-annotation
	 * @param annotatedClassName the class to search
	 * @since 3.1
	 * @see {@link #findAllAnnotationAttributes(Class, String, boolean)}
	 */
	public static List<Map<String, Object>> findAllAnnotationAttributes(
			Class<? extends Annotation> targetAnnotation, String annotatedClassName) throws IOException {
		return findAllAnnotationAttributes(targetAnnotation, annotatedClassName, false);
	}

	/**
	 * Retrieve the <em>value</em> of the <code>&quot;value&quot;</code> attribute of a
	 * single-element Annotation, given an annotation instance.
	 * @param annotation the annotation instance from which to retrieve the value
	 * @return the attribute value, or <code>null</code> if not found
	 * @see #getValue(Annotation, String)
	 */
	public static Object getValue(Annotation annotation) {
		return getValue(annotation, VALUE);
	}

	/**
	 * Retrieve the <em>value</em> of a named Annotation attribute, given an annotation instance.
	 * @param annotation the annotation instance from which to retrieve the value
	 * @param attributeName the name of the attribute value to retrieve
	 * @return the attribute value, or <code>null</code> if not found
	 * @see #getValue(Annotation)
	 */
	public static Object getValue(Annotation annotation, String attributeName) {
		try {
			Method method = annotation.annotationType().getDeclaredMethod(attributeName, new Class[0]);
			return method.invoke(annotation);
		}
		catch (Exception ex) {
			return null;
		}
	}

	/**
	 * Retrieve the <em>default value</em> of the <code>&quot;value&quot;</code> attribute
	 * of a single-element Annotation, given an annotation instance.
	 * @param annotation the annotation instance from which to retrieve the default value
	 * @return the default value, or <code>null</code> if not found
	 * @see #getDefaultValue(Annotation, String)
	 */
	public static Object getDefaultValue(Annotation annotation) {
		return getDefaultValue(annotation, VALUE);
	}

	/**
	 * Retrieve the <em>default value</em> of a named Annotation attribute, given an annotation instance.
	 * @param annotation the annotation instance from which to retrieve the default value
	 * @param attributeName the name of the attribute value to retrieve
	 * @return the default value of the named attribute, or <code>null</code> if not found
	 * @see #getDefaultValue(Class, String)
	 */
	public static Object getDefaultValue(Annotation annotation, String attributeName) {
		return getDefaultValue(annotation.annotationType(), attributeName);
	}

	/**
	 * Retrieve the <em>default value</em> of the <code>&quot;value&quot;</code> attribute
	 * of a single-element Annotation, given the {@link Class annotation type}.
	 * @param annotationType the <em>annotation type</em> for which the default value should be retrieved
	 * @return the default value, or <code>null</code> if not found
	 * @see #getDefaultValue(Class, String)
	 */
	public static Object getDefaultValue(Class<? extends Annotation> annotationType) {
		return getDefaultValue(annotationType, VALUE);
	}

	/**
	 * Retrieve the <em>default value</em> of a named Annotation attribute, given the {@link Class annotation type}.
	 * @param annotationType the <em>annotation type</em> for which the default value should be retrieved
	 * @param attributeName the name of the attribute value to retrieve.
	 * @return the default value of the named attribute, or <code>null</code> if not found
	 * @see #getDefaultValue(Annotation, String)
	 */
	public static Object getDefaultValue(Class<? extends Annotation> annotationType, String attributeName) {
		try {
			Method method = annotationType.getDeclaredMethod(attributeName, new Class[0]);
			return method.getDefaultValue();
		}
		catch (Exception ex) {
			return null;
		}
	}

}
