package org.simplejavamail.internal.clisupport.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.ImmutableCollectionsSerializers;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import org.jetbrains.annotations.NotNull;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

/**
 * Central Kryo boundary for nested Outlook-message attachments and generated CLI metadata.
 * The shared, synchronized Kryo instance registers deterministic representations for reflection methods and immutable
 * collection wrappers so caches survive supported JDK changes without unsafe reflective allocation. Outlook conversion
 * uses the same boundary because its parsed Java structure must be stored inside an attachment and reconstructed later.
 *
 * @see <a href="https://github.com/bbottema/simple-java-mail/issues/298">GitHub issue #314</a>
 */
public class SerializationUtil {

	static {
		// Keep the CLI cache compatible with newer JDKs without reflective/Unsafe access warnings.
		System.setProperty("kryo.unsafe", "false");
	}

	private static final Kryo KRYO = initKryo();

	@NotNull
	private static Kryo initKryo() {
		final Kryo kryo = new Kryo();
		kryo.setRegistrationRequired(false);
		kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
		ImmutableCollectionsSerializers.addDefaultSerializers(kryo);
		registerUnmodifiableListSerializers(kryo);
		registerUnmodifiableMapSerializer(kryo);
		kryo.register(Method.class, new MethodSerializer());
		return kryo;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void registerUnmodifiableListSerializers(final Kryo kryo) {
		final UnmodifiableListSerializer serializer = new UnmodifiableListSerializer();
		kryo.register((Class) Collections.unmodifiableList(new ArrayList<>()).getClass(), serializer);
		kryo.register((Class) Collections.unmodifiableList(new LinkedList<>()).getClass(), serializer);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void registerUnmodifiableMapSerializer(final Kryo kryo) {
		kryo.register((Class) Collections.unmodifiableMap(new LinkedHashMap<>()).getClass(),
				new UnmodifiableMapSerializer());
	}

	@NotNull
	public static synchronized byte[] serialize(@NotNull final Object serializable) {
		final Output output = new Output(1024, -1);
		KRYO.writeClassAndObject(output, serializable);
		return output.toBytes();
	}

	@SuppressWarnings("unchecked")
	@NotNull
	public static synchronized <T> T deserialize(final byte@NotNull[] serialized) {
		return (T) KRYO.readClassAndObject(new Input(serialized));
	}

	/** Stores a method descriptor and resolves it against the classes loaded in the reading JVM. */
	private static final class MethodSerializer extends Serializer<Method> {
		@Override
		public void write(final Kryo kryo, final Output output, final Method method) {
			output.writeString(method.getDeclaringClass().getName());
			output.writeString(method.getName());
			output.writeInt(method.getParameterTypes().length, true);
			for (final Class<?> parameterType : method.getParameterTypes()) {
				output.writeString(parameterType.getName());
			}
		}

		@Override
		public Method read(final Kryo kryo, final Input input, final Class<? extends Method> type) {
			try {
				final Class<?> declaringClass = Class.forName(input.readString());
				final String methodName = input.readString();
				final Class<?>[] parameterTypes = new Class<?>[input.readInt(true)];
				for (int i = 0; i < parameterTypes.length; i++) {
					parameterTypes[i] = resolveType(input.readString());
				}
				return declaringClass.getDeclaredMethod(methodName, parameterTypes);
			} catch (final ClassNotFoundException | NoSuchMethodException e) {
				throw new KryoException("Unable to deserialize method reference", e);
			}
		}

		private static Class<?> resolveType(final String typeName)
				throws ClassNotFoundException {
			return switch (typeName) {
				case "boolean" -> boolean.class;
				case "byte" -> byte.class;
				case "char" -> char.class;
				case "double" -> double.class;
				case "float" -> float.class;
				case "int" -> int.class;
				case "long" -> long.class;
				case "short" -> short.class;
				case "void" -> void.class;
				default -> Class.forName(typeName);
			};
		}
	}

	/** Reconstructs an unmodifiable list while retaining its random-access or linked representation. */
	private static final class UnmodifiableListSerializer extends Serializer<List<?>> {
		@Override
		public void write(final Kryo kryo, final Output output, final List<?> list) {
			output.writeInt(list.size(), true);
			for (final Object element : list) {
				kryo.writeClassAndObject(output, element);
			}
		}

		@Override
		public List<?> read(final Kryo kryo, final Input input, final Class<? extends List<?>> type) {
			final int size = input.readInt(true);
			final List<Object> mutable = RandomAccess.class.isAssignableFrom(type)
					? new ArrayList<>(size) : new LinkedList<>();
			final List<Object> result = Collections.unmodifiableList(mutable);
			kryo.reference(result);
			for (int i = 0; i < size; i++) {
				mutable.add(kryo.readClassAndObject(input));
			}
			return result;
		}
	}

	/** Reconstructs insertion-ordered unmodifiable maps used by generated option metadata. */
	private static final class UnmodifiableMapSerializer extends Serializer<Map<?, ?>> {
		@Override
		public void write(final Kryo kryo, final Output output, final Map<?, ?> map) {
			output.writeInt(map.size(), true);
			for (final Map.Entry<?, ?> entry : map.entrySet()) {
				kryo.writeClassAndObject(output, entry.getKey());
				kryo.writeClassAndObject(output, entry.getValue());
			}
		}

		@Override
		public Map<?, ?> read(final Kryo kryo, final Input input, final Class<? extends Map<?, ?>> type) {
			final int size = input.readInt(true);
			final Map<Object, Object> mutable = new LinkedHashMap<>(size);
			final Map<Object, Object> result = Collections.unmodifiableMap(mutable);
			kryo.reference(result);
			for (int i = 0; i < size; i++) {
				mutable.put(kryo.readClassAndObject(input), kryo.readClassAndObject(input));
			}
			return result;
		}
	}
}
