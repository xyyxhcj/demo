package org.example.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.example.util.JsonUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用主从线程组的nettyServer
 * http://127.0.0.1:8089
 * curl 127.0.0.1:8089
 *
 * @author xyyxhcj@qq.com
 * @date 2020/4/17 23:51
 **/

public class WebSocketServer {
    /**
     * 用于记录所有客户端的channel
     **/
    private final static ChannelGroup CLIENTS = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final static Map<String, ChannelGroup> USER_CHANNELS_GROUP = new LinkedHashMap<>();
    private final static Map<String, String> CHANNEL_DICT = new LinkedHashMap<>();
    private final static String LOGIN = "1";
    private final static String LOGIN_OUT = "-1";

    public static void main(String[] args) throws InterruptedException {
        // 定义一对线程组
        // 主线程组，用于接受客户端连接
        EventLoopGroup mainGroup = new NioEventLoopGroup();
        // 从线程组，用于执行主线程组接受的任务
        EventLoopGroup subGroup = new NioEventLoopGroup();
        try {
            // 使用启动类ServerBootstrap创建netty服务器
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(mainGroup, subGroup)
                    .channel(NioServerSocketChannel.class)
                    // 设置初始化器:channel注册后执行的初始化方法
                    .childHandler(getChildHandler());
            // 设置channel初始化器-每个channel由多个handler共同组成管道pipeline
            // 绑定监听端口，同步监听
            ChannelFuture channelFuture = serverBootstrap.bind(8089).sync();
            // 关闭channel,使用同步监听方式
            channelFuture.channel().closeFuture().sync();
        } finally {
            // 关闭线程组
            mainGroup.shutdownGracefully();
            subGroup.shutdownGracefully();
        }
    }

    private static ChannelInitializer<SocketChannel> getChildHandler() {
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel channel) throws Exception {
                // 通过channel获取对应的管道,向管道pipeline中添加handler
                ChannelPipeline pipeline = channel.pipeline();
                // http编解码器
                pipeline.addLast(new HttpServerCodec());
                // 大数据流处理器
                pipeline.addLast(new ChunkedWriteHandler());
                // http聚合器,对httpMessage进行聚合
                pipeline.addLast(new HttpObjectAggregator(1024 * 64));
                // websocket处理器(用于处理握手,心跳,请求,响应,关闭,...)
                pipeline.addLast(new WebSocketServerProtocolHandler("/ws"));
                // 自定义请求处理handler
                pipeline.addLast(getReqHandler());
            }
        };
    }

    /**
     * TextWebSocketFrame: netty中处理websocket文本的对象
     * Frame: 消息载体
     *
     * @return SimpleChannelInboundHandler<io.netty.handler.codec.http.websocketx.TextWebSocketFrame>
     * @author xyyxhcj@qq.com
     * @date 2020/4/18 21:56
     **/
    private static SimpleChannelInboundHandler<TextWebSocketFrame> getReqHandler() {
        return new SimpleChannelInboundHandler<TextWebSocketFrame>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) throws Exception {
                // 客户端传输的消息
                String reqText = msg.text();
                // 接收type,userId,msg,sendTo
                Map<String, String> reqMap = JsonUtils.json2MapPojo(reqText, new TypeReference<HashMap<String, String>>() {
                });
                String userId = reqMap.get("userId");
                String type = reqMap.get("type");
                Channel channel = ctx.channel();
                if (LOGIN.equals(type)) {
                    // 用户登录时通知系统，将channel与userId建立绑定
                    USER_CHANNELS_GROUP.computeIfAbsent(userId, k -> new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)).add(channel);
                    // 记录连接的userId,连接异常中断时及时刷新USER_CHANNELS_GROUP
                    CHANNEL_DICT.put(channel.id().asLongText(), userId);
                } else if (LOGIN_OUT.equals(type)) {
                    removeInvalidChannel(channel, userId);
                } else {
                    String message = reqMap.get("msg");
                    String sendTo = reqMap.get("sendTo");
                    System.out.println(String.format("accept: %s, %s", channel.id().asLongText(), reqText));
                    Map<String, String> respMap = new LinkedHashMap<>();
                    respMap.put("msg", message);
                    respMap.put("from", userId);
                    ChannelGroup channels = null;
                    if (sendTo == null || "".equals(sendTo.trim())) {
                        // send to all
                        channels = CLIENTS;
                    } else {
                        channels = USER_CHANNELS_GROUP.get(sendTo);
                    }
                    if (channels != null) {
                        channels.writeAndFlush(new TextWebSocketFrame(JsonUtils.object2Json(respMap)));
                    }
                }
                // 向当前连接返回消息
                // ctx.writeAndFlush(new TextWebSocketFrame(JsonUtils.object2Json(respMap)));
            }

            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                // 打开连接时,将客户端的channel放入ChannelGroup中进行管理
                CLIENTS.add(ctx.channel());
            }

            @Override
            public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
                Channel channel = ctx.channel();
                String channelId = channel.id().asLongText();
                // 刷新USER_CHANNELS_GROUP
                String userId = CHANNEL_DICT.remove(channelId);
                removeInvalidChannel(channel, userId);
                // 当连接关闭时,CLIENTS会自动移除掉客户端的channel /CLIENTS
                System.out.println(String.format("客户端断开连接: %s, %s", userId, channelId));
            }

            private void removeInvalidChannel(Channel channel, String userId) {
                ChannelGroup channels = USER_CHANNELS_GROUP.get(userId);
                if (channels != null) {
                    channels.remove(channel);
                    if (channels.isEmpty()) {
                        USER_CHANNELS_GROUP.remove(userId);
                    }
                }
            }
        };
    }
}
