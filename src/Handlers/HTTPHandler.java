package Handlers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.HTTP;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder.EndOfDataDecoderException;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpData.HttpDataType;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import io.netty.util.IllegalReferenceCountException;

public class HTTPHandler extends SimpleChannelInboundHandler<Object> {
	private HttpRequest request;
	private String requestBody;
	private long correlationId;
	private HttpPostRequestDecoder httpDecoder;
	private static final HttpDataFactory factory = new DefaultHttpDataFactory(true);
	private static final String FILE_UPLOAD_LOCN = "/Users/eletreby/Desktop/uploads/";
	volatile String responseBody;
	ExecutorService executorService = Executors.newCachedThreadPool();

	String requestId;
	private static final HashMap<String, String> REQUEST_METHOD_COMMAND_PREFIX_MAP;
	static {
		REQUEST_METHOD_COMMAND_PREFIX_MAP = new HashMap<String, String>();
		REQUEST_METHOD_COMMAND_PREFIX_MAP.put("POST", "Create");
		REQUEST_METHOD_COMMAND_PREFIX_MAP.put("GET", "Retrieve");
		REQUEST_METHOD_COMMAND_PREFIX_MAP.put("PATCH", "Update");
		REQUEST_METHOD_COMMAND_PREFIX_MAP.put("DELETE", "Delete");
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
		final FullHttpRequest req = (FullHttpRequest) msg;

		String service = getServiceName(req);
		String data = parseToJson(req);
		System.out.println(service);
		System.out.println(data);
		String formString = "";
		JSONParser formParser = new JSONParser();
		JSONObject formJson = new JSONObject();

		requestId = UUID.randomUUID().toString();

		if (req.method().toString() == "POST" || req.method().toString() == "PATCH") {
			httpDecoder = new HttpPostRequestDecoder(factory, (HttpRequest) msg);
			httpDecoder.setDiscardThreshold(0);
			if (httpDecoder != null) {
				if (msg instanceof HttpContent) {
					HttpContent chunk = (HttpContent) msg;
					httpDecoder.offer(chunk);
					formString = readChunk(ctx);
					formJson = (JSONObject) formParser.parse(formString);
					if (chunk instanceof LastHttpContent) {
						resetPostRequestDecoder();
					}
				}
			}
		}

		ctx.channel().attr(AttributeKey.valueOf("SERVICE")).set(service);
		ctx.channel().attr(AttributeKey.valueOf("REQUESTID")).set(requestId);
		ctx.channel().attr(AttributeKey.valueOf("PATH")).set(req.uri());
		ctx.channel().attr(AttributeKey.valueOf("METHOD")).set(req.method().toString());
//		ctx.channel().attr(AttributeKey.valueOf("MEDIA")).set(fileName);
		JSONParser parser = new JSONParser();
		JSONObject json = (JSONObject) parser.parse(data);
		json.put("form", formJson);
		data = json.toString();
		ctx.fireChannelRead(data);

	}

	public void channelReadComplete(ChannelHandlerContext ctx) {
		ctx.flush();
		ctx.fireChannelReadComplete();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		cause.printStackTrace();
		ctx.close();
	}

	private String getServiceName(final FullHttpRequest req) {
		String result = req.uri().split("/")[1];
		if (result.contains("?")) {
			result = result.substring(0, result.indexOf("?"));
		}
		return result;
	}

	private String parseToJson(final FullHttpRequest req) {
		JSONObject resultJson = new JSONObject();

		String request_method = req.method().toString();
		String body = req.content().toString(CharsetUtil.UTF_8);

		HashMap<String, String> params = uriDecode(req.uri());

		// TYPE
		resultJson.put("request_method", request_method);
		resultJson.put("uri", req.uri());
		resultJson.put("command", parseCommand(req));

		// HEADERS
		JSONObject headersJson = new JSONObject();
		for (Iterator<Map.Entry<String, String>> headers_hash = req.headers().entries().iterator(); headers_hash
				.hasNext();) {
			Map.Entry<String, String> item = headers_hash.next();
			headersJson.put(item.getKey(), item.getValue());
		}
		resultJson.put("headers", headersJson);

		// BODY
		if (body != "") {
			// parsing body to JSONObject
			JSONParser parser = new JSONParser();
			Object obj = null;
			try {
				obj = parser.parse(body);
			} catch (ParseException e) {
				// should return a 400 Bad Request.
			}
			if (obj != null) {
				JSONObject bodyJson = (JSONObject) obj;
				resultJson.put("body", bodyJson);
			}
		}

		// PARAMETERS
		if (params != null) {
			JSONObject paramsJson = new JSONObject();
			for (String key : params.keySet()) {
				String value = params.get(key);
				paramsJson.put(key, value);
			}
			resultJson.put("parameters", paramsJson);
		}

		return resultJson.toString();
	}

	private static HashMap<String, String> uriDecode(String uri) {
		HashMap<String, String> result = new HashMap<String, String>();

		// check if it's a query
		if (!uri.contains("?"))
			return null;

		String query = uri.substring(uri.indexOf("?") + 1);
		String[] pairs = query.split("&");

		for (String pair : pairs) {
			// validate uri correctness
			if (!pair.contains("="))
				return null;

			String[] kv = pair.split("=");
			result.put(kv[0], kv[1]);
		}
		return result;
	}

	private String getRequestEntity(final FullHttpRequest req) {
		String[] entities = req.uri().split("/");
		int mainEntityIndex = 1;

		if (entities[mainEntityIndex].contains("?")) {
			entities[mainEntityIndex] = entities[mainEntityIndex].substring(0, entities[mainEntityIndex].indexOf("?"));
		}

		String firstChar = entities[mainEntityIndex].substring(0, 1).toUpperCase();
		entities[mainEntityIndex] = firstChar
				+ entities[mainEntityIndex].substring(1, entities[mainEntityIndex].length());

		return entities[mainEntityIndex];
	}

	private String parseCommand(FullHttpRequest req) {
		String command = REQUEST_METHOD_COMMAND_PREFIX_MAP.get(req.method().toString()) + getRequestEntity(req);
		return command;
	}

	// Media

	private String readChunk(ChannelHandlerContext ctx) throws IOException, InterruptedException {
		JSONObject json = new JSONObject();
		try {
			while (httpDecoder.hasNext()) {
				InterfaceHttpData data = httpDecoder.next();
//				System.out.println(data.toString());
				if (data != null) {
					try {
						switch (data.getHttpDataType()) {
						case Attribute:
							Attribute attribute = (Attribute) data;
							String value = attribute.getValue();
//							System.out.println("Data " + data.getName() + " " + value);
							json.put(data.getName(), value);
							break;
						case FileUpload:
							String fileName = "";
							FileUpload fileUpload = (FileUpload) data;
							File file = new File(FILE_UPLOAD_LOCN + fileUpload.getFilename());
							fileName = fileUpload.getFilename();
							int i = 1;
							while (file.exists()) {
								String fileArray[] = fileUpload.getFilename().split("\\.");
								fileName = "";
								for (int j = 0; j < fileArray.length - 1; j++) {
									fileName += fileArray[j] + ".";
								}
								fileName = fileName.substring(0, fileName.length() - 1);
								fileName += " (" + i + ")." + fileArray[fileArray.length - 1];
								file = new File(FILE_UPLOAD_LOCN + fileName);
								i++;
							}
							file.createNewFile();
							try (FileChannel inputChannel = new FileInputStream(fileUpload.getFile()).getChannel();
									FileChannel outputChannel = new FileOutputStream(file).getChannel()) {
								outputChannel.transferFrom(inputChannel, 0, inputChannel.size());
							}
							json.put("media", fileName);
							break;
						}
					} finally {
						data.release();
					}
				}
			}
		} catch (EndOfDataDecoderException e) {
		} finally {
			return json.toString();
		}
	}

	private void resetPostRequestDecoder() {
		try {
			request = null;
			httpDecoder.destroy();
			httpDecoder = null;
		} catch (IllegalReferenceCountException e) {

		}
	}

	public static String getPostParameter(HttpRequest req, String name) {
		HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), req);

		InterfaceHttpData data = decoder.getBodyHttpData(name);
		System.out.println(decoder.getBodyHttpDatas("name"));
		if (data.getHttpDataType() == HttpDataType.Attribute) {
			Attribute attribute = (Attribute) data;
			String value = null;
			try {
				value = attribute.getValue();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return value;
		}

		return null;
	}

}
