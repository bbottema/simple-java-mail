package org.simplejavamail.internal.clisupport.serialization;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer;
import org.jetbrains.annotations.NotNull;
import org.objenesis.strategy.StdInstantiatorStrategy;

/**
 * Used to serialize attachments of nested Outlook messages. This is needed because outlook-message-parser returns a Java structure from a .msg source, but this conversion is 1-way. An Email object
 * represents attachments as DataSources however, so for this we need to serialize back from the java structure to a binary format. This Util does this.
 * <br>
 * Then for users to obtain the Javastructure again, they must use this util to deserialize the relevant attachment.
 *
 * @see <a href="https://github.com/bbottema/simple-java-mail/issues/298">GitHub issue #314</a>
 */
public class SerializationUtil {

	private static final Kryo KRYO = initKryo();

	@NotNull
	private static Kryo initKryo() {
		Kryo kryo = new Kryo();
		kryo.setRegistrationRequired(false);
		kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
		UnmodifiableCollectionsSerializer.registerSerializers(kryo);
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
	public static <T> T deserialize(@NotNull final byte[] serialized) {
		return (T) KRYO.readClassAndObject(new Input(serialized));
	}
}
