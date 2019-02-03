package demo;

import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.api.mailer.AsyncResponse.ExceptionConsumer;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static demo.DemoAppBase.mailerTLS;

/**
 * Demonstrates how to test connections normally as well as asynchronously
 */
public class TestConnectionDemo {
	public static void main(String[] args) throws InterruptedException {
		long now = System.currentTimeMillis();
		
		normalConnectionTest();
		asyncConnectionTestUsingFuture();
		asyncConnectionTestUsingHandlers();
		
		System.out.println("Finished in " + (System.currentTimeMillis() - now) + "ms");
	}
	
	private static void normalConnectionTest() {
		mailerTLS.testConnection();
		mailerTLS.testConnection(false);
	}
	
	private static void asyncConnectionTestUsingFuture() throws InterruptedException {
		AsyncResponse asyncResponse = mailerTLS.testConnection(true);
		
		Future<?> f = asyncResponse.getFuture();
		
		// f.get() actually blocks until done, so below is an example custom while-loop for checking result in a non-blocking way
		while (!f.isDone()) {
			Thread.sleep(100);
		}
		
		// result is in, check it
		try {
			f.get(); // without the above loop, this would actually block until done, but because of the while-loop, we know it's done already
			System.err.println("success");
		} catch (ExecutionException e) {
			System.err.println("error");
			e.printStackTrace(System.err);
		}
	}
	
	private static void asyncConnectionTestUsingHandlers() {
		AsyncResponse asyncResponse = mailerTLS.testConnection(true);
		
		// java 8
		// asyncResponse.onSuccess(() -> System.out.println("Success"));
		// asyncResponse.onException((e) -> System.err.println("error"));
		
		// java 7 meh
		asyncResponse.onSuccess(new Runnable() { public void run() { System.out.println("success"); } });
		asyncResponse.onException(new ExceptionConsumer() { public void accept(Exception e) { System.err.println("error"); } });
	}
}