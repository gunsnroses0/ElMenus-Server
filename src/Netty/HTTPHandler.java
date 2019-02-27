package Netty;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;

public class HTTPHandler extends SimpleChannelInboundHandler<Object> {
    private HttpRequest request;
    private String requestBody;
    private long correlationId;
    volatile String responseBody;
    ExecutorService executorService = Executors.newCachedThreadPool();
    
    String requestId;
    private static final HashMap<String, String> REQUEST_METHOD_COMMAND_PREFIX_MAP;
    static
    {
        REQUEST_METHOD_COMMAND_PREFIX_MAP = new HashMap<String, String>();
        REQUEST_METHOD_COMMAND_PREFIX_MAP.put("POST", "Create");
        REQUEST_METHOD_COMMAND_PREFIX_MAP.put("GET", "Retrieve");
        REQUEST_METHOD_COMMAND_PREFIX_MAP.put("PATCH", "Update");
        REQUEST_METHOD_COMMAND_PREFIX_MAP.put("DELETE", "Delete");
    }
    
    private String getServiceName(final FullHttpRequest req){
        String result = req.uri().split("/")[1];
        if(result.contains("?")){
            result = result.substring(0,result.indexOf("?"));
        }
        return result;
    }
    
    private String parseToJson(final FullHttpRequest req) {
        JSONObject resultJson = new JSONObject();

        String request_method = req.method().toString();
        String body = req.content().toString(CharsetUtil.UTF_8);

        HashMap<String, String> params = uriDecode(req.uri());

        //TYPE
        resultJson.put("request_method", request_method);
        resultJson.put("uri", req.uri());
        resultJson.put("command", parseCommand(req));

        //HEADERS
        JSONObject headersJson = new JSONObject();
        for (Iterator<Map.Entry<String, String>> headers_hash = req.headers().entries().iterator();
             headers_hash.hasNext();) {
            Map.Entry<String, String> item = headers_hash.next();
            headersJson.put(item.getKey(), item.getValue());
        }
        resultJson.put("headers", headersJson);


        //BODY
        if(body != "") {
            //parsing body to JSONObject
            JSONParser parser = new JSONParser();
            Object obj = null;
            try {
                obj = parser.parse(body);
            } catch (ParseException e) {
                //should return a 400 Bad Request.
            }
            if(obj != null){
                JSONObject bodyJson = (JSONObject) obj;
                resultJson.put("body", bodyJson);
            }
        }

        //PARAMETERS
        if(params != null){
            JSONObject paramsJson = new JSONObject();
            for(String key : params.keySet()){
                String value = params.get(key);
                paramsJson.put(key, value);
            }
            resultJson.put("parameters", paramsJson);
        }

        return resultJson.toString();
    }
    
    private static HashMap<String, String> uriDecode(String uri){
        HashMap<String, String> result = new HashMap<String, String>();

        //check if it's a query
        if(!uri.contains("?")) return null;

        String query = uri.substring(uri.indexOf("?")+1);
        String [] pairs = query.split("&");

        for(String pair : pairs){
            //validate uri correctness
            if(!pair.contains("=")) return null;

            String [] kv = pair.split("=");
            result.put(kv[0],kv[1]);
        }
        return result;
    }
    
    private String getRequestEntity(final FullHttpRequest req){
        String[] entities   = req.uri().split("/");
        int mainEntityIndex = 1;

        if(entities[mainEntityIndex].contains("?")) {
            entities[mainEntityIndex] =
                entities[mainEntityIndex].substring(0, entities[mainEntityIndex].indexOf("?"));
        }

        String firstChar = entities[mainEntityIndex].substring(0, 1).toUpperCase();
        entities[mainEntityIndex] =
            firstChar + entities[mainEntityIndex].substring(1, entities[mainEntityIndex].length());

        return entities[mainEntityIndex] ;
    }

    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
        ctx.fireChannelReadComplete();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg)
            throws Exception {
    	final FullHttpRequest req = (FullHttpRequest) msg;

        String service = getServiceName(req);
        String data = parseToJson(req);
        System.out.println(service);
        System.out.println(data);

        requestId = UUID.randomUUID().toString();

        ctx.channel().attr(AttributeKey.valueOf("SERVICE")).set(service);
        ctx.channel().attr(AttributeKey.valueOf("REQUESTID")).set(requestId);
        ctx.channel().attr(AttributeKey.valueOf("PATH")).set(req.uri());
        ctx.channel().attr(AttributeKey.valueOf("METHOD")).set(req.method().toString());
        ctx.fireChannelRead(data);

    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
    
    private String parseCommand(FullHttpRequest req) {
        String command =
            REQUEST_METHOD_COMMAND_PREFIX_MAP.get(req.method().toString()) + getRequestEntity(req);
        return command;
    }


}
