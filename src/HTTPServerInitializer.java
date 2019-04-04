
import Handlers.CacheHandler;
import Handlers.HTTPHandler;
import Handlers.QueueHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.codec.http.cors.CorsHandler;
//import io.netty.handler.codec.http.*;

public class HTTPServerInitializer extends ChannelInitializer<SocketChannel> {
	@Override
	protected void initChannel(SocketChannel arg0) {
		CorsConfig corsConfig = CorsConfigBuilder.forAnyOrigin()
				.allowedRequestHeaders("X-Requested-With", "Content-Type", "Content-Length").allowedRequestMethods(
						HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.OPTIONS)
				.build();
		ChannelPipeline p = arg0.pipeline();
		p.addLast("decoder", new HttpRequestDecoder());
		p.addLast("encoder", new HttpResponseEncoder());
		p.addLast(new CorsHandler(corsConfig));
		p.addLast(new HttpObjectAggregator(10 * 1024 * 1024));
		p.addLast(new HTTPHandler());
		p.addLast(new CacheHandler());
		p.addLast(new QueueHandler());
//        p.addLast(new JSONHandler());
	}
}
