import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import redis.clients.jedis.Jedis;

import static io.netty.buffer.Unpooled.copiedBuffer;

public class CacheHandler extends ChannelInboundHandlerAdapter {

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		final String path = (String) ctx.channel().attr(AttributeKey.valueOf("PATH")).get();
		final String method = (String) ctx.channel().attr(AttributeKey.valueOf("METHOD")).get();
		Jedis jedis = new Jedis("redis", 6379);
	

		System.out.println("Connected to Redis");

		String data = jedis.get(path);
		if (data != null && method == "GET") {
			System.out.println("IN CACHE");

			FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
					copiedBuffer(data.getBytes()));
			response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

			ctx.write(response);
			ctx.flush();
		} else {
			ctx.fireChannelRead(msg);
		}

	}
}
