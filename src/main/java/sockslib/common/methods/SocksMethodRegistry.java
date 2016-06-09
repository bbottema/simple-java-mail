

package sockslib.common.methods;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static sockslib.utils.Util.checkNotNull;

public class SocksMethodRegistry {

	private static final Logger logger = LoggerFactory.getLogger(SocksMethodRegistry.class);

	private static final Map<Byte, SocksMethod> methods = new HashMap<>();

	private SocksMethodRegistry() {
	}

	private static void putMethod(SocksMethod socksMethod) {
		checkNotNull(socksMethod, "Argument [socksMethod] may not be null");
		logger.debug("Register {}[{}]", socksMethod.getMethodName(), socksMethod.getByte());
		methods.put((byte) socksMethod.getByte(), socksMethod);
	}

	public static void overWriteRegistry(List<SocksMethod> socksMethods) {
		checkNotNull(socksMethods, "Argument [socksMethods] may not be null");
		for (SocksMethod socksMethod : socksMethods) {
			putMethod(socksMethod);
		}
	}

	public static SocksMethod getByByte(byte b) {
		return methods.get(b);
	}
}
