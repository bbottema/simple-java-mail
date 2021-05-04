package org.simplejavamail.internal.outlooksupport.internal.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.FieldSerializer;
import com.esotericsoftware.kryo.serializers.JavaSerializer;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer;
import org.jetbrains.annotations.NotNull;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;

import javax.mail.Message;
import java.io.IOException;
import java.io.InputStream;

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
		final FieldSerializer.FieldSerializerConfig config = new FieldSerializer.FieldSerializerConfig();
		// datasource is set to transient in AttachmentResource, but with this particular config, Kryo can handle it
		config.setSerializeTransient(true);
		FieldSerializer<AttachmentResource> fieldSerializer = new FieldSerializer<>(kryo, AttachmentResource.class, config);
		kryo.register(AttachmentResource.class, fieldSerializer);
		kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
		UnmodifiableCollectionsSerializer.registerSerializers(kryo);
		kryo.register(Message.RecipientType.class, new JavaSerializer());
		return kryo;
	}

	@NotNull
	public static byte[] serialize(@NotNull final Object serializable)
			throws IOException {
		final Output output = new Output(1024, -1);
		KRYO.writeClassAndObject(output, serializable);
		return output.toBytes();
	}

	@NotNull
	public static Email deserialize(@NotNull final InputStream sjmInputStream)
			throws IOException {
		return (Email) KRYO.readClassAndObject(new Input(sjmInputStream));
	}
}
