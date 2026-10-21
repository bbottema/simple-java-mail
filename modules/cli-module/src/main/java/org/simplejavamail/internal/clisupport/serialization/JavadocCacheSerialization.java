package org.simplejavamail.internal.clisupport.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.github.therapi.runtimejavadoc.MethodJavadoc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

/**
 * Versioned codec for Therapi's immutable model collections. Kept separate from the existing CLI-option codec so
 * fixing the Javadoc cache does not change the format of {@code cli.data}.
 */
public final class JavadocCacheSerialization {

	private static final byte[] FORMAT_HEADER = {'S', 'J', 'M', 'T', 'H', 'E', 'R', 'A', 'P', 'I', 1};

	private JavadocCacheSerialization() {
	}

	public static byte[] serialize(Map<String, MethodJavadoc> cache) {
		Output output = new Output(4096, -1);
		output.writeBytes(FORMAT_HEADER);
		newCacheKryo().writeClassAndObject(output, new LinkedHashMap<>(cache));
		return output.toBytes();
	}

	public static Map<String, MethodJavadoc> deserialize(byte[] serialized) {
		if (serialized.length < FORMAT_HEADER.length
				|| !Arrays.equals(FORMAT_HEADER, Arrays.copyOf(serialized, FORMAT_HEADER.length))) {
			throw new KryoException("Unsupported CLI Javadoc cache format");
		}
		Input input = new Input(serialized);
		input.setPosition(FORMAT_HEADER.length);
		Object decoded = newCacheKryo().readClassAndObject(input);
		if (!(decoded instanceof Map) || input.position() != input.limit()) {
			throw new KryoException("Invalid CLI Javadoc cache payload");
		}
		Map<String, MethodJavadoc> result = new HashMap<>();
		for (Map.Entry<?, ?> entry : ((Map<?, ?>) decoded).entrySet()) {
			if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof MethodJavadoc)) {
				throw new KryoException("Invalid CLI Javadoc cache entry");
			}
			result.put((String) entry.getKey(), (MethodJavadoc) entry.getValue());
		}
		return result;
	}

	private static Kryo newCacheKryo() {
		// A fresh instance also makes persisted reads independent of the writer's registrations and reference state.
		Kryo kryo = SerializationUtil.initKryo();
		Serializer<List<?>> listSerializer = new UnmodifiableListSerializer();
		kryo.addDefaultSerializer(Collections.unmodifiableList(new ArrayList<>()).getClass(), listSerializer);
		kryo.addDefaultSerializer(Collections.unmodifiableList(new LinkedList<>()).getClass(), listSerializer);
		kryo.addDefaultSerializer(Collections.unmodifiableMap(new LinkedHashMap<>()).getClass(), new UnmodifiableMapSerializer());
		return kryo;
	}

	private static int readSize(Input input, int minimumBytesPerEntry) {
		int size = input.readInt(true);
		if (size < 0 || size > (input.limit() - input.position()) / minimumBytesPerEntry) {
			throw new KryoException("Invalid CLI Javadoc cache collection size");
		}
		return size;
	}

	private static final class UnmodifiableListSerializer extends Serializer<List<?>> {
		@Override
		public void write(Kryo kryo, Output output, List<?> value) {
			output.writeInt(value.size(), true);
			for (Object element : value) {
				kryo.writeClassAndObject(output, element);
			}
		}

		@Override
		public List<?> read(Kryo kryo, Input input, Class<? extends List<?>> type) {
			int size = readSize(input, 1);
			List<Object> backing = RandomAccess.class.isAssignableFrom(type) ? new ArrayList<>(size) : new LinkedList<>();
			List<Object> result = Collections.unmodifiableList(backing);
			kryo.reference(result);
			for (int i = 0; i < size; i++) {
				backing.add(kryo.readClassAndObject(input));
			}
			return result;
		}
	}

	private static final class UnmodifiableMapSerializer extends Serializer<Map<?, ?>> {
		@Override
		public void write(Kryo kryo, Output output, Map<?, ?> value) {
			output.writeInt(value.size(), true);
			for (Map.Entry<?, ?> entry : value.entrySet()) {
				kryo.writeClassAndObject(output, entry.getKey());
				kryo.writeClassAndObject(output, entry.getValue());
			}
		}

		@Override
		public Map<?, ?> read(Kryo kryo, Input input, Class<? extends Map<?, ?>> type) {
			int size = readSize(input, 2);
			Map<Object, Object> backing = new LinkedHashMap<>();
			Map<Object, Object> result = Collections.unmodifiableMap(backing);
			kryo.reference(result);
			for (int i = 0; i < size; i++) {
				Object key = kryo.readClassAndObject(input);
				backing.put(key, kryo.readClassAndObject(input));
			}
			return result;
		}
	}
}
