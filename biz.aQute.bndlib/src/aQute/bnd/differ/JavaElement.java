package aQute.bnd.differ;

import static aQute.bnd.service.diff.Delta.CHANGED;
import static aQute.bnd.service.diff.Delta.IGNORED;
import static aQute.bnd.service.diff.Delta.MAJOR;
import static aQute.bnd.service.diff.Delta.MICRO;
import static aQute.bnd.service.diff.Delta.MINOR;
import static aQute.bnd.service.diff.Type.ACCESS;
import static aQute.bnd.service.diff.Type.ANNOTATED;
import static aQute.bnd.service.diff.Type.ANNOTATION;
import static aQute.bnd.service.diff.Type.API;
import static aQute.bnd.service.diff.Type.CLASS;
import static aQute.bnd.service.diff.Type.CLASS_VERSION;
import static aQute.bnd.service.diff.Type.CONSTANT;
import static aQute.bnd.service.diff.Type.DEFAULT;
import static aQute.bnd.service.diff.Type.ENUM;
import static aQute.bnd.service.diff.Type.EXTENDS;
import static aQute.bnd.service.diff.Type.FIELD;
import static aQute.bnd.service.diff.Type.IMPLEMENTS;
import static aQute.bnd.service.diff.Type.INTERFACE;
import static aQute.bnd.service.diff.Type.METHOD;
import static aQute.bnd.service.diff.Type.PACKAGE;
import static aQute.bnd.service.diff.Type.PROPERTY;
import static aQute.bnd.service.diff.Type.RETURN;
import static aQute.bnd.service.diff.Type.VERSION;

import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Manifest;

import aQute.bnd.header.Attrs;
import aQute.bnd.header.OSGiHeader;
import aQute.bnd.osgi.Analyzer;
import aQute.bnd.osgi.Annotation;
import aQute.bnd.osgi.ClassDataCollector;
import aQute.bnd.osgi.Clazz;
import aQute.bnd.osgi.Clazz.FieldDef;
import aQute.bnd.osgi.Clazz.JAVA;
import aQute.bnd.osgi.Clazz.MemberDef;
import aQute.bnd.osgi.Clazz.MethodDef;
import aQute.bnd.osgi.Constants;
import aQute.bnd.osgi.Descriptors.PackageRef;
import aQute.bnd.osgi.Descriptors.TypeRef;
import aQute.bnd.osgi.Instructions;
import aQute.bnd.osgi.Packages;
import aQute.bnd.service.diff.Delta;
import aQute.bnd.service.diff.Type;
import aQute.bnd.stream.MapStream;
import aQute.bnd.unmodifiable.Sets;
import aQute.bnd.version.Version;
import aQute.lib.collections.MultiMap;
import aQute.libg.generics.Create;

/**
 * An element that compares the access field in a binary compatible way. This
 * element is used for classes, methods, constructors, and fields. For that
 * reason we also included the only method that uses this class as a static
 * method.
 * <p>
 * Packages
 * <ul>
 * <li>MAJOR - Remove a public type
 * <li>MINOR - Add a public class
 * <li>MINOR - Add an interface
 * <li>MINOR - Add a method to a class
 * <li>MINOR - Add a method to a provider interface
 * <li>MAJOR - Add a method to a consumer interface
 * <li>MINOR - Add a field
 * <li>MICRO - Add an annotation to a member
 * <li>MINOR - Change the value of a constant
 * <li>MICRO - -abstract
 * <li>MICRO - -final
 * <li>MICRO - -protected
 * <li>MAJOR - +abstract
 * <li>MAJOR - +final
 * <li>MAJOR - +protected
 * </ul>
 */

class JavaElement {
	final static EnumSet<Type>			INHERITED			= EnumSet.of(FIELD, METHOD, EXTENDS, IMPLEMENTS);
	private static final Element		PROTECTED			= new Element(ACCESS, "protected", null, MAJOR, MINOR,
		null);
	private static final Element		PROTECTED_PROVIDER	= new Element(ACCESS, "protected", null, MINOR, MINOR,
		null);
	private static final Element		STATIC				= new Element(ACCESS, "static", null, MAJOR, MAJOR, null);
	private static final Element		ABSTRACT			= new Element(ACCESS, "abstract", null, MAJOR, MINOR, null);
	private static final Element		FINAL				= new Element(ACCESS, "final", null, MAJOR, MINOR, null);
	// Common return type elements
	static final Element				VOID_R				= new Element(RETURN, "void");
	static final Element				BOOLEAN_R			= new Element(RETURN, "boolean");
	static final Element				BYTE_R				= new Element(RETURN, "byte");
	static final Element				SHORT_R				= new Element(RETURN, "short");
	static final Element				CHAR_R				= new Element(RETURN, "char");
	static final Element				INT_R				= new Element(RETURN, "int");
	static final Element				LONG_R				= new Element(RETURN, "long");
	static final Element				FLOAT_R				= new Element(RETURN, "float");
	static final Element				DOUBLE_R			= new Element(RETURN, "double");
	static final Element				OBJECT_R			= new Element(RETURN, "java.lang.Object");

	static final Set<String>			PROVIDER_TYPE		= Sets.of("aQute.bnd.annotation.ProviderType",
		"org.osgi.annotation.versioning.ProviderType");
	static final Set<String>			CONSUMER_TYPE		= Sets.of("aQute.bnd.annotation.ConsumerType",
		"org.osgi.annotation.versioning.ConsumerType");
	static final Set<String>			VERSION_ANNOTATION	= Sets.of("org.osgi.annotation.versioning.Version");
	final Analyzer						analyzer;
	final Map<PackageRef, Instructions>	providerMatcher		= Create.map();
	final Map<TypeRef, Integer>			innerAccess			= new HashMap<>();
	final Set<TypeRef>					notAccessible		= Create.set();
	final Map<TypeRef, Element>			cache				= Create.map();
	final MultiMap<PackageRef, Element>	packages;
	final Set<JAVA>						javas				= Create.set();
	final Packages						exports;

	/**
	 * Create an element for the API. We take the exported packages and traverse
	 * those for their classes. If there is no manifest or it does not describe
	 * a bundle we assume the whole contents is exported.
	 */
	JavaElement(Analyzer analyzer) throws Exception {
		this.analyzer = analyzer;

		Manifest manifest = analyzer.getJar()
			.getManifest();
		if (manifest != null && manifest.getMainAttributes()
			.getValue(Constants.BUNDLE_MANIFESTVERSION) != null) {
			exports = OSGiHeader.parseHeader(manifest.getMainAttributes()
				.getValue(Constants.EXPORT_PACKAGE))
				.stream()
				.mapKey(analyzer::getPackageRef)
				.collect(MapStream.toMap((Attrs u, Attrs v) -> {
					u.mergeWith(v, true);
					return u;
				}, Packages::new));
		} else
			exports = analyzer.getContained();
		//
		// We have to gather the -providers and parse them into instructions
		// so we can efficiently match them during class parsing to find
		// out who the providers and consumers are
		//

		exports.stream()
			.mapValue(v -> v.get(Constants.PROVIDER_TYPE_DIRECTIVE))
			.filterValue(Objects::nonNull)
			.mapValue(Instructions::new)
			.forEachOrdered(providerMatcher::put);

		// we now need to gather all the packages but without
		// creating the packages yet because we do not yet know
		// which classes are accessible

		packages = new MultiMap<>();

		for (Clazz c : analyzer.getClassspace()
			.values()) {

			// For a package, the annotations are in the synthetic package-info
			// interface.
			if ((!c.isSynthetic() && (c.isPublic() || c.isProtected())) || c.isPackageInfo()) {
				PackageRef packageName = c.getClassName()
					.getPackageRef();

				if (exports.containsKey(packageName)) {
					Element cdef = classElement(c);
					packages.add(packageName, cdef);
				}
			}
		}

	}

	static Element getAPI(Analyzer analyzer) throws Exception {
		analyzer.analyze();
		JavaElement te = new JavaElement(analyzer);
		return te.getLocalAPI();
	}

	private Element getLocalAPI() throws Exception {
		Set<Element> result = new HashSet<>();

		for (Map.Entry<PackageRef, List<Element>> entry : packages.entrySet()) {
			List<Element> children = entry.getValue();
			children.removeIf(child -> notAccessible.contains(analyzer.getTypeRefFromFQN(child.getName())));
			// Find package-info in children, if present, and hoist its
			// annotations into the package's children and remove the
			// package-info child
			children.stream()
				.filter(child -> child.getName()
					.endsWith(".package-info"))
				.findFirst()
				.ifPresent(child -> {
					children.remove(child);
					Arrays.stream(child.getChildren())
						.filter(grandchild -> grandchild.getType() == ANNOTATED)
						.forEach(grandchild -> children.add(grandchild));
				});
			PackageRef pkg = entry.getKey();
			String version = exports.get(pkg)
				.get(Constants.VERSION_ATTRIBUTE);
			if (version == null) { // fallback to Version annotation
				version = children.stream()
					.filter(child -> (child.getType() == ANNOTATED) && VERSION_ANNOTATION.contains(child.getName()))
					.flatMap(child -> Arrays.stream(child.getChildren()))
					.filter(grandchild -> grandchild.getType() == PROPERTY)
					.map(Element::getName)
					.filter(property -> property.startsWith("value='"))
					.map(property -> property.substring(7, property.length() - 1))
					.findFirst()
					.orElse(null);
			}
			if (version != null) {
				Version v = new Version(version);
				children.add(new Element(VERSION, v.toStringWithoutQualifier(), null, IGNORED, IGNORED, null));
			}
			Element pd = new Element(PACKAGE, pkg.getFQN(), children, MINOR, MAJOR, null);
			result.add(pd);
		}

		for (JAVA java : javas) {
			result.add(new Element(CLASS_VERSION, java.toString(), null, CHANGED, CHANGED, null));
		}

		return new Element(API, "<api>", result, CHANGED, CHANGED, null);
	}

	/**
	 * Calculate the class element. This requires parsing the class file and
	 * finding all the methods that were added etc. The parsing will take super
	 * interfaces and super classes into account. For this reason it maintains a
	 * queue of classes/interfaces to parse.
	 */
	Element classElement(final Clazz clazz) throws Exception {
		final TypeRef name = clazz.getClassName();
		//
		// Check if we already had this clazz in the cache
		//
		Element classElement = cache.get(name);
		if (classElement != null) {
			return classElement;
		}

		final Set<Element> members = Create.set();
		final Set<MethodDef> methods = Create.set();
		final Set<FieldDef> fields = Create.set();
		final MultiMap<MemberDef, Element> annotations = new MultiMap<>();


		final String fqn = name.getFQN();
		final String shortName = name.getShortName();

		// Check if this clazz is actually a provider or not
		// providers must be listed in the exported package in the
		// PROVIDER_TYPE directive.
		Instructions matchers = providerMatcher.get(name.getPackageRef());
		boolean p = matchers != null && matchers.matches(shortName);
		final AtomicBoolean provider = new AtomicBoolean(p);

		clazz.parseClassFileWithCollector(new ClassDataCollector() {
			boolean			memberEnd;
			MemberDef	last;

			@Override
			public void version(int minor, int major) {
				javas.add(JAVA.getJava(major, minor));
			}

			@Override
			public void method(MethodDef defined) {
				if ((defined.isProtected() || defined.isPublic())) {
					last = defined;
					methods.add(defined);
				} else {
					last = null;
				}
			}

			@Override
			public void field(FieldDef defined) {
				if (defined.isProtected() || defined.isPublic()) {
					last = defined;
					fields.add(defined);
				} else
					last = null;
			}

			@Override
			public void extendsClass(TypeRef name) throws Exception {
				while (name != null) {
					if (!clazz.isInterface()) {
						inherit(members, name);
					}
					if (name.isObject()) {
						break;
					}
					Clazz c = analyzer.findClass(name);
					if ((c == null) || c.isPublic()) {
						members.add(new Element(EXTENDS, name.getFQN(), null, MICRO, MAJOR, null));
					}
					if (c == null) {
						break;
					}
					name = c.getSuper();
				}
			}

			@Override
			public void implementsInterfaces(TypeRef[] names) throws Exception {
				Deque<TypeRef> queue = new ArrayDeque<>(names.length);
				Collections.addAll(queue, names);
				Set<TypeRef> allInterfaces = new TreeSet<>();
				while (!queue.isEmpty()) {
					TypeRef name = queue.removeFirst();
					if (!allInterfaces.contains(name)) {
						Clazz c = analyzer.findClass(name);
						if ((c == null) || c.isPublic()) {
							allInterfaces.add(name);
						}
						if (c != null) {
							TypeRef[] interfaces = c.getInterfaces();
							if (interfaces != null) {
								Collections.addAll(queue, interfaces);
							}
						}
					}
				}
				for (TypeRef name : allInterfaces) {
					if (clazz.isInterface() || clazz.isAbstract()) {
						inherit(members, name);
					}
					members.add(new Element(IMPLEMENTS, name.getFQN(), null, MINOR, MAJOR, null));
				}
			}

			/**
			 */
			Set<Element> OBJECT = Create.set();

			private void inherit(final Set<Element> members, TypeRef name) throws Exception {
				if (name.isObject()) {
					if (OBJECT.isEmpty()) {
						Clazz c = analyzer.findClass(name);
						if (c == null) {
							// Bnd fails on Java 9 class files #1598
							// Caused by Java 9 not making class rsources
							// available
							return;
						}
						Element s = classElement(c);
						for (Element child : s.getChildren()) {
							if (INHERITED.contains(child.getType())) {
								if (child.getType() == METHOD) {
									String n = child.getName();
									if (n.startsWith("<init>") || n.equals("getClass()")
										|| n.startsWith("wait(") || n.startsWith("notify(")
										|| n.startsWith("notifyAll(")) {
										continue;
									}
								}
								if (isStatic(child)) {
									continue;
								}
								OBJECT.add(child);
							}
						}
					}
					members.addAll(OBJECT);
				} else {
					Clazz c = analyzer.findClass(name);
					if (c == null) {
						inherit(members, analyzer.getTypeRef("java/lang/Object"));
						return;
					}
					Element s = classElement(c);
					for (Element child : s.getChildren()) {
						if (INHERITED.contains(child.getType())) {
							if (child.getName()
								.startsWith("<")) {
								continue;
							}
							if (isStatic(child)) {
								continue;
							}
							members.add(child);
						}
					}
				}
			}

			private boolean isStatic(Element child) {
				boolean isStatic = child.get("static") != null;
				return isStatic;
			}

			/**
			 * Deprecated annotations and Provider/Consumer Type (both bnd and
			 * OSGi) are treated special. Other annotations are turned into a
			 * tree. Starting with ANNOTATED, and then properties. A property is
			 * a PROPERTY property or an ANNOTATED property if it is an
			 * annotation. If it is an array, the key is suffixed with the
			 * index.
			 *
			 * <pre>
			 *  public @interface Outer { Inner[] value(); }
			 * public @interface Inner { String[] value(); } @Outer(
			 * { @Inner("1","2"}) } class Xyz {} ANNOTATED Outer
			 * (CHANGED/CHANGED) ANNOTATED Inner (CHANGED/CHANGED) PROPERTY
			 * value.0=1 (CHANGED/CHANGED) PROPERTY value.1=2 (CHANGED/CHANGED)
			 * </pre>
			 */
			@Override
			public void annotation(Annotation annotation) {
				if (Deprecated.class.getName()
					.equals(annotation.getName()
						.getFQN())) {
					return;
				}

				Element e = annotatedToElement(annotation);
				if (memberEnd) {
					members.add(e);

					//
					// Check for the provider/consumer
					//
					String name = annotation.getName()
						.getFQN();
					if (PROVIDER_TYPE.contains(name)) {
						provider.set(true);
					} else if (CONSUMER_TYPE.contains(name)) {
						provider.set(false);
					}
				} else if (last != null)
					annotations.add(last, e);
			}

			/*
			 * Return an ANNOTATED element for this annotation. An ANNOTATED
			 * element contains either PROPERTY children or ANNOTATED children.
			 */
			private Element annotatedToElement(Annotation annotation) {
				Delta delta = (annotation.getRetentionPolicy() == RetentionPolicy.RUNTIME) ? CHANGED : MICRO;
				Collection<Element> properties = Create.set();
				for (Entry<String, Object> entry : annotation.entrySet()) {
					addAnnotationMember(properties, entry.getKey(), entry.getValue(), delta);
				}
				return new Element(ANNOTATED, annotation.getName()
					.getFQN(), properties, delta, delta, null);
			}

			/*
			 * This method detects 3 cases: An Annotation, which means it
			 * creates a new child ANNOTATED element, an array, which means it
			 * will repeat recursively but suffixes the key with the index, or a
			 * simple value which is turned into a string.
			 */
			private void addAnnotationMember(Collection<Element> properties, String key, Object member, Delta delta) {
				if (member instanceof Annotation) {
					properties.add(annotatedToElement((Annotation) member));
				} else if (member.getClass()
					.isArray()) {
					int l = Array.getLength(member);
					for (int i = 0; i < l; i++) {
						addAnnotationMember(properties, key + "." + i, Array.get(member, i), delta);
					}
				} else {
					StringBuilder sb = new StringBuilder();
					sb.append(key);
					sb.append('=');
					if (member instanceof String) {
						sb.append("'");
						sb.append(member);
						sb.append("'");
					} else
						sb.append(member);

					properties.add(new Element(PROPERTY, sb.toString(), null, delta, delta, null));
				}
			}

			@Override
			public void innerClass(TypeRef innerClass, TypeRef outerClass, String innerName, int innerClassAccessFlags)
				throws Exception {
				innerAccess.computeIfAbsent(innerClass, k -> Integer.valueOf(innerClassAccessFlags));

				if (Modifier.isProtected(innerClassAccessFlags) || Modifier.isPublic(innerClassAccessFlags))
					return;
				notAccessible.add(innerClass);
			}

			@Override
			public void memberEnd() {
				memberEnd = true;
			}
		});

		// This is the heart of the semantic versioning. If we
		// add or remove a method from an interface then
		Delta add;
		Delta remove;
		Type type;

		// Calculate the type of the clazz. A class
		// can be an interface, class, enum, or annotation

		if (clazz.isInterface())
			if (clazz.isAnnotation())
				type = ANNOTATION;
			else
				type = INTERFACE;
		else if (clazz.isEnum())
			type = ENUM;
		else
			type = CLASS;

		if (type == INTERFACE) {
			if (provider.get()) {
				// Adding a method for a provider is not an issue
				// because it must be aware of the changes
				add = MINOR;

				// Removing a method influences consumers since they
				// tend to call this guy.
				remove = MAJOR;
			} else {
				// Adding a method is a major change
				// because the consumer has to implement it
				// or the provider will call a non existent
				// method on the consumer
				add = MAJOR;

				// Removing a method is not an issue for
				// providers, however, consumers could potentially
				// call through this interface :-(

				remove = MAJOR;
			}
		} else {
			// Adding a method to a class can never do any harm
			// except when the class is extended and the new
			// method clashes with the new method. That is
			// why API classes in general should be final, at
			// least not extended by consumers.
			add = MINOR;

			// Removing it will likely hurt consumers
			remove = MAJOR;
		}

		for (MethodDef m : methods) {
			if (m.isSynthetic()) { // Ignore synthetic methods
				continue;
			}
			Collection<Element> children = annotations.get(m);
			if (children == null)
				children = new HashSet<>();

			// Annotations can have a default value, this is a new element
			if ((type == ANNOTATION) && (m.getConstant() != null)) {
				Object constant = m.getConstant();
				String defaultValue;
				if (constant.getClass()
					.isArray()) {
					defaultValue = Arrays.toString((Object[]) constant);
				} else {
					defaultValue = constant.toString();
				}
				children.add(new Element(DEFAULT, defaultValue, null, CHANGED, CHANGED, null));
			}

			access(children, m.getAccess(), m.isDeprecated(), provider.get());

			// A final class cannot be extended, ergo,
			// all methods defined in it are by definition
			// final. However, marking them final (either
			// on the method or inheriting it from the class)
			// will create superfluous changes if we
			// override a method from a super class that was not
			// final. So we actually remove the final for methods
			// in a final class.
			if (clazz.isFinal())
				children.remove(FINAL);

			children.add(getReturn(m.getType()));

			//
			// Java default methods are concrete implementations of methods
			// on an interface.
			//

			if (clazz.isInterface() && !m.isAbstract()) {

				//
				// We have a Java 8 default method!
				// Such a method is always a minor update
				//

				add = MINOR;
			}

			String signature = m.getName() + toString(m.getPrototype());
			Element member = new Element(METHOD, signature, children, add,
				provider.get() && !m.isPublic() ? MINOR : remove, null);

			if (!members.add(member)) {
				members.remove(member);
				members.add(member);
			}
		}

		for (FieldDef f : fields) {
			if (f.isSynthetic()) { // Ignore synthetic fields
				continue;
			}
			Collection<Element> children = annotations.get(f);
			if (children == null)
				children = new HashSet<>();

			// Fields can have a constant value, this is a new element
			if (f.getConstant() != null) {
				children.add(new Element(CONSTANT, f.getConstant()
					.toString(), null, CHANGED, CHANGED, null));
			}

			access(children, f.getAccess(), f.isDeprecated(), provider.get());
			children.add(getReturn(f.getType()));
			Element member = new Element(FIELD, f.getName(), children, MINOR,
				provider.get() && !f.isPublic() ? MINOR : MAJOR, null);

			if (!members.add(member)) {
				members.remove(member);
				members.add(member);
			}
		}

		Integer inner_access_flags = innerAccess.get(clazz.getClassName());
		int access_flags = (inner_access_flags != null) ? inner_access_flags.intValue() : clazz.getAccess();
		access(members, access_flags, clazz.isDeprecated(), provider.get());

		// And make the result
		classElement = new Element(type, fqn, members, MINOR, MAJOR, null);
		cache.put(name, classElement);
		return classElement;
	}

	private String toString(TypeRef[] prototype) {
		StringBuilder sb = new StringBuilder();
		sb.append("(");
		String del = "";
		for (TypeRef ref : prototype) {
			sb.append(del);
			sb.append(ref.getFQN());
			del = ",";
		}
		sb.append(")");
		return sb.toString();
	}

	private Element getReturn(TypeRef type) {
		if (!type.isPrimitive()) {
			return type.isObject() ? OBJECT_R : new Element(RETURN, type.getFQN());
		}
		switch (type.getBinary()
			.charAt(0)) {
			case 'V' :
				return VOID_R;
			case 'Z' :
				return BOOLEAN_R;
			case 'S' :
				return SHORT_R;
			case 'I' :
				return INT_R;
			case 'B' :
				return BYTE_R;
			case 'C' :
				return CHAR_R;
			case 'J' :
				return LONG_R;
			case 'F' :
				return FLOAT_R;
			case 'D' :
				return DOUBLE_R;
			default :
				throw new IllegalArgumentException("Unknown primitive " + type);
		}
	}

	private static void access(Collection<Element> children, int access, @SuppressWarnings("unused") boolean deprecated,
		boolean provider) {
		if (!Modifier.isPublic(access))
			children.add(provider ? PROTECTED_PROVIDER : PROTECTED);
		if (Modifier.isAbstract(access))
			children.add(ABSTRACT);
		if (Modifier.isFinal(access))
			children.add(FINAL);
		if (Modifier.isStatic(access))
			children.add(STATIC);
	}
}
