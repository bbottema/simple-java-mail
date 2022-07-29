package demo;

import org.simplejavamail.api.mailer.Mailer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Demonstrates how to test connections normally as well as asynchronously
 */
public class TestConnectionDemo {
	public static void main(String[] args) throws InterruptedException {
		Mailer mailerTLS = DemoAppBase.mailerTLSBuilder.buildMailer();

		long now = System.currentTimeMillis();
		
		normalConnectionTest(mailerTLS);
		asyncConnectionTestUsingFuture(mailerTLS);
		asyncConnectionTestUsingHandlers(mailerTLS)
				.thenRun(() -> System.exit(0));
		
		System.out.println("Finished in " + (System.currentTimeMillis() - now) + "ms");
	}
	
	private static void normalConnectionTest(Mailer mailerTLS) {
		mailerTLS.testConnection();
		mailerTLS.testConnection(false);
	}
	
	private static void asyncConnectionTestUsingFuture(Mailer mailerTLS) throws InterruptedException {
		CompletableFuture<Void> f = mailerTLS.testConnection(true);

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

	private static CompletableFuture<Void> asyncConnectionTestUsingHandlers(Mailer mailerTLS) {
		return mailerTLS.testConnection(true).whenComplete((result, ex) -> {
			if (ex != null) {
				System.err.printf("Execution failed %s", ex);
			} else {
				System.err.printf("Execution completed: %s", result);
			}
		});
	}
}