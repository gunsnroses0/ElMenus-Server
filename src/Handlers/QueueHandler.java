package Handlers;

import com.rabbitmq.client.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static io.netty.buffer.Unpooled.copiedBuffer;

public class QueueHandler extends ChannelInboundHandlerAdapter {

	ConnectionFactory factory;
	Connection connection;
	Channel mqChannel;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		final ChannelHandlerContext clientCtx = ctx;

		final String service = (String) ctx.channel().attr(AttributeKey.valueOf("SERVICE")).get();
		final String requestId = (String) ctx.channel().attr(AttributeKey.valueOf("REQUESTID")).get();
		final String path = (String) ctx.channel().attr(AttributeKey.valueOf("PATH")).get();
		final String method = (String) ctx.channel().attr(AttributeKey.valueOf("METHOD")).get();
		final String fileName = (String) ctx.channel().attr(AttributeKey.valueOf("MEDIA")).get();
		final String data = (String) msg;

		System.out.println("DATA: " + data);
		System.out.println("SERVICE: " + service);
		System.out.println("PATH: " + path);

		initializeQueue();

		sendMessage(service, requestId, data);

		mqChannel.queueDeclare(service + "-response", false, false, false, null);
		Consumer consumer = new DefaultConsumer(mqChannel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
					byte[] body) throws IOException {
				if (properties.getCorrelationId().equals(requestId)) {
					String data = new String(body, "UTF-8");

					System.out.println("Received: " + data);
					FullHttpResponse response;
					HttpResponseStatus status;

					if (method == "POST") {
						status = HttpResponseStatus.CREATED;
					} else if (method == "PATCH") {
						status = HttpResponseStatus.NO_CONTENT;
					} else {
						status = HttpResponseStatus.OK;
					}
					
					Jedis jedis = new Jedis("redis", 6379);
					System.out.println("KEY: " + path);
					if (method == "GET") {
						jedis.set(path, data);
						System.out.println("KEY Updated");
					} else {
						jedis.del(path);
						jedis.del("/" + service);
						System.out.println("KEY Deleted");
					}

					if (data.equals("") || data.equals("[]")) {
						response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
					} else {
						response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
								copiedBuffer(data.getBytes()));
					}

					response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
					response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

					this.getChannel().basicAck(envelope.getDeliveryTag(), false);

					clientCtx.write(response);
					clientCtx.flush();

					try {
						this.getChannel().close();
						this.getChannel().getConnection().close();
					} catch (TimeoutException e) {
						e.printStackTrace();
					}
				} else {
					mqChannel.basicNack(envelope.getDeliveryTag(), false, true);
				}
			}
		};
		mqChannel.basicConsume(service + "-response", false, consumer);
	}

	private void initializeQueue() {
		try {
			// sharing connection between threads
			String host = System.getenv("RABBIT_MQ_SERVICE_HOST");
			factory = new ConnectionFactory();
			factory.setHost(host);

			connection = factory.newConnection();
			mqChannel = connection.createChannel();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void sendMessage(String service, String requestId, String message) {
		try {
			mqChannel.queueDeclare(service + "-request", false, false, false, null);
			AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().correlationId(requestId)
					.replyTo(service + "-response").build();
			System.out.println("Sent: " + message);
			mqChannel.basicPublish("", service + "-request", props, message.getBytes());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
		ctx.flush();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		cause.printStackTrace();
		ctx.close();
	}
}