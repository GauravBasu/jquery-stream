package flowersinthesand.example;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.Gson;

@WebServlet(urlPatterns = "/chat", asyncSupported = true)
public class ChatServlet extends HttpServlet {

	private static final long serialVersionUID = -2919167206889576860L;
	private Map<String, AsyncContext> contexts = new ConcurrentHashMap<String, AsyncContext>();
	private BlockingQueue<String> messages = new LinkedBlockingQueue<String>();
	private Thread notifier = new Thread(new Runnable() {
		public void run() {
			boolean done = false;
			while (!done) {
				String message = null;
				try {
					message = messages.take();
					for (AsyncContext ac : contexts.values()) {
						try {
							sendMessage(ac.getResponse().getWriter(), message);
						} catch (IOException e) {
							contexts.remove(ac);
						}
					}
				} catch (InterruptedException e) {
					done = true;
				}
			}
		}
	});
	
	private void sendMessage(PrintWriter writer, String message) {
		// Sends a message according to the message format(message-size ; message-data ;) 
		writer.print(message.length());
		writer.print(";");
		writer.print(message);
		writer.print(";");
		writer.flush();
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		notifier.start();
	}

	// GET method is used to open stream
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException,
			IOException {
		response.setCharacterEncoding("utf-8");
		
		// Sets the Content-Type header to 'text/plain' that is the only one satisfying all streaming transport
		response.setContentType("text/plain");
		
		// For XDomainRequest transport, sets the Access-Control-Allow-Origin header
		response.setHeader("Access-Control-Allow-Origin", "*");

		// Prints the stream connection id for further communication and one kilobyte padding at the top of the response
		// each part must end with a semicolon
		PrintWriter writer = response.getWriter();
		
		// UUID is very suitable
		final String id = UUID.randomUUID().toString();
		writer.print(id);
		writer.print(';');
		
		// The padding is needed by XMLHttpRequest of WebKit, XDomainRequest and Hidden iframe transport
		for (int i = 0; i < 1024; i++) {
			writer.print(' ');
		}
		writer.print(';');
		writer.flush();
		
		System.out.println(id + ": open");
		
		// Starts asynchronous mode
		final AsyncContext ac = request.startAsync();
		ac.setTimeout(5 * 60 * 1000);
		ac.addListener(new AsyncListener() {
			public void onComplete(AsyncEvent event) throws IOException {
				contexts.remove(id);
			}

			public void onTimeout(AsyncEvent event) throws IOException {
				contexts.remove(id);
			}

			public void onError(AsyncEvent event) throws IOException {
				contexts.remove(id);
			}

			public void onStartAsync(AsyncEvent event) throws IOException {

			}
		});
		contexts.put(id, ac);
	}

	// POST method is used to handle data sent by user through the stream
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		request.setCharacterEncoding("utf-8");

		// POST request always has metadata parameters
		
		// The stream connection id generated by the server 
		String id = request.getParameter("metadata.id");
		
		// Request type such as send and close
		String type = request.getParameter("metadata.type");
		
		System.out.println(id + ": " + type);
		
		// Handles a special case
		if ("close".equals(type)) {
			// Closes connection by its id
			AsyncContext ac = contexts.get(id);
			if (ac != null) {
				ac.complete();
			}
			
			return;
		}

		// Handles data sent from a client
		Map<String, String> data = new LinkedHashMap<String, String>();
		data.put("username", request.getParameter("username"));
		data.put("message", request.getParameter("message"));

		try {
			messages.put(new Gson().toJson(data));
		} catch (Exception e) {
			throw new IOException(e);
		}
	}

	@Override
	public void destroy() {
		messages.clear();
		contexts.clear();
		notifier.interrupt();
	}

}