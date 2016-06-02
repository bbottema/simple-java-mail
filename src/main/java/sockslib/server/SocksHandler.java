package sockslib.server;

interface SocksHandler extends Runnable {

	void setSession(Session session);

	void setBufferSize(int bufferSize);

}
