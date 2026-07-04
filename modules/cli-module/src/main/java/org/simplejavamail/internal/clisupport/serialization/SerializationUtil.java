package org.simplejavamail.internal.clisupport.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import org.jetbrains.annotations.NotNull;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.lang.reflect.Method;

/**
 * Used to serialize attachments of nested Outlook messages. This is needed because outlook-message-parser returns a Java structure from a .msg source, but this conversion is 1-way. An Email object
 * represents attachments as DataSources however, so for this we need to serialize back from the java structure to a binary format. This Util does this.
 * <br>
 * Then for users to obtain the Javastructure again, they must use this util to deserialize the relevant attachment.
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
		Kryo kryo = new Kryo();
		kryo.setRegistrationRequired(false);
		kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
		kryo.register(Method.class, new MethodSerializer());
		return kryo;
	}

	@NotNull
	public static byte[] serialize(@NotNull final Object serializable) {
		final Output output = new Output(1024, -1);
		KRYO.writeClassAndObject(output, serializable);
		return output.toBytes();
	}

	@SuppressWarnings("unchecked")
	@NotNull
	public static <T> T deserialize(final byte@NotNull[] serialized) {
		return (T) KRYO.readClassAndObject(new Input(serialized));
	}

	private static class MethodSerializer extends Serializer<Method> {
		@Override
		public void write(final Kryo kryo, final Output output, final Method method) {
			output.writeString(method.getDeclaringClass().getName());
			output.writeString(method.getName());
			output.writeInt(method.getParameterTypes().length, true);
			for (Class<?> parameterType : method.getParameterTypes()) {
				output.writeString(parameterType.getName());
			}
		}

		@Override
		public Method read(final Kryo kryo, final Input input, final Class<? extends Method> type) {
			try {
				final Class<?> declaringClass = Class.forName(input.readString());
				final String methodName = input.readString();
				final Class<?>[] parameterTypes = new Class[input.readInt(true)];
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
			switch (typeName) {
				case "boolean": return boolean.class;
				case "byte": return byte.class;
				case "char": return char.class;
				case "double": return double.class;
				case "float": return float.class;
				case "int": return int.class;
				case "long": return long.class;
				case "short": return short.class;
				case "void": return void.class;
				default: return Class.forName(typeName);
			}
		}
	}
}
